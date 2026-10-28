package il.speedalert

import kotlin.math.ceil

/**
 * Decides when to speak. Alerts once when an approaching camera comes within
 * [FIRST_ALERT_M], and again within [SECOND_ALERT_M] if the driver is still too fast.
 *
 * Silence rules (field report 2026-10-28: alerts kept coming after the camera):
 * - a camera that was passed, or that we are moving away from, stays silent until
 *   we are more than [FORGET_M] away from it;
 * - cameras within [CLUSTER_M] of an announced camera (the opposite-direction twin,
 *   duplicate map points) are silent while that camera is remembered;
 * - an alert is spoken only while the distance keeps shrinking;
 * - at most one first alert per [MIN_GAP_MS].
 */
class CameraWatcher(
    cameras: List<Camera>,
    penalties: Penalties,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(var stage: Int, var lastDist: Double, var announced: Boolean = false, var closing: Boolean = false)

    @Volatile private var cameras: List<Camera> = cameras
    @Volatile private var penalties: Penalties = penalties
    private val states = mutableMapOf<Long, State>()
    private var lastAlertAt = Long.MIN_VALUE / 2

    /** Replaces the tables (data update from GitHub) without losing what was announced. */
    fun replaceTables(newCameras: List<Camera>, newPenalties: Penalties) {
        cameras = newCameras
        penalties = newPenalties
    }

    fun onLocation(lat: Double, lon: Double, speedKmh: Double, heading: Double?): String? {
        val all = cameras
        val dist = all.associate { it.id to Geo.distance(lat, lon, it.lat, it.lon) }

        // Forget only with hysteresis, so GPS jitter near the limit cannot re-arm a camera.
        states.keys.retainAll { (dist[it] ?: Double.MAX_VALUE) < FORGET_M }
        // Track distance of remembered cameras: moving away means passed.
        for ((id, s) in states) {
            val d = dist[id] ?: continue
            if (d > s.lastDist + 3) s.stage = PASSED
            s.closing = d < s.lastDist - 1
            s.lastDist = d
        }
        if (heading == null || speedKmh < MIN_SPEED_KMH) return null

        val announced = all.filter { states[it.id]?.announced == true }
        val candidate = all
            .filter { c -> dist.getValue(c.id) <= FIRST_ALERT_M }
            .filter { c -> states[c.id]?.stage != PASSED }
            .filter { c -> isAhead(c, lat, lon, heading) }
            .filter { c -> announced.none { a -> a.id != c.id && Geo.distance(a.lat, a.lon, c.lat, c.lon) <= CLUSTER_M } }
            .minByOrNull { dist.getValue(it.id) } ?: return null

        val d = dist.getValue(candidate.id)
        // First sighting only records the distance: the next fix must confirm we are closing in.
        val state = states[candidate.id] ?: run { states[candidate.id] = State(0, d); return null }
        if (!state.closing) return null

        val newStage = if (d <= SECOND_ALERT_M) 2 else 1
        if (newStage <= state.stage) return null
        val over = candidate.limit?.let { ceil(speedKmh - it).toInt() } ?: 0
        val now = clock()
        if (state.stage == 0 && now - lastAlertAt < MIN_GAP_MS) return null
        state.stage = newStage
        state.announced = true
        if (newStage == 2 && over <= 0) return null
        lastAlertAt = now
        return message(candidate, d, over)
    }

    private fun isAhead(c: Camera, lat: Double, lon: Double, heading: Double): Boolean {
        if (Geo.angleDiff(heading, Geo.bearing(lat, lon, c.lat, c.lon)) > AHEAD_DEG) return false
        // A camera facing across our road belongs to another road (crossing / parallel ramp).
        val dir = c.direction ?: return true
        val diff = Geo.angleDiff(heading, dir)
        return diff <= 60 || diff >= 120
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
        // Niqqud: without it TTS reads "הורד" as "ורד" (rose).
        return "$head הוֹרֵד $over קילומטר לשעה.$tail"
    }

    companion object {
        const val FIRST_ALERT_M = 500.0
        const val SECOND_ALERT_M = 200.0
        const val FORGET_M = 1000.0
        const val CLUSTER_M = 300.0
        const val AHEAD_DEG = 35.0
        const val MIN_SPEED_KMH = 10.0
        const val MIN_GAP_MS = 20_000L
        private const val PASSED = -1
    }
}
