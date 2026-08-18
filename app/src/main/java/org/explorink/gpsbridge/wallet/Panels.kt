package org.explorink.gpsbridge.wallet

/**
 * One e-ink panel, physical (storage) and logical (reading) geometry.
 *
 * Port of `PanelProfile` in `tools/walletgen.py` and the only place in the app a
 * screen size is written down. `rowBytes` and `assetBytes` are derived, never
 * typed.
 *
 * The panel is physically landscape; portrait is a logical orientation only, so
 * the logical portrait tile is the physical size with the axes swapped
 * (`docs/wallet-format.md`, "Two geometries").
 */
class PanelProfile(val name: String, val width: Int, val height: Int) {

    /** 1bpp stride of one physical row. */
    val rowBytes: Int = (width + 7) / 8

    /** One whole screen, packed. 48,000 B on X4, 52,272 B on X3. */
    val assetBytes: Int = rowBytes * height

    /** Logical portrait tile width == physical height. */
    val tileW: Int = height

    /** Logical portrait tile height == physical width. */
    val tileH: Int = width

    /** Bytes in one RLE band: whole physical rows. */
    fun bandBytes(bandRows: Int): Int = bandRows * rowBytes

    /** Bands needed to cover the panel. The last one may be short (X3 is). */
    fun bandCount(bandRows: Int): Int = (height + bandRows - 1) / bandRows

    override fun toString(): String =
        "PanelProfile($name, ${width}x$height, $rowBytes B/row, $assetBytes B/asset)"
}

/**
 * Verified, read off the firmware SDK
 * (`freeink-sdk/libs/display/FreeInkDisplay/include/FreeInkDisplay.h:47-54`).
 * Adding a panel is one entry here; nothing else in the pipeline knows a screen
 * size. Kept in the same order and with the same numbers as `PANELS` in
 * `tools/walletgen.py`.
 */
object Panels {

    val X4 = PanelProfile("x4", 800, 480)
    val X3 = PanelProfile("x3", 792, 528)

    const val DEFAULT_NAME = "x4"

    private val byName = mapOf("x4" to X4, "x3" to X3)

    fun byName(name: String): PanelProfile =
        byName[name] ?: throw IllegalArgumentException(
            "unknown panel '$name'; known: ${byName.keys.sorted().joinToString(", ")}")

    val default: PanelProfile get() = X4
}
