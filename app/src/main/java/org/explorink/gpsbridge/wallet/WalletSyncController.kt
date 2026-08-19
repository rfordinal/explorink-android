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

    val engine = WalletSyncEngine(
        bytes = object : WalletSyncEngine.AssetBytes {
            override fun read(a: SyncAsset): ByteArray? {
                val f = if (a.isManifest) java.io.File(store.treeDir, "manifest.json")
                else store.assetFile(a.key, "dat")
                return if (f.isFile) f.readBytes() else null
            }
        },
        listener = object : WalletSyncEngine.Listener {
            override fun onSyncStarted(transport: String, pendingAssets: Int, pendingBytes: Long) {
                listener.onLine("start transport=$transport pending=$pendingAssets " +
                    "bytes=$pendingBytes")
            }

            override fun onAssetProgress(a: SyncAsset, sent: Int, total: Int) {
                listener.onChanged()
            }

            override fun onAssetConfirmed(a: SyncAsset, transport: String, detail: String) {
                // "confirmed" is the device's word, not ours: the line records what the
                // device said, which is what makes an adb log readable as evidence.
                listener.onLine("confirmed ${a.cls.label} ${a.key} ${a.bytes}B " +
                    "via $transport: $detail")
                listener.onChanged()
            }

            override fun onAssetFailed(a: SyncAsset, reason: String) {
                listener.onLine("failed ${a.cls.label} ${a.key}: $reason")
                listener.onChanged()
            }

            override fun onSyncFinished(confirmed: Int, failed: Int, remaining: Int, reason: String) {
                listener.onLine("finish confirmed=$confirmed failed=$failed " +
                    "remaining=$remaining reason=$reason")
                listener.onFinished(confirmed, failed, remaining, reason)
            }

            override fun onQueueChanged() {
                listener.onChanged()
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
            val queue = WalletSyncQueue(plan, state.confirmed, state.errors, state.queued)
            main.post {
                engine.setQueue(queue)
                listener.onPlanReady(queue)
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
    }
}
