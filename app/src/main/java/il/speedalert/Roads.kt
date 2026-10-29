// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Answers the speed limit of the road under the car. */
fun interface RoadLookup {
    fun at(lat: Double, lon: Double, heading: Double): Roads.Match?
}

/** Speed limit of the road under the car, from one tile of data/roads/ (OSM, built by tools/fetch_roads.py and tile_roads.py). */
class Roads private constructor(
    private val lat1: IntArray, private val lon1: IntArray,   // segment ends, degrees * 1e5
    private val lat2: IntArray, private val lon2: IntArray,
    private val way: IntArray,                                // segment -> way
    private val limits: IntArray, private val urban: BooleanArray,
    private val grid: Map<Long, IntArray>,                    // cell -> segments
) : RoadLookup {
    class Match(val limit: Int, val urban: Boolean, val distance: Double)

    val segments get() = lat1.size

    /**
     * The road under the car: of the roads within [MATCH_M] that run along [heading] (either way),
     * the one with the highest limit, since a ramp or service street beside a highway is closer
     * than the highway's centre line when driving in its outer lane (field report 2026-10-29,
     * "50" in the middle of the Ayalon). Null when none, or when one of them has an unknown
     * limit (0): then the limit cannot be told.
     */
    override fun at(lat: Double, lon: Double, heading: Double): Match? {
        val k = cos(Math.toRadians(lat)) * 1.1132  // metres per 1e-5 degree of longitude
        val px = lon * 1e5; val py = lat * 1e5
        var best = -1; var bestD = MATCH_M
        var unknown = false
        val cx = cell(lon); val cy = cell(lat)
        for (dx in -1..1) for (dy in -1..1) {
            val segs = grid[key(cx + dx, cy + dy)] ?: continue
            for (s in segs) {
                val ax = (lon1[s] - px) * k; val ay = (lat1[s] - py) * 1.1054
                val bx = (lon2[s] - px) * k; val by = (lat2[s] - py) * 1.1054
                val ex = bx - ax; val ey = by - ay
                val len2 = ex * ex + ey * ey
                if (len2 < 1.0) continue
                val t = (-(ax * ex + ay * ey) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * ex, ay + t * ey)
                if (d >= MATCH_M) continue
                val bearing = (Math.toDegrees(kotlin.math.atan2(ex, ey)) + 360) % 360
                val diff = Geo.angleDiff(heading, bearing)
                if (diff > ALONG_DEG && diff < 180 - ALONG_DEG) continue
                val lim = limits[way[s]]
                if (lim == 0) unknown = true
                if (best < 0 || lim > limits[way[best]] || (lim == limits[way[best]] && d < bestD)) { best = s; bestD = d }
            }
        }
        if (best < 0 || unknown) return null
        val w = way[best]
        return Match(limits[w], urban[w], bestD)
    }

    companion object {
        const val MATCH_M = 20.0
        const val ALONG_DEG = 30.0
        private const val CELL = 0.005  // degrees, about 500 m

        private fun cell(deg: Double) = floor(deg / CELL).toInt()
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

        /**
         * Format: '#' comment lines, then one way per line:
         * `limit urban lat lon dlat dlon dlat dlon ...` (urban 0/1, coordinates in 1e-5 degrees,
         * deltas from the previous point). Parsed without boxing: the file has ~10^6 numbers.
         */
        fun parse(text: CharSequence): Roads {
            val lat1 = IntList(); val lon1 = IntList(); val lat2 = IntList(); val lon2 = IntList()
            val way = IntList(); val limits = IntList(); val urban = ArrayList<Boolean>()
            val nums = IntList()
            var i = 0
            val n = text.length
            while (i < n) {
                var end = i
                while (end < n && text[end] != '\n') end++
                if (end > i && text[i] != '#') {
                    nums.clear()
                    var j = i
                    while (j < end) {
                        while (j < end && text[j] == ' ') j++
                        if (j >= end) break
                        var neg = false
                        if (text[j] == '-') { neg = true; j++ }
                        var v = 0
                        while (j < end && text[j] in '0'..'9') { v = v * 10 + (text[j] - '0'); j++ }
                        while (j < end && text[j] != ' ') j++   // '\r' or junk
                        nums.add(if (neg) -v else v)
                    }
                    if (nums.size >= 6 && nums.size % 2 == 0) {
                        val w = limits.size
                        limits.add(nums[0]); urban.add(nums[1] == 1)
                        var la = nums[2]; var lo = nums[3]
                        var p = 4
                        while (p < nums.size) {
                            val nla = la + nums[p]; val nlo = lo + nums[p + 1]
                            lat1.add(la); lon1.add(lo); lat2.add(nla); lon2.add(nlo); way.add(w)
                            la = nla; lo = nlo; p += 2
                        }
                    }
                }
                i = end + 1
            }
            val cells = HashMap<Long, IntList>()
            for (s in 0 until lat1.size) {
                val xs = minOf(cell(lon1[s] / 1e5), cell(lon2[s] / 1e5))..maxOf(cell(lon1[s] / 1e5), cell(lon2[s] / 1e5))
                val ys = minOf(cell(lat1[s] / 1e5), cell(lat2[s] / 1e5))..maxOf(cell(lat1[s] / 1e5), cell(lat2[s] / 1e5))
                for (x in xs) for (y in ys) cells.getOrPut(key(x, y)) { IntList() }.add(s)
            }
            return Roads(lat1.toArray(), lon1.toArray(), lat2.toArray(), lon2.toArray(), way.toArray(),
                limits.toArray(), urban.toBooleanArray(), cells.mapValues { it.value.toArray() })
        }
    }
}

/** Growable int array (no boxing). */
private class IntList {
    private var a = IntArray(16)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = a.copyOf(size)
}

/**
 * Speaks without a camera when the speed is [OVER_KMH] or more above the limit of the road
 * (user request 2026-10-28). Starts after [CONFIRM] fixes in a row, then repeats while it lasts.
 */
class SpeedingWatcher(@Volatile var penalties: Penalties) {
    @Volatile var roads: RoadLookup? = null
    private var streak = 0
    private var owed = false   // an alert was given; say once when back at or under the limit

    fun onLocation(lat: Double, lon: Double, speedKmh: Double, heading: Double?): Alert? {
        val r = roads
        val m = if (r == null || heading == null) null else r.at(lat, lon, heading)
        val over = if (m == null) 0 else kotlin.math.ceil(speedKmh - m.limit).toInt()
        if (m == null || over < OVER_KMH) {
            streak = 0
            if (owed && m != null && over <= 0) { owed = false; return Alert("${CameraWatcher.OK} מותרת ${m.limit}.", repeat = false) }
            return null
        }
        if (++streak < CONFIRM) return null
        owed = true
        return Alert("חריגת מהירות. מהירות נוכחית ${speedKmh.roundToInt()}. מותרת ${m.limit}. ${penalties.spoken(over, m.urban)}",
            repeat = true)
    }

    companion object {
        const val OVER_KMH = 30
        const val CONFIRM = 3
    }
}
