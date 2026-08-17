package org.explorink.gpsbridge

/**
 * When is a position worth a BLE write?
 *
 * Every write changes `seq`, which wakes the X4 and can cost it a redraw. A
 * position that has not moved is not worth that. Sitting out a lunch hour should
 * cost the device one packet, not 720.
 *
 * The rule is distance-driven, which makes it speed-adaptive with almost no
 * speed term in it: at road speed the floor is what limits us and it behaves
 * like a fixed 7 s cadence; parked, one an hour. The one exception is walking
 * pace, which gets its own, slower floor (30 s) -- distance alone would still
 * send every ~36 s there, but that is fragile: a slightly brisker walk drops
 * below it.
 *
 * One more exception on top: if the phone sat down on a bad fix (weak GPS
 * indoors, say) and then settles on a good one while still parked, that is
 * worth fixing right away rather than waiting an hour for the next keepalive.
 *
 * Kept free of Android types on purpose -- this is the part with the reasoning
 * in it, so it is the part that gets unit-tested.
 */
object SendPolicy {

    /** Never send faster than this, whatever the phone is doing. */
    const val MIN_INTERVAL_MS = 7_000L

    /**
     * At walking pace, [MIN_INTERVAL_MS] is too chatty for how little the map
     * changes between packets -- this floor replaces it whenever the average
     * speed since the last send is at or below [WALKING_SPEED_MAX_MPS].
     */
    const val WALKING_MIN_INTERVAL_MS = 30_000L

    /** ~8 km/h -- brisk walk. Above this, the ordinary [MIN_INTERVAL_MS] floor applies. */
    const val WALKING_SPEED_MAX_MPS = 2.2

    /** Never send slower than this, so the device knows the phone is alive. */
    const val KEEPALIVE_INTERVAL_MS = 60L * 60L * 1000L

    /** Movement that earns a packet on its own. */
    const val MOVE_THRESHOLD_M = 50.0

    /**
     * A heading change earns a packet too -- a corner changes the map more than
     * crawling down a straight -- but only with real movement behind it. A
     * stationary phone's bearing wanders across all 16 sectors on noise alone.
     */
    const val HEADING_MIN_MOVE_M = 10.0

    /** Below this, the phone counts as parked rather than drifting on GPS noise. */
    const val STATIONARY_MOVE_MAX_M = 5.0

    /** How long parked before a bad last-sent fix is worth correcting. */
    const val STATIONARY_MIN_MS = 10_000L

    /** The last sent fix has to be at least this bad for a correction to be worth it. */
    const val NOT_PRECISE_ACCURACY_M = 20.0

    /** The current fix has to be at least this good to be trusted as the correction. */
    const val PRECISE_ACCURACY_M = 10.0

    /** How many precise fixes in a row, so one lucky good fix can't trigger it. */
    const val PRECISE_FIX_STREAK = 3

    enum class Reason {
        /** Nothing has been sent yet on this link. */
        FIRST,

        /** Moved far enough to be worth a redraw. */
        MOVED,

        /** Turned, and actually moved while doing it. */
        HEADING,

        /** Nothing happened for an hour; say hello anyway. */
        KEEPALIVE,

        /** Parked on a bad fix; the GPS has since settled on a good one. */
        CORRECTION,
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
     * Average speed since the last send. There is no live speed reading here on
     * purpose -- this stays a pure function of the same distance/time the rest
     * of the policy already uses, so it needs no Android type and no new input.
     */
    private fun isWalkingPace(movedM: Double, sinceLastMs: Long): Boolean {
        if (sinceLastMs <= 0L) return false
        return movedM / (sinceLastMs / 1000.0) <= WALKING_SPEED_MAX_MPS
    }

    /**
     * @param hasSent false before the first packet of this link
     * @param sinceLastMs millis since the last send; ignored when [hasSent] is false
     * @param movedM metres from the last sent position
     * @param accuracyM the current fix's accuracy, which raises the move threshold
     * @param headingChanged the 16-sector heading differs from the last sent one
     * @param lastSentAccuracyM the accuracy of the fix the last packet actually carried
     * @param consecutivePreciseFixCount how many fixes in a row have been at or
     *   under [PRECISE_ACCURACY_M]
     * @return why to send now, or null to stay quiet
     */
    fun decide(
        hasSent: Boolean,
        sinceLastMs: Long,
        movedM: Double,
        accuracyM: Double,
        headingChanged: Boolean,
        lastSentAccuracyM: Double = 0.0,
        consecutivePreciseFixCount: Int = 0,
    ): Reason? {
        if (!hasSent) return Reason.FIRST

        // Bypasses the floor below: this is a one-off correction of a bad fix
        // already sent, not a routine update, so it should not wait out a floor
        // meant to pace routine updates.
        if (sinceLastMs >= STATIONARY_MIN_MS &&
            movedM <= STATIONARY_MOVE_MAX_M &&
            lastSentAccuracyM > NOT_PRECISE_ACCURACY_M &&
            accuracyM <= PRECISE_ACCURACY_M &&
            consecutivePreciseFixCount >= PRECISE_FIX_STREAK
        ) {
            return Reason.CORRECTION
        }

        val floorMs = if (isWalkingPace(movedM, sinceLastMs)) WALKING_MIN_INTERVAL_MS else MIN_INTERVAL_MS
        if (sinceLastMs < floorMs) return null
        if (movedM >= moveThresholdM(accuracyM)) return Reason.MOVED
        if (headingChanged && movedM >= HEADING_MIN_MOVE_M) return Reason.HEADING
        if (sinceLastMs >= KEEPALIVE_INTERVAL_MS) return Reason.KEEPALIVE
        return null
    }
}
