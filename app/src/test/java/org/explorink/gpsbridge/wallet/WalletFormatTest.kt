package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format's arithmetic: panel profiles, ids, the 32-byte header and the page
 * geometry. Every number here is pinned in `docs/wallet-format.md` and in
 * `tools/walletgen.py`, and the parity test proves the two agree on a real page.
 * These tests are the cheap, fast statement of the same facts.
 */
class WalletFormatTest {

    @Test
    fun panel_profiles_derive_their_lengths() {
        assertEquals(100, Panels.X4.rowBytes)
        assertEquals(48000, Panels.X4.assetBytes)
        assertEquals(480, Panels.X4.tileW)
        assertEquals(800, Panels.X4.tileH)
        assertEquals(99, Panels.X3.rowBytes)
        assertEquals(52272, Panels.X3.assetBytes)
        assertEquals(528, Panels.X3.tileW)
        assertEquals(792, Panels.X3.tileH)
    }

    @Test
    fun rle_bands_are_per_panel_and_x3_ends_short() {
        assertEquals(6, Panels.X4.bandCount(Rle.BAND_ROWS))
        assertEquals(8000, Panels.X4.bandBytes(Rle.BAND_ROWS))
        // 528 = 6 * 80 + 48
        assertEquals(7, Panels.X3.bandCount(Rle.BAND_ROWS))
        assertEquals(7920, Panels.X3.bandBytes(Rle.BAND_ROWS))
    }

    @Test
    fun one_to_one_grids_per_panel() {
        // Pinned: a DEVICE_PPI edit that moves a grid must fail here, not silently
        // re-cut every page and change every asset id.
        assertEquals(Pair(1819, 2572), WalletFormat.paperPx("a4"))
        assertEquals(Pair(1282, 1819), WalletFormat.paperPx("a5"))
        assertEquals(Pair(4, 4), WalletFormat.oneToOneGrid("a4", Panels.X4))
        assertEquals(Pair(3, 3), WalletFormat.oneToOneGrid("a5", Panels.X4))
        assertEquals(Pair(4, 4), WalletFormat.oneToOneGrid("a4", Panels.X3))
        assertEquals(Pair(3, 3), WalletFormat.oneToOneGrid("a5", Panels.X3))
    }

    @Test
    fun focal_tile_is_the_centre_biased_top_left() {
        assertEquals(Pair(0, 0), WalletFormat.defaultTile(1, 1))
        assertEquals(Pair(0, 0), WalletFormat.defaultTile(2, 2))
        assertEquals(Pair(1, 1), WalletFormat.defaultTile(3, 3))
        assertEquals(Pair(1, 1), WalletFormat.defaultTile(4, 4))
    }

    @Test
    fun asset_ids_are_panel_scoped() {
        val x4 = WalletFormat.assetId("x4", "abcd1234abcd1234", "p001", 3, 5, 1)
        val x3 = WalletFormat.assetId("x3", "abcd1234abcd1234", "p001", 3, 5, 1)
        assertEquals(16, x4.length)
        assertNotEquals("the same document on two panels must not share an asset path", x4, x3)
    }

    @Test
    fun asset_ids_are_deterministic_and_index_sensitive() {
        val a = WalletFormat.assetId("x4", "i", "p001", 5, 0, 1)
        val b = WalletFormat.assetId("x4", "i", "p001", 5, 0, 1)
        val c = WalletFormat.assetId("x4", "i", "p001", 5, 1, 1)
        assertEquals(a, b)
        // With index fixed at 0 all three page images shared one id and overwrote
        // each other on disk. That regression is what this line guards.
        assertNotEquals(a, c)
    }

    @Test
    fun item_ids_are_not_panel_scoped() {
        // An item id is a logical identity inside a manifest, never a filename.
        assertEquals(WalletFormat.itemIdFor("Passport", listOf("a.png")),
            WalletFormat.itemIdFor("Passport", listOf("a.png")))
        assertNotEquals(WalletFormat.itemIdFor("Passport", listOf("a.png")),
            WalletFormat.itemIdFor("Passport", listOf("b.png")))
        assertEquals(16, WalletFormat.itemIdFor("Passport", listOf("a.png")).length)
    }

    @Test
    fun shard_is_the_first_two_hex_chars() {
        assertEquals("38", WalletFormat.shardOf("38c37e815214c036"))
    }

    @Test
    fun asset_header_is_32_bytes_little_endian() {
        val payload = ByteArray(48000) { (it % 7).toByte() }
        val h = WalletFormat.buildAssetHeader(
            WalletFormat.ASSET_ONE_TO_ONE_TILE, 1, 2, 3, 800, 480, payload, 1)
        assertEquals(32, h.size)
        assertEquals("EWA1", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals(3, h[4].toInt())        // assetType
        assertEquals(1, h[5].toInt())        // bitDepth
        assertEquals(2, h[6].toInt())        // tileCol
        assertEquals(3, h[7].toInt())        // tileRow
        assertEquals(0x20, h[8].toInt() and 0xff)   // width 800 = 0x0320, LE
        assertEquals(0x03, h[9].toInt() and 0xff)
        assertEquals(0xe0, h[10].toInt() and 0xff)  // height 480 = 0x01e0
        assertEquals(0x01, h[11].toInt() and 0xff)
        assertEquals(0x80, h[12].toInt() and 0xff)  // rawLen 48000 = 0x0000bb80
        assertEquals(0xbb, h[13].toInt() and 0xff)
        assertEquals(1, h[16].toInt())       // version
        assertEquals(0, h[20].toInt())       // flags: not encrypted in P4
        assertEquals(1, h[21].toInt())       // presentation: portrait
        assertEquals(0, h[22].toInt())       // reserved: TWO bytes, 22..23
        assertEquals(0, h[23].toInt())
        // sha256_prefix starts at 24, not 25: the struct tail is `2s8s` and
        // 25 + 8 would not fit in 32 bytes. docs/wallet-format.md said 25 and
        // was wrong; corrected in the same pass.
        assertEquals(WalletFormat.sha256Hex(payload).substring(0, 16),
            WalletFormat.hex(h.copyOfRange(24, 32)))
    }

    @Test
    fun pick_paper_uses_dpi_when_it_has_it() {
        // The demo page: 1240 x 1754 at 150 DPI is A4.
        assertEquals("a4", WalletFormat.pickPaper(1240, 1754, 150.0124, 150.0124))
        // A5 at 150 DPI.
        assertEquals("a5", WalletFormat.pickPaper(874, 1240, 150.0, 150.0))
        // No metadata, or nonsense metadata: a4.
        assertEquals("a4", WalletFormat.pickPaper(1240, 1754, null, null))
        assertEquals("a4", WalletFormat.pickPaper(300, 400, 96.0, 96.0))
    }

    @Test
    fun unknown_panel_and_paper_fail_loudly() {
        var threw = false
        try {
            Panels.byName("x9")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        threw = false
        try {
            WalletFormat.paperPx("letter")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
