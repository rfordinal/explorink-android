package org.explorink.gpsbridge

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Heading from the trend of recent positions, never from phone orientation --
 * the phone rides in a backpack or tank bag, so its own attitude says nothing
 * about the direction of travel.
 *
 * A candidate heading is the bearing from the oldest to the newest point in a
 * window of recent fixes. It only replaces the current heading when three
 * things all hold, because any one alone still lets noise through:
 *   - enough fixes have accumulated to call it a trend, not one hop
 *   - the average speed across the window clears a floor, so GPS jitter on a
 *     stationary phone cannot be mistaken for travel
 *   - the leg-to-leg bearings agree with the overall trend, so a wandering
 *     phone (lots of direction, little net distance) does not get to claim a
 *     heading
 *
 * Kept free of Android types for the same reason as [FixGate] and
 * [SendPolicy]: this is the part with the reasoning in it, so it is the part
 * that gets unit-tested.
 */
object HeadingTrend {

    /** Fixes required in the window before a trend is even considered. */
    const val WINDOW_SIZE = 5

    /** Below this average speed across the window, treat it as not moving. */
    const val MIN_SPEED_MPS = 0.5

    /** Above this much disagreement between individual legs and the overall
     * trend, treat the window as wandering rather than heading anywhere. */
    const val MAX_BEARING_SPREAD_DEG = 45.0

    /** A position sample: lat/lon in degrees, and the time it was fixed, in
     * elapsed-realtime nanos so gaps between fixes measure correctly. */
    data class Point(val latDeg: Double, val lonDeg: Double, val elapsedRealtimeNanos: Long)

    /**
     * A confident heading, and how tightly the window agreed on it.
     *
     * [spreadDeg] is the worst leg-to-leg disagreement against the overall
     * trend, which is the same number [heading] already tests against
     * [MAX_BEARING_SPREAD_DEG] before it will return anything. It is reported
     * rather than discarded because the device draws it: a tight window earns a
     * sharp arrow, a loose one earns a wedge, and no window at all earns no
     * heading mark. See the firmware's `docs/marker-fix-trust.md`.
     */
    data class Trend(val bearingDeg: Double, val spreadDeg: Double)

    /**
     * @param points oldest first, most recent last; only the last [windowSize]
     *   are considered, so callers can pass more history than that
     * @return the trend heading in degrees, [0, 360), or null if the window
     *   is not yet a confident trend
     */
    fun heading(
        points: List<Point>,
        windowSize: Int = WINDOW_SIZE,
        minSpeedMps: Double = MIN_SPEED_MPS,
        maxBearingSpreadDeg: Double = MAX_BEARING_SPREAD_DEG,
    ): Double? = trend(points, windowSize, minSpeedMps, maxBearingSpreadDeg)?.bearingDeg

    /**
     * The same decision as [heading], but keeping the spread the window agreed
     * to. Every gate is identical, so a caller cannot get a [Trend] where
     * [heading] would have returned null.
     *
     * @return the trend and its spread, or null if the window is not yet a
     *   confident trend
     */
    fun trend(
        points: List<Point>,
        windowSize: Int = WINDOW_SIZE,
        minSpeedMps: Double = MIN_SPEED_MPS,
        maxBearingSpreadDeg: Double = MAX_BEARING_SPREAD_DEG,
    ): Trend? {
        if (points.size < windowSize) return null
        val window = points.takeLast(windowSize)

        val totalDistanceM = distanceM(window.first(), window.last())
        val totalTimeS = (window.last().elapsedRealtimeNanos - window.first().elapsedRealtimeNanos) / 1_000_000_000.0
        if (totalTimeS <= 0.0 || totalDistanceM / totalTimeS < minSpeedMps) return null

        val trendBearing = bearingDeg(window.first(), window.last())
        val legBearings = (0 until window.size - 1).map { bearingDeg(window[it], window[it + 1]) }
        val spread = legBearings.maxOf { angleDiffDeg(it, trendBearing) }
        if (spread > maxBearingSpreadDeg) return null

        return Trend(trendBearing, spread)
    }

    /** Great-circle bearing from [from] to [to], in degrees, [0, 360). */
    fun bearingDeg(from: Point, to: Point): Double {
        val lat1 = Math.toRadians(from.latDeg)
        val lat2 = Math.toRadians(to.latDeg)
        val dLon = Math.toRadians(to.lonDeg - from.lonDeg)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Haversine distance between [from] and [to], in metres. */
    private fun distanceM(from: Point, to: Point): Double {
        val earthRadiusM = 6_371_000.0
        val lat1 = Math.toRadians(from.latDeg)
        val lat2 = Math.toRadians(to.latDeg)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(to.lonDeg - from.lonDeg)
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * earthRadiusM * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Smallest signed difference between two bearings, in degrees, always >= 0. */
    private fun angleDiffDeg(aDeg: Double, bDeg: Double): Double {
        var d = (aDeg - bDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return abs(d)
    }
}
