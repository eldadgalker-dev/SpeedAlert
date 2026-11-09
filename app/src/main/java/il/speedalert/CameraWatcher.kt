// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import kotlin.math.roundToInt

/**
 * Decides what to say. From [ALERT_M] before a speed camera ahead, every fix returns the current
 * alert (distance, current speed, limit, how much to slow down, fine), so the service speaks
 * without a break until the camera is passed (user decision 2026-10-28). A red-light camera is
 * announced from [RED_LIGHT_M] before it and repeated until it is passed or the car stops
 * (user request 2026-10-29). At or under the limit the alert says "מהירות תקינה".
 *
 * Silence rules (field report 2026-10-28: alerts kept coming after the camera):
 * - a camera is passed when the distance to it grows while moving; it then stays silent until
 *   we are more than [FORGET_M] away from it;
 * - cameras within [CLUSTER_M] of an announced camera (the opposite-direction twin,
 *   duplicate map points) are silent while that camera is remembered;
 * - a new camera starts only when two fixes confirm we are closing in on it.
 *
 * [passAt] marks the moment the car is [PASS_BEFORE_M] before an announced camera, for the pass
 * sound (user requests 2026-11-02, 2026-11-09).
 */
/**
 * What to say; [repeat] alerts are spoken again and again while they last, the others once.
 * [key] names the event, so the repeats of one event get one beep instead of three.
 */
data class Alert(val text: String, val repeat: Boolean, val key: String? = null)

class CameraWatcher(cameras: List<Camera>, penalties: Penalties) {
    private class State(var lastDist: Double, var announced: Boolean = false, var passed: Boolean = false, var closing: Boolean = false,
                        var beeped: Boolean = false)

    @Volatile private var cameras: List<Camera> = cameras
    @Volatile private var penalties: Penalties = penalties
    private val states = mutableMapOf<Long, State>()

    /**
     * Speech timing (set by the service; null in tests): the distance is the one expected when it
     * is heard, and a sentence that would end after the camera is shortened.
     */
    @Volatile var timing: SpeechTiming? = null

    /** Distance to the nearest camera at the last fix, metres. */
    var nearestM = Double.MAX_VALUE
        private set

    /**
     * Set by the fix that sees an announced camera reached within [PASS_AHEAD_MS]: the camera id
     * and the milliseconds left until the car is at it (0 when it was just passed between two
     * fixes). Once per camera; null on the other fixes.
     */
    var passAt: Pair<Long, Long>? = null
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
        passAt = null
        var gone: Long? = null

        // Forget only with hysteresis, so GPS jitter near the limit cannot re-arm a camera.
        states.keys.retainAll { (dist[it] ?: Double.MAX_VALUE) < FORGET_M }
        for ((id, s) in states) {
            val d = dist[id] ?: continue
            // Moving away means passed; while standing still GPS jitter must not end the alert.
            val away = moving && d > s.lastDist + 3
            if (s.announced && !s.beeped && moving) {
                // The pass sound ends the event [PASS_BEFORE_M] before the camera (user request 2026-11-09).
                val eta = (d - PASS_BEFORE_M).coerceAtLeast(0.0) / (speedKmh / 3.6) * 1000
                if (away && s.lastDist < PASS_NEAR_M) { s.beeped = true; passAt = id to 0L }
                else if (!away && eta <= PASS_AHEAD_MS) { s.beeped = true; passAt = id to eta.toLong() }
            }
            // Moving away from an announced camera that was not reached: the road turned off before it
            // (user request 2026-11-08): the event ends with "מצלמה לא בהמשך הדרך".
            if (away && s.announced && !s.beeped && !s.passed) gone = id
            if (away) s.passed = true
            s.closing = d < s.lastDist - 1
            s.lastDist = d
        }

        // A red-light camera falls silent once the car stops at the light.
        if (speedKmh < STOP_KMH) for (c in all) if (c.kind == Camera.Kind.RED_LIGHT) states[c.id]?.let { if (it.announced) it.passed = true }

        gone?.let { return Alert(NOT_AHEAD, repeat = false, key = "$KEY$it") }

        // A camera already announced keeps talking until it is passed, even when stopped
        // at a light (speed cameras) or when the heading jitters.
        all.filter { c -> dist.getValue(c.id) <= range(c) &&
                states[c.id]?.let { it.announced && !it.passed } == true }
            .minByOrNull { dist.getValue(it.id) }
            ?.let { return Alert(message(it, dist.getValue(it.id), speedKmh, beeps = 1), repeat = true, key = "$KEY${it.id}") }

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
        return Alert(message(candidate, d, speedKmh), repeat = true, key = "$KEY${candidate.id}")
    }

    private fun range(c: Camera) = if (c.kind == Camera.Kind.SPEED) ALERT_M else RED_LIGHT_M

    private fun isAhead(c: Camera, lat: Double, lon: Double, heading: Double): Boolean {
        if (Geo.angleDiff(heading, Geo.bearing(lat, lon, c.lat, c.lon)) > AHEAD_DEG) return false
        // A camera facing across our road belongs to another road (crossing / parallel ramp).
        val dir = c.direction ?: return true
        val diff = Geo.angleDiff(heading, dir)
        return diff <= 60 || diff >= 120
    }

    /**
     * The sentence for [cam] at [dist] metres, said after [beeps] beeps. With [timing], the distance
     * is the one left when the number is heard, and the longest form that ends before the camera
     * is chosen (the fine goes first, then the current speed).
     */
    fun message(cam: Camera, dist: Double, speedKmh: Double, beeps: Int = EventBeeps.FIRST): String {
        val t = timing
        val mps = speedKmh / 3.6
        fun leftAfter(before: String) = if (t == null) dist else dist - mps * t.until(before, beeps) / 1000
        val opening = if (cam.kind == Camera.Kind.RED_LIGHT) "מצלמת רמזור בעוד " else "מצלמת מהירות בעוד "
        val heard = leftAfter(opening)
        val metres = if (t == null) (Math.round(dist / 10.0) * 10).toInt()
                     else (Math.round(heard / 10.0) * 10).toInt().coerceAtLeast(10)
        val head = "$opening$metres מטר."
        val forms = mutableListOf<String>()
        if (cam.kind == Camera.Kind.RED_LIGHT) {
            val ok = cam.limit?.let { speedKmh <= it } == true
            forms += head + if (ok) " $OK" else ""
            forms += head
        } else {
            val shown = speedKmh.roundToInt()
            val now = " מהירות נוכחית $shown."
            val limit = cam.limit
            if (limit == null) { forms += head + now; forms += head }
            else {
                val over = shown - limit   // from the speed as spoken (field report 2026-11-08)
                val tail = if (over <= 0) " $OK" else " " + penalties.spoken(over, cam.urban)
                val slow = if (over <= 0) " $OK" else " ${tail.trim().substringBefore('.')}."
                forms += "$head$now מותרת $limit.$tail"
                forms += "$head$now מותרת $limit.$slow"
                forms += "$head מותרת $limit.$slow"
            }
        }
        return if (t == null) forms.first() else forms.firstOrNull { leftAfter(it) >= 0 } ?: forms.last()
    }

    companion object {
        const val ALERT_M = 500.0
        const val RED_LIGHT_M = 300.0
        const val FORGET_M = 1000.0
        const val CLUSTER_M = 300.0
        const val AHEAD_DEG = 35.0
        const val MIN_SPEED_KMH = 10.0
        const val STOP_KMH = 5.0
        const val OK = "מהירות תקינה."
        const val NOT_AHEAD = "מצלמה לא בהמשך הדרך."
        const val KEY = "camera-"
        const val PASS_AHEAD_MS = 1500.0   // more than the 1 s between fixes, so the sound is scheduled before the camera
        const val PASS_NEAR_M = 150.0
        const val PASS_BEFORE_M = 10.0
    }
}
