// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
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
    private fun watcher(vararg cams: Camera) = CameraWatcher(cams.toList(), penalties)

    /** Drives north from [fromLat] to [toLat] in 11 m steps, one fix per second; returns the alerts. */
    private fun drive(w: CameraWatcher, fromLat: Double, toLat: Double, speed: Double, heading: (Int) -> Double = { 0.0 }): List<Alert> {
        val alerts = mutableListOf<Alert>()
        var lat = fromLat
        var i = 0
        while (lat <= toLat) {
            w.onLocation(lat, 34.8, speed, heading(i))?.let(alerts::add)
            lat += 0.0001
            i++
        }
        return alerts
    }

    @Test fun alertsWithCurrentSpeedFrom500MetresUntilPassed() {
        // Fixes every 11 m from 556 m before the camera: every fix inside 500 m speaks until it is passed.
        val alerts = drive(watcher(cam), 32.0000, 32.0120, 120.0)
        assertTrue(alerts.all { it.repeat })
        assertEquals(44, alerts.size)
        assertEquals("מצלמת מהירות בעוד 480 מטר. מהירות נוכחית 120. מותרת 90. הוֹרֵד 30 קילומטר לשעה. קנס 750 שקלים ועוד 8 נקודות.",
            alerts[0].text)
        assertTrue(alerts.last().text.substringAfter("בעוד ").substringBefore(" ").toInt() <= 20)
    }

    @Test fun legalSpeedAnnouncesSpeedAndLimit() {
        val alerts = drive(watcher(cam), 31.9990, 32.0010, 85.0)
        assertEquals("מצלמת מהירות בעוד 480 מטר. מהירות נוכחית 85. מותרת 90. מהירות תקינה.", alerts[0].text)
    }

    @Test fun keepsAlertingWhenStoppedNearTheCamera() {
        val w = watcher(cam)
        drive(w, 32.0000, 32.0030, 60.0)
        // Stopped at a light: no heading, speed 0, GPS jitter of a few metres both ways.
        for (jitter in listOf(0.0, 0.00004, -0.00003, 0.00005)) {
            assertTrue(w.onLocation(32.0030 + jitter, 34.8, 0.0, null)!!.text.contains("מהירות נוכחית 0."))
        }
    }

    @Test fun silentAfterPassingEvenWithHeadingJitter() {
        // Field report 2026-10-28: alerts continued after the camera.
        val w = watcher(cam)
        drive(w, 32.0000, 32.0049, 100.0) { i -> if (i % 2 == 0) 20.0 else 340.0 }
        assertTrue(drive(w, 32.0053, 32.0140, 100.0) { i -> if (i % 2 == 0) 20.0 else 340.0 }.isEmpty())
    }

    @Test fun oppositeDirectionTwinIsSilent() {
        val twin = cam.copy(id = 2, lat = 32.0060)
        val alerts = drive(watcher(cam, twin), 32.0000, 32.0120, 100.0)
        assertTrue(alerts.isNotEmpty())
        // Every alert is about the first camera: the distance only goes down.
        val metres = alerts.map { it.text.substringAfter("בעוד ").substringBefore(" ").toInt() }
        assertEquals(metres.sortedDescending(), metres)
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
        drive(w, 32.0000, 32.0060, 100.0)
        assertNull(w.onLocation(31.9900, 34.8, 100.0, 180.0)) // > 1 km away: forgotten
        assertTrue(drive(w, 32.0000, 32.0045, 100.0).isNotEmpty())
    }

    @Test fun redLightCameraRepeatsUntilPassed() {
        val red = cam.copy(kind = Camera.Kind.RED_LIGHT, limit = 50)
        val w = watcher(red)
        val alerts = drive(w, 32.0000, 32.0049, 60.0)
        assertTrue(alerts.size > 20 && alerts.all { it.repeat })
        assertEquals("מצלמת רמזור בעוד 280 מטר.", alerts[0].text)
        assertTrue(drive(w, 32.0053, 32.0120, 60.0).isEmpty())
        // Under the limit the alert says so.
        assertEquals("מצלמת רמזור בעוד 280 מטר. מהירות תקינה.", drive(watcher(red), 32.0000, 32.0030, 45.0)[0].text)
    }

    @Test fun redLightCameraSilentAfterStopping() {
        val w = watcher(cam.copy(kind = Camera.Kind.RED_LIGHT, limit = 50))
        assertTrue(drive(w, 32.0000, 32.0040, 60.0).isNotEmpty())
        assertNull(w.onLocation(32.0045, 34.8, 0.0, null))     // stopped at the light
        assertTrue(drive(w, 32.0046, 32.0049, 30.0).isEmpty())  // green: drives on, silent
    }

    @Test fun speedingWithoutCamera() {
        // A north-south road, limit 50, urban: "50 1 lat lon dlat dlon".
        val roads = Roads.parse("# test\n50 1 3200000 3480000 1000 0\n")
        val w = SpeedingWatcher(penalties).apply { this.roads = roads }
        assertNull(w.onLocation(32.002, 34.8, 75.0, 0.0, 0))            // 25 over: no alert
        assertNull(w.onLocation(32.002, 34.8, 80.0, 0.0, 1000))         // 30 over: confirming
        assertNull(w.onLocation(32.002, 34.8, 80.0, 0.0, 2000))
        val a = w.onLocation(32.002, 34.8, 80.0, 180.0, 3000)           // either direction of the road
        assertEquals("חריגת מהירות. מהירות נוכחית 80. מותרת 50. הוֹרֵד 30 קילומטר לשעה. קנס 750 שקלים ועוד 8 נקודות.", a!!.text)
        assertFalse(a.repeat)                                           // spoken once, again every 20 s
        assertNull(w.onLocation(32.002, 34.8, 80.0, 0.0, 4000))
        assertNull(w.onLocation(32.002, 34.8, 80.0, 90.0, 23_000))      // crossing, not along the road
        assertNull(w.onLocation(32.002, 34.8031, 80.0, 0.0, 23_000))    // 290 m off the road
        assertNull(w.onLocation(32.002, 34.8, 60.0, 0.0, 22_999))       // slowing down, still over
        assertEquals("חריגת מהירות. מהירות נוכחית 60. מותרת 50. הוֹרֵד 10 קילומטר לשעה. קנס 250 שקלים, ללא נקודות.",
            w.onLocation(32.002, 34.8, 60.0, 0.0, 23_000)!!.text)       // 20 s later, still over: again
        assertNull(w.onLocation(32.002, 34.8, 51.0, 0.0, 42_999))
        assertNotNull(w.onLocation(32.002, 34.8, 51.0, 0.0, 43_000))   // 1 over: still repeats
        assertEquals("מהירות תקינה. מותרת 50.", w.onLocation(32.002, 34.8, 48.0, 0.0, 44_000)!!.text)
        assertNull(w.onLocation(32.002, 34.8, 45.0, 0.0, 70_000))       // said once
        assertNull(w.onLocation(32.002, 34.8, 60.0, 0.0, 71_000))       // 10 over again: under the start threshold
    }

    @Test fun speedingUsesTheFasterRoadBesideARamp() {
        // Field report 2026-10-29: a 50 km/h ramp 8 m from the car, the 90 km/h highway 12 m away.
        val roads = Roads.parse(listOf("90 0 3200000 3480000 1000 0", "50 1 3200000 3480015 1000 0",
            "0 0 3200000 3490000 1000 0").joinToString("\n"))
        assertEquals(90, roads.at(32.002, 34.80008, 0.0)!!.limit)
        val w = SpeedingWatcher(penalties).apply { this.roads = roads }
        repeat(5) { assertNull(w.onLocation(32.002, 34.80008, 100.0, 0.0, it * 1000L)) }
        // Unknown limit (0) on the road: no alert at any speed.
        assertNull(roads.at(32.002, 34.9, 0.0))
    }

    @Test fun israeliDate() {
        assertEquals("28/10/2026", Database.israeliDate("2026-10-28"))
        assertEquals("?", Database.israeliDate("?"))
    }

    @Test fun busLaneAnnouncedOnceOnEntry() {
        val lane = BusLane("כביש 9", 32.0100, 34.8, 32.0300, 34.8)
        val w = BusLaneWatcher(listOf(lane))
        val alerts = mutableListOf<Alert>()
        var lat = 32.0000
        while (lat <= 32.0450) { w.onLocation(lat, 34.8, 80.0, 0.0)?.let(alerts::add); lat += 0.0001 }
        assertEquals(listOf("אכיפת נתיב תחבורה ציבורית בכביש 9. אסור לנסוע בנתיב התחבורה הציבורית."), alerts.map { it.text })
    }
}
