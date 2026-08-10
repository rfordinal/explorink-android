package org.explorink.gpsbridge

/**
 * When is a position worth a BLE write?
 *
 * Every write changes `seq`, which wakes the X4 and can cost it a redraw. A
 * position that has not moved is not worth that. Sitting out a lunch hour should
 * cost the device one packet, not 720.
 *
 * The rule is distance-driven, which makes it speed-adaptive with no speed term
 * in it: at road speed the floor is what limits us and it behaves like the old
 * fixed 5 s cadence; at hiking pace it is one packet every ~18 s; parked, one an
 * hour.
 *
 * Kept free of Android types on purpose -- this is the part with the reasoning
 * in it, so it is the part that gets unit-tested.
 */
object SendPolicy {

    /** Never send faster than this, whatever the phone is doing. */
    const val MIN_INTERVAL_MS = 5_000L

    /** Never send slower than this, so the device knows the phone is alive. */
    const val KEEPALIVE_INTERVAL_MS = 60L * 60L * 1000L

    /** Movement that earns a packet on its own. */
    const val MOVE_THRESHOLD_M = 25.0

    /**
     * A heading change earns a packet too -- a corner changes the map more than
     * crawling down a straight -- but only with real movement behind it. A
     * stationary phone's bearing wanders across all 16 sectors on noise alone.
     */
    const val HEADING_MIN_MOVE_M = 10.0

    enum class Reason {
        /** Nothing has been sent yet on this link. */
        FIRST,

        /** Moved far enough to be worth a redraw. */
        MOVED,

        /** Turned, and actually moved while doing it. */
        HEADING,

        /** Nothing happened for an hour; say hello anyway. */
        KEEPALIVE,
        ;

        /** Lower-case name, which is what goes in the log line. */
        val logName: String get() = name.lowercase()
    }

    /**
     * Movement has to beat the fix's own accuracy, or a bad fix indoors triggers
     * sends for movement the phone never made.
     */
    fun moveThresholdM(accuracyM: Double): Double = maxOf(MOVE_THRESHOLD_M, accuracyM)

    /**
     * @param hasSent false before the first packet of this link
     * @param sinceLastMs millis since the last send; ignored when [hasSent] is false
     * @param movedM metres from the last sent position
     * @param accuracyM the current fix's accuracy, which raises the move threshold
     * @param headingChanged the 16-sector heading differs from the last sent one
     * @return why to send now, or null to stay quiet
     */
    fun decide(
        hasSent: Boolean,
        sinceLastMs: Long,
        movedM: Double,
        accuracyM: Double,
        headingChanged: Boolean,
    ): Reason? {
        if (!hasSent) return Reason.FIRST
        if (sinceLastMs < MIN_INTERVAL_MS) return null
        if (movedM >= moveThresholdM(accuracyM)) return Reason.MOVED
        if (headingChanged && movedM >= HEADING_MIN_MOVE_M) return Reason.HEADING
        if (sinceLastMs >= KEEPALIVE_INTERVAL_MS) return Reason.KEEPALIVE
        return null
    }
}
