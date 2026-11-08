// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

/**
 * How many beeps open the next sentence (user requests 2026-11-02 and 2026-11-08): three for the
 * first sentence of an event, one for every further sentence of the same event up to and
 * including its closing sentence ("מהירות תקינה"). An event is one alert key (a camera, speeding,
 * an average-speed section); it ends with its closing sentence, or when its key has been neither
 * due nor spoken for [EVENT_GAP_MS]. The pass sound at the camera is separate (AlertService).
 */
class EventBeeps {
    var key: String? = null
        private set
    private var seen = 0L

    /** [live]: the keys due on this fix (repeating and queued alerts) and the key being spoken. */
    fun tick(live: Collection<String?>, nowMs: Long) {
        if (key != null && key in live) seen = nowMs
        else if (nowMs - seen > EVENT_GAP_MS) key = null
    }

    fun forNext(next: Alert, nowMs: Long): Int {
        val same = next.key != null && next.key == key
        key = when {
            next.repeat -> next.key
            same -> null          // the closing sentence ends the event
            else -> key           // an unrelated one-time sentence leaves the event open
        }
        seen = nowMs
        return if (same) 1 else FIRST
    }

    companion object {
        const val FIRST = 3
        const val EVENT_GAP_MS = 3000L
    }
}
