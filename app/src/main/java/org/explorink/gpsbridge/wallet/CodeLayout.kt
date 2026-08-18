package org.explorink.gpsbridge.wallet

/**
 * The symbologies a wallet code can be, and the quiet zone each one needs.
 *
 * Port of `CODE_SYMBOLOGIES` in `tools/walletgen.py`. `key` is what goes into the
 * manifest's `symbology` field, so it is the contract's spelling and nothing else
 * (`docs/wallet-format.md` section 10).
 *
 * Quiet zone: the brief asks for at least 4 modules; two symbologies specify more
 * and get their own minimum. The rule is `max(4, what the symbology specifies)`
 * and it lives in [quietZone].
 */
enum class Symbology(val key: String, private val ownMinimum: Int) {
    QR("qr", 4),
    PDF417("pdf417", 4),
    AZTEC("aztec", 4),
    DATAMATRIX("datamatrix", 4),
    CODE128("code128", 10),
    EAN13("ean13", 9);

    /** `max(4, symbology minimum)`. Code128 gets 10, EAN13 9, the rest 4. */
    val quietZone: Int get() = maxOf(CodeLayout.QUIET_ZONE_MIN, ownMinimum)

    companion object {
        fun byKey(key: String): Symbology? = entries.firstOrNull { it.key == key }

        fun require(key: String): Symbology = byKey(key)
            ?: throw IllegalArgumentException(
                "unknown symbology '$key'; known: ${entries.joinToString(", ") { it.key }}")
    }
}

/**
 * Where a code goes on a screen: how big one module can be, and which way round
 * the code is laid out.
 *
 * Pure arithmetic, no barcode library, no image. Port of `code_canvas_size()`,
 * `code_layout()` and `choose_orientation()` in `tools/walletgen.py`, and the
 * numbers it produces are the ones the manifest carries.
 *
 * The orientation rule is arithmetic **on purpose** and not a list of
 * symbologies: DataMatrix is square for a short payload and rectangular for a
 * long one, so a per-symbology list would ship a bug
 * (`docs/wallet-format.md` section 10, "Orientation: portrait or landscape").
 */
object CodeLayout {

    /** Brief section 12: at least four modules of white around every code. */
    const val QUIET_ZONE_MIN = 4

    /** The rider holds the device as usual; the canvas is the logical screen. */
    const val PORTRAIT = "portrait"

    /** The rider turns the device sideways; the canvas is the panel as it is. */
    const val LANDSCAPE = "landscape"

    val ORIENTATIONS = listOf(PORTRAIT, LANDSCAPE)

    /**
     * The drawing area for a code, in the orientation's own coordinates.
     *
     * portrait  -> the logical portrait screen (480 x 800 on X4)
     * landscape -> the panel as it physically is (800 x 480 on X4)
     */
    fun canvasSize(panel: PanelProfile, orientation: String): Pair<Int, Int> = when (orientation) {
        PORTRAIT -> Pair(panel.tileW, panel.tileH)
        LANDSCAPE -> Pair(panel.width, panel.height)
        else -> throw IllegalArgumentException("orientation must be one of $ORIENTATIONS")
    }

    /**
     * One code's geometry. [moduleSize] 0 means it does not fit at all in this
     * orientation -- the caller decides whether that is an error or just the
     * losing orientation.
     */
    data class Layout(
        val orientation: String,
        val presentation: Int,
        val moduleSize: Int,
        val quietZone: Int,
        val modulesX: Int,
        val modulesY: Int,
        val codeWidthPx: Int,
        val codeHeightPx: Int,
    )

    /**
     * `module = min(canvasW / (mw + 2qz), canvasH / (mh + 2qz))`, integer
     * division: the largest whole module that still fits with its quiet zone.
     */
    fun layout(modulesX: Int, modulesY: Int, symbology: Symbology,
               panel: PanelProfile, orientation: String): Layout {
        require(modulesX > 0 && modulesY > 0) { "empty matrix ${modulesX}x$modulesY" }
        val qz = symbology.quietZone
        val (cw, ch) = canvasSize(panel, orientation)
        val totalW = modulesX + 2 * qz
        val totalH = modulesY + 2 * qz
        val module = minOf(cw / totalW, ch / totalH)
        return Layout(
            orientation = orientation,
            presentation = if (orientation == PORTRAIT) WalletFormat.PRESENTATION_PORTRAIT
                           else WalletFormat.PRESENTATION_LANDSCAPE,
            moduleSize = module,
            quietZone = qz,
            modulesX = modulesX,
            modulesY = modulesY,
            codeWidthPx = totalW * module,
            codeHeightPx = totalH * module,
        )
    }

    /**
     * Landscape **only when it actually buys a bigger module**.
     *
     * A wide matrix wins: its width axis gets the panel's long side, 800 px
     * instead of 480. A square one ties -- the short side limits both -- so it
     * stays portrait and the rider does not have to turn the device for nothing.
     */
    fun chooseOrientation(modulesX: Int, modulesY: Int, symbology: Symbology,
                          panel: PanelProfile): String {
        val port = layout(modulesX, modulesY, symbology, panel, PORTRAIT).moduleSize
        val land = layout(modulesX, modulesY, symbology, panel, LANDSCAPE).moduleSize
        return if (land > port) LANDSCAPE else PORTRAIT
    }

    /** The best module size this matrix can get on this panel, either way round. */
    fun bestModuleSize(modulesX: Int, modulesY: Int, symbology: Symbology,
                       panel: PanelProfile): Int = maxOf(
        layout(modulesX, modulesY, symbology, panel, PORTRAIT).moduleSize,
        layout(modulesX, modulesY, symbology, panel, LANDSCAPE).moduleSize)
}
