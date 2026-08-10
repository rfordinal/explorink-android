package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The send policy decides how often the X4 gets woken up, so its edges are worth
 * pinning down: a bug here either spams the device or goes silent on a ride.
 */
class SendPolicyTest {

    private val acc = 5.0 // a normal outdoor fix, below the 25 m move threshold

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
        // Moving fast enough to earn a packet, but only 4 s since the last one.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 4_999,
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
                sinceLastMs = 5_000,
                movedM = 25.0,
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
        // Indoors: 40 m of "movement" with 40 m of accuracy is not movement.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 30_000,
                movedM = 39.0,
                accuracyM = 40.0,
                headingChanged = false,
            )
        )
        assertEquals(40.0, SendPolicy.moveThresholdM(40.0), 0.0)
        // A good fix never lowers it below the constant.
        assertEquals(SendPolicy.MOVE_THRESHOLD_M, SendPolicy.moveThresholdM(1.0), 0.0)
    }

    @Test
    fun roadSpeedCollapsesToTheOldFixedCadence() {
        // 90 km/h is 25 m/s, so the move threshold is met within a second and the
        // 5 s floor is the only thing limiting the rate.
        val movedInFiveSeconds = 25.0 * 5
        assertEquals(
            SendPolicy.Reason.MOVED,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = SendPolicy.MIN_INTERVAL_MS,
                movedM = movedInFiveSeconds,
                accuracyM = acc,
                headingChanged = false,
            ),
        )
    }

    @Test
    fun hikingPaceSendsEveryTwentySecondsish() {
        // 5 km/h is 1.39 m/s. At 5 s only 7 m has passed: quiet.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 5_000,
                movedM = 1.39 * 5,
                accuracyM = acc,
                headingChanged = false,
            )
        )
        // By 18 s it is 25 m: send.
        assertEquals(
            SendPolicy.Reason.MOVED,
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 18_000,
                movedM = 1.39 * 18,
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
        assertEquals(5_000L, SendPolicy.MIN_INTERVAL_MS)
        assertEquals(60L * 60L * 1000L, SendPolicy.KEEPALIVE_INTERVAL_MS)
    }
}
