// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Decides what to say. From [ALERT_M] before a speed camera ahead, every fix returns the current
 * alert (distance, current speed, limit, how much to slow down, fine), so the service speaks
 * without a break until the camera is passed (user decision 2026-10-28). A red-light camera is
 * announced once, [RED_LIGHT_M] before it.
 *
 * Silence rules (field report 2026-10-28: alerts kept coming after the camera):
 * - a camera is passed when the distance to it grows while moving; it then stays silent until
 *   we are more than [FORGET_M] away from it;
 * - cameras within [CLUSTER_M] of an announced camera (the opposite-direction twin,
 *   duplicate map points) are silent while that camera is remembered;
 * - a new camera starts only when two fixes confirm we are closing in on it.
 */
/** What to say; [repeat] alerts are spoken again and again while they last, the others once. */
data class Alert(val text: String, val repeat: Boolean)

class CameraWatcher(cameras: List<Camera>, penalties: Penalties) {
    private class State(var lastDist: Double, var announced: Boolean = false, var passed: Boolean = false, var closing: Boolean = false)

    @Volatile private var cameras: List<Camera> = cameras
    @Volatile private var penalties: Penalties = penalties
    private val states = mutableMapOf<Long, State>()

    /** Distance to the nearest camera at the last fix, metres. */
    var nearestM = Double.MAX_VALUE
        private set

    /** Replaces the tables (data update from GitHub) without losing what was announced. */
    fun replaceTables(newCameras: List<Camera>, newPenalties: Penalties) {
        cameras = newCameras
        penalties = newPenalties
    }

    /** The alert to speak now, or null when no camera is being approached. */
    fun onLocation(lat: Double, lon: Double, speedKmh: Double, heading: Double?): Alert? {
        val all = cameras
        val dist = all.associate { it.id to Geo.distance(lat, lon, it.lat, it.lon) }
        val moving = speedKmh >= MIN_SPEED_KMH
        nearestM = dist.values.minOrNull() ?: Double.MAX_VALUE

        // Forget only with hysteresis, so GPS jitter near the limit cannot re-arm a camera.
        states.keys.retainAll { (dist[it] ?: Double.MAX_VALUE) < FORGET_M }
        for ((id, s) in states) {
            val d = dist[id] ?: continue
            // Moving away means passed; while standing still GPS jitter must not end the alert.
            if (moving && d > s.lastDist + 3) s.passed = true
            s.closing = d < s.lastDist - 1
            s.lastDist = d
        }

        // A camera already announced keeps talking until it is passed, even when stopped
        // at a light or when the heading jitters.
        all.filter { c -> c.kind == Camera.Kind.SPEED && dist.getValue(c.id) <= ALERT_M &&
                states[c.id]?.let { it.announced && !it.passed } == true }
            .minByOrNull { dist.getValue(it.id) }
            ?.let { return Alert(message(it, dist.getValue(it.id), speedKmh), repeat = true) }

        if (heading == null || !moving) return null
        val announced = all.filter { states[it.id]?.announced == true }
        val candidate = all
            .filter { c -> dist.getValue(c.id) <= range(c) }
            .filter { c -> states[c.id]?.let { it.passed || it.announced } != true }
            .filter { c -> isAhead(c, lat, lon, heading) }
            .filter { c -> announced.none { a -> a.id != c.id && a.kind == c.kind && Geo.distance(a.lat, a.lon, c.lat, c.lon) <= CLUSTER_M } }
            .minByOrNull { dist.getValue(it.id) } ?: return null

        val d = dist.getValue(candidate.id)
        // First sighting only records the distance: the next fix must confirm we are closing in.
        val state = states[candidate.id] ?: run { states[candidate.id] = State(d); return null }
        if (!state.closing) return null
        state.announced = true
        return Alert(message(candidate, d, speedKmh), repeat = candidate.kind == Camera.Kind.SPEED)
    }

    private fun range(c: Camera) = if (c.kind == Camera.Kind.SPEED) ALERT_M else RED_LIGHT_M

    private fun isAhead(c: Camera, lat: Double, lon: Double, heading: Double): Boolean {
        if (Geo.angleDiff(heading, Geo.bearing(lat, lon, c.lat, c.lon)) > AHEAD_DEG) return false
        // A camera facing across our road belongs to another road (crossing / parallel ramp).
        val dir = c.direction ?: return true
        val diff = Geo.angleDiff(heading, dir)
        return diff <= 60 || diff >= 120
    }

    fun message(cam: Camera, dist: Double, speedKmh: Double): String {
        val metres = (Math.round(dist / 10.0) * 10).toInt()
        if (cam.kind == Camera.Kind.RED_LIGHT) return "מצלמת רמזור בעוד $metres מטר."
        val head = "מצלמת מהירות בעוד $metres מטר. מהירות נוכחית ${speedKmh.roundToInt()}."
        val limit = cam.limit ?: return head
        val over = ceil(speedKmh - limit).toInt()
        if (over <= 0) return "$head מותרת $limit."
        return "$head מותרת $limit. ${penalties.spoken(over, cam.urban)}"
    }

    companion object {
        const val ALERT_M = 500.0
        const val RED_LIGHT_M = 300.0
        const val FORGET_M = 1000.0
        const val CLUSTER_M = 300.0
        const val AHEAD_DEG = 35.0
        const val MIN_SPEED_KMH = 10.0
    }
}
