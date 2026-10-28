package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWatcherTest {
    private val penalties = Penalties(
        urban = listOf(Penalties.Band(1, 20, Penalty(250, 0, false)), Penalties.Band(21, 30, Penalty(750, 8, false))),
        interurban = listOf(Penalties.Band(1, 25, Penalty(250, 0, false)), Penalties.Band(26, 40, Penalty(750, 8, false))),
    )

    // Camera on a north-bound road; 0.0001 deg latitude is about 11 m.
    private val cam = Camera(1, 32.0050, 34.8, limit = 90, direction = null, urban = false)
    private var now = 0L
    private fun watcher(vararg cams: Camera) = CameraWatcher(cams.toList(), penalties) { now }

    /** Drives north from [fromLat] to [toLat] in 11 m steps, one fix per second; returns spoken alerts. */
    private fun drive(w: CameraWatcher, fromLat: Double, toLat: Double, speed: Double, heading: (Int) -> Double = { 0.0 }): List<String> {
        val alerts = mutableListOf<String>()
        var lat = fromLat
        var i = 0
        while (lat <= toLat) {
            w.onLocation(lat, 34.8, speed, heading(i))?.let(alerts::add)
            lat += 0.0001
            now += 1000
            i++
        }
        return alerts
    }

    @Test fun alertsWhenApproachingAndSpeeding() {
        val alerts = drive(watcher(cam), 31.9990, 32.0010, 120.0)
        assertEquals(1, alerts.size)
        assertEquals("מצלמת מהירות בעוד 480 מטר. הורד 30 קילומטר לשעה. קנס 750 שקלים ועוד 8 נקודות.", alerts[0])
    }

    @Test fun secondAlertOnlyWhenStillSpeeding() {
        assertEquals(2, drive(watcher(cam), 32.0000, 32.0045, 100.0).size)
        assertEquals(1, drive(watcher(cam), 32.0000, 32.0045, 85.0).size)
    }

    @Test fun legalSpeedAnnouncesLimit() {
        val alerts = drive(watcher(cam), 31.9990, 32.0010, 85.0)
        assertEquals(listOf("מצלמת מהירות בעוד 480 מטר. מהירות מותרת 90."), alerts)
    }

    @Test fun silentAfterPassingEvenWithHeadingJitter() {
        // Field report 2026-10-28: alerts continued after the camera.
        val alerts = drive(watcher(cam), 32.0000, 32.0120, 100.0) { i -> if (i % 2 == 0) 20.0 else 340.0 }
        assertEquals(2, alerts.size)
    }

    @Test fun oppositeDirectionTwinIsSilent() {
        val twin = cam.copy(id = 2, lat = 32.0060)
        val alerts = drive(watcher(cam, twin), 32.0000, 32.0120, 100.0)
        assertEquals(2, alerts.size)
        assertTrue(alerts.all { it.startsWith("מצלמת מהירות") })
    }

    @Test fun ignoresCameraBehindOrOffToTheSide() {
        assertTrue(drive(watcher(cam), 32.0000, 32.0010, 100.0) { 180.0 }.isEmpty())
        assertTrue(drive(watcher(cam), 32.0000, 32.0010, 100.0) { 90.0 }.isEmpty())
    }

    @Test fun ignoresCameraFacingAcrossTheRoad() {
        assertTrue(drive(watcher(cam.copy(direction = 90.0)), 32.0000, 32.0045, 100.0).isEmpty())
    }

    @Test fun alertsAgainOnNextPass() {
        val w = watcher(cam)
        drive(w, 32.0000, 32.0045, 100.0)
        assertNull(w.onLocation(31.9900, 34.8, 100.0, 180.0)) // > 1 km away: forgotten
        now += 60_000
        assertEquals(2, drive(w, 32.0000, 32.0045, 100.0).size)
    }
}
