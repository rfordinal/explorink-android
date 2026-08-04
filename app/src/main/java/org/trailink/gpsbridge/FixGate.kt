package org.trailink.gpsbridge

/**
 * Is a new fix too big a jump from the last accepted one to trust on its own?
 *
 * A network-provider fix racing a live GPS, multipath off a building, a
 * momentary glitch -- all show up the same way: a position far from the last
 * accepted one, in too little time to have actually got there. Rather than
 * reject it outright (a real GPS reacquisition after a tunnel looks the same
 * at first), a suspect fix is held back one cycle. If the very next fix
 * lands nearer to it than to the old position, the phone kept going that way
 * and it was real; otherwise it was noise and gets dropped.
 *
 * This reasons about which fix gets trusted as the phone's position --
 * separate from and upstream of [SendPolicy], which decides whether an
 * already-trusted position is worth a BLE write. Fix acquisition, this
 * confirmation step, and the BLE send each run on their own cadence; mixing
 * their timeouts together is how a hunch about GPS noise turns into a stale
 * map on the device.
 *
 * Kept free of Android types for the same reason as [SendPolicy]: this is
 * the part with the reasoning in it, so it is the part that gets unit-tested.
 */
object FixGate {

    /** Generous enough to cover a motorcycle at speed; anything faster is not the ride, it's noise. */
    const val MAX_PLAUSIBLE_SPEED_MPS = 55.0

    /**
     * How long a suspect fix waits for confirmation before it is dropped on
     * its own, in provider elapsed-realtime millis. Bounded by the fix
     * interval ([BridgeService.LOCATION_INTERVAL_MS]), not the BLE send
     * interval -- a held-back position must clear within about one location
     * update, not one BLE write cycle.
     */
    const val CONFIRM_TIMEOUT_MS = 3_000L

    /**
     * @param distanceM metres from the last accepted fix
     * @param dtMs elapsed-realtime millis between the two fixes
     * @param prevAccuracyM the *previous* accepted fix's accuracy -- never the
     *   new fix's own, which a bad provider can claim as generously as it likes
     */
    fun isImplausibleJump(distanceM: Double, dtMs: Long, prevAccuracyM: Double): Boolean {
        val dtS = if (dtMs > 0) dtMs / 1000.0 else 0.0
        return distanceM > prevAccuracyM + MAX_PLAUSIBLE_SPEED_MPS * dtS
    }

    /** Did the confirming fix land closer to the suspect position than to the old one? */
    fun jumpConfirmedBy(distanceToPendingM: Double, distanceToAcceptedM: Double): Boolean =
        distanceToPendingM < distanceToAcceptedM

    /**
     * How long GPS can go quiet before a network fix is worth listening to at
     * all, in provider elapsed-realtime millis. Generous past one missed
     * [BridgeService.LOCATION_INTERVAL_MS] update so a single dropped GPS fix
     * doesn't hand control to network noise.
     */
    const val GPS_LIVE_WINDOW_MS = 5_000L

    /**
     * A network fix's own accuracy claim can't be trusted (that is what
     * [isImplausibleJump] already guards against), so the only question worth
     * asking of one is whether GPS itself has gone quiet. While GPS is still
     * answering, a network fix is field-observed noise -- confirming or not
     * confirming a suspect jump makes no difference, it should never be
     * trusted as the phone's position. Ignoring it here, before it ever
     * becomes a pending fix, is what stops it from "confirming" a jump too.
     *
     * @param msSinceLastGpsFix elapsed-realtime millis since the last
     *   GPS_PROVIDER fix arrived this session, or null if none has arrived yet
     */
    fun isNetworkFixIgnorable(msSinceLastGpsFix: Long?): Boolean =
        msSinceLastGpsFix != null && msSinceLastGpsFix < GPS_LIVE_WINDOW_MS
}
