package org.explorink.gpsbridge.wallet

/**
 * The image pipeline: source pages in, device assets and a manifest item out.
 *
 * Step for step the same as `tools/walletgen.py` (`load_page()` + `build_page()`),
 * because the output has to be byte-identical -- `WalletParityTest` checks every
 * asset, every header and the whole manifest against a recorded generator run.
 *
 * Per page, in order (`docs/wallet-format.md` section 6):
 *
 *  1. EXIF orientation, grayscale        (caller's job -- `ImageImport` on Android)
 *  2. autocontrast(cutoff = 1) on the full-resolution grey, once
 *  3. resize per level (Lanczos), letterbox white
 *  4. Floyd-Steinberg dither to 1bpp
 *  5. slice into logical tiles
 *  6. rotate to panel byte order and pack
 *
 * Steps 2-6 are here. Step 1 needs a decoder, so it is the only Android-only part.
 */
class WalletPipeline(
    val panel: PanelProfile = Panels.default,
    /** Design B assets. On by default: the device viewer prefers them. */
    val pageImage: Boolean = true,
    /** The encryption seam. [AssetCipher.None] until P3. */
    val cipher: AssetCipher = AssetCipher.None,
) {

    /** One source page: grayscale pixels plus whatever the decoder knew about it. */
    class PageSource(
        val gray: GrayImage,
        /** Display name, used for the item id. Null when the source has none. */
        val name: String,
        val dpiX: Double? = null,
        val dpiY: Double? = null,
    )

    /**
     * Build one item. Writes every asset through [sink] and returns the manifest
     * item; the caller decides where it lands in the wallet.
     *
     * [paper] is `"auto"`, `"a4"` or `"a5"`. `auto` follows the generator: DPI
     * metadata when the source has it, else a4.
     */
    fun buildItem(
        itemId: String,
        title: String,
        createdAt: String,
        sortOrder: Int,
        sources: List<PageSource>,
        sink: AssetSink,
        paper: String = "auto",
        version: Int = 1,
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ): WalletItem {
        require(sources.isNotEmpty()) { "an item needs at least one page" }
        val first = sources[0]
        val resolvedPaper = if (paper == "auto") {
            WalletFormat.pickPaper(first.gray.width, first.gray.height, first.dpiX, first.dpiY)
        } else {
            paper
        }

        val perPage = assetsPerPage(resolvedPaper)
        val total = perPage * sources.size
        var done = 0

        val pages = ArrayList<WalletPage>(sources.size)
        for ((i, source) in sources.withIndex()) {
            val pageId = "p%03d".format(i + 1)
            // Step 2: deterministic contrast, once, on the full-resolution grey.
            val gray = source.gray.autocontrast(WalletFormat.AUTOCONTRAST_CUTOFF)
            pages.add(buildPage(itemId, pageId, gray, resolvedPaper, version, sink) {
                done++
                progress?.invoke(done, total)
            })
        }
        return WalletItem(itemId, title, createdAt, sortOrder, pages)
    }

    /** How many assets one page produces, so a UI can show real progress. */
    fun assetsPerPage(paper: String): Int {
        var n = 0
        for (level in WalletFormat.LEVELS) {
            val (cols, rows) = levelGrid(level, paper)
            n += cols * rows
            if (pageImage) n++
        }
        return n
    }

    private fun levelGrid(level: String, paper: String): Pair<Int, Int> = when (level) {
        "fit" -> Pair(1, 1)
        "detail" -> Pair(2, 2)
        "one_to_one" -> WalletFormat.oneToOneGrid(paper, panel)
        else -> throw IllegalArgumentException("unknown level '$level'")
    }

    // --- one page ----------------------------------------------------------

    private fun buildPage(
        itemId: String,
        pageId: String,
        gray: GrayImage,
        paper: String,
        version: Int,
        sink: AssetSink,
        tick: () -> Unit,
    ): WalletPage {
        val levels = LinkedHashMap<String, WalletLevel>()
        for (level in WalletFormat.LEVELS) {
            val canvas = levelCanvas(gray, level, paper)
            val (cols, rows) = levelGrid(level, paper)
            // Step 4. The grey canvas is dropped straight after, so only one big
            // raster is alive at a time.
            val mono = canvas.dither()
            val assetType = WalletFormat.LEVEL_TYPE.getValue(level)

            val entries = ArrayList<WalletAsset>(cols * rows)
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    // Steps 5 + 6 in one pass: the tile is never materialised as a
                    // separate image, it is packed straight out of the canvas.
                    val payload = mono.packNativeRegion(
                        col * panel.tileW, row * panel.tileH, panel.tileW, panel.tileH)
                    if (payload.size != panel.assetBytes) {
                        throw AssertionError(
                            "tile is ${payload.size} bytes, panel ${panel.name} wants ${panel.assetBytes}")
                    }
                    val index = row * cols + col
                    val aid = WalletFormat.assetId(
                        panel.name, itemId, pageId, assetType, index, version)
                    entries.add(writeAsset(sink, aid, assetType, col, row, payload, version,
                        width = panel.width, height = panel.height, rowBytes = panel.rowBytes))
                    tick()
                }
            }

            val (dx, dy) = WalletFormat.defaultTile(cols, rows)
            var pi: WalletPageImage? = null
            if (pageImage) {
                pi = writePageImage(sink, itemId, pageId, mono, level, paper, version, cols, rows)
                tick()
            }
            levels[level] = WalletLevel(cols, rows, dx, dy, entries, pi)
        }
        // Codes are phase P5 on this side. The list exists so the manifest shape
        // is final now.
        return WalletPage(pageId, paper, levels, emptyList())
    }

    /**
     * Grayscale canvas for one zoom level, already the exact tile-grid size.
     *
     * FIT one logical tile, DETAIL 2x2, ONE_TO_ONE the sheet at physical size
     * anchored top-left and padded white out to whole tiles.
     */
    fun levelCanvas(gray: GrayImage, level: String, paper: String): GrayImage = when (level) {
        "fit" -> gray.fitInto(panel.tileW, panel.tileH)
        "detail" -> gray.fitInto(panel.tileW * 2, panel.tileH * 2)
        "one_to_one" -> {
            val (pxW, pxH) = WalletFormat.paperPx(paper)
            val (cols, rows) = WalletFormat.oneToOneGrid(paper, panel)
            val sheet = gray.fitInto(pxW, pxH)
            val canvas = GrayImage.filled(cols * panel.tileW, rows * panel.tileH, 255)
            canvas.paste(sheet, 0, 0)
            canvas
        }
        else -> throw IllegalArgumentException("unknown level '$level'")
    }

    // --- design B: one whole page per level ---------------------------------

    /**
     * Logical extent of the real page inside a level canvas. The tile canvas is
     * padded out to whole tiles; a page image keeps only the page.
     *
     * Two constraints: at least one screen in each axis, and the logical HEIGHT is
     * rounded up to a multiple of 8, because it becomes the page image's native
     * WIDTH and a native row must end on a byte. The extra rows come from the
     * canvas's own white padding, so they are white.
     */
    fun pageExtent(canvasW: Int, canvasH: Int, level: String, paper: String): Pair<Int, Int> {
        var extW = canvasW
        var extH = canvasH
        if (level == "one_to_one") {
            val (pxW, pxH) = WalletFormat.paperPx(paper)
            extW = minOf(maxOf(pxW, panel.tileW), canvasW)
            extH = minOf(maxOf(pxH, panel.tileH), canvasH)
        }
        extH = minOf(canvasH, alignUp(extH, 8))
        return Pair(extW, extH)
    }

    private fun writePageImage(
        sink: AssetSink,
        itemId: String,
        pageId: String,
        mono: MonoImage,
        level: String,
        paper: String,
        version: Int,
        cols: Int,
        rows: Int,
    ): WalletPageImage {
        val (extW, extH) = pageExtent(mono.width, mono.height, level, paper)
        // Same rotation rule as a tile, applied to the whole page. The pixels are
        // the SAME dithered bitmap the tiles are cut from, so a tile-aligned window
        // is byte-identical to the pre-cut tile.
        val payload = mono.packNativeRegion(0, 0, extW, extH)
        val nativeW = extH
        val nativeH = extW
        val rowBytes = nativeW / 8
        if (payload.size != rowBytes * nativeH) {
            throw AssertionError("page image is ${payload.size} bytes, want ${rowBytes * nativeH}")
        }

        val aid = WalletFormat.assetId(panel.name, itemId, pageId,
            WalletFormat.ASSET_PAGE_IMAGE, WalletFormat.LEVEL_INDEX.getValue(level), version)
        val entry = writeAsset(sink, aid, WalletFormat.ASSET_PAGE_IMAGE, 0, 0, payload, version,
            width = nativeW, height = nativeH, rowBytes = rowBytes)

        val (stepX, stepY) = windowStep()
        val (fc, fr) = WalletFormat.defaultTile(cols, rows)
        val (fx, fy) = tileWindowOrigin(fc, fr, extW)
        return WalletPageImage(
            assetId = aid,
            nativeWidth = nativeW,
            nativeHeight = nativeH,
            rowBytes = rowBytes,
            rawLen = payload.size,
            sha256 = entry.sha256,
            rleLen = entry.rleLen,
            windowStepX = stepX,
            windowStepY = stepY,
            focalX = clampOrigin(fx, nativeW, panel.width),
            focalY = clampOrigin(fy, nativeH, panel.height),
        )
    }

    /**
     * Default pan increment, native px: half a screen each way.
     *
     * Native x is the shift axis -- it is a byte offset inside a row, and only a
     * multiple of 8 avoids a bit rotation on the device -- so the x step is rounded
     * DOWN to a multiple of 8 (x4 400; x3 396 becomes 392). Native y is pure row
     * selection and needs no alignment.
     */
    fun windowStep(): Pair<Int, Int> = Pair(alignDown(panel.width / 2, 8), panel.height / 2)

    /** Window origin clamped to the page edge. Never wraps. */
    fun clampOrigin(v: Int, span: Int, window: Int): Int {
        if (span <= window) return 0
        return maxOf(0, minOf(v, span - window))
    }

    /**
     * Native origin of the pre-cut tile (col, row) inside the page image. From the
     * rotation rule: native x = logical y, native y = extW - 1 - logical x.
     */
    fun tileWindowOrigin(col: Int, row: Int, extW: Int): Pair<Int, Int> =
        Pair(row * panel.tileH, extW - (col + 1) * panel.tileW)

    // --- writing one asset --------------------------------------------------

    /**
     * Header, payload, sidecar, and the manifest entry that describes them.
     *
     * `width`/`height` describe the payload **as stored**: one screen at the
     * panel's physical size for a tile, the whole page for a page image. Bands are
     * 80 native rows of THIS asset, so [rowBytes] is the asset's own stride.
     */
    private fun writeAsset(
        sink: AssetSink,
        assetId: String,
        assetType: Int,
        col: Int,
        row: Int,
        payload: ByteArray,
        version: Int,
        width: Int,
        height: Int,
        rowBytes: Int,
        bitDepth: Int = WalletFormat.BIT_DEPTH_1BPP,
        presentation: Int = WalletFormat.PRESENTATION_PORTRAIT,
    ): WalletAsset {
        val header = WalletFormat.buildAssetHeader(
            assetType, bitDepth, col, row, width, height, payload, version,
            flags = cipher.flags, presentation = presentation)

        // THE ENCRYPTION SEAM. With AssetCipher.None both calls are the identity
        // and the bytes are exactly what walletgen.py writes.
        val dat = header + cipher.seal(assetId, header, payload)
        val rle = header + cipher.sealSidecar(assetId, header, Rle.encode(payload, rowBytes))
        sink.write(assetId, dat, rle)

        return WalletAsset(
            assetId = assetId,
            type = assetType,
            col = col,
            row = row,
            rawLen = payload.size,
            sha256 = WalletFormat.sha256Hex(payload),
            rleLen = rle.size,
        )
    }

    private fun alignUp(v: Int, n: Int): Int = ((v + n - 1) / n) * n

    private fun alignDown(v: Int, n: Int): Int = (v / n) * n
}

/** Where the pipeline puts the two files of one asset. */
interface AssetSink {
    fun write(assetId: String, dat: ByteArray, rle: ByteArray)
}

/**
 * The encryption seam, and the only one.
 *
 * P3 is landing AES-256-CTR at rest on the laptop side (`docs/wallet-plan.md`
 * section 3.4). When it lands here, one implementation of this interface is the
 * whole change on the pipeline side: the 32-byte header stays cleartext (so a
 * recovery scan can rebuild a lost manifest), `flags` sets bit 0, and the payload
 * that follows becomes ciphertext.
 *
 * Two decisions P3 owns and this seam deliberately does not pre-empt:
 *
 *  - whether the header's `sha256_prefix` and the manifest's `sha256` cover the
 *    plaintext (they do today) or the ciphertext;
 *  - whether the sidecar's EWRL block is sealed as one unit or per band. Per band
 *    keeps the band-independent resume the sidecar exists for, which argues for
 *    per band -- hence the separate [sealSidecar] hook rather than one `seal`.
 *
 * Compression must stay **before** encryption: ciphertext does not compress.
 */
interface AssetCipher {
    /** Header flags, e.g. bit 0 = encrypted. */
    val flags: Int

    /** Plaintext payload in, what goes after the 32-byte header out. */
    fun seal(assetId: String, header: ByteArray, payload: ByteArray): ByteArray

    /** Same, for the `.rle` sidecar's EWRL block. */
    fun sealSidecar(assetId: String, header: ByteArray, block: ByteArray): ByteArray

    /** Phase P0-P2 on the laptop, P4 here: nothing is encrypted yet. */
    object None : AssetCipher {
        override val flags: Int get() = 0
        override fun seal(assetId: String, header: ByteArray, payload: ByteArray) = payload
        override fun sealSidecar(assetId: String, header: ByteArray, block: ByteArray) = block
    }
}
