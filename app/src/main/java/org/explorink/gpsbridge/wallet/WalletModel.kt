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
    val rleLen: Int,
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
            rleLen = Json.asInt(o["rleLen"]),
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
    val nativeWidth: Int,
    val nativeHeight: Int,
    val rowBytes: Int,
    val rawLen: Int,
    val sha256: String,
    val rleLen: Int,
    val windowStepX: Int,
    val windowStepY: Int,
    val focalX: Int,
    val focalY: Int,
) {
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "assetId" to assetId,
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
            nativeWidth = Json.asInt(o["nativeWidth"]),
            nativeHeight = Json.asInt(o["nativeHeight"]),
            rowBytes = Json.asInt(o["rowBytes"]),
            rawLen = Json.asInt(o["rawLen"]),
            sha256 = Json.asString(o["sha256"]),
            rleLen = Json.asInt(o["rleLen"]),
            windowStepX = Json.asInt(o["windowStepX"]),
            windowStepY = Json.asInt(o["windowStepY"]),
            focalX = Json.asInt(o["focalX"]),
            focalY = Json.asInt(o["focalY"]),
        )
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
    val rleLen: Int,
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
            rleLen = Json.asInt(o["rleLen"]),
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
) {
    val pageCount: Int get() = pages.size
    val codeCount: Int get() = pages.sumOf { it.codes.size }
    val assetCount: Int get() = pages.sumOf { p ->
        p.levels.values.sumOf { it.assets.size + (if (it.pageImage != null) 1 else 0) } + p.codes.size
    }
    val rawBytes: Long get() = pages.sumOf { p ->
        p.levels.values.sumOf { l ->
            l.assets.sumOf { it.rawLen.toLong() } + (l.pageImage?.rawLen?.toLong() ?: 0L)
        }
    }

    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id,
        "title" to title,
        "createdAt" to createdAt,
        "sortOrder" to sortOrder,
        "pages" to pages.map { it.toJson() },
    )

    companion object {
        fun fromJson(o: Map<String, Any?>) = WalletItem(
            id = Json.asString(o["id"]),
            title = Json.asString(o["title"]),
            createdAt = Json.asString(o["createdAt"]),
            sortOrder = Json.asInt(o["sortOrder"]),
            pages = Json.asList(o["pages"]).map { WalletPage.fromJson(Json.asMap(it)) },
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
) {
    val panel: PanelProfile get() = Panels.byName(panelName)

    fun item(id: String): WalletItem? = items.firstOrNull { it.id == id }

    /** Every asset id in the wallet, in manifest order. */
    fun assetIds(): List<String> {
        val out = ArrayList<String>()
        for (item in items) for (page in item.pages) {
            for (name in WalletFormat.LEVELS) {
                val level = page.levels[name] ?: continue
                for (a in level.assets) out.add(a.assetId)
                level.pageImage?.let { out.add(it.assetId) }
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
            "items" to items.map { it.toJson() },
        )
        return Json.write(tree) + "\n"
    }

    companion object {
        fun empty(panelName: String = Panels.DEFAULT_NAME) = Wallet(
            formatVersion = WalletFormat.MANIFEST_FORMAT_VERSION,
            walletVersion = 0,
            panelName = panelName,
            items = emptyList(),
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
            )
        }
    }
}

/**
 * Where an item stands with respect to the device.
 *
 * Brief section 27 owns this list. The brief itself is not in this repo (it is
 * the Slovak product spec `ExplorInk - Personal Wallet.md`), so these five are
 * taken from what `docs/wallet-plan.md` states about it (sections 3.5 and 6):
 * per-asset ACK before an item may be called on-device, resume by asset, and
 * "never show Synced for phone-only data". **Assumed, not verified against the
 * brief's own wording** -- confirm when P6 wires the sync queue.
 */
enum class SyncState {
    /** Rendered on the phone, nothing sent. The state every import starts in. */
    PHONE_ONLY,

    /** In the sync queue, waiting for a transport. */
    QUEUED,

    /** Assets are going over now. */
    SYNCING,

    /** Every asset acknowledged by the device. Only this one may say "on device". */
    ON_DEVICE,

    /** A transfer failed and was not retried yet. */
    FAILED;

    fun label(): String = when (this) {
        PHONE_ONLY -> "phone only"
        QUEUED -> "queued"
        SYNCING -> "syncing"
        ON_DEVICE -> "on device"
        FAILED -> "failed"
    }
}

/**
 * App-private state that the wallet format does not carry: sync state per item
 * and the source names an item was built from. Kept in `state.json` beside the
 * manifest so `manifest.json` stays byte-identical to a generator run.
 */
data class WalletLocalState(
    val syncState: Map<String, SyncState> = emptyMap(),
    val sourceNames: Map<String, List<String>> = emptyMap(),
) {
    fun stateOf(itemId: String): SyncState = syncState[itemId] ?: SyncState.PHONE_ONLY

    fun toJson(): String {
        val states = LinkedHashMap<String, Any?>()
        for ((k, v) in syncState) states[k] = v.name
        val sources = LinkedHashMap<String, Any?>()
        for ((k, v) in sourceNames) sources[k] = v
        return Json.write(linkedMapOf<String, Any?>(
            "version" to 1,
            "syncState" to states,
            "sourceNames" to sources,
        )) + "\n"
    }

    companion object {
        fun fromJson(text: String): WalletLocalState {
            val o = Json.asMap(Json.parse(text))
            val states = LinkedHashMap<String, SyncState>()
            for ((k, v) in Json.asMap(o["syncState"] ?: emptyMap<String, Any?>())) {
                states[k] = try {
                    SyncState.valueOf(Json.asString(v))
                } catch (e: IllegalArgumentException) {
                    SyncState.PHONE_ONLY
                }
            }
            val sources = LinkedHashMap<String, List<String>>()
            for ((k, v) in Json.asMap(o["sourceNames"] ?: emptyMap<String, Any?>())) {
                sources[k] = Json.asList(v).map { Json.asString(it) }
            }
            return WalletLocalState(states, sources)
        }
    }
}
