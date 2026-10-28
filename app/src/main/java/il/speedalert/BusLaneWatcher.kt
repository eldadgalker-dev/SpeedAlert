package il.speedalert

/**
 * Bus-lane enforcement sections (Ministry of Transport list, data/buslanes.json). Announced once
 * when driving into a section from either end; armed again after leaving it by [REARM_M].
 */
class BusLaneWatcher(@Volatile var lanes: List<BusLane>) {
    private val inside = mutableSetOf<BusLane>()

    fun onLocation(lat: Double, lon: Double, speedKmh: Double, heading: Double?): Alert? {
        var out: Alert? = null
        for (z in lanes) {
            val len = Geo.distance(z.aLat, z.aLon, z.bLat, z.bLon)
            val da = Geo.distance(lat, lon, z.aLat, z.aLon)
            val db = Geo.distance(lat, lon, z.bLat, z.bLon)
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
    }
}
