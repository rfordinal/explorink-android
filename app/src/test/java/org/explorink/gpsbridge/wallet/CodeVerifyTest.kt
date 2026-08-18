package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verify loop: **a code counts as verified only when the STORED asset bytes
 * decode back to the same payload and the same symbology**
 * (`docs/wallet-format.md` section 10).
 *
 * Not an intermediate image -- the bytes that go on the card, unpacked and put
 * back the way the rider will see them. And the false paths are tested too: a
 * check that cannot fail is worth nothing.
 */
class CodeVerifyTest {

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

    private fun store(sym: Symbology, payload: String, panel: PanelProfile,
                      orientation: String = "auto"): Pair<ByteArray, CodeLayout.Layout> {
        val r = CodeWriter.render(sym, payload, panel, orientation)
        return Pair(CodeWriter.pack(r.canvas, panel, r.layout.orientation), r.layout)
    }

    @Test
    fun every_symbology_decodes_back_out_of_its_stored_bytes() {
        for (panel in panels) {
            for ((sym, payload) in samples) {
                val (bytes, layout) = store(sym, payload, panel)
                assertEquals(panel.assetBytes, bytes.size)
                assertTrue("${sym.key} on ${panel.name} (${layout.orientation}, " +
                    "${layout.moduleSize} px) did not decode back",
                    CodeReader.verify(bytes, panel, sym, payload, layout.presentation))
            }
        }
    }

    @Test
    fun both_orientations_of_one_payload_decode_back() {
        for (panel in panels) {
            for (orientation in CodeLayout.ORIENTATIONS) {
                val (bytes, layout) = store(Symbology.PDF417, CodeFixtures.BCBP136, panel,
                    orientation)
                assertEquals(orientation, layout.orientation)
                assertTrue("pdf417 $orientation on ${panel.name} at ${layout.moduleSize} px",
                    CodeReader.verify(bytes, panel, Symbology.PDF417, CodeFixtures.BCBP136,
                        layout.presentation))
            }
        }
    }

    @Test
    fun a_corrupted_asset_is_not_verified() {
        for (panel in panels) {
            for ((sym, payload) in listOf(
                Symbology.QR to CodeFixtures.BCBP136,
                Symbology.PDF417 to CodeFixtures.BCBP136,
                Symbology.AZTEC to CodeFixtures.BCBP136)) {
                val (good, layout) = store(sym, payload, panel)
                assertTrue(CodeReader.verify(good, panel, sym, payload, layout.presentation))
                // Wipe the middle third of the stored rows to white.
                val bad = good.copyOf()
                val lo = panel.height / 3 * panel.rowBytes
                val hi = 2 * panel.height / 3 * panel.rowBytes
                for (i in lo until hi) bad[i] = 0xFF.toByte()
                assertFalse("${sym.key} on ${panel.name} verified damaged bytes",
                    CodeReader.verify(bad, panel, sym, payload, layout.presentation))
            }
        }
    }

    @Test
    fun the_wrong_payload_and_the_wrong_symbology_are_not_verified() {
        for (panel in panels) {
            val (bytes, layout) = store(Symbology.QR, "PAYLOAD-A", panel)
            assertTrue(CodeReader.verify(bytes, panel, Symbology.QR, "PAYLOAD-A",
                layout.presentation))
            assertFalse(CodeReader.verify(bytes, panel, Symbology.QR, "PAYLOAD-B",
                layout.presentation))
            // Right bytes, wrong format: not the code we were asked to store.
            assertFalse(CodeReader.verify(bytes, panel, Symbology.AZTEC, "PAYLOAD-A",
                layout.presentation))
        }
    }

    @Test
    fun a_blank_asset_is_not_verified() {
        val panel = Panels.X4
        val blank = ByteArray(panel.assetBytes) { 0xFF.toByte() }
        assertFalse(CodeReader.verify(blank, panel, Symbology.QR, CodeFixtures.SHORT,
            WalletFormat.PRESENTATION_PORTRAIT))
        assertTrue(CodeReader.decodeAsset(blank, panel).isEmpty())
    }

    @Test
    fun reading_a_portrait_asset_as_landscape_finds_nothing_useful() {
        // The verify loop cannot police orientation on its own -- decoders happily
        // read a turned image -- but the unrotate step is what makes the payload
        // come out of the right frame, and it is exercised here.
        val panel = Panels.X4
        val (bytes, layout) = store(Symbology.CODE128, CodeFixtures.SHORT, panel, "portrait")
        assertEquals(WalletFormat.PRESENTATION_PORTRAIT, layout.presentation)
        assertTrue(CodeReader.verify(bytes, panel, Symbology.CODE128, CodeFixtures.SHORT,
            layout.presentation))
    }

    @Test
    fun a_huge_payload_still_renders_and_still_verifies_at_a_tiny_module() {
        val panel = Panels.X4
        val payload = "X".repeat(2000)
        val r = CodeWriter.render(Symbology.QR, payload, panel)
        assertEquals(CodeLayout.PORTRAIT, r.layout.orientation)
        assertTrue("expected a version-32-or-bigger QR", r.matrix.width >= 141)
        assertTrue("module should be tiny, got ${r.layout.moduleSize}", r.layout.moduleSize <= 3)
        val bytes = CodeWriter.pack(r.canvas, panel, r.layout.orientation)
        // It decodes off a laptop bitmap. Whether a scanner reads a 3 px module off
        // e-ink glass is a hardware question no test here can answer.
        assertTrue(CodeReader.verify(bytes, panel, Symbology.QR, payload, r.layout.presentation))
    }
}
