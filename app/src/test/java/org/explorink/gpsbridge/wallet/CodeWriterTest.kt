package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The render rules of `docs/wallet-format.md` section 10, asserted on the drawn
 * canvas rather than trusted from a field: pure black and white, integer module
 * size, a quiet zone that is really white, centred, and the byte layout that
 * `presentation` claims.
 */
class CodeWriterTest {

    private val panels = listOf(Panels.X4, Panels.X3)

    private val samples = listOf(
        Symbology.QR to CodeFixtures.SHORT,
        Symbology.QR to CodeFixtures.BCBP136,
        Symbology.PDF417 to CodeFixtures.BCBP61,
        Symbology.PDF417 to CodeFixtures.BCBP136,
        Symbology.AZTEC to CodeFixtures.BCBP136,
        Symbology.DATAMATRIX to CodeFixtures.SHORT,
        Symbology.DATAMATRIX to CodeFixtures.BCBP136,
        Symbology.CODE128 to CodeFixtures.SHORT,
        Symbology.EAN13 to CodeFixtures.EAN,
    )

    @Test
    fun every_pixel_is_black_or_white() {
        for (panel in panels) {
            for ((sym, payload) in samples) {
                val r = CodeWriter.render(sym, payload, panel)
                for (b in r.canvas.pixels) {
                    val v = b.toInt() and 0xff
                    if (v != 0 && v != 255) {
                        fail("${sym.key} on ${panel.name} has a grey pixel: $v")
                    }
                }
            }
        }
    }

    @Test
    fun the_canvas_is_a_whole_screen_and_the_asset_is_a_whole_screen_of_bytes() {
        for (panel in panels) {
            for ((sym, payload) in samples) {
                val r = CodeWriter.render(sym, payload, panel)
                val (cw, ch) = CodeLayout.canvasSize(panel, r.layout.orientation)
                assertEquals(cw, r.canvas.width)
                assertEquals(ch, r.canvas.height)
                val bytes = CodeWriter.pack(r.canvas, panel, r.layout.orientation)
                assertEquals("${sym.key} on ${panel.name}", panel.assetBytes, bytes.size)
            }
        }
    }

    @Test
    fun the_quiet_zone_is_really_white() {
        for (panel in panels) {
            for ((sym, payload) in samples) {
                val r = CodeWriter.render(sym, payload, panel)
                val qzPx = r.layout.quietZone * r.layout.moduleSize
                val x0 = r.drawnX - qzPx
                val y0 = r.drawnY - qzPx
                val x1 = r.drawnX + r.matrix.width * r.layout.moduleSize + qzPx
                val y1 = r.drawnY + r.matrix.height * r.layout.moduleSize + qzPx
                // The quiet zone is at least this big; the canvas may add more.
                assertTrue("${sym.key} quiet zone runs off the canvas", x0 >= 0 && y0 >= 0)
                assertTrue(x1 <= r.canvas.width && y1 <= r.canvas.height)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val inside = x >= r.drawnX && y >= r.drawnY &&
                            x < r.drawnX + r.matrix.width * r.layout.moduleSize &&
                            y < r.drawnY + r.matrix.height * r.layout.moduleSize
                        if (!inside && r.canvas[x, y] != 255) {
                            fail("${sym.key} on ${panel.name}: quiet zone pixel ($x,$y) is not white")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nothing_is_drawn_outside_the_code() {
        for ((sym, payload) in samples) {
            val r = CodeWriter.render(sym, payload, Panels.X4)
            val w = r.matrix.width * r.layout.moduleSize
            val h = r.matrix.height * r.layout.moduleSize
            for (y in 0 until r.canvas.height) {
                for (x in 0 until r.canvas.width) {
                    val inside = x >= r.drawnX && x < r.drawnX + w &&
                        y >= r.drawnY && y < r.drawnY + h
                    if (!inside && r.canvas[x, y] != 255) fail("${sym.key}: ink at ($x,$y)")
                }
            }
        }
    }

    @Test
    fun the_code_is_centred() {
        for (panel in panels) {
            for ((sym, payload) in samples) {
                val r = CodeWriter.render(sym, payload, panel)
                val w = r.matrix.width * r.layout.moduleSize
                val h = r.matrix.height * r.layout.moduleSize
                val left = r.drawnX
                val right = r.canvas.width - (r.drawnX + w)
                val top = r.drawnY
                val bottom = r.canvas.height - (r.drawnY + h)
                assertTrue("${sym.key} on ${panel.name} is off-centre: $left/$right",
                    Math.abs(left - right) <= 1)
                assertTrue("${sym.key} on ${panel.name} is off-centre: $top/$bottom",
                    Math.abs(top - bottom) <= 1)
            }
        }
    }

    @Test
    fun a_module_is_a_solid_block_of_one_colour() {
        val r = CodeWriter.render(Symbology.QR, CodeFixtures.SHORT, Panels.X4)
        val m = r.layout.moduleSize
        assertTrue("a 21x21 QR should get a fat module on a 480 px panel", m >= 8)
        for (my in 0 until r.matrix.height) {
            for (mx in 0 until r.matrix.width) {
                val want = if (r.matrix[mx, my]) 0 else 255
                for (dy in 0 until m) {
                    for (dx in 0 until m) {
                        assertEquals("module ($mx,$my) pixel ($dx,$dy)", want,
                            r.canvas[r.drawnX + mx * m + dx, r.drawnY + my * m + dy])
                    }
                }
            }
        }
    }

    @Test
    fun landscape_is_stored_without_rotation() {
        for (panel in panels) {
            val r = CodeWriter.render(Symbology.PDF417, CodeFixtures.BCBP136, panel, "landscape")
            assertEquals(CodeLayout.LANDSCAPE, r.layout.orientation)
            assertEquals(WalletFormat.PRESENTATION_LANDSCAPE, r.layout.presentation)
            val packed = CodeWriter.pack(r.canvas, panel, CodeLayout.LANDSCAPE)
            // "No rotation" is a claim about bytes, so it is asserted on bytes.
            assertTrue("landscape bytes are not the canvas rows",
                packed.contentEquals(r.canvas.pack1bpp()))
        }
    }

    @Test
    fun portrait_is_stored_rotated_and_unrotates_back_to_the_canvas() {
        for (panel in panels) {
            val r = CodeWriter.render(Symbology.QR, CodeFixtures.BCBP136, panel, "portrait")
            val packed = CodeWriter.pack(r.canvas, panel, CodeLayout.PORTRAIT)
            assertNotEquals("a portrait canvas cannot be stored unrotated",
                panel.width, r.canvas.width)
            val back = MonoImage.unpack1bpp(packed, panel.width, panel.height).unrotateNative()
            assertEquals(r.canvas.width, back.width)
            assertEquals(r.canvas.height, back.height)
            assertTrue("unrotate is not the inverse of the pack",
                back.pixels.contentEquals(r.canvas.pixels))
        }
    }

    @Test
    fun a_code_too_big_for_either_orientation_fails_loudly() {
        // 60 letters of Code128 is 695 modules wide (one 11-module character each,
        // no numeric compaction): 1 px per module landscape, nothing at all
        // portrait. Digits would NOT do -- ZXing's Code128 writer packs two digits
        // per symbol, so 40 digits still fit.
        val long = "A".repeat(60)
        try {
            CodeWriter.render(Symbology.CODE128, long, Panels.X4, "portrait")
            fail("expected a refusal, not a render")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not fit"))
        }
        // Landscape turns the hard failure into a marginal render.
        val land = CodeWriter.render(Symbology.CODE128, long, Panels.X4)
        assertEquals(CodeLayout.LANDSCAPE, land.layout.orientation)
        assertEquals(1, land.layout.moduleSize)
    }

    @Test
    fun free_error_correction_takes_a_stronger_level_when_the_matrix_does_not_grow() {
        // TEST12345 is a version-1 QR at both M and H, so H is free. segno does the
        // same thing by default (boost_error), which is why the generator's "M" QR
        // of a short payload is really an H.
        val short = CodeWriter.matrix(Symbology.QR, CodeFixtures.SHORT, Panels.X4)
        assertEquals(21, short.width)
        assertEquals(21, short.height)
        assertTrue("expected a boosted level, got '${short.note}'", short.note.contains("ecc=H"))
        // The boarding pass fills version 8 at M; H would grow the matrix, so M stays.
        val long = CodeWriter.matrix(Symbology.QR, CodeFixtures.BCBP136, Panels.X4)
        assertEquals(49, long.width)
        assertTrue("expected the baseline level, got '${long.note}'", long.note.contains("ecc=M"))
    }

    @Test
    fun the_1d_symbologies_keep_the_generators_bar_height() {
        val code128 = CodeWriter.matrix(Symbology.CODE128, CodeFixtures.SHORT, Panels.X4)
        assertEquals(CodeWriter.ONE_D_BAR_HEIGHT_MODULES, code128.height)
        // EAN13's guard bars run 5 modules lower than the data bars.
        val ean = CodeWriter.matrix(Symbology.EAN13, CodeFixtures.EAN, Panels.X4)
        assertEquals(95, ean.width)
        assertEquals(CodeWriter.ONE_D_BAR_HEIGHT_MODULES + CodeWriter.EAN13_GUARD_EXTENSION_MODULES,
            ean.height)
        // The extension carries the guard bars and nothing else.
        val lastRow = ean.height - 1
        var dark = 0
        for (x in 0 until ean.width) if (ean[x, lastRow]) dark++
        assertEquals("only the six guard bars reach the bottom row", 6, dark)
        assertTrue(ean[0, lastRow] && ean[2, lastRow] && ean[92, lastRow] && ean[94, lastRow])
    }

    @Test
    fun pdf417_rows_are_three_modules_tall() {
        val m = CodeWriter.matrix(Symbology.PDF417, CodeFixtures.BCBP136, Panels.X4)
        assertEquals(0, m.height % CodeWriter.PDF417_ROW_HEIGHT_MODULES)
        // Every codeword row is drawn as three identical pixel rows.
        for (y in 0 until m.height step CodeWriter.PDF417_ROW_HEIGHT_MODULES) {
            for (r in 1 until CodeWriter.PDF417_ROW_HEIGHT_MODULES) {
                for (x in 0 until m.width) {
                    assertEquals("pdf417 row $y offset $r column $x", m[x, y], m[x, y + r])
                }
            }
        }
    }

    @Test
    fun datamatrix_shape_follows_the_payload_not_a_list() {
        // The reason the orientation rule is arithmetic: the same symbology is
        // square for a short payload and (on the laptop) rectangular for a long one.
        val short = CodeWriter.matrix(Symbology.DATAMATRIX, CodeFixtures.SHORT, Panels.X4)
        assertEquals(short.width, short.height)
        val long = CodeWriter.matrix(Symbology.DATAMATRIX, CodeFixtures.BCBP136, Panels.X4)
        assertTrue("a 136-char DataMatrix should be bigger than a 9-char one",
            long.width > short.width)
    }
}
