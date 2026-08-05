package org.trailink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format against the numbers in `docs/ble-map-transfer-protocol.md`.
 * Byte-for-byte, because the device parses these by offset and a field in the
 * wrong place is a transfer that fails on a CRC nobody can explain.
 */
class TransferFramesTest {

    @Test
    fun `begin frame is opcode then little endian length crc and path`() {
        // Four distinct bytes so a swapped pair is visible, and under the 8 MB
        // the device accepts -- 0x00405060 is 4,214,880.
        val frame = TransferFrames.beginFrame("base/13/4482/2789.tib", 0x00405060, 0xdeadbeefL)

        assertEquals(TransferFrames.OP_BEGIN, frame[0])
        // 0x00405060 little endian.
        assertEquals(0x60.toByte(), frame[1])
        assertEquals(0x50.toByte(), frame[2])
        assertEquals(0x40.toByte(), frame[3])
        assertEquals(0x00.toByte(), frame[4])
        // 0xdeadbeef little endian.
        assertEquals(0xef.toByte(), frame[5])
        assertEquals(0xbe.toByte(), frame[6])
        assertEquals(0xad.toByte(), frame[7])
        assertEquals(0xde.toByte(), frame[8])

        val path = "base/13/4482/2789.tib"
        assertEquals(path.length.toByte(), frame[9])
        assertEquals(path, String(frame, 10, frame.size - 10, Charsets.UTF_8))
        // "The frame must end exactly here: trailing bytes are rejected."
        assertEquals(10 + path.length, frame.size)
    }

    @Test
    fun `chunk frame is opcode offset then payload`() {
        val payload = byteArrayOf(9, 8, 7)
        val frame = TransferFrames.chunkFrame(0x0000ff01, payload)

        assertEquals(TransferFrames.OP_CHUNK, frame[0])
        assertEquals(0x01.toByte(), frame[1])
        assertEquals(0xff.toByte(), frame[2])
        assertEquals(0x00.toByte(), frame[3])
        assertEquals(0x00.toByte(), frame[4])
        assertEquals(listOf<Byte>(9, 8, 7), frame.drop(5))
    }

    @Test
    fun `chunk frame can send part of a buffer`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = TransferFrames.chunkFrame(0, payload, payloadLen = 2)
        assertEquals(7, frame.size)
        assertEquals(listOf<Byte>(1, 2), frame.drop(5))
    }

    @Test
    fun `abort frame has no body`() {
        assertEquals(1, TransferFrames.abortFrame().size)
        assertEquals(TransferFrames.OP_ABORT, TransferFrames.abortFrame()[0])
    }

    @Test
    fun `chunk payload leaves room for both headers`() {
        // The default ATT MTU: 23 - 3 ATT - 5 frame = 15 bytes, the number that
        // measured 0.2 KB/s against the X4 and is why the MTU is renegotiated.
        assertEquals(15, TransferFrames.maxChunkPayload(23))
        assertEquals(509, TransferFrames.maxChunkPayload(517))
        // A bogus reported MTU must not produce a zero or negative payload.
        assertEquals(1, TransferFrames.maxChunkPayload(0))
    }

    @Test
    fun `crc32 matches zlib`() {
        // zlib.crc32(b"123456789") == 0xCBF43926, which is what the device's
        // esp_rom_crc32_le(0, ...) computes.
        assertEquals(0xCBF43926L, TransferFrames.crc32("123456789".toByteArray()))
        assertEquals(0L, TransferFrames.crc32(ByteArray(0)))
    }

    @Test
    fun `tile path is the layout both sides already use`() {
        assertEquals("base/13/4482/2789.tib", TransferFrames.tileRelPath(13, 4482, 2789))
    }

    @Test
    fun `path rules mirror the device's guard`() {
        assertTrue(TransferFrames.isSafeRelPath("base/13/4482/2789.tib"))
        assertTrue(TransferFrames.isSafeRelPath("a..b.tib"))  // not a `..` component

        assertFalse(TransferFrames.isSafeRelPath(""))
        assertFalse(TransferFrames.isSafeRelPath("/base/13/1/1.tib"))
        assertFalse(TransferFrames.isSafeRelPath("base/13/1/"))
        assertFalse(TransferFrames.isSafeRelPath("base/../13/1/1.tib"))
        assertFalse(TransferFrames.isSafeRelPath("base//13/1.tib"))
        assertFalse(TransferFrames.isSafeRelPath("base\\13\\1.tib"))
        assertFalse(TransferFrames.isSafeRelPath("base/13/ä.tib"))  // printable ASCII only
        assertFalse(TransferFrames.isSafeRelPath("b".repeat(81)))
    }

    @Test
    fun `status lines parse as the device spells them`() {
        assertEquals(
            TransferFrames.Status.Ready(4096),
            TransferFrames.parseStatus("RDY 4096"),
        )
        assertEquals(
            TransferFrames.Status.Ok(4096, 0xdeadbeefL),
            TransferFrames.parseStatus("OK 4096 deadbeef"),
        )
        assertEquals(
            TransferFrames.Status.Err("crc mismatch"),
            TransferFrames.parseStatus("ERR crc mismatch"),
        )
        // Unknown is its own case, never guessed into one of the others: acting
        // on a verdict we cannot read is worse than ignoring it.
        assertTrue(TransferFrames.parseStatus("HELLO") is TransferFrames.Status.Unknown)
        assertTrue(TransferFrames.parseStatus("RDY nope") is TransferFrames.Status.Unknown)
        assertTrue(TransferFrames.parseStatus("OK 4096") is TransferFrames.Status.Unknown)
    }

    @Test
    fun `tile header version is read from the magic and the u16`() {
        val v2 = byteArrayOf('T'.code.toByte(), 'I'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte(), 2, 0)
        assertEquals(2, TileHeader.formatVersion(v2))
        assertTrue(TileHeader.isAcceptable(v2, 2))
        // The case this whole check exists for: transfers fine, passes CRC, and
        // the device's reader refuses it on open.
        assertFalse(TileHeader.isAcceptable(v2, 3))
        // No version stated (older firmware): magic is all there is to go on.
        assertTrue(TileHeader.isAcceptable(v2, null))

        val notATile = "PKabc".toByteArray()
        assertNull(TileHeader.formatVersion(notATile))
        assertFalse(TileHeader.isAcceptable(notATile, null))
        assertNull(TileHeader.formatVersion(byteArrayOf(1, 2)))
    }
}
