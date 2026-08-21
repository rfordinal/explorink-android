package org.explorink.gpsbridge.wallet

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The Android glue around [WalletSyncEngine]: builds the plan off the UI thread,
 * owns the worker executor, persists the ledger, and posts everything back to the
 * main looper.
 *
 * **This is the only file in the sync path that knows it is on Android**, and that
 * is deliberate. [WalletSyncPlan], [WalletSyncQueue], [WalletSyncEngine],
 * [WalletTransport] and both transport implementations are plain Kotlin with no
 * Android import, so a second client (iOS is a target -- see
 * `docs/android-wallet.md`, "iOS notes") rewrites this file and the wire, and lifts
 * the ordering, the state machine, the delta computation and the stage-verify-swap
 * order unchanged.
 */
class WalletSyncController(
    private val store: WalletStore,
    private val listener: Listener,
) {

    interface Listener {
        fun onPlanReady(queue: WalletSyncQueue)
        fun onChanged()
        fun onLine(line: String)
        fun onFinished(confirmed: Int, failed: Int, remaining: Int, reason: String)

        /** The card holds the other kind of manifest. A question, not a failure. */
        fun onManifestConflict(conflict: ManifestConflict) {}
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * One thread, not a pool. Both transports are strictly one job at a time (the
     * device holds one BLE connection and one HTTP upload), so a pool would only buy
     * a way to violate that.
     */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "wallet-sync") }

    private val scheduler = object : WalletBleTransport.Scheduler {
        override fun postDelayed(delayMs: Long, action: () -> Unit): WalletBleTransport.Scheduler.Cancellable {
            val r = Runnable { action() }
            main.postDelayed(r, delayMs)
            return object : WalletBleTransport.Scheduler.Cancellable {
                override fun cancel() = main.removeCallbacks(r)
            }
        }
    }

    // Explicit type: the engine's own listener calls back into `engine` to publish the
    // session, and without it Kotlin reports "Type checking has run into a recursive
    // problem" rather than resolving the cycle.
    val engine: WalletSyncEngine = WalletSyncEngine(
        bytes = object : WalletSyncEngine.AssetBytes {
            override fun read(a: SyncAsset): ByteArray? {
                // The manifest's own file name follows the tree's kind -- manifest.json
                // or manifest.enc. The plan already put the right name in `relPath`; use
                // it, so the two cannot disagree.
                val f = if (a.isManifest) java.io.File(store.treeDir, store.manifestFile.name)
                else store.assetFile(a.key, "dat")
                return if (f.isFile) f.readBytes() else null
            }
        },
        listener = object : WalletSyncEngine.Listener {

            /**
             * Tell [WalletSyncSession] where the sync stands, from **the controller**
             * rather than from a screen.
             *
             * It used to be published by `WalletSyncActivity`, so a transfer driven from
             * the debug activity was invisible: the wallet list said "no sync running"
             * while bytes were moving (seen 2026-08-19). Every path that syncs builds a
             * controller, so this is the one place that cannot be forgotten.
             */
            private fun publish() {
                WalletSyncSession.publish(engine.queue, engine.transport?.label, engine.running,
                                          engine.rate)
            }

            override fun onSyncStarted(transport: String, pendingAssets: Int, pendingBytes: Long) {
                listener.onLine("start transport=$transport pending=$pendingAssets " +
                    "bytes=$pendingBytes")
                publish()
            }

            override fun onManifestConflict(conflict: ManifestConflict) {
                listener.onLine("manifest conflict local=${conflict.local.label} " +
                    "card=${conflict.card.label} invisible=${conflict.invisible}")
                listener.onManifestConflict(conflict)
            }

            override fun onAssetProgress(a: SyncAsset, sent: Int, total: Int) {
                listener.onChanged()
                publish()
            }

            override fun onAssetConfirmed(a: SyncAsset, transport: String, detail: String) {
                // "confirmed" is the device's word, not ours: the line records what the
                // device said, which is what makes an adb log readable as evidence.
                listener.onLine("confirmed ${a.cls.label} ${a.key} ${a.bytes}B " +
                    "via $transport: $detail")
                listener.onChanged()
                publish()
            }

            override fun onAssetFailed(a: SyncAsset, reason: String) {
                listener.onLine("failed ${a.cls.label} ${a.key}: $reason")
                listener.onChanged()
                publish()
            }

            override fun onSyncFinished(confirmed: Int, failed: Int, remaining: Int, reason: String) {
                listener.onLine("finish confirmed=$confirmed failed=$failed " +
                    "remaining=$remaining reason=$reason")
                listener.onFinished(confirmed, failed, remaining, reason)
                publish()
            }

            override fun onQueueChanged() {
                listener.onChanged()
                publish()
            }
        },
        persist = { q -> store.saveSyncState(q) },
    )

    /** Reads and hashes the whole tree, then hands the queue back on the main thread. */
    fun rebuildPlan() {
        worker.execute {
            val wallet = store.load()
            val state = store.loadState()
            val plan = WalletSyncPlan.build(wallet, store.treeDir)
            val queue = WalletSyncQueue(plan, state.confirmed, state.errors, state.queued,
                state.fullQuality)
            val kind = wallet.manifestKind
            main.post {
                engine.localManifest = kind
                engine.setQueue(queue)
                listener.onPlanReady(queue)
            }
        }
    }

    /**
     * Ask the card which manifest it holds, off the UI thread, and hand the answer to
     * the engine.
     *
     * Two blocking HTTP round trips on Wi-Fi and an instant `UNKNOWN` on BLE, so it is
     * cheap enough to run before every sync -- which is when it matters, because the
     * card can change between two runs. [then] lands on the main thread with the
     * conflict, or null when there is none.
     */
    fun probeCardManifest(t: WalletTransport, then: (ManifestConflict?) -> Unit) {
        worker.execute {
            val kind = try {
                t.probeCardManifest()
            } catch (e: Throwable) {
                ManifestKind.UNKNOWN
            }
            main.post {
                engine.cardManifest = kind
                listener.onLine("card manifest=${kind.label} local=${engine.localManifest.label}")
                then(engine.manifestConflict)
            }
        }
    }

    fun wifiTransport(host: String): WalletWifiTransport =
        WalletWifiTransport(host, executor = worker, poster = { main.post(it) })

    fun bleTransport(frames: WalletBleTransport.FrameSink): WalletBleTransport =
        WalletBleTransport(frames, scheduler)

    fun shutdown() {
        engine.stop("screen closed")
        worker.shutdownNow()
        // A queue left in the session would report a transfer that no longer exists.
        WalletSyncSession.clear()
    }
}
