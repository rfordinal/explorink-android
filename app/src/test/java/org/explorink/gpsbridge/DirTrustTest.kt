package org.explorink.gpsbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The heading-quality bits, and the trend spread behind them.
 *
 * The compatibility rule is the one worth guarding: an app that never sets
 * these bits writes zero, and zero has to keep meaning "said nothing" rather
 * than "bad", or every phone still on an old build would suddenly make the
 * device draw an alarm.
 */
class DirTrustTest {

    @Test
    fun unstatedIsZeroSoAnOldBuildIsUnchanged() {
        assertEquals(0, PositionPacket.DirTrust.UNSTATED)
        val bytes = PositionPacket.build(
            latDeg = 0.0,
            lonDeg = 0.0,
            utcSeconds = 0L,
            tzOffsetMinutes = 0,
            heading = 0,
            seq = 0,
            flags = PositionPacket.withDirTrust(0, PositionPacket.DirTrust.UNSTATED),
            accuracyMetres = 0.0,
            speedKmh = 0.0,
        )
        assertEquals(0, bytes[16].toInt())
    }

    @Test
    fun theBitsLandInTheirOwnTwoPlacesAndLeaveTheOthersAlone() {
        assertEquals(0x04, PositionPacket.withDirTrust(0, PositionPacket.DirTrust.GOOD))
        assertEquals(0x08, PositionPacket.withDirTrust(0, PositionPacket.DirTrust.COARSE))
        assertEquals(0x0C, PositionPacket.withDirTrust(0, PositionPacket.DirTrust.UNKNOWN))
        // Altitude's bit1 and the off-route bit0 must survive untouched.
        assertEquals(0x0B, PositionPacket.withDirTrust(0x03, PositionPacket.DirTrust.COARSE))
        // And setting it twice replaces rather than accumulates.
        val once = PositionPacket.withDirTrust(0x02, PositionPacket.DirTrust.UNKNOWN)
        assertEquals(0x02 or 0x0C, once)
        assertEquals(0x02 or 0x04, PositionPacket.withDirTrust(once, PositionPacket.DirTrust.GOOD))
    }

    @Test
    fun altitudeStillSetsItsOwnBitAlongsideTheQualityBits() {
        val bytes = PositionPacket.build(
            latDeg = 0.0,
            lonDeg = 0.0,
            utcSeconds = 0L,
            tzOffsetMinutes = 0,
            heading = 0,
            seq = 0,
            flags = PositionPacket.withDirTrust(0, PositionPacket.DirTrust.COARSE),
            accuracyMetres = 0.0,
            speedKmh = 0.0,
            altitudeMetres = 512.0,
        )
        assertEquals(0x02 or 0x08, bytes[16].toInt())
        assertEquals(512, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(19).toInt())
        assertEquals(PositionPacket.SIZE, bytes.size)
    }

    @Test
    fun thisAppNeverSendsCoarse() {
        // COARSE exists on the wire for the device's own receiver, where the
        // course is an instantaneous reading. This app's heading is a
        // conclusion HeadingTrend either reached or did not, so there is no
        // half-believed heading for it to report.
        assertEquals(2, PositionPacket.DirTrust.COARSE)
        assertEquals(0x08, PositionPacket.withDirTrust(0, PositionPacket.DirTrust.COARSE))
    }

    @Test
    fun aHeadingLostSendExistsBecauseAParkedPhoneSendsNothingElse() {
        // Parked: no movement, no turn, and the hourly keepalive is nowhere
        // near. Without HEADING_LOST the device would keep the last arrow for
        // up to an hour after the timer behind it expired.
        val parked = SendPolicy.decide(
            hasSent = true,
            sinceLastMs = 120_000L,
            movedM = 0.0,
            accuracyM = 8.0,
            headingChanged = false,
            headingLost = true,
        )
        assertEquals(SendPolicy.Reason.HEADING_LOST, parked)

        // Same situation without the loss stays quiet, which is the behaviour
        // every existing caller relies on.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 120_000L,
                movedM = 0.0,
                accuracyM = 8.0,
                headingChanged = false,
            )
        )
    }

    @Test
    fun movementStillWinsOverHeadingLost() {
        // A rider who is moving gets MOVED, which carries the new quality in
        // the same packet -- HEADING_LOST must not steal that reason and make
        // the log say the wrong thing.
        val moving = SendPolicy.decide(
            hasSent = true,
            sinceLastMs = 60_000L,
            movedM = 500.0,
            accuracyM = 8.0,
            headingChanged = false,
            headingLost = true,
        )
        assertEquals(SendPolicy.Reason.MOVED, moving)
    }

    @Test
    fun headingLostStillWaitsOutTheSendFloor() {
        // It is not a correction and does not bypass the floor: nothing about a
        // lost heading is urgent enough to beat the 7 s pacing.
        assertNull(
            SendPolicy.decide(
                hasSent = true,
                sinceLastMs = 1_000L,
                movedM = 0.0,
                accuracyM = 8.0,
                headingChanged = false,
                headingLost = true,
            )
        )
    }
}
