package il.speedalert

import kotlin.math.ceil

/**
 * Decides when to speak. Alerts once when an approaching camera comes within
 * [FIRST_ALERT_M], and again within [SECOND_ALERT_M] if the driver is still too fast.
 */
class CameraWatcher(private val cameras: List<Camera>, private val penalties: Penalties) {
    private val stage = mutableMapOf<Long, Int>() // camera id -> last alert stage

    fun onLocation(lat: Double, lon: Double, speedKmh: Double, heading: Double?): String? {
        // Forget cameras that are behind us or far away, so the next pass alerts again.
        stage.keys.retainAll { id ->
            cameras.firstOrNull { it.id == id }?.let { Geo.distance(lat, lon, it.lat, it.lon) < FORGET_M } == true
        }
        if (heading == null || speedKmh < MIN_SPEED_KMH) return null

        val (cam, dist) = cameras
            .map { it to Geo.distance(lat, lon, it.lat, it.lon) }
            .filter { (c, d) -> d <= FIRST_ALERT_M && isAhead(c, lat, lon, heading) }
            .minByOrNull { it.second } ?: return null

        val newStage = if (dist <= SECOND_ALERT_M) 2 else 1
        if (newStage <= (stage[cam.id] ?: 0)) return null
        stage[cam.id] = newStage
        val over = cam.limit?.let { ceil(speedKmh - it).toInt() } ?: 0
        if (newStage == 2 && over <= 0) return null
        return message(cam, dist, over)
    }

    private fun isAhead(c: Camera, lat: Double, lon: Double, heading: Double): Boolean {
        if (Geo.angleDiff(heading, Geo.bearing(lat, lon, c.lat, c.lon)) > AHEAD_DEG) return false
        // A camera facing across our road belongs to another road (crossing / parallel ramp).
        val dir = c.direction ?: return true
        val d = Geo.angleDiff(heading, dir)
        return d <= 60 || d >= 120
    }

    fun message(cam: Camera, dist: Double, over: Int): String {
        val metres = (Math.round(dist / 10.0) * 10).toInt()
        val head = "מצלמת מהירות בעוד $metres מטר."
        val limit = cam.limit ?: return head
        if (over <= 0) return "$head מהירות מותרת $limit."
        val p = penalties.forExcess(over, cam.urban)
        val tail = when {
            p == null -> ""
            p.court -> " הזמנה לדין, ${p.points} נקודות ופסילה."
            p.points == 0 -> " קנס ${p.fine} שקלים, ללא נקודות."
            else -> " קנס ${p.fine} שקלים ועוד ${p.points} נקודות."
        }
        return "$head הורד $over קילומטר לשעה.$tail"
    }

    companion object {
        const val FIRST_ALERT_M = 500.0
        const val SECOND_ALERT_M = 200.0
        const val FORGET_M = 700.0
        const val AHEAD_DEG = 35.0
        const val MIN_SPEED_KMH = 10.0
    }
}
