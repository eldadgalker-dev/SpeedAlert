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
)

data class Penalty(val fine: Int, val points: Int, val court: Boolean)

class Penalties(private val urban: List<Band>, private val interurban: List<Band>) {
    class Band(val from: Int, val to: Int, val penalty: Penalty)

    fun forExcess(overKmh: Int, urbanRoad: Boolean): Penalty? =
        (if (urbanRoad) urban else interurban).firstOrNull { overKmh in it.from..it.to }?.penalty

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

object Database {
    fun cameras(ctx: Context): List<Camera> {
        val a = JSONObject(asset(ctx, "cameras.json")).getJSONArray("cameras")
        return (0 until a.length()).map { i ->
            val c = a.getJSONObject(i)
            Camera(
                id = c.getLong("id"),
                lat = c.getDouble("lat"),
                lon = c.getDouble("lon"),
                limit = if (c.isNull("limit")) null else c.getInt("limit"),
                direction = if (c.isNull("direction")) null else c.getDouble("direction"),
                urban = c.optBoolean("urban", false),
            )
        }
    }

    fun penalties(ctx: Context) = Penalties.parse(asset(ctx, "penalties.json"))

    private fun asset(ctx: Context, name: String) =
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
