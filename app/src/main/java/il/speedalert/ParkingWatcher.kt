// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

/** A fixed parking-enforcement camera (data/parking.json). */
data class ParkingCamera(val name: String, val city: String, val lat: Double, val lon: Double)

/**
 * Parking-enforcement cameras (user request 2026-11-10, Ness Ziona). They record stopping, not
 * speed, so a car that passes by hears nothing: the warning comes once the car has stood within
 * [NEAR_M] of a camera for [STOP_MS] (about to park, or dropping someone off). One warning per
 * camera until the car is [REARM_M] away from it.
 */
class ParkingWatcher(@Volatile var cameras: List<ParkingCamera>) {
    private val warned = mutableSetOf<ParkingCamera>()
    private var stoppedAt: ParkingCamera? = null
    private var stoppedSince = 0L

    fun onLocation(lat: Double, lon: Double, speedKmh: Double, timeMs: Long): Alert? {
        var nearest: ParkingCamera? = null
        var best = Double.MAX_VALUE
        for (c in cameras) {
            val d = Geo.distance(lat, lon, c.lat, c.lon)
            if (d > REARM_M) warned -= c
            if (d < best) { best = d; nearest = c }
        }
        if (nearest == null || best > NEAR_M || speedKmh > STOP_KMH || nearest in warned) {
            stoppedAt = null
            return null
        }
        if (stoppedAt != nearest) { stoppedAt = nearest; stoppedSince = timeMs; return null }
        if (timeMs - stoppedSince < STOP_MS) return null
        warned += nearest
        stoppedAt = null
        return Alert("מצלמת אכיפת חנייה ב${nearest.name}. אסור לעצור ולחנות כאן, גם כשהנהג ברכב.", repeat = false)
    }

    companion object {
        const val NEAR_M = 40.0
        const val STOP_KMH = 5.0
        const val STOP_MS = 8_000L
        const val REARM_M = 200.0
    }
}
