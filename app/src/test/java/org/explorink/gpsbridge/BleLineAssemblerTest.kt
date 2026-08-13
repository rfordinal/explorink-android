package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `appendIndicationBytes` is `BleLink.handleIndication`'s line assembler,
 * pulled out to a top-level function so the cap can be tested without a live
 * `BleLink` (`docs/ble-review-2026-08.md`, "Stability -- app", "indication
 * line assembler unbounded"). A peer that never sends `\n` must not grow the
 * buffer forever.
 */
class BleLineAssemblerTest {

    @Test
    fun oneCompleteLineIsReturnedAndDrained() {
        val buffer = StringBuilder()
        var overflowed = false
        val lines = appendIndicationBytes(buffer, "RDY\n".toByteArray(Charsets.US_ASCII)) {
            overflowed = true
        }
        assertEquals(listOf("RDY"), lines)
        assertEquals(0, buffer.length)
        assertTrue(!overflowed)
    }

    @Test
    fun aLineSplitAcrossTwoIndicationsAssemblesOnTheSecond() {
        val buffer = StringBuilder()
        var overflowed = false
        val firstHalf = appendIndicationBytes(buffer, "NEED_TIL".toByteArray(Charsets.US_ASCII)) {
            overflowed = true
        }
        assertEquals(emptyList<String>(), firstHalf)

        val secondHalf = appendIndicationBytes(buffer, "ES 3\n".toByteArray(Charsets.US_ASCII)) {
            overflowed = true
        }
        assertEquals(listOf("NEED_TILES 3"), secondHalf)
        assertEquals(0, buffer.length)
        assertTrue(!overflowed)
    }

    @Test
    fun blankLinesAreDropped() {
        val buffer = StringBuilder()
        val lines = appendIndicationBytes(buffer, "\n\nOK\n".toByteArray(Charsets.US_ASCII)) {}
        assertEquals(listOf("OK"), lines)
    }

    @Test
    fun underCapWithNoNewlineJustWaitsForMore() {
        val buffer = StringBuilder()
        var overflowed = false
        val lines = appendIndicationBytes(
            buffer,
            "x".repeat(BleLink.LINE_BUFFER_CAP - 1).toByteArray(Charsets.US_ASCII),
        ) { overflowed = true }
        assertEquals(emptyList<String>(), lines)
        assertEquals(BleLink.LINE_BUFFER_CAP - 1, buffer.length)
        assertTrue(!overflowed)
    }

    @Test
    fun overCapWithNoNewlineDropsTheBufferAndFiresOnce() {
        val buffer = StringBuilder()
        var overflowCount = 0
        val lines = appendIndicationBytes(
            buffer,
            "x".repeat(BleLink.LINE_BUFFER_CAP + 1).toByteArray(Charsets.US_ASCII),
        ) { overflowCount++ }
        assertEquals(emptyList<String>(), lines)
        assertEquals(0, buffer.length)
        assertEquals(1, overflowCount)
    }

    @Test
    fun bufferRecoversAfterAnOverflowOnTheNextGoodLine() {
        // Dropping the buffer must not wedge the assembler -- the very next
        // indication, if it happens to contain a real line, is not owed
        // anything from the discarded bytes.
        val buffer = StringBuilder()
        appendIndicationBytes(buffer, "x".repeat(BleLink.LINE_BUFFER_CAP + 1).toByteArray(Charsets.US_ASCII)) {}
        assertEquals(0, buffer.length)

        var overflowed = false
        val lines = appendIndicationBytes(buffer, "OK\n".toByteArray(Charsets.US_ASCII)) { overflowed = true }
        assertEquals(listOf("OK"), lines)
        assertTrue(!overflowed)
    }
}
