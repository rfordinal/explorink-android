package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase P5 against the laptop generator, per symbology.
 *
 * The phone writes codes with ZXing's Java writers; `tools/walletgen.py` writes
 * them with segno (QR) and zxing-cpp (the rest). **Those are different writers,
 * so some symbologies come out with different module bits, and that is a real
 * divergence, not a bug to tune away.** This test states exactly which:
 *
 * | Case | Verdict |
 * |---|---|
 * | qr short | **byte-identical** -- ZXing at ECC H equals segno's boosted H |
 * | qr boarding pass | same 49x49, bits differ (mask and mode segmentation) |
 * | aztec | same 37x37, bits differ |
 * | datamatrix short | **byte-identical** |
 * | datamatrix boarding pass | 40x40 square here, 64x24 DMRE there; ZXing's Java writer has no rectangular extension |
 * | code128 | **byte-identical** |
 * | ean13 | **byte-identical** (with the guard extension reproduced) |
 * | pdf417 | different shape on purpose: the columns are chosen to maximise the module, 5 px against 4 px for a boarding pass |
 *
 * What must hold for every case regardless of the writer:
 *
 *  - the layout arithmetic agrees (that is `CodeLayoutTest`, on the generator's
 *    own matrix sizes);
 *  - our asset decodes back out of its stored bytes -- the verify loop;
 *  - the **generator's** asset decodes with our reader, so both writers really do
 *    produce a code this app can read.
 */
class CodeParityTest {

    private val panels = listOf("x4", "x3")

    /** Cases whose stored bytes are identical to the generator's, both panels. */
    private val byteIdentical = setOf(
        "qr-short", "datamatrix-short", "code128-short", "ean13-std")

    /**
     * Our own geometry per case, panel x4: matrix, orientation, module size.
     * Recorded from a measured run so a library upgrade that changes a code cannot
     * pass unnoticed.
     */
    private val ours = mapOf(
        "qr-short" to Geo(21, 21, CodeLayout.PORTRAIT, 16),
        "qr-bcbp136" to Geo(49, 49, CodeLayout.PORTRAIT, 8),
        "pdf417-bcbp61" to Geo(120, 72, CodeLayout.LANDSCAPE, 6),
        "pdf417-bcbp136" to Geo(137, 84, CodeLayout.LANDSCAPE, 5),
        "pdf417-bcbp136-portrait" to Geo(137, 84, CodeLayout.PORTRAIT, 3),
        "pdf417-bcbp136-landscape" to Geo(137, 84, CodeLayout.LANDSCAPE, 5),
        "aztec-bcbp136" to Geo(37, 37, CodeLayout.PORTRAIT, 10),
        "datamatrix-short" to Geo(14, 14, CodeLayout.PORTRAIT, 21),
        "datamatrix-bcbp136" to Geo(40, 40, CodeLayout.PORTRAIT, 10),
        "code128-short" to Geo(123, 50, CodeLayout.LANDSCAPE, 5),
        "ean13-std" to Geo(95, 55, CodeLayout.LANDSCAPE, 6),
    )

    private class Geo(val modulesX: Int, val modulesY: Int, val orientation: String,
                      val moduleSize: Int)

    private fun render(case: CodeFixtures.Case, panel: PanelProfile): CodeWriter.Rendered =
        CodeWriter.render(case.symbology, case.payload, panel, case.requestedOrientation)

    @Test
    fun the_fixture_is_the_one_this_test_was_written_against() {
        assertEquals(11, CodeFixtures.cases.size)
        assertEquals(136, CodeFixtures.BCBP136.length)
        assertEquals(61, CodeFixtures.BCBP61.length)
        assertEquals(ours.keys, CodeFixtures.cases.map { it.name }.toSet())
        // Every case verified on the laptop too, or the fixture itself is suspect.
        for (case in CodeFixtures.cases) {
            for (p in panels) {
                assertTrue("${case.name} $p was not verified by the generator",
                    case.panel(p).asset["verified"] == true)
            }
        }
    }

    @Test
    fun our_geometry_is_what_was_measured() {
        for (case in CodeFixtures.cases) {
            val want = ours.getValue(case.name)
            val r = render(case, Panels.X4)
            assertEquals("${case.name} modulesX", want.modulesX, r.matrix.width)
            assertEquals("${case.name} modulesY", want.modulesY, r.matrix.height)
            assertEquals("${case.name} orientation", want.orientation, r.layout.orientation)
            assertEquals("${case.name} moduleSize", want.moduleSize, r.layout.moduleSize)
        }
    }

    @Test
    fun the_byte_identical_cases_are_byte_identical() {
        for (case in CodeFixtures.cases.filter { it.name in byteIdentical }) {
            for (panelName in panels) {
                val panel = Panels.byName(panelName)
                val r = render(case, panel)
                val bytes = CodeWriter.pack(r.canvas, panel, r.layout.orientation)
                assertArrayEquals("${case.name} on $panelName is not byte-identical",
                    CodeFixtures.assetBytes(case, panelName), bytes)
                // And so is the header, which carries presentation and the hash.
                val header = WalletFormat.buildAssetHeader(
                    WalletFormat.ASSET_MACHINE_CODE, WalletFormat.BIT_DEPTH_1BPP, 0, 0,
                    panel.width, panel.height, bytes, 1,
                    presentation = r.layout.presentation)
                assertEquals("${case.name} on $panelName header",
                    Json.asString(case.panel(panelName).asset["headerHex"]),
                    WalletFormat.hex(header))
            }
        }
    }

    /**
     * The negative half of the same claim: where the writers differ, they really
     * do differ. A parity table that quietly became "all identical" would mean the
     * doc is wrong, and that is worth failing over.
     */
    @Test
    fun the_diverging_cases_really_do_diverge() {
        for (case in CodeFixtures.cases.filter { it.name !in byteIdentical }) {
            val panel = Panels.X4
            val r = render(case, panel)
            val bytes = CodeWriter.pack(r.canvas, panel, r.layout.orientation)
            assertFalse("${case.name} is listed as diverging but the bytes now match",
                bytes.contentEquals(CodeFixtures.assetBytes(case, "x4")))
        }
    }

    @Test
    fun every_case_decodes_back_out_of_our_own_stored_bytes() {
        for (case in CodeFixtures.cases) {
            for (panelName in panels) {
                val panel = Panels.byName(panelName)
                val r = render(case, panel)
                val bytes = CodeWriter.pack(r.canvas, panel, r.layout.orientation)
                assertTrue("${case.name} on $panelName did not verify",
                    CodeReader.verify(bytes, panel, case.symbology, case.payload,
                        r.layout.presentation))
            }
        }
    }

    /**
     * Cross-decode: the **generator's** bytes, read by the phone's reader.
     *
     * This is what makes "the bytes differ but both are valid codes" a measurement
     * rather than an opinion -- and it is also the case that matters in practice,
     * because a wallet built on the laptop can be copied onto the phone.
     */
    @Test
    fun the_generators_own_assets_decode_with_our_reader() {
        for (case in CodeFixtures.cases) {
            for (panelName in panels) {
                val panel = Panels.byName(panelName)
                val asset = case.panel(panelName).asset
                val bytes = CodeFixtures.assetBytes(case, panelName)
                assertEquals(panel.assetBytes, bytes.size)
                assertTrue("the generator's ${case.name} on $panelName did not decode here",
                    CodeReader.verify(bytes, panel, case.symbology, case.payload,
                        Json.asInt(asset["presentation"])))
            }
        }
    }

    /**
     * The boarding pass is the case the whole phase exists for, so its numbers are
     * stated outright rather than left in a table.
     */
    @Test
    fun the_boarding_pass_pdf417_gains_module_size_from_the_orientation() {
        val panel = Panels.X4
        val case = CodeFixtures.case("pdf417-bcbp136")
        val portrait = CodeWriter.render(case.symbology, case.payload, panel, "portrait")
        val landscape = CodeWriter.render(case.symbology, case.payload, panel, "landscape")
        assertEquals(3, portrait.layout.moduleSize)
        assertEquals(5, landscape.layout.moduleSize)
        // The generator lands at 2 px and 4 px on the same payload: same arithmetic,
        // a narrower matrix (4 data columns against 6).
        assertEquals(2, Json.asInt(
            Json.asMap(case.panel("x4").map["portrait"])["moduleSize"]))
        assertEquals(4, Json.asInt(
            Json.asMap(case.panel("x4").map["landscape"])["moduleSize"]))
        assertEquals(CodeLayout.LANDSCAPE,
            CodeWriter.render(case.symbology, case.payload, panel).layout.orientation)
    }

    @Test
    fun datamatrix_goes_portrait_here_because_the_java_writer_has_no_dmre() {
        // The generator's DataMatrix of a long payload is 64x24 (a Data Matrix
        // Rectangular Extension size) and therefore landscape. ZXing's Java writer
        // only has the square and the small standard rectangles, so the same payload
        // is 40x40 and the arithmetic -- unchanged -- says portrait.
        //
        // This is exactly why the orientation rule is arithmetic and not a list of
        // symbologies: the same symbology lands in different orientations depending
        // on what its writer produced.
        val case = CodeFixtures.case("datamatrix-bcbp136")
        assertEquals("landscape", Json.asString(case.panel("x4").asset["orientation"]))
        val r = CodeWriter.render(case.symbology, case.payload, Panels.X4)
        assertEquals(CodeLayout.PORTRAIT, r.layout.orientation)
        assertEquals(r.matrix.width, r.matrix.height)
    }
}
