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

    @Test fun updateIsSaidAtTheStartOfEveryDrive() {
        val n = UpdateNotice()
        val min = 60_000L
        assertTrue(n.onFix(false, 1000, true) == null)                 // standing: no drive yet
        val a = n.onFix(true, 2000, true)
        assertTrue(n.started)
        assertTrue(a?.text == UpdateNotice.TEXT && a.repeat == false)
        assertTrue(n.onFix(true, 3000, true) == null)                  // once per drive
        assertFalse(n.started)
        assertTrue(n.onFix(true, 5 * min, true) == null)               // same drive (stops shorter than 10 minutes)
        assertTrue(n.onFix(true, 20 * min, true) != null)              // a new drive after a pause: said again
    }

    @Test fun anAnswerThatComesSoonAfterTheStartIsStillSaid() {
        val n = UpdateNotice()
        assertTrue(n.onFix(true, 1000, false) == null)                 // started, the answer not in yet
        assertTrue(n.onFix(true, 30_000, true) != null)                // in 30 s later
        val m = UpdateNotice()
        m.onFix(true, 1000, false)
        assertTrue(m.onFix(true, 1000 + UpdateNotice.WINDOW_MS + 1, true) == null)   // too late in the drive
        assertTrue(UpdateNotice().onFix(true, 1000, false) == null)    // no newer version: nothing
    }
}
