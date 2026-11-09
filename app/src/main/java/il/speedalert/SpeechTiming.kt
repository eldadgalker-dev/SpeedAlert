// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

/**
 * How long a sentence takes before and while it is heard (field report 2026-11-09: the last alert
 * said "150 metres" and the camera came right after it; the fix, the beeps, the engine start and
 * the sentence itself had taken seconds at speed).
 *
 * The time to a point of a sentence = age of the fix + the beeps + the engine's start + the text
 * before that point. The engine's start and its pace are learned from the sentences it spoke
 * (onStart / onDone), starting from defaults close to the Hebrew Google voice.
 */
class SpeechTiming(@Volatile var startMs: Double = START_MS, @Volatile var msPerUnit: Double = MS_PER_UNIT,
                   val beepGapMs: Long = 300) {
    /** Age of the fix the next sentence is made from, set by the service before each fix is handled. */
    @Volatile var fixAgeMs = 0L

    /** Milliseconds from now until the text [before] has been spoken, after [beeps] beeps. */
    fun until(before: String, beeps: Int) = fixAgeMs + beeps * beepGapMs + startMs + units(before) * msPerUnit

    /** The engine took [lagMs] to start [text] and [talkMs] to say it. */
    fun learn(text: String, lagMs: Long, talkMs: Long) {
        val u = units(text)
        if (u < 10 || lagMs < 0 || talkMs <= 0) return
        startMs = (startMs * 0.7 + lagMs.coerceIn(0, 3000) * 0.3).coerceIn(0.0, 3000.0)
        msPerUnit = (msPerUnit * 0.7 + talkMs / u * 0.3).coerceIn(30.0, 250.0)
    }

    companion object {
        const val START_MS = 400.0
        const val MS_PER_UNIT = 75.0

        /** Rough length of a text in speech: a digit is said as a word part, a full stop is a pause. */
        fun units(s: String) = s.sumOf { c ->
            when {
                c.isDigit() -> 3.0
                c == '.' -> 5.0
                c.isWhitespace() -> 1.0
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> 0.0   // niqqud
                else -> 1.0
            }
        }
    }
}
