// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The files in data/ are what the app downloads from GitHub: they must pass the app's own checks. */
class DataFilesTest {
    private fun data(name: String) = File("../data/$name").readText(Charsets.UTF_8)

    @Test fun camerasPassValidation() {
        DataUpdate.validate(Database.CAMERAS, data(Database.CAMERAS))
        assertTrue(Database.parseCameras(data(Database.CAMERAS)).all { it.lat in 29.0..34.0 && it.lon in 34.0..36.0 })
    }

    @Test fun penaltiesMatchTheOrder() {
        val text = data(Database.PENALTIES)
        DataUpdate.validate(Database.PENALTIES, text)
        val p = Penalties.parse(text)
        assertEquals(Penalty(250, 0, false), p.forExcess(20, true))
        assertEquals(Penalty(750, 8, false), p.forExcess(21, true))
        assertEquals(Penalty(1500, 10, false), p.forExcess(40, true))
        assertTrue(p.forExcess(41, true)!!.court)
        assertEquals(Penalty(250, 0, false), p.forExcess(25, false))
        assertEquals(Penalty(750, 8, false), p.forExcess(40, false))
        assertEquals(Penalty(1500, 10, false), p.forExcess(50, false))
        assertTrue(p.forExcess(51, false)!!.court)
    }

    @Test fun busLanesAndRoadsPassValidation() {
        DataUpdate.validate(Database.BUS_LANES, data(Database.BUS_LANES))
        val index = data(RoadTiles.INDEX)
        DataUpdate.validate(RoadTiles.INDEX, index)
        // Every tile matches its sha256 in the index, as the app checks after a download.
        for ((t, sha) in RoadTiles.parseIndex(index)) {
            val bytes = File("../data/roads/$t.txt").readBytes()
            val got = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(t, sha, got)
        }
        // Ayalon (road 20) near HaShalom, north-bound: a tagged motorway-class limit, not the urban default.
        val m = Roads.parse(data("roads/${RoadTiles.name(32.0735, 34.7930)}.txt")).at(32.0735, 34.7930, 20.0)
        assertTrue(m != null && m.limit >= 80)
    }

    @Test fun tilesAnswerLikeTheWholeTable() {
        val whole = Roads.parse(data("roads.txt"))
        val tiles = HashMap<String, Roads>()
        var checked = 0
        // Points along the coast road and inland, both directions.
        for (i in 0 until 400) {
            val lat = 31.5 + i * 0.0047; val lon = 34.6 + (i % 37) * 0.017
            for (h in listOf(0.0, 90.0)) {
                val t = RoadTiles.name(lat, lon)
                val r = tiles.getOrPut(t) { Roads.parse(runCatching { data("roads/$t.txt") }.getOrDefault("")) }
                val a = whole.at(lat, lon, h); val b = r.at(lat, lon, h)
                assertEquals("$lat,$lon", a?.limit, b?.limit)
                if (a != null) checked++
            }
        }
        assertTrue(checked > 0)
    }

    @Test fun sectionsPassValidation() {
        DataUpdate.validate(Database.SECTIONS, data(Database.SECTIONS))
    }

    @Test(expected = Exception::class) fun truncatedDownloadIsRejected() {
        val text = data(Database.CAMERAS)
        DataUpdate.validate(Database.CAMERAS, text.substring(0, text.length / 2))
    }
}
