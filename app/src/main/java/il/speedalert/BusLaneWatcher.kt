// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Bus-lane enforcement (data/buslanes.json).
 * Interurban sections (gov.il list): announced once when driving into a section from either end
 * within [ENTER_M]; armed again after leaving it by [REARM_M].
 * Urban lanes (data.gov.il, user decision 2026-11-09): one way, from the start only, within
 * [URBAN_ENTER_M] and along the lane, and only in the lane's enforcement hours; one announcement
 * per street until the car is [URBAN_REARM_M] away from the point where it was given.
 */
class BusLaneWatcher(@Volatile var lanes: List<BusLane>) {
    private val inside = mutableSetOf<BusLane>()
    var clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("Asia/Jerusalem")) }
    private var lastStreet: String? = null
    private var lastLat = 0.0
    private var lastLon = 0.0

    fun onLocation(lat: Double, lon: Double, speedKmh: Double, heading: Double?): Alert? {
        var out: Alert? = null
        if (lastStreet != null && Geo.distance(lat, lon, lastLat, lastLon) > URBAN_REARM_M) lastStreet = null
        for (z in lanes) {
            val len = Geo.distance(z.aLat, z.aLon, z.bLat, z.bLon)
            val da = Geo.distance(lat, lon, z.aLat, z.aLon)
            val db = Geo.distance(lat, lon, z.bLat, z.bLon)
            if (z.oneWay) {
                if (z in inside) { if (minOf(da, db) > URBAN_EXIT_M && da + db > len + 2 * URBAN_EXIT_M) inside.remove(z); continue }
                if (out != null || heading == null || speedKmh < CameraWatcher.MIN_SPEED_KMH) continue
                if (da > URBAN_ENTER_M || db < len - URBAN_ENTER_M / 2) continue
                if (Geo.angleDiff(heading, Geo.bearing(z.aLat, z.aLon, z.bLat, z.bLon)) > URBAN_ANGLE) continue
                inside += z
                if (z.street == lastStreet || z.hours?.active(clock()) != true) continue
                lastStreet = z.street; lastLat = lat; lastLon = lon
                out = Alert("מצלמת אכיפה בנתיב התחבורה הציבורית ב${z.street}. אסור לנסוע בנתיב.", repeat = false)
                continue
            }
            if (z in inside) {
                if (minOf(da, db) > REARM_M && da + db > len + 2 * REARM_M) inside.remove(z)
                continue
            }
            if (heading == null || speedKmh < CameraWatcher.MIN_SPEED_KMH) continue
            // Entering at an end: near it, that end ahead, and still outside (the far end is farther than the section).
            val enter = listOf(Triple(da, z.aLat, z.aLon) to db, Triple(db, z.bLat, z.bLon) to da).any { (near, far) ->
                near.first <= ENTER_M && far > len &&
                    Geo.angleDiff(heading, Geo.bearing(lat, lon, near.second, near.third)) <= 45
            }
            if (enter) {
                inside += z
                out = Alert("אכיפת נתיב תחבורה ציבורית ב${z.name}. אסור לנסוע בנתיב התחבורה הציבורית.", repeat = false)
            }
        }
        return out
    }

    companion object {
        const val ENTER_M = 300.0
        const val REARM_M = 1000.0
        const val URBAN_ENTER_M = 40.0
        const val URBAN_EXIT_M = 60.0
        const val URBAN_ANGLE = 35.0
        const val URBAN_REARM_M = 800.0
    }
}
