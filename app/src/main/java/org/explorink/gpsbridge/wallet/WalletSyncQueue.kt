package org.explorink.gpsbridge.wallet

/**
 * The one queue both transports drive (brief section 30).
 *
 * BLE, Wi-Fi and any later transport must **not** have three independent sync
 * systems: there is one ordered list of pending assets and one ledger of what the
 * device confirmed. A rider who starts on BLE, walks away, and comes back on
 * Wi-Fi continues at the next pending asset -- that is the whole point of the
 * abstraction, and it is a property of this class, not of either transport.
 *
 * Everything here is pure: no I/O, no Android, no threads. [WalletSyncEngine]
 * owns the loop and the transport; this owns the answer to "what next, and where
 * does each item stand". That split is what makes every state transition
 * testable on the laptop.
 */
class WalletSyncQueue(
    /** Everything the card should hold, already in priority order. */
    val plan: List<SyncAsset>,
    confirmed: Map<String, ConfirmedAsset> = emptyMap(),
    errors: Map<String, String> = emptyMap(),
    /** Item ids the rider asked to sync. Intent, not progress. */
    queued: Set<String> = emptySet(),
) {

    private val ledger = LinkedHashMap(confirmed)
    private val failures = LinkedHashMap(errors)
    private val wanted = LinkedHashSet(queued)

    /** Bytes of the asset currently going over, for progress only. */
    private val sent = HashMap<String, Int>()

    /** The asset a transport is working on right now, or null. */
    var inFlight: String? = null
        private set

    val confirmed: Map<String, ConfirmedAsset> get() = ledger
    val errors: Map<String, String> get() = failures
    val queuedItems: Set<String> get() = wanted

    // --- what the rider asked for -----------------------------------------

    fun queue(itemId: String) {
        wanted.add(itemId)
    }

    fun queueAll() {
        for (a in plan) a.itemId?.let { wanted.add(it) }
    }

    fun unqueue(itemId: String) {
        wanted.remove(itemId)
    }

    /**
     * The manifest rides along with any queued item and is never queued on its own.
     *
     * It is also the reason a title change costs one small upload and nothing else
     * (brief section 40): the manifest is a file like any other, its hash changes
     * when the title does, and every image asset's hash does not.
     */
    private fun isWanted(a: SyncAsset): Boolean =
        if (a.isManifest) wanted.isNotEmpty() else a.itemId in wanted

    // --- the ledger --------------------------------------------------------

    fun isConfirmed(a: SyncAsset): Boolean = ledger[a.key]?.sha256 == a.sha256

    /**
     * Record that the **device said** it holds these bytes. The only way an asset
     * ever counts as on the card (brief section 28).
     *
     * [sha256] is the caller's own hash of the file it sent; a transport may only
     * call this after the device's own verdict agreed -- `OK <bytes> <crc32hex>` on
     * BLE, `/api/hash` on Wi-Fi. A 200 from `/upload` is not a verdict.
     */
    fun confirm(a: SyncAsset, transport: String, atMs: Long) {
        ledger[a.key] = ConfirmedAsset(a.sha256, a.bytes, transport, atMs)
        failures.remove(a.key)
        sent.remove(a.key)
        if (inFlight == a.key) inFlight = null
    }

    fun fail(key: String, reason: String) {
        failures[key] = reason
        sent.remove(key)
        if (inFlight == key) inFlight = null
    }

    fun clearErrors() {
        failures.clear()
    }

    // --- what to send next -------------------------------------------------

    /**
     * Pending assets, in priority order: wanted, not confirmed. Recomputed rather
     * than cached, because the plan is rebuilt whenever the wallet changes and a
     * cached queue would be a second source of truth about the same files.
     */
    fun pending(): List<SyncAsset> = plan.filter { isWanted(it) && !isConfirmed(it) }

    /**
     * The next asset to send: the first pending one that has not already failed in
     * this run. A failed asset is skipped rather than retried in a tight loop --
     * [WalletSyncEngine] decides when a retry is worth it.
     */
    fun next(): SyncAsset? = plan.firstOrNull {
        isWanted(it) && !isConfirmed(it) && it.key !in failures
    }

    fun takeNext(): SyncAsset? {
        val a = next() ?: return null
        inFlight = a.key
        return a
    }

    fun release() {
        inFlight = null
    }

    fun progress(key: String, bytes: Int) {
        sent[key] = bytes
    }

    fun sentBytes(key: String): Int = sent[key] ?: 0

    // --- states that never lie ---------------------------------------------

    /**
     * Where one item stands. Brief section 27's seven states, derived -- never
     * stored.
     *
     * Read the order of the branches as the priority of what the rider must not be
     * misled about:
     *
     *  1. every asset confirmed -> [SyncState.FULLY_SYNCED]. The only state that
     *     may be shown as "synced", and it needs a confirmation for every file.
     *  2. anything of this item failed -> [SyncState.ERROR]. A failure the rider
     *     cannot see is the thing that lies, so it outranks "usable". The count of
     *     what still works is carried beside it in [ItemStatus], not folded in.
     *  3. manifest + FIT + verified codes confirmed -> usable. With a transfer
     *     running that is [SyncState.FULL_QUALITY_SYNCING], without one
     *     [SyncState.USABLE_ON_DEVICE].
     *  4. a transfer running -> [SyncState.SYNCING]; queued -> [SyncState.QUEUED];
     *     otherwise [SyncState.LOCAL_ONLY].
     */
    fun statusOf(itemId: String): ItemStatus {
        val mine = plan.filter { it.itemId == itemId }
        val manifest = plan.firstOrNull { it.isManifest }
        val manifestOk = manifest == null || isConfirmed(manifest)

        var confirmedCount = 0
        var confirmedBytes = 0L
        var totalBytes = 0L
        var failedCount = 0
        var usableTotal = 0
        var usableConfirmed = 0
        for (a in mine) {
            totalBytes += a.bytes
            if (isConfirmed(a)) {
                confirmedCount++
                confirmedBytes += a.bytes
            } else if (a.key in failures) {
                failedCount++
            }
            if (a.cls == SyncClass.FIT || a.cls == SyncClass.CODE) {
                usableTotal++
                if (isConfirmed(a)) usableConfirmed++
            }
        }
        if (manifest != null && manifest.key in failures && itemId in wanted) failedCount++

        val allOk = manifestOk && mine.isNotEmpty() && confirmedCount == mine.size
        val usable = manifestOk && usableConfirmed == usableTotal && usableTotal > 0
        val running = inFlight != null &&
            (plan.firstOrNull { it.key == inFlight }?.itemId == itemId ||
                (inFlight == SyncAsset.MANIFEST_KEY && itemId in wanted))

        val state = when {
            allOk -> SyncState.FULLY_SYNCED
            failedCount > 0 -> SyncState.ERROR
            usable && running -> SyncState.FULL_QUALITY_SYNCING
            usable -> SyncState.USABLE_ON_DEVICE
            running -> SyncState.SYNCING
            itemId in wanted -> SyncState.QUEUED
            else -> SyncState.LOCAL_ONLY
        }
        return ItemStatus(
            itemId = itemId,
            state = state,
            assets = mine.size,
            confirmedAssets = confirmedCount,
            failedAssets = failedCount,
            totalBytes = totalBytes,
            confirmedBytes = confirmedBytes,
            usable = usable,
        )
    }

    /** Whole-wallet numbers for the sync screen (brief sections 38 and 56). */
    fun totals(): Totals {
        var pendingAssets = 0
        var pendingBytes = 0L
        var confirmedAssets = 0
        var confirmedBytes = 0L
        var totalBytes = 0L
        for (a in plan) {
            totalBytes += a.bytes
            when {
                isConfirmed(a) -> {
                    confirmedAssets++
                    confirmedBytes += a.bytes
                }
                isWanted(a) -> {
                    pendingAssets++
                    pendingBytes += a.bytes
                }
            }
        }
        return Totals(
            pendingAssets = pendingAssets,
            pendingBytes = pendingBytes,
            confirmedAssets = confirmedAssets,
            confirmedBytes = confirmedBytes,
            totalAssets = plan.size,
            totalBytes = totalBytes,
            failedAssets = failures.size,
        )
    }

    /** Pending bytes per class, so the screen can say what is left and why. */
    fun pendingByClass(): Map<SyncClass, Long> {
        val out = LinkedHashMap<SyncClass, Long>()
        for (a in pending()) out[a.cls] = (out[a.cls] ?: 0L) + a.bytes
        return out
    }

    data class ItemStatus(
        val itemId: String,
        val state: SyncState,
        val assets: Int,
        val confirmedAssets: Int,
        val failedAssets: Int,
        val totalBytes: Long,
        val confirmedBytes: Long,
        /** Manifest, FIT and every verified code confirmed. */
        val usable: Boolean,
    )

    data class Totals(
        val pendingAssets: Int,
        val pendingBytes: Long,
        val confirmedAssets: Int,
        val confirmedBytes: Long,
        val totalAssets: Int,
        val totalBytes: Long,
        val failedAssets: Int,
    )
}
