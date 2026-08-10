package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks the packet against the firmware's declared layout
 * (`BlePositionServer.h`): 21 bytes, little endian, no padding. A wrong length
 * or a wrong field offset is silently dropped or misread by the device, so this
 * is the one thing worth testing off the phone.
 */
class PositionPacketTest {

    @Test
    fun lengthIsExactlyTwentyOne() {
        val p = PositionPacket.build(0.0, 0.0, 0L, 0, 0, 0, 0, 0.0, 0.0)
        assertEquals(21, p.size)
        assertEquals(21, PositionPacket.SIZE)
    }

    @Test
    fun fieldsRoundTripAtTheRightOffsets() {
        val lat = 48.1485965
        val lon = 17.1077477
        val utc = 1_754_240_000L
        val tz = 120 // Bratislava in summer, minutes east of UTC
        val bytes = PositionPacket.build(
            latDeg = lat,
            lonDeg = lon,
            utcSeconds = utc,
            tzOffsetMinutes = tz,
            heading = 5,
            seq = 200,
            flags = 0,
            accuracyMetres = 7.4,
            speedKmh = 63.6,
        )

        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(481485965, b.getInt(0))
        assertEquals(171077477, b.getInt(4))
        assertEquals(utc, b.getInt(8).toLong() and 0xFFFFFFFFL)
        assertEquals(120, b.getShort(12).toInt())
        assertEquals(5, bytes[14].toInt())
        assertEquals(200, bytes[15].toInt() and 0xFF)
        assertEquals(0, bytes[16].toInt())  // no altitude given, so bit1 stays clear
        assertEquals(7, bytes[17].toInt() and 0xFF)
        assertEquals(64, bytes[18].toInt() and 0xFF)
        assertEquals(0, b.getShort(19).toInt())  // altitude bytes unused when absent
    }

    @Test
    fun altitudeSetsTheFlagBitAndRoundTrips() {
        val bytes = PositionPacket.build(
            latDeg = 48.1485965,
            lonDeg = 17.1077477,
            utcSeconds = 0L,
            tzOffsetMinutes = 0,
            heading = 0,
            seq = 0,
            flags = 0,
            accuracyMetres = 0.0,
            speedKmh = 0.0,
            altitudeMetres = 412.0,
        )
        assertEquals(0x02, bytes[16].toInt())
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(412, b.getShort(19).toInt())
    }

    @Test
    fun altitudeCanBeBelowSeaLevel() {
        val bytes = PositionPacket.build(
            0.0, 0.0, 0L, 0, 0, 0, 0, 0.0, 0.0, altitudeMetres = -420.0,
        )
        assertEquals(0x02, bytes[16].toInt())
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-420, b.getShort(19).toInt())
    }

    @Test
    fun altitudeFlagCombinesWithOtherFlagBits() {
        val bytes = PositionPacket.build(
            0.0, 0.0, 0L, 0, 0, 0, flags = 0x01, accuracyMetres = 0.0, speedKmh = 0.0,
            altitudeMetres = 100.0,
        )
        assertEquals(0x03, bytes[16].toInt())  // off-route bit and altitude-present bit both set
    }

    @Test
    fun altitudeSaturatesAtInt16Range() {
        val bytes = PositionPacket.build(
            0.0, 0.0, 0L, 0, 0, 0, 0, 0.0, 0.0, altitudeMetres = 100_000.0,
        )
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Short.MAX_VALUE.toInt(), b.getShort(19).toInt())
    }

    @Test
    fun negativeCoordinatesStayNegative() {
        val bytes = PositionPacket.build(-33.8688, -151.2093, 0L, -300, 0, 0, 0, 0.0, 0.0)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-338688000, b.getInt(0))
        assertEquals(-1512093000, b.getInt(4))
        assertEquals(-300, b.getShort(12).toInt())
    }

    @Test
    fun accuracyAndSpeedSaturate() {
        val bytes = PositionPacket.build(0.0, 0.0, 0L, 0, 0, 0, 0, 9999.0, 4000.0)
        assertEquals(255, bytes[17].toInt() and 0xFF)
        assertEquals(255, bytes[18].toInt() and 0xFF)
    }

    @Test
    fun seqWrapsAtByteBoundary() {
        val bytes = PositionPacket.build(0.0, 0.0, 0L, 0, 0, 256, 0, 0.0, 0.0)
        assertEquals(0, bytes[15].toInt() and 0xFF)
    }

    @Test
    fun utcSurvivesPastTwoBillionSeconds() {
        // 2038-ish: still fine because the field is read as uint32 by the device.
        val utc = 2_200_000_000L
        val bytes = PositionPacket.build(0.0, 0.0, utc, 0, 0, 0, 0, 0.0, 0.0)
        val read = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
        assertEquals(utc, read.toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun headingSnapsToSixteenSectors() {
        assertEquals(0, PositionPacket.headingSector(0f))
        assertEquals(0, PositionPacket.headingSector(5f))
        assertEquals(1, PositionPacket.headingSector(22.5f))
        assertEquals(2, PositionPacket.headingSector(45f))
        assertEquals(4, PositionPacket.headingSector(90f))
        assertEquals(8, PositionPacket.headingSector(180f))
        assertEquals(12, PositionPacket.headingSector(270f))
        assertEquals(15, PositionPacket.headingSector(337.5f))
        assertEquals(0, PositionPacket.headingSector(359f))
        assertEquals(0, PositionPacket.headingSector(360f))
        assertEquals(12, PositionPacket.headingSector(-90f))
    }

    @Test
    fun headingFieldNeverExceedsFifteen() {
        for (deg in 0 until 360) {
            val s = PositionPacket.headingSector(deg.toFloat())
            assert(s in 0..15) { "bearing $deg produced sector $s" }
        }
    }

    @Test
    fun hexIsLowercaseAndFullWidth() {
        val bytes = byteArrayOf(0x00, 0x0f, 0xff.toByte(), 0x10)
        assertEquals("000fff10", PositionPacket.toHex(bytes))
    }
}
