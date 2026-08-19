package org.explorink.gpsbridge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Distance to a pin, and how it is written.
 *
 * A port of the device's own `PinGeo`
 * (`firmware/explorink/src/activities/map/PinGeo.cpp`), so the phone and the
 * panel never state two different distances to the same pin. Same
 * equirectangular approximation, same `cos(latitude)` taken at the **midpoint**
 * latitude, same 111,320 m per degree, same rounding brackets.
 *
 * Deliberately **not** `Location.distanceBetween()`, which is a Vincenty
 * inverse on the WGS84 ellipsoid: it is the better number, and it disagrees with
 * the device by tens of metres over a long separation. A rider comparing the
 * phone's `4.2 km` with the panel's `4.3 km` has no way to tell which one is
 * lying, and the answer -- "both, by less than the rounding" -- is not visible
 * from either screen. Agreement beats accuracy here; the pin is a direction and
 * a rough distance, never a survey.
 *
 * The phone has an FPU, so this uses doubles where the device uses a cosine
 * table and an integer square root. That is the one deliberate difference: the
 * table's own interpolation error is far below the 10 m the result is rounded
 * to, so the two agree at every printed value.
 */
object PinGeo {

    /** Metres per degree of latitude -- the WGS84 equatorial figure, as on the device. */
    const val METRES_PER_DEGREE = 111_320.0

    /** Straight-line metres between two 1e7 fixed-point coordinates. */
    fun distanceM(lat1E7: Int, lon1E7: Int, lat2E7: Int, lon2E7: Int): Long {
        val dLat = (lat2E7.toLong() - lat1E7.toLong()).toDouble() / 1e7
        var dLon = (lon2E7.toLong() - lon1E7.toLong()).toDouble() / 1e7
        // The short way round: two points either side of the antimeridian are
        // close, and a naive difference would make them 40,000 km apart.
        if (dLon > 180.0) dLon -= 360.0
        if (dLon < -180.0) dLon += 360.0

        // Midpoint latitude, not either end: at the far end of a long
        // north-south separation the two cosines differ, and either end would
        // make the distance depend on the argument order.
        val midLatDeg = (lat1E7.toLong() + lat2E7.toLong()).toDouble() / 2e7
        val scale = cos(abs(midLatDeg) * PI / 180.0)

        val dyM = dLat * METRES_PER_DEGREE
        val dxM = dLon * METRES_PER_DEGREE * scale
        return sqrt(dyM * dyM + dxM * dxM).roundToLong()
    }

    /**
     * `820 m` below a kilometre, `4.2 km` below ten, `37 km` above.
     *
     * 999 m prints as `1.0 km`, never `1000 m`: two spellings of one kilometre
     * in one list is how a rider decides the numbers cannot be trusted.
     */
    fun formatDistance(metres: Long): String {
        if (metres < 1000) {
            val rounded = ((metres + 5) / 10) * 10
            if (rounded < 1000) return "$rounded m"
        }
        if (metres < 10000) {
            val tenths = (metres + 50) / 100
            return "${tenths / 10}.${tenths % 10} km"
        }
        return "${(metres + 500) / 1000} km"
    }
}
