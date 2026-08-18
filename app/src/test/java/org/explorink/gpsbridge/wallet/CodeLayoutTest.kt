package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code layout arithmetic, and the orientation rule.
 *
 * This is the part of phase P5 that must agree with `tools/walletgen.py`
 * **exactly**, on every panel, whatever the barcode library does: given a module
 * matrix of a certain size, the module size, the quiet zone, the drawn pixels and
 * the chosen orientation are pure arithmetic (`docs/wallet-format.md` section 10).
 *
 * So the generator's recorded matrix sizes are fed in here and the results are
 * compared field by field. That isolates the arithmetic from the writer: where
 * ZXing's matrix differs from zxing-cpp's (`CodeParityTest`), the arithmetic
 * applied to it is still the same arithmetic.
 */
class CodeLayoutTest {

    private val panels = listOf(Panels.X4, Panels.X3)

    @Test
    fun quiet_zone_is_max_of_four_and_the_symbology_minimum() {
        assertEquals(4, Symbology.QR.quietZone)
        assertEquals(4, Symbology.PDF417.quietZone)
        assertEquals(4, Symbology.AZTEC.quietZone)
        assertEquals(4, Symbology.DATAMATRIX.quietZone)
        assertEquals(10, Symbology.CODE128.quietZone)
        assertEquals(9, Symbology.EAN13.quietZone)
    }

    @Test
    fun symbology_keys_are_the_manifest_spelling() {
        assertEquals(listOf("qr", "pdf417", "aztec", "datamatrix", "code128", "ean13"),
            Symbology.entries.map { it.key })
        assertEquals(Symbology.PDF417, Symbology.byKey("pdf417"))
        assertEquals(null, Symbology.byKey("qrcode"))
    }

    @Test
    fun canvas_is_the_logical_screen_portrait_and_the_panel_landscape() {
        for (panel in panels) {
            assertEquals(Pair(panel.tileW, panel.tileH),
                CodeLayout.canvasSize(panel, CodeLayout.PORTRAIT))
            assertEquals(Pair(panel.width, panel.height),
                CodeLayout.canvasSize(panel, CodeLayout.LANDSCAPE))
        }
    }

    @Test
    fun module_size_is_the_largest_that_fits_with_its_quiet_zone() {
        for (panel in panels) {
            for (sym in Symbology.entries) {
                for (o in CodeLayout.ORIENTATIONS) {
                    val lay = CodeLayout.layout(37, 21, sym, panel, o)
                    val (cw, ch) = CodeLayout.canvasSize(panel, o)
                    val totalW = 37 + 2 * lay.quietZone
                    val totalH = 21 + 2 * lay.quietZone
                    assertEquals("${panel.name} ${sym.key} $o",
                        minOf(cw / totalW, ch / totalH), lay.moduleSize)
                    // One more module would not fit any more.
                    assertTrue("${panel.name} ${sym.key} $o is not maximal",
                        (lay.moduleSize + 1) * totalW > cw || (lay.moduleSize + 1) * totalH > ch)
                    assertEquals(totalW * lay.moduleSize, lay.codeWidthPx)
                    assertEquals(totalH * lay.moduleSize, lay.codeHeightPx)
                }
            }
        }
    }

    @Test
    fun presentation_follows_the_orientation() {
        val portrait = CodeLayout.layout(21, 21, Symbology.QR, Panels.X4, CodeLayout.PORTRAIT)
        val landscape = CodeLayout.layout(21, 21, Symbology.QR, Panels.X4, CodeLayout.LANDSCAPE)
        assertEquals(WalletFormat.PRESENTATION_PORTRAIT, portrait.presentation)
        assertEquals(WalletFormat.PRESENTATION_LANDSCAPE, landscape.presentation)
    }

    @Test
    fun landscape_only_when_it_buys_a_bigger_module() {
        for (panel in panels) {
            for (sym in Symbology.entries) {
                for (mw in listOf(14, 21, 49, 95, 137, 171)) {
                    for (mh in listOf(14, 21, 48, 55, 137)) {
                        val port = CodeLayout.layout(mw, mh, sym, panel, CodeLayout.PORTRAIT)
                        val land = CodeLayout.layout(mw, mh, sym, panel, CodeLayout.LANDSCAPE)
                        val chosen = CodeLayout.chooseOrientation(mw, mh, sym, panel)
                        val want = if (land.moduleSize > port.moduleSize) CodeLayout.LANDSCAPE
                                   else CodeLayout.PORTRAIT
                        assertEquals("${panel.name} ${sym.key} ${mw}x$mh", want, chosen)
                    }
                }
            }
        }
    }

    @Test
    fun a_square_matrix_ties_and_stays_portrait() {
        // The short axis limits both ways round, so turning the device buys nothing.
        for (panel in panels) {
            for (n in listOf(14, 21, 37, 49, 141)) {
                val port = CodeLayout.layout(n, n, Symbology.QR, panel, CodeLayout.PORTRAIT)
                val land = CodeLayout.layout(n, n, Symbology.QR, panel, CodeLayout.LANDSCAPE)
                assertEquals("${panel.name} ${n}x$n", port.moduleSize, land.moduleSize)
                assertEquals(CodeLayout.PORTRAIT,
                    CodeLayout.chooseOrientation(n, n, Symbology.QR, panel))
            }
        }
    }

    @Test
    fun a_wide_matrix_gains_the_long_axis_and_goes_landscape() {
        // The boarding-pass PDF417 case, on the generator's own matrix size.
        val port = CodeLayout.layout(171, 48, Symbology.PDF417, Panels.X4, CodeLayout.PORTRAIT)
        val land = CodeLayout.layout(171, 48, Symbology.PDF417, Panels.X4, CodeLayout.LANDSCAPE)
        assertEquals(2, port.moduleSize)
        assertEquals(4, land.moduleSize)
        assertEquals(CodeLayout.LANDSCAPE,
            CodeLayout.chooseOrientation(171, 48, Symbology.PDF417, Panels.X4))
    }

    @Test
    fun a_code_that_does_not_fit_reports_module_size_zero() {
        // code128 with a 40-character payload: 475 modules wide plus 20 of quiet
        // zone does not fit 480 px portrait at all.
        val port = CodeLayout.layout(475, 50, Symbology.CODE128, Panels.X4, CodeLayout.PORTRAIT)
        val land = CodeLayout.layout(475, 50, Symbology.CODE128, Panels.X4, CodeLayout.LANDSCAPE)
        assertEquals(0, port.moduleSize)
        assertEquals(1, land.moduleSize)
    }

    /**
     * The whole arithmetic, against the generator, for every recorded case: both
     * orientations, both panels, every field the manifest carries.
     */
    @Test
    fun arithmetic_matches_the_generator_on_its_own_matrix_sizes() {
        var checks = 0
        for (case in CodeFixtures.cases) {
            for (panelName in listOf("x4", "x3")) {
                val panel = Panels.byName(panelName)
                val fx = case.panel(panelName)
                for (o in CodeLayout.ORIENTATIONS) {
                    val want = fx.orientation(o)
                    val got = CodeLayout.layout(fx.modulesX, fx.modulesY, case.symbology, panel, o)
                    val where = "${case.name} $panelName $o"
                    assertEquals("$where moduleSize", Json.asInt(want["moduleSize"]), got.moduleSize)
                    assertEquals("$where quietZone", Json.asInt(want["quietZone"]), got.quietZone)
                    assertEquals("$where codeWidthPx",
                        Json.asInt(want["codeWidthPx"]), got.codeWidthPx)
                    assertEquals("$where codeHeightPx",
                        Json.asInt(want["codeHeightPx"]), got.codeHeightPx)
                    checks++
                }
                assertEquals("${case.name} $panelName chosen orientation", fx.chosen,
                    CodeLayout.chooseOrientation(fx.modulesX, fx.modulesY, case.symbology, panel))
            }
        }
        assertEquals("every case, both panels, both orientations",
            CodeFixtures.cases.size * 2 * 2, checks)
    }
}
