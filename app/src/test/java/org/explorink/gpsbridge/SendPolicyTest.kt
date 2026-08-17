package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The send policy decides how often the X4 gets woken up, so its edges are worth
 * pinning down: a bug here either spams the device or goes silent on a ride.
 */
class SendPolicyTest {

    private val acc = 5.0 // a normal outdoor fix, below the 50 m move threshold

    @Test
    fun firstPacketAlwaysGoes() {
        assertEquals(
            SendPolicy.Reason.FIRST,
            SendPolicy.decide(
                hasSent = false,
                sinceLastMs = 0,
                movedM = 0.0,
                accuracyM = acc,
                headingChanged = false,
            ),
        )
    }

    @Test
    fun nothingGoesFasterThanTheFloor() {
        // Moving fast enough to earn a packet, but only 6 s since the last one.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 6_999,
                movedM = 1000.0,
                accuracyM = acc,
                headingChanged = true,
            )
        )
    }

    @Test
    fun movementPastTheThresholdSends() {
        assertEquals(
            SendPolicy.Reason.MOVED,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 7_000,
                movedM = 50.0,
                accuracyM = acc,
                headingChanged = false,
            ),
        )
    }

    @Test
    fun sittingStillStaysQuiet() {
        // Ten minutes parked, GPS jittering a couple of metres: no packet.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 10 * 60_000L,
                movedM = 3.0,
                accuracyM = acc,
                headingChanged = false,
            )
        )
    }

    @Test
    fun aParkedHourEndsInOneKeepAlive() {
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.KEEPALIVE_INTERVAL_MS - 1,
                movedM = 0.0,
                accuracyM = acc,
                headingChanged = false,
            )
        )
        assertEquals(
            SendPolicy.Reason.KEEPALIVE,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.KEEPALIVE_INTERVAL_MS,
                movedM = 0.0,
                accuracyM = acc,
                headingChanged = false,
            ),
        )
    }

    @Test
    fun headingChangeNeedsRealMovementBehindIt() {
        // Bearing wandering on noise while stationary: not a packet.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 60_000,
                movedM = 2.0,
                accuracyM = acc,
                headingChanged = true,
            )
        )
        // Same turn, but the phone actually went somewhere.
        assertEquals(
            SendPolicy.Reason.HEADING,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 60_000,
                movedM = SendPolicy.HEADING_MIN_MOVE_M,
                accuracyM = acc,
                headingChanged = true,
            ),
        )
    }

    @Test
    fun aBadFixRaisesTheThresholdInsteadOfTriggering() {
        // Indoors: 65 m of "movement" with 70 m of accuracy is not movement.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 30_000,
                movedM = 65.0,
                accuracyM = 70.0,
                headingChanged = false,
            )
        )
        assertEquals(70.0, SendPolicy.moveThresholdM(70.0), 0.0)
        // A good fix never lowers it below the constant.
        assertEquals(SendPolicy.MOVE_THRESHOLD_M, SendPolicy.moveThresholdM(1.0), 0.0)
    }

    @Test
    fun aKnownDiagonalReplacesTheFlatThreshold() {
        // Rung 4 (20 m/px, 480x800 px panel): diagonal is ~18,660 m.
        val diagonalM = 18_660.0
        val expected = diagonalM * SendPolicy.DIAGONAL_THRESHOLD_FRACTION
        assertEquals(expected, SendPolicy.moveThresholdM(1.0, diagonalM), 0.01)
        // Not the flat constant -- the device's own viewport size won.
        assertEquals(false, expected == SendPolicy.MOVE_THRESHOLD_M)
    }

    @Test
    fun noDiagonalHeardYetFallsBackToTheFlatConstant() {
        // Never heard a DIAG_M on this link -- same behaviour as before this
        // parameter existed.
        assertEquals(SendPolicy.MOVE_THRESHOLD_M, SendPolicy.moveThresholdM(1.0, null), 0.0)
        assertEquals(SendPolicy.MOVE_THRESHOLD_M, SendPolicy.moveThresholdM(1.0), 0.0)
    }

    @Test
    fun accuracyStillOverridesADiagonalDerivedThreshold() {
        // A tiny rung (diagonal near zero, e.g. rung 0) must not let a bad fix
        // through just because the derived threshold collapsed underneath it.
        assertEquals(70.0, SendPolicy.moveThresholdM(accuracyM = 70.0, diagonalM = 933.0), 0.0)
    }

    @Test
    fun diagonalDerivedThresholdFlowsThroughDecide() {
        // rung 0 (933 m diagonal): derived threshold ~7.5 m -- far under the
        // flat 25/50 m constant, so this segment only makes sense once a
        // diagonal is known. Both movements here are small enough to read as
        // walking pace, so sinceLastMs is set to WALKING_MIN_INTERVAL_MS --
        // the floor that actually applies -- rather than MIN_INTERVAL_MS, or
        // the floor check alone would swallow the result being tested here.
        val diagonalM = 933.0
        val derived = diagonalM * SendPolicy.DIAGONAL_THRESHOLD_FRACTION

        // Under the derived threshold: stay quiet.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.WALKING_MIN_INTERVAL_MS,
                movedM = derived - 1.0,
                accuracyM = acc,
                headingChanged = false,
                diagonalM = diagonalM,
            )
        )
        // Over it: MOVED fires, even though this movement would never have
        // cleared the flat MOVE_THRESHOLD_M constant on its own.
        assertEquals(
            SendPolicy.Reason.MOVED,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.WALKING_MIN_INTERVAL_MS,
                movedM = derived + 1.0,
                accuracyM = acc,
                headingChanged = false,
                diagonalM = diagonalM,
            ),
        )
    }

    @Test
    fun roadSpeedCollapsesToTheFixedCadence() {
        // 90 km/h is 25 m/s, so the move threshold is met well within the 7 s
        // floor, and the floor is the only thing limiting the rate.
        val movedInSevenSeconds = 25.0 * 7
        assertEquals(
            SendPolicy.Reason.MOVED,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.MIN_INTERVAL_MS,
                movedM = movedInSevenSeconds,
                accuracyM = acc,
                headingChanged = false,
            ),
        )
    }

    @Test
    fun hikingPaceSendsEveryThirtysixSecondsish() {
        // 5 km/h is 1.39 m/s. At 7 s only 9.7 m has passed: quiet.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 7_000,
                movedM = 1.39 * 7,
                accuracyM = acc,
                headingChanged = false,
            )
        )
        // By 36 s it is just past 50 m: send.
        assertEquals(
            SendPolicy.Reason.MOVED,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 36_000,
                movedM = 1.39 * 36,
                accuracyM = acc,
                headingChanged = false,
            ),
        )
    }

    @Test
    fun reasonLogNamesAreStableAndLowerCase() {
        assertEquals("first", SendPolicy.Reason.FIRST.logName)
        assertEquals("moved", SendPolicy.Reason.MOVED.logName)
        assertEquals("heading", SendPolicy.Reason.HEADING.logName)
        assertEquals("keepalive", SendPolicy.Reason.KEEPALIVE.logName)
    }

    @Test
    fun boundsAreWhatTheBriefAsksFor() {
        assertEquals(7_000L, SendPolicy.MIN_INTERVAL_MS)
        assertEquals(60L * 60L * 1000L, SendPolicy.KEEPALIVE_INTERVAL_MS)
        assertEquals(30_000L, SendPolicy.WALKING_MIN_INTERVAL_MS)
    }

    @Test
    fun walkingPaceBlocksAMoveThatWouldOtherwiseQualify() {
        // 50 m in 25 s is 2 m/s -- walking pace -- and clears the 50 m move
        // threshold, but the 30 s walking floor hasn't elapsed yet.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 25_000,
                movedM = 50.0,
                accuracyM = acc,
                headingChanged = false,
            )
        )
    }

    @Test
    fun walkingPaceSendsOnceItsOwnFloorElapses() {
        // Same 2 m/s pace, now 30 s in: the walking floor is satisfied.
        assertEquals(
            SendPolicy.Reason.MOVED,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 30_000,
                movedM = 60.0,
                accuracyM = acc,
                headingChanged = false,
            ),
        )
    }

    @Test
    fun correctionFiresWhenABadFixSettlesWhileParked() {
        assertEquals(
            SendPolicy.Reason.CORRECTION,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.STATIONARY_MIN_MS,
                movedM = SendPolicy.STATIONARY_MOVE_MAX_M,
                accuracyM = SendPolicy.PRECISE_ACCURACY_M,
                headingChanged = false,
                lastSentAccuracyM = SendPolicy.NOT_PRECISE_ACCURACY_M + 0.1,
                consecutivePreciseFixCount = SendPolicy.PRECISE_FIX_STREAK,
            ),
        )
    }

    @Test
    fun correctionWaitsForTheStreak() {
        // One precise fix short of the streak: stay quiet, not a lucky single fix.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.STATIONARY_MIN_MS,
                movedM = SendPolicy.STATIONARY_MOVE_MAX_M,
                accuracyM = SendPolicy.PRECISE_ACCURACY_M,
                headingChanged = false,
                lastSentAccuracyM = SendPolicy.NOT_PRECISE_ACCURACY_M + 0.1,
                consecutivePreciseFixCount = SendPolicy.PRECISE_FIX_STREAK - 1,
            )
        )
    }

    @Test
    fun correctionWaitsForTheStationaryTime() {
        // Parked only 9.999 s: correction hasn't earned its bypass yet.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.STATIONARY_MIN_MS - 1,
                movedM = SendPolicy.STATIONARY_MOVE_MAX_M,
                accuracyM = SendPolicy.PRECISE_ACCURACY_M,
                headingChanged = false,
                lastSentAccuracyM = SendPolicy.NOT_PRECISE_ACCURACY_M + 0.1,
                consecutivePreciseFixCount = SendPolicy.PRECISE_FIX_STREAK,
            )
        )
    }

    @Test
    fun correctionIgnoresAnAlreadyGoodLastSentFix() {
        // The last sent fix was already at the "not precise" boundary, not past it.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.STATIONARY_MIN_MS,
                movedM = SendPolicy.STATIONARY_MOVE_MAX_M,
                accuracyM = SendPolicy.PRECISE_ACCURACY_M,
                headingChanged = false,
                lastSentAccuracyM = SendPolicy.NOT_PRECISE_ACCURACY_M,
                consecutivePreciseFixCount = SendPolicy.PRECISE_FIX_STREAK,
            )
        )
    }
}
