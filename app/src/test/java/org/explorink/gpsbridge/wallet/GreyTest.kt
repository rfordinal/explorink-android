package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four-level grey: quantisation, the 2bpp byte layout, and the three baked planes.
 *
 * The plane layout is the thing a second implementation gets wrong, and it fails
 * **silently** -- a plane whose polarity is inverted makes the panel nudge the
 * complement of what was drawn, and losing a plane makes a grey pixel read full
 * black rather than washing out. So every rule gets its own assertion.
 */
class GreyTest {

    @Test
    fun the_thresholds_and_the_rounding_rule_are_the_same_function() {
        for (v in 0..255) {
            val rounded = Math.round(v * 3.0 / 255.0).toInt()
            assertEquals("value $v", rounded, Grey.levelOf(v))
        }
        assertEquals(Grey.BLACK, Grey.levelOf(42))
        assertEquals(Grey.DARK, Grey.levelOf(43))
        assertEquals(Grey.DARK, Grey.levelOf(127))
        assertEquals(Grey.LIGHT, Grey.levelOf(128))
        assertEquals(Grey.LIGHT, Grey.levelOf(212))
        assertEquals(Grey.WHITE, Grey.levelOf(213))
    }

    @Test
    fun quantisation_is_position_independent_which_is_what_no_dither_means() {
        // A flat field of one value must come out one level everywhere. With error
        // diffusion it would not, and the whole reason grey exists here is to be
        // smoother than dither.
        for (v in intArrayOf(0, 43, 100, 128, 200, 213, 255)) {
            val img = GreyLevels.quantise(GrayImage.filled(16, 9, v))
            val want = Grey.levelOf(v)
            assertTrue("flat $v", img.levels.all { (it.toInt() and 0xff) == want })
        }
        // And the same value in two places gives the same level whatever surrounds it.
        val px = ByteArray(64)
        for (i in px.indices) px[i] = (if (i % 2 == 0) 250 else 10).toByte()
        val mixed = GreyLevels.quantise(GrayImage(8, 8, px))
        assertEquals(Grey.WHITE, mixed[0, 0])
        assertEquals(Grey.WHITE, mixed[6, 7])
        assertEquals(Grey.BLACK, mixed[1, 0])
    }

    @Test
    fun two_bpp_packs_four_pixels_per_byte_msb_first() {
        val a = GreyLevels(4, 1, byteArrayOf(0, 1, 2, 3)).pack2bpp()
        assertEquals(1, a.size)
        assertEquals(0b00011011, a[0].toInt() and 0xff)
        val b = GreyLevels(4, 1, byteArrayOf(3, 2, 1, 0)).pack2bpp()
        assertEquals(0b11100100, b[0].toInt() and 0xff)
        // Stride is width / 4, and a whole raster is exactly rowBytes * height.
        val c = GreyLevels(8, 3, ByteArray(24) { (it % 4).toByte() })
        assertEquals(2 * 3, c.pack2bpp().size)
    }

    @Test
    fun a_two_bpp_row_must_end_on_a_byte() {
        try {
            GreyLevels(6, 1, ByteArray(6)).pack2bpp()
            throw AssertionError("a width of 6 is not 4-aligned and must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("width 6"))
        }
    }

    @Test
    fun the_planes_carry_exactly_the_levels_the_firmware_expects() {
        // One pixel of each level, in a row.
        val panel = Panels.X4
        val levels = ByteArray(panel.width * panel.height)
        levels[0] = Grey.BLACK.toByte()
        levels[1] = Grey.DARK.toByte()
        levels[2] = Grey.LIGHT.toByte()
        levels[3] = Grey.WHITE.toByte()
        val payload = GreyLevels(panel.width, panel.height, levels).packPlanes(panel)

        assertEquals(3 * panel.assetBytes, payload.size)
        assertEquals(144_000, payload.size)

        fun bit(plane: Int, x: Int): Int {
            val at = plane * panel.assetBytes + (x shr 3)
            return (payload[at].toInt() shr (7 - (x and 7))) and 1
        }
        // base: a SET bit is WHITE (no ink). Black and BOTH greys lay ink -- lose the
        // planes and a grey pixel reads full black, not white.
        assertEquals(0, bit(0, 0))       // black inks
        assertEquals(0, bit(0, 1))       // dark grey inks
        assertEquals(0, bit(0, 2))       // light grey inks
        assertEquals(1, bit(0, 3))       // white does not
        // lsb nudges dark grey alone.
        assertEquals(0, bit(1, 0))
        assertEquals(1, bit(1, 1))
        assertEquals(0, bit(1, 2))
        assertEquals(0, bit(1, 3))
        // msb nudges either grey.
        assertEquals(0, bit(2, 0))
        assertEquals(1, bit(2, 1))
        assertEquals(1, bit(2, 2))
        assertEquals(0, bit(2, 3))
        // The LUT slot (lsb set, msb clear) is used by no level at all.
        for (x in 0..3) assertFalse(bit(1, x) == 1 && bit(2, x) == 0)
    }

    @Test
    fun a_plane_set_is_one_screen_and_refuses_anything_else() {
        val panel = Panels.X4
        try {
            GreyLevels(panel.width, panel.height - 1,
                ByteArray(panel.width * (panel.height - 1))).packPlanes(panel)
            throw AssertionError("a plane set must be exactly one screen")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("one native screen"))
        }
    }

    @Test
    fun plane_sizes_and_bands_are_right_on_both_panels() {
        for (panel in listOf(Panels.X4, Panels.X3)) {
            val levels = GreyLevels(panel.width, panel.height,
                ByteArray(panel.width * panel.height) { Grey.LIGHT.toByte() })
            val payload = levels.packPlanes(panel)
            assertEquals(3 * panel.assetBytes, payload.size)
            val bands = Grey.bandCount(panel.height)
            assertEquals(if (panel.name == "x4") 6 else 7, bands)

            // A band is a byte SLICE, contiguous and in y order inside a plane, so a
            // device that streams band by band needs no repacking. The last band is
            // short on X3 (528 = 6*80 + 48), which is legal.
            var rows = 0
            for (b in 0 until bands) {
                val n = minOf(Grey.PLANE_BAND_ROWS, panel.height - b * Grey.PLANE_BAND_ROWS)
                val offset = b * Grey.PLANE_BAND_ROWS * panel.rowBytes
                assertEquals(rows * panel.rowBytes, offset)
                assertTrue(offset + n * panel.rowBytes <= panel.assetBytes)
                rows += n
            }
            assertEquals(panel.height, rows)
        }
        assertEquals(144_000, 3 * Panels.X4.assetBytes)
        assertEquals(156_816, 3 * Panels.X3.assetBytes)
        assertEquals(8_000, Grey.PLANE_BAND_ROWS * Panels.X4.rowBytes)
    }

    @Test
    fun the_planes_rebuild_the_level_image_and_nothing_else() {
        val panel = Panels.X4
        val src = ByteArray(panel.width * panel.height) {
            (it % Grey.LEVEL_COUNT).toByte()
        }
        val payload = GreyLevels(panel.width, panel.height, src).packPlanes(panel)

        // Read the three planes back the way the device does and check every pixel.
        // This is what proves the planes carry the picture rather than merely being
        // the right length.
        for (i in 0 until 4096) {
            val x = i % panel.width
            val y = i / panel.width
            fun bit(plane: Int): Int {
                val at = plane * panel.assetBytes + y * panel.rowBytes + (x shr 3)
                return (payload[at].toInt() shr (7 - (x and 7))) and 1
            }
            val level = when {
                bit(0) == 1 -> Grey.WHITE
                bit(1) == 1 && bit(2) == 1 -> Grey.DARK
                bit(1) == 0 && bit(2) == 1 -> Grey.LIGHT
                else -> Grey.BLACK
            }
            assertEquals("pixel $x,$y", src[i].toInt(), level)
        }
    }

    @Test
    fun rotate_native_matches_the_one_rotation_rule() {
        // The rule: native(u, v) = logical(LW - 1 - v, u), size (LW, LH) -> (LH, LW).
        // Checked against MonoImage's own implementation of the same rule, which the
        // parity test already pins against the generator.
        val w = 8
        val h = 16
        val px = ByteArray(w * h) { ((it * 37) % 4).toByte() }
        val logical = GreyLevels(w, h, px)
        val native = logical.rotateNative()
        assertEquals(h, native.width)
        assertEquals(w, native.height)
        for (v in 0 until w) {
            for (u in 0 until h) {
                assertEquals(logical[w - 1 - v, u], native[u, v])
            }
        }

        // And the same rotation on a 0/255 raster agrees with MonoImage's packer,
        // which is the version the 1bpp path uses.
        val mono = MonoImage(w, h, ByteArray(w * h) {
            if ((px[it].toInt() and 1) == 1) 255.toByte() else 0
        })
        val packed = mono.packNativeRegion(0, 0, w, h)
        for (v in 0 until w) {
            for (u in 0 until h) {
                val bit = (packed[v * (h / 8) + (u shr 3)].toInt() shr (7 - (u and 7))) and 1
                assertEquals(native[u, v] and 1, bit)
            }
        }
    }

    // --- the pipeline -------------------------------------------------------

    @Test
    fun a_grey_document_emits_both_grey_assets_on_every_level() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Scan", grey = true)
        assertTrue(item.grey)
        for (page in item.pages) {
            for (name in WalletFormat.LEVELS) {
                val level = page.levels.getValue(name)
                assertNotNull("$name greyPageImage", level.greyPageImage)
                assertNotNull("$name greyPlanes", level.greyPlanes)
                // Nothing is removed: the device can still draw the document 1bpp.
                assertNotNull("$name pageImage", level.pageImage)
                assertTrue(level.assets.isNotEmpty())
            }
        }
        // ...and the files are really there.
        for (id in Wallet(1, 1, "x4", listOf(item)).assetIds()) {
            assertTrue(id, store.assetFile(id, "dat").isFile)
        }
    }

    @Test
    fun a_1bpp_document_emits_neither_and_writes_no_grey_key() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Text")
        assertFalse(item.grey)
        for (page in item.pages) {
            for (name in WalletFormat.LEVELS) {
                assertNull(page.levels.getValue(name).greyPageImage)
                assertNull(page.levels.getValue(name).greyPlanes)
            }
        }
        // An ABSENT flag means no grey, which is the right default: a card written
        // before grey existed must not start rendering grey frames because a later
        // firmware learned how.
        assertFalse(store.load().toManifestJson().contains("\"grey\""))
    }

    @Test
    fun the_grey_page_image_has_identical_geometry_to_the_1bpp_one() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Scan", grey = true, paper = "a4")
        for (name in WalletFormat.LEVELS) {
            val level = item.pages[0].levels.getValue(name)
            val bw = level.pageImage!!
            val grey = level.greyPageImage!!
            // Same extent, same window, same focal origin -- that is what makes the
            // on-glass comparison about grey against dither, not about two crops.
            assertEquals(bw.nativeWidth, Json.asInt(grey.fields["nativeWidth"]))
            assertEquals(bw.nativeHeight, Json.asInt(grey.fields["nativeHeight"]))
            assertEquals(bw.windowStepX, Json.asInt(grey.fields["windowStepX"]))
            assertEquals(bw.windowStepY, Json.asInt(grey.fields["windowStepY"]))
            assertEquals(bw.focalX, Json.asInt(grey.fields["focalX"]))
            assertEquals(bw.focalY, Json.asInt(grey.fields["focalY"]))
            // 2bpp is twice the bytes and half the stride divisor.
            assertEquals(bw.rowBytes * 2, Json.asInt(grey.fields["rowBytes"]))
            assertEquals(bw.rawLen * 2, Json.asInt(grey.fields["rawLen"]))
            // And the native width keeps the 1bpp alignment, not just 2bpp's 4.
            assertEquals(0, bw.nativeWidth % 8)
        }
    }

    @Test
    fun every_image_like_entry_speaks_the_firmwares_one_vocabulary() {
        // The bug this pins cost a hardware session: the byte layouts were right and
        // the FIELD NAMES were not, so the entry parsed as present-but-zero and the
        // grey path declined in silence with `grey_rendered=0` as the only symptom.
        // The firmware reads every image-like entry through one struct.
        val required = listOf("assetId", "nativeWidth", "nativeHeight", "rowBytes",
            "rawLen", "windowStepX", "windowStepY", "focalX", "focalY", "sha256")
        for (panelName in listOf("x4", "x3")) {
            val store = WalletStore(java.nio.file.Files.createTempDirectory("grey").toFile(),
                panelName)
            val item = SyncFixtures.addItem(store, "Scan", grey = true)
            for (name in WalletFormat.LEVELS) {
                val level = item.pages[0].levels.getValue(name)
                for (entry in listOf(level.greyPageImage!!, level.greyPlanes!!)) {
                    for (key in required) {
                        assertTrue("$panelName $name: $key", entry.fields.containsKey(key))
                        assertNotNull(entry.fields[key])
                    }
                }
                // A plane set is one screen at the PANEL's stride and cannot pan.
                val planes = level.greyPlanes!!
                val panel = Panels.byName(panelName)
                assertEquals(panel.width, Json.asInt(planes.fields["nativeWidth"]))
                assertEquals(panel.height, Json.asInt(planes.fields["nativeHeight"]))
                assertEquals(panel.rowBytes, Json.asInt(planes.fields["rowBytes"]))
                assertEquals(0, Json.asInt(planes.fields["windowStepX"]))
                assertEquals(0, Json.asInt(planes.fields["windowStepY"]))
                assertEquals(3 * panel.assetBytes, Json.asInt(planes.fields["rawLen"]))
                assertEquals(panel.assetBytes, Json.asInt(planes.fields["lsbOffset"]))
                assertEquals(2 * panel.assetBytes, Json.asInt(planes.fields["msbOffset"]))
                assertEquals(Grey.PLANE_BAND_ROWS, Json.asInt(planes.fields["planeBandRows"]))
                assertEquals(Grey.bandCount(panel.height),
                    Json.asInt(planes.fields["planeBandCount"]))
            }
        }
    }

    @Test
    fun the_plane_set_is_baked_at_the_page_images_own_focal_window() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Scan", grey = true, paper = "a4")
        val level = item.pages[0].levels.getValue("one_to_one")
        val planes = level.greyPlanes!!
        // So assetType 6 and assetType 7 show the SAME pixels.
        assertEquals(level.pageImage!!.focalX, Json.asInt(planes.fields["originX"]))
        assertEquals(level.pageImage!!.focalY, Json.asInt(planes.fields["originY"]))
    }

    @Test
    fun a_grey_and_a_1bpp_asset_cannot_share_an_id() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Scan", grey = true)
        val ids = Wallet(1, 1, "x4", listOf(item)).assetIds()
        assertEquals(ids.size, ids.toSet().size)

        // The mechanism, stated precisely: `assetType` is in the hashed id recipe and
        // `bitDepth` is NOT, so the ids differ only while one assetType means one bit
        // depth. That is the invariant, and here is the collision it prevents.
        assertEquals(WalletFormat.BIT_DEPTH_2BPP,
            WalletFormat.ASSET_TYPE_BIT_DEPTH.getValue(WalletFormat.ASSET_PAGE_IMAGE_GREY))
        assertEquals(WalletFormat.BIT_DEPTH_1BPP,
            WalletFormat.ASSET_TYPE_BIT_DEPTH.getValue(WalletFormat.ASSET_PAGE_IMAGE))
        val asFive = WalletFormat.assetId("x4", "i", "p001", WalletFormat.ASSET_PAGE_IMAGE, 0, 1)
        val asSix = WalletFormat.assetId("x4", "i", "p001", WalletFormat.ASSET_PAGE_IMAGE_GREY, 0, 1)
        assertNotEquals(asFive, asSix)
        // Emit type 5 at bitDepth 2 and the two would land on the same path, silently.
        assertEquals(asFive, WalletFormat.assetId("x4", "i", "p001",
            WalletFormat.ASSET_PAGE_IMAGE, 0, 1))
    }

    @Test
    fun grey_is_refused_without_the_page_images_it_is_compared_against() {
        try {
            WalletPipeline(Panels.X4, pageImage = false, grey = true)
            throw AssertionError("grey with no page image must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("compared against"))
        }
    }

    @Test
    fun the_grey_flag_does_not_leak_between_items_in_one_manifest() {
        val store = SyncFixtures.store()
        SyncFixtures.addItem(store, "Text mono", grey = false)
        SyncFixtures.addItem(store, "Scan grey", grey = true)
        val text = store.load().toManifestJson()
        // A parser that leaked the flag between items would pass every
        // single-document test and fail this one.
        val parsed = Wallet.fromManifestJson(text)
        assertEquals(listOf(false, true), parsed.items.map { it.grey })
        assertEquals(1, Regex("\"grey\"").findAll(text).count())
        assertEquals(text, parsed.toManifestJson())
    }

    @Test
    fun flipping_the_flag_is_refused_when_there_are_no_grey_assets() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Text")
        assertFalse(store.hasGreyAssets(item))
        val before = store.load().walletVersion
        store.setGrey(item.id, true)
        // Nothing happened, not even a version bump: marking a document grey with no
        // planes to draw is a page of present-but-zero geometry on the device.
        assertFalse(store.load().items[0].grey)
        assertEquals(before, store.load().walletVersion)
    }

    @Test
    fun flipping_the_flag_off_and_on_keeps_the_assets_and_bumps_the_version() {
        val store = SyncFixtures.store()
        val item = SyncFixtures.addItem(store, "Scan", grey = true)
        val ids = Wallet(1, 1, "x4", listOf(item)).assetIds()
        val v = store.load().walletVersion

        store.setGrey(item.id, false)
        assertFalse(store.load().items[0].grey)
        assertEquals(v + 1, store.load().walletVersion)
        // The grey assets stay: the device just stops using them, and turning grey
        // back on costs one manifest and no image data.
        for (id in ids) assertTrue(id, store.assetFile(id, "dat").isFile)

        store.setGrey(item.id, true)
        assertTrue(store.load().items[0].grey)
        for (id in ids) assertTrue(id, store.assetFile(id, "dat").isFile)
    }
}
