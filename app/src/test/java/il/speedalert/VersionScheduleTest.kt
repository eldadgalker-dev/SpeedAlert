// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionScheduleTest {
    private val min = 60_000L

    @Test fun firstCheckIsAtOnce() {
        assertTrue(VersionSchedule.due(5, 0, false, VersionSchedule.ACTIVE_MS, true))
    }

    @Test fun everyTwoMinutesOnTheScreen() {
        val t = 100 * min
        assertFalse(VersionSchedule.due(t + 2 * min - 1, t, false, VersionSchedule.ACTIVE_MS, true))
        assertTrue(VersionSchedule.due(t + 2 * min, t, false, VersionSchedule.ACTIVE_MS, true))
    }

    @Test fun aFailedCheckIsRepeatedAfterAMinute() {
        val t = 100 * min
        assertFalse(VersionSchedule.due(t + min - 1, t, true, VersionSchedule.SAVING_MS, true))
        assertTrue(VersionSchedule.due(t + min, t, true, VersionSchedule.SAVING_MS, true))
        assertFalse(VersionSchedule.due(t + 29 * min, t, false, VersionSchedule.SAVING_MS, true))
    }

    @Test fun aReturnToTheScreenAsksAfterFiveSeconds() {
        val t = 100 * min
        assertFalse(VersionSchedule.due(t + 4_000, t, false, VersionSchedule.RESUME_MS, true))
        assertTrue(VersionSchedule.due(t + 5_000, t, false, VersionSchedule.RESUME_MS, true))
        assertTrue(VersionSchedule.due(t + 5_000, t, true, VersionSchedule.RESUME_MS, true))   // the retry never waits longer than the interval
    }

    @Test fun neverWithoutInternet() {
        assertFalse(VersionSchedule.due(5, 0, false, VersionSchedule.ACTIVE_MS, false))
        assertFalse(VersionSchedule.due(1000 * min, 1, true, VersionSchedule.ACTIVE_MS, false))
    }
}
