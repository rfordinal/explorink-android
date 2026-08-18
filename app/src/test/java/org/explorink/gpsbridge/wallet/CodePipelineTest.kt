package org.explorink.gpsbridge.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Codes through the pipeline and into the tree: the asset, the sidecar, the
 * manifest entry, the ids, and what the store does with them.
 *
 * The manifest shape is the contract (`docs/wallet-format.md` section 10) and the
 * generator writes exactly these keys, so it is asserted key by key rather than
 * by eyeballing the JSON.
 */
class CodePipelineTest {

    private val panel = Panels.X4
    private val pipeline = WalletPipeline(panel)

    /** A grey page with a code on it, the way an import sees one. */
    private fun photoPage(sym: Symbology, payload: String, modulePx: Int = 8): GrayImage {
        val m = CodeWriter.matrix(sym, payload, panel)
        val qz = sym.quietZone
        val w = (m.width + 2 * qz) * modulePx + 200
        val h = (m.height + 2 * qz) * modulePx + 300
        val img = GrayImage.filled(w, h, 255)
        val x0 = (w - m.width * modulePx) / 2
        val y0 = (h - m.height * modulePx) / 2
        for (my in 0 until m.height) {
            for (mx in 0 until m.width) {
                if (!m[mx, my]) continue
                for (dy in 0 until modulePx) {
                    val base = (y0 + my * modulePx + dy) * w + x0 + mx * modulePx
                    java.util.Arrays.fill(img.pixels, base, base + modulePx, 0)
                }
            }
        }
        return img
    }

    private fun buildWith(codes: List<WalletPipeline.CodeRequest>,
                          sink: AssetSink = MemoryAssetSink(),
                          pages: Int = 1): Pair<WalletItem, AssetSink> {
        val sources = (0 until pages).map { i ->
            WalletPipeline.PageSource(GrayImage.filled(600, 850, 200), "p$i.png",
                codes = if (i == 0) codes else emptyList())
        }
        val item = pipeline.buildItem("aabbccddeeff0011", "Boarding pass",
            "2026-08-18T00:00:00Z", 0, sources, sink, paper = "a4")
        return Pair(item, sink)
    }

    @Test
    fun a_code_becomes_one_asset_and_one_manifest_entry() {
        val (item, sink) = buildWith(listOf(
            WalletPipeline.CodeRequest(Symbology.PDF417, CodeFixtures.BCBP136)))
        val page = item.pages[0]
        assertEquals(1, page.codes.size)
        val code = page.codes[0]
        assertEquals("c001", code.id)
        assertEquals("pdf417", code.symbology)
        assertEquals(CodeFixtures.BCBP136, code.payload)
        assertTrue("a rendered pdf417 must verify", code.verified)
        assertEquals("landscape", code.orientation)
        assertEquals(WalletFormat.PRESENTATION_LANDSCAPE, code.presentation)
        assertEquals(5, code.moduleSize)
        assertEquals(4, code.quietZone)
        assertEquals((137 + 8) * 5, code.codeWidthPx)
        assertEquals((84 + 8) * 5, code.codeHeightPx)

        // The id recipe: assetType 4, index = position in the page's code list.
        assertEquals(WalletFormat.assetId(panel.name, item.id, page.id,
            WalletFormat.ASSET_MACHINE_CODE, 0, 1), code.assetId)

        val mem = sink as MemoryAssetSink
        val dat = mem.dat.getValue(code.assetId)
        assertEquals(WalletFormat.ASSET_HEADER_LEN + panel.assetBytes, dat.size)
        val payload = dat.copyOfRange(WalletFormat.ASSET_HEADER_LEN, dat.size)
        assertEquals(code.sha256, WalletFormat.sha256Hex(payload))
        assertEquals(WalletFormat.ASSET_MACHINE_CODE, dat[4].toInt())
        assertEquals(WalletFormat.BIT_DEPTH_1BPP, dat[5].toInt())
        assertEquals(0, dat[6].toInt())                       // col
        assertEquals(0, dat[7].toInt())                       // row
        assertEquals(WalletFormat.PRESENTATION_LANDSCAPE, dat[21].toInt())
        // And a sidecar like any other asset.
        assertEquals(code.rleLen, mem.rle.getValue(code.assetId).size)
        assertArrayEquals(dat.copyOfRange(0, WalletFormat.ASSET_HEADER_LEN),
            mem.rle.getValue(code.assetId).copyOfRange(0, WalletFormat.ASSET_HEADER_LEN))

        // Verified means the STORED bytes decoded back.
        assertTrue(CodeReader.verify(payload, panel, Symbology.PDF417, CodeFixtures.BCBP136,
            WalletFormat.PRESENTATION_LANDSCAPE))
    }

    @Test
    fun the_manifest_json_carries_every_field_in_order() {
        val (item, _) = buildWith(listOf(
            WalletPipeline.CodeRequest(Symbology.QR, CodeFixtures.SHORT)))
        val wallet = Wallet(WalletFormat.MANIFEST_FORMAT_VERSION, 1, panel.name, listOf(item))
        val json = wallet.toManifestJson()
        val page = Json.asMap(Json.asList(Json.asMap(Json.asList(
            Json.asMap(Json.parse(json))["items"])[0])["pages"])[0])
        val code = Json.asMap(Json.asList(page["codes"])[0])
        assertEquals(listOf("id", "symbology", "payload", "verified", "assetId", "orientation",
            "presentation", "moduleSize", "quietZone", "codeWidthPx", "codeHeightPx",
            "sha256", "rleLen"), code.keys.toList())
        // And it survives a round trip, which is what a device or a re-read needs.
        val back = Wallet.fromManifestJson(json)
        assertEquals(item.pages[0].codes, back.items[0].pages[0].codes)
        assertEquals(json, back.toManifestJson())
    }

    @Test
    fun both_orientations_of_one_payload_are_two_assets() {
        val (item, sink) = buildWith(listOf(
            WalletPipeline.CodeRequest(Symbology.PDF417, CodeFixtures.BCBP136, "portrait"),
            WalletPipeline.CodeRequest(Symbology.PDF417, CodeFixtures.BCBP136, "landscape")))
        val codes = item.pages[0].codes
        assertEquals(2, codes.size)
        assertEquals(listOf("c001", "c002"), codes.map { it.id })
        assertNotEquals(codes[0].assetId, codes[1].assetId)
        assertEquals("portrait", codes[0].orientation)
        assertEquals("landscape", codes[1].orientation)
        assertEquals(3, codes[0].moduleSize)
        assertEquals(5, codes[1].moduleSize)
        assertTrue(codes.all { it.verified })
        assertEquals(2, (sink as MemoryAssetSink).dat.keys.count { it in codes.map { c -> c.assetId } })
    }

    @Test
    fun codes_do_not_collide_with_tiles_or_page_images() {
        val (item, sink) = buildWith(listOf(
            WalletPipeline.CodeRequest(Symbology.QR, CodeFixtures.SHORT),
            WalletPipeline.CodeRequest(Symbology.AZTEC, CodeFixtures.BCBP136)))
        val ids = ArrayList<String>()
        for (page in item.pages) {
            for (level in page.levels.values) {
                ids.addAll(level.assets.map { it.assetId })
                level.pageImage?.let { ids.add(it.assetId) }
            }
            ids.addAll(page.codes.map { it.assetId })
        }
        assertEquals("an id repeats", ids.size, ids.toSet().size)
        assertEquals(ids.size, (sink as MemoryAssetSink).dat.size)
        assertEquals(ids.toSet(), sink.dat.keys)
        // 24 assets for an A4 page (21 tiles + 3 page images) plus the two codes.
        assertEquals(26, ids.size)
        assertEquals(26, item.assetCount)
        assertEquals(2, item.codeCount)
    }

    @Test
    fun codes_belong_to_the_page_they_were_found_on() {
        val (item, _) = buildWith(listOf(
            WalletPipeline.CodeRequest(Symbology.QR, CodeFixtures.SHORT)), pages = 2)
        assertEquals(1, item.pages[0].codes.size)
        assertEquals(0, item.pages[1].codes.size)
        // Page 2's codes would get their own ids: the page id is in the recipe.
        assertNotEquals(
            WalletFormat.assetId(panel.name, item.id, "p001", WalletFormat.ASSET_MACHINE_CODE, 0, 1),
            WalletFormat.assetId(panel.name, item.id, "p002", WalletFormat.ASSET_MACHINE_CODE, 0, 1))
    }

    @Test
    fun a_page_with_no_code_gets_an_empty_list() {
        val (item, _) = buildWith(emptyList())
        assertEquals(emptyList<MachineReadableCode>(), item.pages[0].codes)
        assertEquals(0, item.codeCount)
        assertTrue(Wallet(1, 1, panel.name, listOf(item)).toManifestJson().contains("\"codes\": []"))
    }

    @Test
    fun detection_feeds_the_pipeline_the_way_the_importer_does() {
        // The whole path minus Android: photo -> detect -> regenerate -> store -> verify.
        val found = CodeReader.detect(photoPage(Symbology.QR, CodeFixtures.BCBP136))
        assertEquals(1, found.size)
        val (item, sink) = buildWith(found.map {
            WalletPipeline.CodeRequest(it.symbology, it.payload)
        })
        val code = item.pages[0].codes.single()
        assertEquals("qr", code.symbology)
        assertEquals(CodeFixtures.BCBP136, code.payload)
        assertTrue(code.verified)
        val dat = (sink as MemoryAssetSink).dat.getValue(code.assetId)
        assertTrue(CodeReader.verify(dat.copyOfRange(WalletFormat.ASSET_HEADER_LEN, dat.size),
            panel, Symbology.QR, CodeFixtures.BCBP136, code.presentation))
    }

    @Test
    fun a_deleted_item_takes_its_code_assets_with_it() {
        val root: File = Files.createTempDirectory("wallet-code-store").toFile()
        try {
            val store = WalletStore(root)
            val (item, _) = buildWith(listOf(
                WalletPipeline.CodeRequest(Symbology.QR, CodeFixtures.SHORT)),
                sink = store.sink())
            store.addItem(item, listOf("p0.png"))
            val code = item.pages[0].codes.single()
            assertTrue(store.assetFile(code.assetId, "dat").isFile)
            assertTrue(store.assetFile(code.assetId, "rle").isFile)
            assertTrue(store.load().items[0].pages[0].codes.single().verified)
            store.deleteItem(item.id)
            assertTrue("the code asset outlived its item",
                !store.assetFile(code.assetId, "dat").exists())
            assertTrue(!store.assetFile(code.assetId, "rle").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
