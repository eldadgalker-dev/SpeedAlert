// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import kotlin.math.roundToInt

/**
 * Average-speed (point-to-point) enforcement sections (data/sections.json; user request 2026-10-29).
 * Announces the start and the end of a section; inside it, alerts only while the average speed
 * over the section so far is above the limit, with the speed that brings the average back by
 * the end. One entry per direction: [AverageSection.aLat] is where the driver enters.
 */
class AverageSpeedWatcher(@Volatile var sections: List<AverageSection>) {
    private class Trip(val section: AverageSection, val startMs: Long, var lat: Double, var lon: Double,
                       var travelled: Double = 0.0, var minToEnd: Double = Double.MAX_VALUE, var owed: Boolean = false)
    private var trip: Trip? = null
    private val done = mutableSetOf<AverageSection>()   // passed; armed again after leaving by [REARM_M]

    fun onLocation(lat: Double, lon: Double, speedKmh: Double, heading: Double?, nowMs: Long): Alert? {
        done.removeAll { Geo.distance(lat, lon, it.aLat, it.aLon) > REARM_M }
        trip?.let { return inside(it, lat, lon, speedKmh, nowMs) }
        if (heading == null || speedKmh < CameraWatcher.MIN_SPEED_KMH) return null
        for (s in sections) {
            if (s in done || Geo.distance(lat, lon, s.aLat, s.aLon) > ENTER_M) continue
            if (Geo.angleDiff(heading, Geo.bearing(s.aLat, s.aLon, s.bLat, s.bLon)) > 45) continue
            trip = Trip(s, nowMs, lat, lon)
            return Alert("תחילת קטע אכיפת מהירות ממוצעת${s.label()}. מותרת ${s.limit}.", repeat = false)
        }
        return null
    }

    private fun inside(t: Trip, lat: Double, lon: Double, speedKmh: Double, nowMs: Long): Alert? {
        val s = t.section
        t.travelled += Geo.distance(t.lat, t.lon, lat, lon)
        t.lat = lat; t.lon = lon
        val length = s.lengthM ?: Geo.distance(s.aLat, s.aLon, s.bLat, s.bLon)
        val seconds = (nowMs - t.startMs) / 1000.0
        val average = if (seconds < 1) 0.0 else t.travelled / seconds * 3.6
        val toEnd = Geo.distance(lat, lon, s.bLat, s.bLon)
        t.minToEnd = minOf(t.minToEnd, toEnd)
        // End: at the far point, or moving away from it after coming close, or far off the section.
        val passed = toEnd <= END_M || (t.minToEnd <= EXIT_M && toEnd > t.minToEnd + END_M)
        val lost = toEnd > length + LOST_M || t.travelled > length * 3 + LOST_M
        if (passed || lost) {
            trip = null
            done += s
            return if (lost) null
                else Alert("סוף קטע אכיפת מהירות ממוצעת. מהירות ממוצעת ${average.roundToInt()}.", repeat = false)
        }
        if (seconds < MIN_SECONDS || average <= s.limit) {
            if (!t.owed) return null
            t.owed = false
            return Alert("מהירות ממוצעת תקינה. מותרת ${s.limit}.", repeat = false)
        }
        t.owed = true
        // Speed for the rest of the section that ends it exactly at the limit.
        val remaining = (length - t.travelled).coerceAtLeast(0.0)
        val budget = length / (s.limit / 3.6) - seconds
        val advice = when {
            budget <= 0 || remaining <= 0 -> "לא ניתן להשלים לממוצע המותר."
            else -> "סע עד ${(remaining / budget * 3.6).toInt().coerceAtMost(s.limit)} עד סוף הקטע."
        }
        return Alert("מהירות ממוצעת בקטע ${average.roundToInt()}. מותרת ${s.limit}. מהירות נוכחית ${speedKmh.roundToInt()}. $advice", repeat = true)
    }

    companion object {
        const val ENTER_M = 150.0
        const val EXIT_M = 300.0
        const val END_M = 40.0
        const val LOST_M = 2000.0
        const val REARM_M = 1000.0
        const val MIN_SECONDS = 30.0
    }
}

data class AverageSection(val name: String, val limit: Int, val aLat: Double, val aLon: Double,
                          val bLat: Double, val bLon: Double, val lengthM: Double? = null) {
    fun label() = if (name.isEmpty()) "" else " ב$name"
}
