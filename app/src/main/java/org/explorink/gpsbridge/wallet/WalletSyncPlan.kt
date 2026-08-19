package org.explorink.gpsbridge.wallet

import java.io.File

/**
 * What a wallet needs to have on the card, in the order it is worth sending.
 *
 * This file is the queue's input and it knows nothing about transports. It turns
 * a `manifest.json` plus a tree of files into a flat, ordered list of
 * [SyncAsset]s -- one per file, the manifest included -- and the order is brief
 * sections 26 and 39: **priority by user value, never FIFO.**
 *
 * Pure JVM, no Android, so the whole ordering and the delta computation run in
 * `./gradlew test`.
 */

/**
 * Priority classes, in send order. An item must become *usable* -- listed,
 * titled, code scannable, page readable at FIT -- long before it is complete,
 * which is what these five buy (brief section 26).
 */
enum class SyncClass(val label: String) {
    /** `manifest.json`. Without it the device does not know the item exists. */
    MANIFEST("manifest"),

    /** The FIT level: its tiles, its page image, its grey assets. One screen a page. */
    FIT("fit"),

    /**
     * Machine-readable codes that are **verified** -- decoded back out of the
     * stored bytes (`docs/wallet-format.md` section 10). Brief section 39's own
     * example: the QR matters far more to the rider than the bottom-right tile.
     */
    CODE("code"),

    /** The DETAIL level, 2x2 screens a page. */
    DETAIL("detail"),

    /** The 1:1 level, up to 4x4 screens a page. The bulk, and the least urgent. */
    ONE_TO_ONE("1:1"),

    /**
     * A code whose stored bytes did **not** decode back. Sent last and never
     * counted towards "usable": brief section 11 says an unverified code must not
     * be presented as trusted, so it may not be what makes a document look ready.
     */
    UNVERIFIED_CODE("unverified code"),
}

/**
 * One file to put on the card.
 *
 * [sha256] is the hash of the **whole file** -- 32-byte header included -- not the
 * manifest's `sha256`, which covers the payload alone. Both transports confirm the
 * whole file (`/api/hash` streams it off the card; BLE CRC32s it), so the whole
 * file is what the ledger has to compare.
 */
data class SyncAsset(
    /** Asset id, or [MANIFEST_KEY] for `manifest.json`. */
    val key: String,
    val itemId: String?,
    val pageId: String?,
    val cls: SyncClass,
    /** Path relative to `/trailink` on the card, which is what a BLE begin frame wants. */
    val relPath: String,
    val bytes: Int,
    val sha256: String,
    /** Tiebreak inside a class: item order, then page, then distance from the focal tile. */
    val order: Long,
) {
    val isManifest: Boolean get() = key == MANIFEST_KEY

    companion object {
        const val MANIFEST_KEY = "manifest"
    }
}

/**
 * The ordered list of everything a wallet tree should have on the card.
 *
 * Building it reads and hashes every file, which is the honest cost of "the
 * hashes decide" (below). Measured on the laptop JVM: a 1.05 MB one-page wallet
 * takes ~15 ms, so a 50-item wallet is well under a second. Run it off the UI
 * thread anyway -- [WalletSyncEngine] does.
 */
object WalletSyncPlan {

    /** Where the wallet tree lives on the card, relative to `/trailink`. */
    const val CARD_DIR = "wallet"

    /**
     * Every file of [wallet], ordered by [SyncClass] then by [SyncAsset.order].
     *
     * Ordering is **class first, item second**, on purpose: with three documents
     * queued, every one of them becomes usable before any one of them gets its 1:1
     * tiles. The other way round -- item first -- would leave document three
     * completely absent while document one spent two minutes on tiles nobody has
     * asked to read yet.
     */
    fun build(wallet: Wallet, treeDir: File): List<SyncAsset> {
        val out = ArrayList<SyncAsset>()

        // `manifest.json` or `manifest.enc`, whichever this wallet is written as. The
        // name is not cosmetic: the device prefers the encrypted one whenever one
        // exists, so sending the wrong name is a sync that lands and stays invisible
        // (`docs/wallet-plan.md` 7l).
        val manifestName = wallet.manifestFileName
        val manifest = File(treeDir, manifestName)
        if (manifest.isFile) {
            val bytes = manifest.readBytes()
            out.add(SyncAsset(
                key = SyncAsset.MANIFEST_KEY,
                itemId = null,
                pageId = null,
                cls = SyncClass.MANIFEST,
                relPath = "$CARD_DIR/$manifestName",
                bytes = bytes.size,
                sha256 = WalletFormat.sha256Hex(bytes),
                order = 0,
            ))
        }

        for ((itemIndex, item) in wallet.items.withIndex()) {
            for ((pageIndex, page) in item.pages.withIndex()) {
                for (levelName in WalletFormat.LEVELS) {
                    val level = page.levels[levelName] ?: continue
                    val cls = classOfLevel(levelName)
                    for (a in level.assets) {
                        // Brief section 39: "FIT -> central/detail tile -> other
                        // tiles". Distance from the level's own focal tile is that
                        // rule as arithmetic, so a 4x4 page sends the middle of the
                        // text before the corners.
                        val dist = Math.abs(a.col - level.defaultTileX) +
                            Math.abs(a.row - level.defaultTileY)
                        out.add(asset(item, page, itemIndex, pageIndex, cls, a.assetId,
                            treeDir, rank = dist.toLong() * 1000 + (a.row * level.cols + a.col)))
                    }
                    // The page image is what the viewer actually opens (design B),
                    // so it ranks ahead of the tiles of the same level.
                    level.pageImage?.let {
                        out.add(asset(item, page, itemIndex, pageIndex, cls, it.assetId,
                            treeDir, rank = -3))
                    }
                    // Grey: the plane set is the screen the viewer opens at, so it
                    // outranks the whole-page 2bpp copy.
                    level.greyPlanes?.let {
                        out.add(asset(item, page, itemIndex, pageIndex, cls, it.assetId,
                            treeDir, rank = -2))
                    }
                    level.greyPageImage?.let {
                        out.add(asset(item, page, itemIndex, pageIndex, cls, it.assetId,
                            treeDir, rank = -1))
                    }
                }
                for ((codeIndex, code) in page.codes.withIndex()) {
                    val cls = if (code.verified) SyncClass.CODE else SyncClass.UNVERIFIED_CODE
                    out.add(asset(item, page, itemIndex, pageIndex, cls, code.assetId,
                        treeDir, rank = codeIndex.toLong()))
                }
            }
        }

        out.sortWith(compareBy({ it.cls.ordinal }, { it.order }, { it.key }))
        return out
    }

    /** FIT / DETAIL / ONE_TO_ONE from the level name. One place. */
    fun classOfLevel(levelName: String): SyncClass = when (levelName) {
        "fit" -> SyncClass.FIT
        "detail" -> SyncClass.DETAIL
        "one_to_one" -> SyncClass.ONE_TO_ONE
        else -> throw IllegalArgumentException("unknown level '$levelName'")
    }

    /** `wallet/<2 hex>/<16 hex>.dat`, relative to `/trailink`. */
    fun relPathOf(assetId: String): String =
        "$CARD_DIR/${WalletFormat.shardOf(assetId)}/$assetId.dat"

    /**
     * Only `.dat` is sent, never the `.rle` sidecar.
     *
     * Plan 3.1 wanted the sidecar on the BLE wire with the device expanding it.
     * **Nothing on the device knows the sidecar format** -- `EWRL` and `.rle`
     * appear nowhere in the firmware (checked across `src/` and `lib/` on the
     * wallet firmware branch, 2026-08-19), so a sidecar pushed to the card would
     * just sit there. And an encrypted tree ships no sidecars at all, because
     * ciphertext does not compress (`docs/wallet-format.md` section 12). Raw bytes
     * it is; bulk belongs on Wi-Fi, where the whole page is seconds either way.
     */
    private fun asset(
        item: WalletItem,
        page: WalletPage,
        itemIndex: Int,
        pageIndex: Int,
        cls: SyncClass,
        assetId: String,
        treeDir: File,
        rank: Long,
    ): SyncAsset {
        val f = File(File(treeDir, WalletFormat.shardOf(assetId)), "$assetId.dat")
        val bytes = if (f.isFile) f.readBytes() else ByteArray(0)
        return SyncAsset(
            key = assetId,
            itemId = item.id,
            pageId = page.id,
            cls = cls,
            relPath = relPathOf(assetId),
            bytes = bytes.size,
            sha256 = WalletFormat.sha256Hex(bytes),
            // item, then page, then the class's own rank. 1e9 / 1e6 keeps the three
            // fields apart for any wallet a phone can hold.
            order = itemIndex.toLong() * 1_000_000_000L + pageIndex.toLong() * 1_000_000L +
                (rank + 100_000L),
        )
    }
}
