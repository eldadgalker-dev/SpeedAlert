// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AverageSpeedWatcherTest {
    // North-bound section of about 5.5 km (0.05 deg latitude), limit 100.
    private val section = AverageSection("כביש 6", 100, 32.000, 34.9, 32.050, 34.9)

    /** Drives north from 31.999 to 32.052 at [kmh] (one fix a second); returns the alert texts. */
    private fun drive(kmh: (Double) -> Double): List<Alert> {
        val w = AverageSpeedWatcher(listOf(section))
        val out = mutableListOf<Alert>()
        var lat = 31.999
        var t = 0L
        while (lat <= 32.052) {
            val v = kmh(lat)
            w.onLocation(lat, 34.9, v, 0.0, t)?.let(out::add)
            lat += v / 3.6 / 111_195.0   // metres per second -> degrees of latitude
            t += 1000
        }
        return out
    }

    @Test fun withinLimitOnlyStartAndEnd() {
        val a = drive { 95.0 }
        assertEquals(2, a.size)
        assertEquals("תחילת קטע אכיפת מהירות ממוצעת בכביש 6. מותרת 100.", a[0].text)
        assertTrue(a[1].text, a[1].text.startsWith("סוף קטע אכיפת מהירות ממוצעת. מהירות ממוצעת 9"))
    }

    @Test fun aboveAverageAlertsUntilBack() {
        // 120 for the first 2 km, then 80: the average goes above 100 and back under it.
        val a = drive { if (it < 32.018) 120.0 else 80.0 }
        val over = a.filter { it.repeat }
        assertTrue(over.isNotEmpty())
        assertTrue(over[0].text, over[0].text.startsWith("מהירות ממוצעת בקטע 1") && over[0].text.contains("סע עד"))
        assertTrue(a.any { it.text == "מהירות ממוצעת תקינה. מותרת 100." })
        assertTrue(a.last().text.startsWith("סוף קטע"))
    }

    @Test fun wrongDirectionIsIgnored() {
        val w = AverageSpeedWatcher(listOf(section))
        assertEquals(null, w.onLocation(32.0005, 34.9, 100.0, 180.0, 0))
    }
}
