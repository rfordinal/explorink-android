package org.explorink.gpsbridge.wallet

import java.security.MessageDigest
import kotlin.math.ceil

/**
 * The on-disk wallet format: constants, ids, the 32-byte asset header and the
 * page geometry. Contract: `docs/wallet-format.md`. Reference implementation:
 * `tools/walletgen.py` -- this file is a port of it and must stay byte-identical
 * (checked by `WalletParityTest`).
 *
 * Nothing here touches Android, so it runs in a laptop unit test.
 */
object WalletFormat {

    // --- fixed constants, not per panel ------------------------------------

    /**
     * Vendor spec (Xteink website), 2026-08-18. Unverified -- nobody has put a
     * caliper on the active area. Everything about ONE_TO_ONE derives from this
     * one number: change it and every 1:1 canvas, asset id and page image moves
     * (`docs/wallet-format.md`, "PPI and the 1:1 grid").
     */
    const val DEVICE_PPI = 220

    const val MM_PER_INCH = 25.4

    val PAPER_MM: Map<String, Pair<Double, Double>> = linkedMapOf(
        "a4" to Pair(210.0, 297.0),
        "a5" to Pair(148.0, 210.0),
    )

    // Asset types, as written into the .dat header.
    const val ASSET_FIT = 1
    const val ASSET_DETAIL_TILE = 2
    const val ASSET_ONE_TO_ONE_TILE = 3
    const val ASSET_MACHINE_CODE = 4          // phase P5 on this side
    const val ASSET_PAGE_IMAGE = 5            // design B: one whole page per level
    const val ASSET_PAGE_IMAGE_GREY = 6       // the same page at 2bpp (grey)
    const val ASSET_GREY_PLANES = 7           // ONE screen, base||lsb||msb, baked

    const val BIT_DEPTH_1BPP = 1
    const val BIT_DEPTH_2BPP = 2              // four-level grey

    /**
     * **One assetType means one bit depth.** The asset id recipe has `assetType` in
     * it but not `bitDepth`, so the only reason a grey asset cannot collide with a
     * 1bpp one is that they use different types. Emit type 5 at bitDepth 2 and the
     * two share one id and one path, silently, which is the same class of failure the
     * panel scoping fixed. Named here with a test that demonstrates the collision it
     * prevents (`docs/wallet-format.md` section 13, "Ids").
     */
    val ASSET_TYPE_BIT_DEPTH: Map<Int, Int> = mapOf(
        ASSET_FIT to BIT_DEPTH_1BPP,
        ASSET_DETAIL_TILE to BIT_DEPTH_1BPP,
        ASSET_ONE_TO_ONE_TILE to BIT_DEPTH_1BPP,
        ASSET_MACHINE_CODE to BIT_DEPTH_1BPP,
        ASSET_PAGE_IMAGE to BIT_DEPTH_1BPP,
        ASSET_PAGE_IMAGE_GREY to BIT_DEPTH_2BPP,
        ASSET_GREY_PLANES to BIT_DEPTH_2BPP,
    )

    /**
     * `flags` bit 0 of the asset header: the payload after the 32-byte cleartext
     * header is AES-256-CTR ciphertext (`docs/wallet-format.md` section 11).
     */
    const val FLAG_ENCRYPTED = 0x01

    /** Document upright with the device held landscape; payload not rotated. */
    const val PRESENTATION_LANDSCAPE = 0

    /** Document upright with the device held portrait; payload rotated at build time. */
    const val PRESENTATION_PORTRAIT = 1

    val ASSET_MAGIC = "EWA1".toByteArray(Charsets.US_ASCII)
    const val ASSET_HEADER_LEN = 32

    const val MANIFEST_FORMAT_VERSION = 1

    /** A cleartext tree's manifest. */
    const val MANIFEST_CLEAR_NAME = "manifest.json"

    /**
     * An encrypted tree's manifest: the `EWM1` GCM container ([WalletCrypto]).
     *
     * **The device prefers this file whenever it exists** -- `treeIsEncrypted()` is an
     * existence check -- so a cleartext manifest written beside one is invisible, with
     * nothing reporting an error (`docs/wallet-plan.md` 7l). That is what
     * [WalletManifestConflict] exists to catch.
     */
    const val MANIFEST_ENC_NAME = "manifest.enc"

    /** One backup of the previous good `manifest.enc`. Encrypted trees only. */
    const val MANIFEST_BAK_NAME = "manifest.bak"

    private const val ID_DOMAIN = "explorink-wallet-v1|"
    private const val ITEM_ID_DOMAIN = "explorink-wallet-item-v1|"

    /** Percent clipped off each end of the histogram. Deterministic. */
    const val AUTOCONTRAST_CUTOFF = 1

    /** Zoom levels, in the order the manifest writes them. */
    val LEVELS = listOf("fit", "detail", "one_to_one")

    val LEVEL_TYPE = mapOf(
        "fit" to ASSET_FIT,
        "detail" to ASSET_DETAIL_TILE,
        "one_to_one" to ASSET_ONE_TO_ONE_TILE,
    )

    /**
     * A page image's `index` in the id recipe is its level ordinal: one asset per
     * level, and assetType 5 alone does not say which level it belongs to.
     */
    val LEVEL_INDEX: Map<String, Int> = LEVELS.withIndex().associate { it.value to it.index }

    // --- ids ---------------------------------------------------------------

    /**
     * THE CRYPTO SEAM FOR IDS. One function, one line, nothing else knows how the
     * digest is made -- same seam as `_id_digest()` in `tools/walletgen.py`.
     *
     * P3 did **not** replace it: an id is scoped by panel and by crypto state instead
     * (see [assetId]), which is what the generator did, so a filename still leaks
     * nothing on its own without a keyed digest. Swapping this for
     * `HMAC-SHA256(kName, payload)` remains possible and remains a format change on
     * both sides; nothing above depends on the digest being sha256.
     */
    private fun idDigest(payload: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(payload)

    /**
     * Deterministic 8-byte asset id, hex, **panel-scoped and crypto-scoped**.
     *
     *     sha256("explorink-wallet-v1|" + panel + "|" + ("enc"|"clear") + "|"
     *            + item + "|" + page + "|" + type + "|" + index + "|" + version)[0:8]
     *
     * The panel name is in the input so the same document built for two panels gets
     * two different ids and the trees can share a directory -- foreign-panel assets
     * become impossible rather than forbidden.
     *
     * The **encryption state** is in the input for the same reason, and it was added
     * after the collision was hit for real (`docs/wallet-plan.md` 7f): a cleartext
     * tree and an encrypted tree of the same document used to produce identical ids,
     * so on one card they occupied the same path and the last one written won. Nothing
     * was silently wrong -- `flags` bit 0 says what each file is, and a cleartext file
     * read as ciphertext fails the plaintext hash -- but the symptom was "asset
     * corrupt" rather than "wrong kind of file". Impossible beats detectable.
     */
    fun assetId(panelName: String, itemId: String, pageId: String,
                assetType: Int, index: Int, version: Int,
                encrypted: Boolean = false): String {
        val scope = if (encrypted) "enc" else "clear"
        val payload = (ID_DOMAIN +
            "$panelName|$scope|$itemId|$pageId|$assetType|$index|$version")
            .toByteArray(Charsets.UTF_8)
        return hex(idDigest(payload), 8)
    }

    /**
     * Deterministic item id, so a rerun on the same input rewrites the same files
     * instead of growing the tree. NOT panel-scoped, on purpose: an item id is a
     * logical identity that lives only inside a manifest, never a filename.
     *
     * [sourceNames] are the page sources' base names. On the laptop those are
     * file names; in the app they are the picked image's display name, which is
     * the closest equivalent a content Uri has (see `docs/android-wallet.md`).
     */
    /**
     * How long a document title may be, in UTF-8 bytes.
     *
     * 48 because the binary index stores it in a fixed 48-byte field
     * (`docs/wallet-index-v2.md`); enforcing it here means the JSON manifest never holds
     * a name the next format would have to truncate behind the rider's back.
     */
    const val TITLE_MAX_BYTES = 48

    fun itemIdFor(title: String, sourceNames: List<String>): String {
        val payload = (ITEM_ID_DOMAIN + title + "|" + sourceNames.joinToString("|"))
            .toByteArray(Charsets.UTF_8)
        return hex(idDigest(payload), 8)
    }

    fun shardOf(assetId: String): String = assetId.substring(0, 2)

    // --- asset header ------------------------------------------------------

    /**
     * The 32-byte cleartext header, little endian. Python's struct format is
     * `"<4sBBBBHHIIBB2s8s"` (`docs/wallet-format.md` section 5):
     *
     *     0  4 magic "EWA1"      16 4 version
     *     4  1 assetType         20 1 flags
     *     5  1 bitDepth          21 1 presentation
     *     6  1 tileCol           22 2 reserved (zero)
     *     7  1 tileRow           24 8 sha256(payload)[0:8]
     *     8  2 width
     *    10  2 height
     *    12  4 rawLen
     *
     * Note the tail: `2s8s` is **two** reserved bytes at 22 and the hash at
     * **24**. `docs/wallet-format.md` said 3 reserved at 22 and the hash at 25,
     * which does not fit in 32 bytes at all; the struct and the bytes on disk are
     * the authority and the doc has been corrected. Found by this port.
     *
     * Cleartext even after P3, so a recovery scan can rebuild a lost manifest
     * without leaking anything.
     */
    fun buildAssetHeader(assetType: Int, bitDepth: Int, col: Int, row: Int,
                         width: Int, height: Int, payload: ByteArray,
                         version: Int, flags: Int = 0,
                         presentation: Int = PRESENTATION_PORTRAIT): ByteArray {
        val out = ByteArray(ASSET_HEADER_LEN)
        System.arraycopy(ASSET_MAGIC, 0, out, 0, 4)
        out[4] = assetType.toByte()
        out[5] = bitDepth.toByte()
        out[6] = col.toByte()
        out[7] = row.toByte()
        putU16(out, 8, width)
        putU16(out, 10, height)
        putU32(out, 12, payload.size)
        putU32(out, 16, version)
        out[20] = flags.toByte()
        out[21] = presentation.toByte()
        // 22..23 reserved, already zero
        val sha = MessageDigest.getInstance("SHA-256").digest(payload)
        System.arraycopy(sha, 0, out, 24, 8)
        return out
    }

    /**
     * The asset `version` out of a built header (offset 16, u32 LE). The cipher seam
     * needs it for the CTR IV and the header already carries it, so nothing above has
     * to pass it a second time.
     */
    fun versionOfHeader(header: ByteArray): Int {
        require(header.size >= ASSET_HEADER_LEN) { "not an asset header" }
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (header[16 + i].toInt() and 0xff)
        return v
    }

    private fun putU16(buf: ByteArray, at: Int, v: Int) {
        buf[at] = (v and 0xff).toByte()
        buf[at + 1] = ((v ushr 8) and 0xff).toByte()
    }

    private fun putU32(buf: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) buf[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    // --- page geometry per zoom level --------------------------------------

    /**
     * Physical paper size in device pixels at [DEVICE_PPI]. One rule for every
     * sheet: `px = round(mm / 25.4 * ppi)`.
     *
     * `rint` and not `Math.round`: Python's `round()` is half-to-even and
     * `Math.round` is half-up, so the two disagree on an exact .5 -- which no
     * current sheet hits, but a future one could. Half-to-even is the reference.
     */
    fun paperPx(paper: String): Pair<Int, Int> {
        val mm = PAPER_MM[paper] ?: throw IllegalArgumentException("unknown paper '$paper'")
        return Pair(Math.rint(mm.first / MM_PER_INCH * DEVICE_PPI).toInt(),
                    Math.rint(mm.second / MM_PER_INCH * DEVICE_PPI).toInt())
    }

    /** (cols, rows) of logical tiles that cover one sheet at [DEVICE_PPI]. */
    fun oneToOneGrid(paper: String, panel: PanelProfile): Pair<Int, Int> {
        val (pw, ph) = paperPx(paper)
        return Pair(ceil(pw / panel.tileW.toDouble()).toInt(),
                    ceil(ph / panel.tileH.toDouble()).toInt())
    }

    /**
     * `auto` paper choice, port of `pick_paper()`.
     *
     * A4 (1.4143) and A5 (1.4189) have practically the same aspect ratio, so
     * aspect alone cannot tell them apart. DPI metadata can, when the source
     * carries any. Order: DPI if present and the derived mm size is within 40 mm
     * of a known sheet, else a4.
     */
    fun pickPaper(widthPx: Int, heightPx: Int, dpiX: Double?, dpiY: Double?): String {
        if (dpiX != null && dpiY != null && dpiX != 0.0 && dpiY != 0.0) {
            val mmW = widthPx / dpiX * MM_PER_INCH
            val mmH = heightPx / dpiY * MM_PER_INCH
            var best = "a4"
            var bestErr = Double.MAX_VALUE
            for ((name, mm) in PAPER_MM) {
                val err = Math.abs(mmW - mm.first) + Math.abs(mmH - mm.second)
                if (err < bestErr) {
                    best = name
                    bestErr = err
                }
            }
            if (bestErr < 40.0) return best
        }
        return "a4"
    }

    /**
     * Focal tile of a grid (brief section 15): the centre, biased top-left, so a
     * 4x4 grid gives (1, 1) -- the upper-left of the middle four, where a page's
     * text starts. A hint for the viewer, not a constraint.
     */
    fun defaultTile(cols: Int, rows: Int): Pair<Int, Int> =
        Pair((cols - 1) / 2, (rows - 1) / 2)

    // --- helpers -----------------------------------------------------------

    fun hex(bytes: ByteArray, count: Int = bytes.size): String {
        val sb = StringBuilder(count * 2)
        for (i in 0 until count) {
            val v = bytes[i].toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xf])
        }
        return sb.toString()
    }

    fun sha256Hex(payload: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(payload))

    private val HEX = "0123456789abcdef".toCharArray()
}
