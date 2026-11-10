// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParkingWatcherTest {
    private val cam = ParkingCamera("רחוב ויצמן 16", "נס ציונה", 31.9300, 34.8000)

    @Test fun passingByIsSilent() {
        val w = ParkingWatcher(listOf(cam))
        for (i in 0..20) assertNull(w.onLocation(31.9297 + i * 0.00003, 34.8000, 30.0, i * 1000L))
    }

    @Test fun standingNearWarnsOnce() {
        val w = ParkingWatcher(listOf(cam))
        assertNull(w.onLocation(31.9301, 34.8000, 2.0, 0))
        assertNull(w.onLocation(31.9301, 34.8000, 0.0, 5_000))
        val a = w.onLocation(31.9301, 34.8000, 0.0, 9_000)
        assertNotNull(a)
        assertTrue(a!!.text.contains("ויצמן 16"))
        assertNull(w.onLocation(31.9301, 34.8000, 0.0, 30_000))
        // Leaves (300 m) and comes back: armed again.
        assertNull(w.onLocation(31.9330, 34.8000, 30.0, 40_000))
        assertNull(w.onLocation(31.9301, 34.8000, 0.0, 60_000))
        assertNotNull(w.onLocation(31.9301, 34.8000, 0.0, 70_000))
    }

    @Test fun shortStopIsSilent() {
        val w = ParkingWatcher(listOf(cam))
        assertNull(w.onLocation(31.9301, 34.8000, 0.0, 0))
        assertNull(w.onLocation(31.9301, 34.8000, 20.0, 4_000))
        assertNull(w.onLocation(31.9301, 34.8000, 0.0, 6_000))
        assertNull(w.onLocation(31.9301, 34.8000, 0.0, 12_000))
        assertNotNull(w.onLocation(31.9301, 34.8000, 0.0, 14_500))
    }

    @Test fun farStopIsSilent() {
        val w = ParkingWatcher(listOf(cam))
        for (t in 0..60) assertNull(w.onLocation(31.9306, 34.8000, 0.0, t * 1000L))
    }

    @Test fun dataFilePassesValidation() {
        val text = File("../data/${Database.PARKING}").readText(Charsets.UTF_8)
        DataUpdate.validate(Database.PARKING, text)
        val c = Database.parseParking(text)
        assertEquals(9, c.count { it.city == "נס ציונה" })
        assertTrue(c.all { it.lat in 31.90..31.96 && it.lon in 34.77..34.83 })
    }
}
