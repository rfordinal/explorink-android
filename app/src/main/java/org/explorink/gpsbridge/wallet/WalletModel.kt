package org.explorink.gpsbridge.wallet

/**
 * The wallet data model and its `manifest.json` form.
 *
 * The app's store IS a wallet tree in the documented format
 * (`docs/wallet-format.md` section 8) -- same manifest, same shards, same asset
 * ids. One format instead of two: the sync phase (P6) pushes what is already on
 * disk, and the parity test can compare this writer's manifest against
 * `tools/walletgen.py`'s as text.
 *
 * Anything the format does not carry -- sync state, the source Uris an item was
 * imported from -- lives in a separate app-private `state.json`
 * ([WalletLocalState]), so `manifest.json` stays exactly what the generator
 * would have written.
 */

/** One asset entry in a level: one prepared screen. */
data class WalletAsset(
    val assetId: String,
    val type: Int,
    val col: Int,
    val row: Int,
    val rawLen: Int,
    val sha256: String,
    /** Sidecar size, or **null on an encrypted tree**, which writes no sidecar. */
    val rleLen: Int?,
) {
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "assetId" to assetId,
        "type" to type,
        "col" to col,
        "row" to row,
        "rawLen" to rawLen,
        "sha256" to sha256,
        "rleLen" to rleLen,
    )

    companion object {
        fun fromJson(o: Map<String, Any?>) = WalletAsset(
            assetId = Json.asString(o["assetId"]),
            type = Json.asInt(o["type"]),
            col = Json.asInt(o["col"]),
            row = Json.asInt(o["row"]),
            rawLen = Json.asInt(o["rawLen"]),
            sha256 = Json.asString(o["sha256"]),
            rleLen = Json.asIntOrNull(o["rleLen"]),
        )
    }
}

/**
 * Design B: one whole-page asset per level, so the device can blit an arbitrary
 * window instead of stepping whole screens. `windowStepX/Y` is the pan increment
 * and `focalX/Y` the window the level opens at (`docs/wallet-format.md` section 9).
 */
data class WalletPageImage(
    val assetId: String,
    /**
     * 1 for the dithered page image, and stated rather than implied: the firmware reads
     * every image-like entry through one struct, and `greyPageImage` beside it is the
     * same shape at 2 (`docs/wallet-format.md` section 13).
     */
    val bitDepth: Int = WalletFormat.BIT_DEPTH_1BPP,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val rowBytes: Int,
    val rawLen: Int,
    val sha256: String,
    /** Null on an encrypted tree: no sidecar is written. */
    val rleLen: Int?,
    val windowStepX: Int,
    val windowStepY: Int,
    val focalX: Int,
    val focalY: Int,
) {
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "assetId" to assetId,
        "bitDepth" to bitDepth,
        "nativeWidth" to nativeWidth,
        "nativeHeight" to nativeHeight,
        "rowBytes" to rowBytes,
        "rawLen" to rawLen,
        "sha256" to sha256,
        "rleLen" to rleLen,
        "windowStepX" to windowStepX,
        "windowStepY" to windowStepY,
        "focalX" to focalX,
        "focalY" to focalY,
    )

    companion object {
        fun fromJson(o: Map<String, Any?>) = WalletPageImage(
            assetId = Json.asString(o["assetId"]),
            bitDepth = Json.asInt(o["bitDepth"]),
            nativeWidth = Json.asInt(o["nativeWidth"]),
            nativeHeight = Json.asInt(o["nativeHeight"]),
            rowBytes = Json.asInt(o["rowBytes"]),
            rawLen = Json.asInt(o["rawLen"]),
            sha256 = Json.asString(o["sha256"]),
            rleLen = Json.asIntOrNull(o["rleLen"]),
            windowStepX = Json.asInt(o["windowStepX"]),
            windowStepY = Json.asInt(o["windowStepY"]),
            focalX = Json.asInt(o["focalX"]),
            focalY = Json.asInt(o["focalY"]),
        )
    }
}

/**
 * The 2bpp whole page (`assetType 6`) and the baked plane set (`assetType 7`) of
 * one level, emitted only for a document the rider marked grey
 * (`docs/wallet-format.md` section 13).
 *
 * Held as the raw manifest map rather than two more data classes: neither entry
 * has a field the phone reads back, both are written once and pushed, and the
 * firmware parses them through one struct whose key names are the contract. A
 * typed mirror would be a second place for those names to drift, and drifting
 * key names are exactly the bug that made grey silently decline on hardware
 * (`docs/wallet-plan.md` 7j).
 */
data class WalletGreyEntry(val fields: LinkedHashMap<String, Any?>) {
    val assetId: String get() = Json.asString(fields["assetId"])
    val rawLen: Int get() = Json.asInt(fields["rawLen"])
    val sha256: String get() = Json.asString(fields["sha256"])
    val rleLen: Int? get() = Json.asIntOrNull(fields["rleLen"])

    fun toJson(): LinkedHashMap<String, Any?> = fields

    companion object {
        fun fromJson(o: Map<String, Any?>) = WalletGreyEntry(LinkedHashMap(o))
    }
}

/** One zoom level of one page: its tile grid, its tiles, its page image. */
data class WalletLevel(
    val cols: Int,
    val rows: Int,
    val defaultTileX: Int,
    val defaultTileY: Int,
    val assets: List<WalletAsset>,
    val pageImage: WalletPageImage?,
    /** `assetType 6`, the whole page at 2bpp. Only on a grey document. */
    val greyPageImage: WalletGreyEntry? = null,
    /** `assetType 7`, one screen with all three planes baked. Only on a grey document. */
    val greyPlanes: WalletGreyEntry? = null,
) {
    fun toJson(): LinkedHashMap<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            "cols" to cols,
            "rows" to rows,
            "defaultTileX" to defaultTileX,
            "defaultTileY" to defaultTileY,
            "assets" to assets.map { it.toJson() },
        )
        if (pageImage != null) out["pageImage"] = pageImage.toJson()
        // Same order the generator writes them in: pageImage, greyPageImage,
        // greyPlanes (`tools/walletgen.py`, write_grey_assets).
        if (greyPageImage != null) out["greyPageImage"] = greyPageImage.toJson()
        if (greyPlanes != null) out["greyPlanes"] = greyPlanes.toJson()
        return out
    }

    companion object {
        fun fromJson(o: Map<String, Any?>) = WalletLevel(
            cols = Json.asInt(o["cols"]),
            rows = Json.asInt(o["rows"]),
            defaultTileX = Json.asInt(o["defaultTileX"]),
            defaultTileY = Json.asInt(o["defaultTileY"]),
            assets = Json.asList(o["assets"]).map { WalletAsset.fromJson(Json.asMap(it)) },
            pageImage = o["pageImage"]?.let { WalletPageImage.fromJson(Json.asMap(it)) },
            greyPageImage = o["greyPageImage"]?.let { WalletGreyEntry.fromJson(Json.asMap(it)) },
            greyPlanes = o["greyPlanes"]?.let { WalletGreyEntry.fromJson(Json.asMap(it)) },
        )
    }
}

/**
 * A machine-readable code on a page. Written by phase P5 on this side; the model
 * exists now so the manifest needs no format bump later, and so an imported
 * `manifest.json` that already has codes (from `walletgen.py --code`) survives a
 * round trip through the app.
 *
 * `verified` means one thing only: the code was decoded back out of the STORED
 * asset bytes and matched, payload and symbology (`docs/wallet-format.md`
 * section 10). Nothing else may set it.
 */
data class MachineReadableCode(
    val id: String,
    val symbology: String,
    val payload: String,
    val verified: Boolean,
    val assetId: String,
    val orientation: String,
    val presentation: Int,
    val moduleSize: Int,
    val quietZone: Int,
    val codeWidthPx: Int,
    val codeHeightPx: Int,
    val sha256: String,
    /** Null on an encrypted tree: no sidecar is written. */
    val rleLen: Int?,
) {
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id,
        "symbology" to symbology,
        "payload" to payload,
        "verified" to verified,
        "assetId" to assetId,
        "orientation" to orientation,
        "presentation" to presentation,
        "moduleSize" to moduleSize,
        "quietZone" to quietZone,
        "codeWidthPx" to codeWidthPx,
        "codeHeightPx" to codeHeightPx,
        "sha256" to sha256,
        "rleLen" to rleLen,
    )

    companion object {
        fun fromJson(o: Map<String, Any?>) = MachineReadableCode(
            id = Json.asString(o["id"]),
            symbology = Json.asString(o["symbology"]),
            payload = Json.asString(o["payload"]),
            verified = o["verified"] == true,
            assetId = Json.asString(o["assetId"]),
            orientation = Json.asString(o["orientation"]),
            presentation = Json.asInt(o["presentation"]),
            moduleSize = Json.asInt(o["moduleSize"]),
            quietZone = Json.asInt(o["quietZone"]),
            codeWidthPx = Json.asInt(o["codeWidthPx"]),
            codeHeightPx = Json.asInt(o["codeHeightPx"]),
            sha256 = Json.asString(o["sha256"]),
            rleLen = Json.asIntOrNull(o["rleLen"]),
        )
    }
}

/** One page of one item: three zoom levels plus its codes. */
data class WalletPage(
    val id: String,
    val paper: String,
    val levels: Map<String, WalletLevel>,
    val codes: List<MachineReadableCode>,
) {
    fun toJson(): LinkedHashMap<String, Any?> {
        val lv = LinkedHashMap<String, Any?>()
        // Fixed order, so the manifest is byte-comparable against the generator's.
        for (name in WalletFormat.LEVELS) levels[name]?.let { lv[name] = it.toJson() }
        return linkedMapOf(
            "id" to id,
            "paper" to paper,
            "levels" to lv,
            "codes" to codes.map { it.toJson() },
        )
    }

    companion object {
        fun fromJson(o: Map<String, Any?>): WalletPage {
            val levels = LinkedHashMap<String, WalletLevel>()
            for ((k, v) in Json.asMap(o["levels"])) {
                levels[k] = WalletLevel.fromJson(Json.asMap(v))
            }
            return WalletPage(
                id = Json.asString(o["id"]),
                paper = Json.asString(o["paper"]),
                levels = levels,
                codes = Json.asList(o["codes"] ?: emptyList<Any?>())
                    .map { MachineReadableCode.fromJson(Json.asMap(it)) },
            )
        }
    }
}

/** One document: a title, an order, and its pages. */
data class WalletItem(
    val id: String,
    val title: String,
    val createdAt: String,
    val sortOrder: Int,
    val pages: List<WalletPage>,
    /**
     * Four-level grey for THIS document (`docs/wallet-plan.md` 7k, decided on the
     * panel 2026-08-18). Not a card-wide mode and not a build flag: a scan or a
     * photo earns the tone, a page of text the rider pans around does not, because
     * a grey frame costs 2,604 ms and cannot pan at all.
     *
     * A grey document carries `greyPageImage` and `greyPlanes` per level beside the
     * 1bpp assets; nothing is removed, so the device can still draw it 1bpp.
     */
    val grey: Boolean = false,
) {
    val pageCount: Int get() = pages.size
    val codeCount: Int get() = pages.sumOf { it.codes.size }
    val assetCount: Int get() = pages.sumOf { p ->
        p.levels.values.sumOf {
            it.assets.size + (if (it.pageImage != null) 1 else 0) +
                (if (it.greyPageImage != null) 1 else 0) + (if (it.greyPlanes != null) 1 else 0)
        } + p.codes.size
    }
    val rawBytes: Long get() = pages.sumOf { p ->
        p.levels.values.sumOf { l ->
            l.assets.sumOf { it.rawLen.toLong() } + (l.pageImage?.rawLen?.toLong() ?: 0L) +
                (l.greyPageImage?.rawLen?.toLong() ?: 0L) + (l.greyPlanes?.rawLen?.toLong() ?: 0L)
        }
    }

    /**
     * `grey` is written **always**, true or false, because that is what
     * `tools/walletgen.py` writes and the manifest is compared as text.
     *
     * A reader must still treat an **absent** flag as "no grey": a card written before
     * grey existed must not start rendering grey frames because a later firmware
     * learned how (`docs/wallet-format.md` section 13). Writing it every time and
     * reading a missing one as false are both true at once, and the parser below does
     * the second.
     */
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id,
        "title" to title,
        "createdAt" to createdAt,
        "sortOrder" to sortOrder,
        "grey" to grey,
        "pages" to pages.map { it.toJson() },
    )

    companion object {
        fun fromJson(o: Map<String, Any?>) = WalletItem(
            id = Json.asString(o["id"]),
            title = Json.asString(o["title"]),
            createdAt = Json.asString(o["createdAt"]),
            sortOrder = Json.asInt(o["sortOrder"]),
            pages = Json.asList(o["pages"]).map { WalletPage.fromJson(Json.asMap(it)) },
            grey = o["grey"] == true,
        )
    }
}

/**
 * A whole wallet: one panel, a monotone version, and the items.
 *
 * `walletVersion` increments on **every** write to the tree (brief section 54).
 * It is the sync unit: the device compares it, not a timestamp.
 */
data class Wallet(
    val formatVersion: Int,
    val walletVersion: Int,
    val panelName: String,
    val items: List<WalletItem>,
    /**
     * The `crypto` block: `{scheme, assets, manifest}` on an encrypted tree, **null**
     * on a cleartext one. The key is always written -- `"crypto": null` -- because
     * that is what `tools/walletgen.py` writes and the manifest is compared as text.
     *
     * Held as the raw map rather than a typed mirror so a manifest written by a newer
     * scheme survives a round trip through this app instead of being silently
     * rewritten as ours.
     */
    val crypto: Map<String, Any?>? = null,
) {
    val panel: PanelProfile get() = Panels.byName(panelName)

    /**
     * Which manifest file this wallet is written as. **Not a cosmetic difference**:
     * the device prefers `manifest.enc` whenever one exists, so a cleartext wallet
     * synced onto a card holding an encrypted one is invisible
     * (`docs/wallet-plan.md` 7l, [WalletManifestConflict]).
     */
    val manifestKind: ManifestKind
        get() = if (crypto != null) ManifestKind.ENCRYPTED else ManifestKind.CLEARTEXT

    val manifestFileName: String
        get() = if (crypto != null) WalletFormat.MANIFEST_ENC_NAME
        else WalletFormat.MANIFEST_CLEAR_NAME

    fun item(id: String): WalletItem? = items.firstOrNull { it.id == id }

    /** Every asset id in the wallet, in manifest order. */
    fun assetIds(): List<String> {
        val out = ArrayList<String>()
        for (item in items) for (page in item.pages) {
            for (name in WalletFormat.LEVELS) {
                val level = page.levels[name] ?: continue
                for (a in level.assets) out.add(a.assetId)
                level.pageImage?.let { out.add(it.assetId) }
                level.greyPageImage?.let { out.add(it.assetId) }
                level.greyPlanes?.let { out.add(it.assetId) }
            }
            for (c in page.codes) out.add(c.assetId)
        }
        return out
    }

    /** The `manifest.json` text, exactly as `tools/walletgen.py` writes it. */
    fun toManifestJson(): String {
        val p = panel
        val tree = linkedMapOf<String, Any?>(
            "formatVersion" to formatVersion,
            "walletVersion" to walletVersion,
            "panel" to linkedMapOf<String, Any?>(
                "name" to p.name,
                "width" to p.width,
                "height" to p.height,
                "rowBytes" to p.rowBytes,
                "assetBytes" to p.assetBytes,
            ),
            "crypto" to crypto,
            "items" to items.map { it.toJson() },
        )
        return Json.write(tree) + "\n"
    }

    companion object {
        fun empty(panelName: String = Panels.DEFAULT_NAME, encrypted: Boolean = false) = Wallet(
            formatVersion = WalletFormat.MANIFEST_FORMAT_VERSION,
            walletVersion = 0,
            panelName = panelName,
            items = emptyList(),
            crypto = if (encrypted) WalletCrypto.descriptor() else null,
        )

        fun fromManifestJson(text: String): Wallet {
            val o = Json.asMap(Json.parse(text))
            val fv = Json.asInt(o["formatVersion"])
            if (fv != WalletFormat.MANIFEST_FORMAT_VERSION) {
                throw IllegalArgumentException(
                    "manifest formatVersion $fv, expected ${WalletFormat.MANIFEST_FORMAT_VERSION}")
            }
            return Wallet(
                formatVersion = fv,
                walletVersion = Json.asInt(o["walletVersion"]),
                panelName = Json.asString(Json.asMap(o["panel"])["name"]),
                items = Json.asList(o["items"]).map { WalletItem.fromJson(Json.asMap(it)) },
                crypto = o["crypto"]?.let { Json.asMap(it) },
            )
        }
    }
}

/**
 * Where an item stands with respect to the device. Brief section 27, verbatim
 * list.
 *
 * The rule the brief cares about most is negative: **the app must not lie.** So
 * none of these is stored as a fact about an item. They are DERIVED from the
 * confirmation ledger ([WalletLocalState.confirmed]) by
 * [WalletSyncStatus.of], and a confirmation only exists after the device said
 * what it holds -- `OK <bytes> <crc32hex>` on BLE, `/api/hash` on Wi-Fi. There
 * is no field anywhere that can say [FULLY_SYNCED] without one, which is what
 * makes the "unreachable state" tests possible at all.
 *
 * The five-value enum P4 shipped (PHONE_ONLY / QUEUED / SYNCING / ON_DEVICE /
 * FAILED) was a guess at this list, written before the brief was readable here.
 * Old `state.json` files are migrated on read.
 */
enum class SyncState {
    /** Rendered on the phone, nothing confirmed on the card, not queued. */
    LOCAL_ONLY,

    /** In the queue, nothing of it confirmed yet, no transfer running. */
    QUEUED,

    /** A transfer is running and the item is not usable on the device yet. */
    SYNCING,

    /**
     * Manifest, every FIT asset and every **verified** code confirmed. The rider
     * can find the document, read it at FIT and show a code -- brief section 27's
     * "Ready on device". Detail and 1:1 are still missing.
     */
    USABLE_ON_DEVICE,

    /** Usable, and the rest of its assets are going over now. */
    FULL_QUALITY_SYNCING,

    /** Every asset of the item confirmed. The only state that may say "synced". */
    FULLY_SYNCED,

    /** An asset of this item failed and has not been retried. */
    ERROR;

    fun label(): String = when (this) {
        LOCAL_ONLY -> "local only"
        QUEUED -> "queued"
        SYNCING -> "syncing"
        USABLE_ON_DEVICE -> "usable on device"
        FULL_QUALITY_SYNCING -> "full quality syncing"
        FULLY_SYNCED -> "fully synced"
        ERROR -> "error"
    }

    /** True only for the one state that means every byte is on the card. */
    val isFullySynced: Boolean get() = this == FULLY_SYNCED

    /** True when the rider can actually use the document off the panel. */
    val isUsable: Boolean
        get() = this == USABLE_ON_DEVICE || this == FULL_QUALITY_SYNCING || this == FULLY_SYNCED
}

/**
 * One asset the **device confirmed it holds**, and the only reason any item is
 * ever shown as synced.
 *
 * Keyed by asset id, but the [sha256] is what decides: a re-rendered page keeps
 * its asset id (the id recipe has no content in it) and gets new bytes, so
 * comparing the manifest's hash against this one is what makes delta sync fall
 * out instead of being computed (`docs/android-wallet.md` section 14).
 */
data class ConfirmedAsset(
    val sha256: String,
    val bytes: Int,
    /** "ble" or "wifi" -- which transport's confirmation this was. */
    val transport: String,
    val atMs: Long,
) {
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "sha256" to sha256,
        "bytes" to bytes,
        "transport" to transport,
        "atMs" to atMs,
    )

    companion object {
        fun fromJson(o: Map<String, Any?>) = ConfirmedAsset(
            sha256 = Json.asString(o["sha256"]),
            bytes = Json.asInt(o["bytes"]),
            transport = Json.asString(o["transport"]),
            atMs = Json.asLong(o["atMs"]),
        )
    }
}

/**
 * App-private state the wallet format does not carry. Kept in `state.json`
 * beside the manifest so `manifest.json` stays byte-identical to a generator run.
 *
 * Three maps, and none of them is an item state:
 *
 *  - [confirmed] -- the ledger. What the device said it holds. Survives a kill,
 *    which is what makes resume work across app restarts (brief section 29).
 *  - [queued] -- item ids the rider asked to sync. Intent, not progress.
 *  - [errors] -- asset id to last failure reason. Cleared when that asset lands.
 *  - [sourceNames] -- the picked images' display names, for the item id recipe.
 */
data class WalletLocalState(
    val confirmed: Map<String, ConfirmedAsset> = emptyMap(),
    val queued: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val sourceNames: Map<String, List<String>> = emptyMap(),
) {
    /**
     * The one question the ledger answers. Both halves matter: the id says which
     * asset, the hash says which bytes.
     */
    fun isConfirmed(assetId: String, sha256: String): Boolean =
        confirmed[assetId]?.sha256 == sha256

    fun toJson(): String {
        val conf = LinkedHashMap<String, Any?>()
        for ((k, v) in confirmed) conf[k] = v.toJson()
        val sources = LinkedHashMap<String, Any?>()
        for ((k, v) in sourceNames) sources[k] = v
        val errs = LinkedHashMap<String, Any?>()
        for ((k, v) in errors) errs[k] = v
        return Json.write(linkedMapOf<String, Any?>(
            "version" to 2,
            "confirmed" to conf,
            "queued" to queued.toList(),
            "errors" to errs,
            "sourceNames" to sources,
        )) + "\n"
    }

    companion object {
        /**
         * A version-1 file (P4/P5) carried a `syncState` map instead of a ledger.
         * There is no honest migration for it -- "ON_DEVICE" recorded no hash and
         * no bytes, so it cannot become a confirmation -- so the states are
         * **dropped** and every item falls back to LOCAL_ONLY. The wallet then
         * re-syncs once. That is the right way round: forgetting a confirmation
         * costs a transfer, inventing one shows "synced" for bytes nobody checked.
         */
        fun fromJson(text: String): WalletLocalState {
            val o = Json.asMap(Json.parse(text))
            val conf = LinkedHashMap<String, ConfirmedAsset>()
            for ((k, v) in Json.asMap(o["confirmed"] ?: emptyMap<String, Any?>())) {
                conf[k] = ConfirmedAsset.fromJson(Json.asMap(v))
            }
            val sources = LinkedHashMap<String, List<String>>()
            for ((k, v) in Json.asMap(o["sourceNames"] ?: emptyMap<String, Any?>())) {
                sources[k] = Json.asList(v).map { Json.asString(it) }
            }
            val errs = LinkedHashMap<String, String>()
            for ((k, v) in Json.asMap(o["errors"] ?: emptyMap<String, Any?>())) {
                errs[k] = Json.asString(v)
            }
            return WalletLocalState(
                confirmed = conf,
                queued = Json.asList(o["queued"] ?: emptyList<Any?>())
                    .map { Json.asString(it) }.toSet(),
                errors = errs,
                sourceNames = sources,
            )
        }
    }
}
