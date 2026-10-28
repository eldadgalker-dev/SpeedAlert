package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraWatcherTest {
    private val penalties = Penalties(
        urban = listOf(Penalties.Band(1, 20, Penalty(250, 0, false)), Penalties.Band(21, 30, Penalty(750, 8, false))),
        interurban = listOf(Penalties.Band(1, 25, Penalty(250, 0, false)), Penalties.Band(26, 40, Penalty(750, 8, false))),
    )
    // Camera due north of the start point; 0.0044 deg latitude is about 490 m.
    private val cam = Camera(1, 32.0044, 34.8, limit = 90, direction = null, urban = false)

    @Test fun alertsWhenApproachingAndSpeeding() {
        val w = CameraWatcher(listOf(cam), penalties)
        val msg = w.onLocation(32.0, 34.8, 120.0, heading = 0.0)
        assertEquals("מצלמת מהירות בעוד 490 מטר. הורד 30 קילומטר לשעה. קנס 750 שקלים ועוד 8 נקודות.", msg)
        assertNull("no repeat at same stage", w.onLocation(32.0005, 34.8, 120.0, heading = 0.0))
    }

    @Test fun secondAlertOnlyWhenStillSpeeding() {
        val w = CameraWatcher(listOf(cam), penalties)
        w.onLocation(32.0, 34.8, 100.0, heading = 0.0)
        val second = w.onLocation(32.003, 34.8, 100.0, heading = 0.0)
        assertEquals("מצלמת מהירות בעוד 160 מטר. הורד 10 קילומטר לשעה. קנס 250 שקלים, ללא נקודות.", second)
    }

    @Test fun legalSpeedAnnouncesLimit() {
        val msg = CameraWatcher(listOf(cam), penalties).onLocation(32.0, 34.8, 85.0, heading = 0.0)
        assertEquals("מצלמת מהירות בעוד 490 מטר. מהירות מותרת 90.", msg)
    }

    @Test fun ignoresCameraBehindOrOffToTheSide() {
        val w = CameraWatcher(listOf(cam), penalties)
        assertNull(w.onLocation(32.0, 34.8, 100.0, heading = 180.0))
        assertNull(w.onLocation(32.0, 34.8, 100.0, heading = 90.0))
    }

    @Test fun ignoresCameraFacingAcrossTheRoad() {
        val side = cam.copy(direction = 90.0)
        assertNull(CameraWatcher(listOf(side), penalties).onLocation(32.0, 34.8, 100.0, heading = 0.0))
    }

    @Test fun alertsAgainOnNextPass() {
        val w = CameraWatcher(listOf(cam), penalties)
        w.onLocation(32.0, 34.8, 100.0, 0.0)
        w.onLocation(31.99, 34.8, 100.0, 0.0) // > 700 m away: forgotten
        assertEquals(true, w.onLocation(32.0, 34.8, 100.0, 0.0)?.startsWith("מצלמת מהירות"))
    }
}
