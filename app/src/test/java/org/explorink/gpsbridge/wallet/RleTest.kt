package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The `.rle` sidecar. The encoder has to be deterministic and the decoder has to
 * be the exact inverse, because the device rebuilds the `.dat` from the sidecar
 * alone (`docs/wallet-format.md` section 7). The parity test then checks the
 * bytes against the generator's; these check the rules.
 */
class RleTest {

    @Test
    fun a_run_of_three_becomes_a_repeat_op() {
        // op 0x80 + 3 - 2 = 0x81, then the byte.
        assertEquals("81aa", WalletFormat.hex(Rle.encodeBand(ByteArray(3) { 0xaa.toByte() })))
    }

    @Test
    fun a_run_of_two_stays_literal() {
        // Same cost, and it keeps the literal stretch going.
        assertEquals("01aaaa", WalletFormat.hex(Rle.encodeBand(ByteArray(2) { 0xaa.toByte() })))
    }

    @Test
    fun the_longest_repeat_is_129_bytes() {
        val out = Rle.encodeBand(ByteArray(129) { 7 })
        assertEquals("ff07", WalletFormat.hex(out))
        val over = Rle.encodeBand(ByteArray(130) { 7 })
        // 129 in one op, then the leftover as a literal.
        assertEquals("ff070007", WalletFormat.hex(over))
    }

    @Test
    fun literals_break_at_128_bytes() {
        val src = ByteArray(200) { (it * 37 % 251).toByte() }
        val out = Rle.encodeBand(src)
        assertEquals(0x7f, out[0].toInt() and 0xff)
        assertArrayEquals(src, Rle.decodeBand(out))
    }

    @Test
    fun worst_case_adds_one_byte_per_128() {
        val src = ByteArray(1000) { (it * 97 % 250).toByte() }
        val out = Rle.encodeBand(src)
        assertTrue("expanded too far: ${out.size}", out.size <= src.size + (src.size / 128) + 1)
    }

    @Test
    fun round_trips_random_data() {
        val rnd = Random(4711)
        for (trial in 0 until 20) {
            val src = ByteArray(rnd.nextInt(3000) + 1)
            // Mixed runs and noise, which is what a dithered page looks like.
            var i = 0
            while (i < src.size) {
                if (rnd.nextInt(3) == 0) {
                    val v = rnd.nextInt(256).toByte()
                    val n = minOf(src.size - i, rnd.nextInt(200) + 1)
                    for (k in 0 until n) src[i + k] = v
                    i += n
                } else {
                    src[i++] = rnd.nextInt(256).toByte()
                }
            }
            assertArrayEquals(src, Rle.decodeBand(Rle.encodeBand(src)))
        }
    }

    @Test
    fun a_block_bands_by_the_assets_own_stride() {
        // X4 screen: 6 bands of 80 rows at 100 B/row.
        val raw = ByteArray(48000) { (it % 251).toByte() }
        val block = Rle.encode(raw, 100)
        assertEquals("EWRL", String(block, 0, 4, Charsets.US_ASCII))
        assertEquals(1, block[4].toInt())
        assertEquals(80, block[5].toInt())
        assertEquals(6, (block[6].toInt() and 0xff) or ((block[7].toInt() and 0xff) shl 8))
        assertArrayEquals(raw, Rle.decode(block))
    }

    @Test
    fun a_short_last_band_is_legal() {
        // X3: 528 rows = 6 * 80 + 48, so seven bands and the last one is short.
        val panel = Panels.X3
        val raw = ByteArray(panel.assetBytes) { (it % 97).toByte() }
        val block = Rle.encode(raw, panel.rowBytes)
        assertEquals(7, (block[6].toInt() and 0xff) or ((block[7].toInt() and 0xff) shl 8))
        assertArrayEquals(raw, Rle.decode(block))
    }

    @Test
    fun the_sidecar_carries_the_asset_header_verbatim() {
        val payload = ByteArray(1000) { (it % 13).toByte() }
        val header = WalletFormat.buildAssetHeader(
            WalletFormat.ASSET_FIT, 1, 0, 0, 800, 480, payload, 1)
        val file = Rle.buildSidecar(header, payload, 100)
        assertEquals(WalletFormat.hex(header),
            WalletFormat.hex(file.copyOfRange(0, WalletFormat.ASSET_HEADER_LEN)))
        // A device can rebuild the whole .dat from the sidecar alone.
        val rebuilt = Rle.decode(file.copyOfRange(WalletFormat.ASSET_HEADER_LEN, file.size))
        assertArrayEquals(payload, rebuilt)
    }
}
