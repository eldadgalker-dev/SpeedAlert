package il.speedalert

import android.content.Context
import org.json.JSONObject

data class Camera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val limit: Int?,        // km/h, null when unknown
    val direction: Double?, // degrees, null when unknown
    val urban: Boolean,
    val kind: Kind = Kind.SPEED,
) {
    enum class Kind { SPEED, RED_LIGHT }
}

/** A road section with bus-lane enforcement cameras, from end [a] to end [b] (data/buslanes.json). */
data class BusLane(val name: String, val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double)

data class Penalty(val fine: Int, val points: Int, val court: Boolean)

class Penalties(private val urban: List<Band>, private val interurban: List<Band>) {
    class Band(val from: Int, val to: Int, val penalty: Penalty)

    fun forExcess(overKmh: Int, urbanRoad: Boolean): Penalty? =
        (if (urbanRoad) urban else interurban).firstOrNull { overKmh in it.from..it.to }?.penalty

    /** "הוֹרֵד N קילומטר לשעה." plus the fine and points, as spoken. */
    fun spoken(overKmh: Int, urbanRoad: Boolean): String {
        val p = forExcess(overKmh, urbanRoad)
        val tail = when {
            p == null -> ""
            p.court -> " הזמנה לדין, ${p.points} נקודות ופסילה."
            p.points == 0 -> " קנס ${p.fine} שקלים, ללא נקודות."
            else -> " קנס ${p.fine} שקלים ועוד ${p.points} נקודות."
        }
        // Niqqud: without it TTS reads "הורד" as "ורד" (rose).
        return "הוֹרֵד $overKmh קילומטר לשעה.$tail"
    }

    companion object {
        fun parse(json: String): Penalties {
            val o = JSONObject(json)
            fun bands(key: String) = o.getJSONArray(key).let { a ->
                (0 until a.length()).map { i ->
                    val b = a.getJSONObject(i)
                    Band(b.getInt("from"), b.getInt("to"),
                        Penalty(b.getInt("fine"), b.getInt("points"), b.optBoolean("court", false)))
                }
            }
            return Penalties(bands("urban"), bands("interurban"))
        }
    }
}

/**
 * Camera and penalty tables. The newest valid copy downloaded from GitHub (filesDir) wins;
 * the copy packed into the APK (assets) is the fallback.
 */
object Database {
    const val CAMERAS = "cameras.json"
    const val PENALTIES = "penalties.json"
    const val BUS_LANES = "buslanes.json"
    const val ROADS = "roads.txt"

    fun cameras(ctx: Context): List<Camera> = parseCameras(read(ctx, CAMERAS))

    fun penalties(ctx: Context) = Penalties.parse(read(ctx, PENALTIES))

    fun busLanes(ctx: Context): List<BusLane> = parseBusLanes(read(ctx, BUS_LANES))

    fun roads(ctx: Context): Roads = Roads.parse(read(ctx, ROADS))

    fun parseBusLanes(json: String): List<BusLane> {
        val a = JSONObject(json).getJSONArray("sections")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val from = o.getJSONArray("from"); val to = o.getJSONArray("to")
            BusLane(o.getString("road"), from.getDouble(0), from.getDouble(1), to.getDouble(0), to.getDouble(1))
        }
    }

    fun parseCameras(json: String): List<Camera> {
        val a = JSONObject(json).getJSONArray("cameras")
        return (0 until a.length()).map { i ->
            val c = a.getJSONObject(i)
            Camera(
                id = c.getLong("id"),
                lat = c.getDouble("lat"),
                lon = c.getDouble("lon"),
                limit = if (c.isNull("limit")) null else c.getInt("limit"),
                direction = if (c.isNull("direction")) null else c.getDouble("direction"),
                urban = c.optBoolean("urban", false),
                kind = if (c.optString("kind") == "red_light") Camera.Kind.RED_LIGHT else Camera.Kind.SPEED,
            )
        }
    }

    /** Date of the data in use ("fetched" field), for the status line. */
    fun fetched(ctx: Context): String = JSONObject(read(ctx, CAMERAS)).optString("fetched", "?")

    fun read(ctx: Context, name: String): String {
        val local = java.io.File(ctx.filesDir, name)
        if (local.isFile) runCatching { return local.readText(Charsets.UTF_8) }
        return ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
