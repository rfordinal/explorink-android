package org.explorink.gpsbridge.wallet

import java.io.File
import java.nio.file.Files

/**
 * A real wallet tree in a temp directory, for the sync tests.
 *
 * Real on purpose: the plan reads and hashes the **files**, so a fixture of
 * hand-made manifest entries would not exercise the thing under test. These build
 * one through [WalletPipeline] from a synthetic page, so every asset on disk has
 * the right length, the right header and a hash the plan can compute.
 */
object SyncFixtures {

    /** A page with some ink in it, so the dither and the RLE do real work. */
    fun page(w: Int = 300, h: Int = 420): GrayImage {
        val px = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // A gradient with a few dark bands: four grey levels all appear, which
                // is what the grey tests need.
                val v = ((x * 255 / w) + (if ((y / 40) % 3 == 0) -90 else 40)).coerceIn(0, 255)
                px[y * w + x] = v.toByte()
            }
        }
        return GrayImage(w, h, px)
    }

    fun store(): WalletStore = WalletStore(Files.createTempDirectory("wallet-sync").toFile())

    /**
     * One item in [store], built by the real pipeline. [paper] "a5" keeps the 1:1
     * grid at 3x3 instead of 4x4, which keeps the fixtures quick.
     */
    fun addItem(
        store: WalletStore,
        title: String,
        codes: List<WalletPipeline.CodeRequest> = emptyList(),
        grey: Boolean = false,
        paper: String = "a5",
        pages: Int = 1,
    ): WalletItem {
        val pipeline = WalletPipeline(Panels.byName(store.panelName), grey = grey)
        val sources = (0 until pages).map {
            WalletPipeline.PageSource(page(), "$title-$it.png", codes = if (it == 0) codes else emptyList())
        }
        val item = pipeline.buildItem(
            itemId = WalletFormat.itemIdFor(title, sources.map { it.name }),
            title = title,
            createdAt = "2026-08-19T00:00:00Z",
            sortOrder = 0,
            sources = sources,
            sink = store.sink(),
            paper = paper,
        )
        store.addItem(item, sources.map { it.name })
        return item
    }

    fun queue(store: WalletStore): WalletSyncQueue {
        val state = store.loadState()
        return WalletSyncQueue(WalletSyncPlan.build(store.load(), store.treeDir),
            state.confirmed, state.errors, state.queued)
    }

    fun bytesOf(store: WalletStore): WalletSyncEngine.AssetBytes =
        object : WalletSyncEngine.AssetBytes {
            override fun read(a: SyncAsset): ByteArray? {
                val f = if (a.isManifest) File(store.treeDir, "manifest.json")
                else store.assetFile(a.key, "dat")
                return if (f.isFile) f.readBytes() else null
            }
        }

    /**
     * A transport that confirms everything immediately, and counts what it saw.
     *
     * "Immediately" is not a shortcut: the point of the engine tests is the ordering
     * and the state transitions, so the wire is reduced to a counter and the real
     * wires get their own tests against a double ([WalletWifiTransportTest]) and a
     * frame-level stub ([WalletBleTransportTest]).
     */
    class FakeTransport(
        override val name: String = "fake",
        override val bytesPerSecond: Int = 100_000,
        var ready: Boolean = true,
    ) : WalletTransport {
        override val label: String get() = name
        override val resumesAcrossSessions: Boolean get() = false

        val sentPaths = ArrayList<String>()
        var failNext: Pair<String, Boolean>? = null

        /** Paths this transport must fail on, with (reason, retryable). */
        val failPaths = HashMap<String, Pair<String, Boolean>>()

        /** When set, the job is held instead of answered, so a switch can happen mid-asset. */
        var holdNext = false
        var held: Pair<SendJob, SendCallback>? = null

        override fun isReady(): Boolean = ready

        override fun cancel() {
            held = null
        }

        override fun send(job: SendJob, cb: SendCallback) {
            if (holdNext) {
                holdNext = false
                held = Pair(job, cb)
                return
            }
            sentPaths.add(job.relPath)
            val forced = failPaths[job.relPath] ?: failNext?.also { failNext = null }
            if (forced != null) {
                cb.onFailed(forced.first, forced.second)
                return
            }
            cb.onProgress(job.bytes.size)
            cb.onConfirmed("fake ${job.bytes.size} B")
        }
    }
}
