package org.explorink.gpsbridge.wallet

/**
 * Drives one [WalletTransport] over one [WalletSyncQueue] (brief section 51).
 *
 * The engine owns the loop, the retries and the persistence hook. It does not own
 * the wire and it does not own the priorities: the queue decides what is next, a
 * transport moves bytes, and the engine's whole job is "send the next pending
 * asset, and only believe the device".
 *
 * Callback driven and single-threaded on purpose -- the same shape as
 * `TileFetcher`, for the same reason: every transport callback arrives on the
 * thread the caller pumps from, so there is no lock anywhere and the state machine
 * can be unit tested by hand-driving a fake transport.
 *
 * Switching transport mid-sync **continues** (brief section 30). The proof is
 * structural, not a special case: the in-flight asset was never confirmed, so it
 * is simply pending again, and the next [pump] hands it to whichever transport is
 * installed. Nothing is reset and nothing restarts from the beginning.
 */
class WalletSyncEngine(
    private val bytes: AssetBytes,
    private val listener: Listener,
    /** Called after every ledger change so the caller can persist `state.json`. */
    private val persist: (WalletSyncQueue) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        /**
         * Passes over the queue before giving up.
         *
         * Three, not "retry forever": a wallet sync is a foreground thing the rider
         * is watching, and an asset that failed twice is failing for a reason the
         * next attempt will hit too. Ending with a visible ERROR beats a spinner.
         */
        const val MAX_PASSES = 3
    }

    /** Where the file bytes come from. A seam so the engine needs no filesystem. */
    interface AssetBytes {
        /** The asset's file, or null when it is gone from the tree. */
        fun read(a: SyncAsset): ByteArray?
    }

    interface Listener {
        fun onSyncStarted(transport: String, pendingAssets: Int, pendingBytes: Long) {}

        /** One asset's progress. [sent] is bytes the wire took, not bytes confirmed. */
        fun onAssetProgress(a: SyncAsset, sent: Int, total: Int) {}

        /** The device confirmed this asset. The only place "on device" begins. */
        fun onAssetConfirmed(a: SyncAsset, transport: String, detail: String) {}

        fun onAssetFailed(a: SyncAsset, reason: String) {}

        /** Nothing left to do, or stopped. [reason] is for the log and the screen. */
        fun onSyncFinished(confirmed: Int, failed: Int, remaining: Int, reason: String) {}

        /** Anything the screen should redraw on. */
        fun onQueueChanged() {}
    }

    var queue: WalletSyncQueue = WalletSyncQueue(emptyList())
        private set

    var transport: WalletTransport? = null
        private set

    var running: Boolean = false
        private set

    /** Assets confirmed and assets failed in this run, for the finish line. */
    private var confirmedThisRun = 0
    private var failedThisRun = 0
    private var pass = 1

    /** Guards a callback that arrives after a stop or a transport switch. */
    private var generation = 0

    fun setQueue(q: WalletSyncQueue) {
        queue = q
        listener.onQueueChanged()
    }

    /**
     * Install a transport. Safe mid-sync: whatever is in flight is cancelled, and
     * because it was never confirmed the queue still lists it as pending.
     */
    fun useTransport(t: WalletTransport?) {
        if (t === transport) return
        generation++
        transport?.cancel()
        queue.release()
        transport = t
        if (running) {
            // Keep the pass count: a switch is not a fresh start, it is the same run
            // on a different pipe.
            pump()
        }
    }

    fun start() {
        if (running) return
        val t = transport
        if (t == null || !t.isReady()) {
            listener.onSyncFinished(0, 0, queue.pending().size,
                if (t == null) "no transport" else "${t.label} not ready")
            return
        }
        running = true
        confirmedThisRun = 0
        failedThisRun = 0
        pass = 1
        queue.clearErrors()
        val totals = queue.totals()
        listener.onSyncStarted(t.name, totals.pendingAssets, totals.pendingBytes)
        pump()
    }

    fun stop(reason: String = "stopped") {
        if (!running) return
        generation++
        running = false
        transport?.cancel()
        queue.release()
        listener.onSyncFinished(confirmedThisRun, failedThisRun, queue.pending().size, reason)
        listener.onQueueChanged()
    }

    // --- the loop ----------------------------------------------------------

    /** Reentrancy guard, so a synchronous transport loops instead of recursing. */
    private var pumping = false
    private var pumpAgain = false

    /**
     * Send the next pending asset. A trampoline, not recursion.
     *
     * A transport whose callbacks come back on the caller's thread -- a test double,
     * a future USB pipe, the Wi-Fi transport with a direct executor -- would
     * otherwise nest one frame per asset for the whole wallet. Correct behaviour must
     * not depend on the transport being asynchronous.
     */
    private fun pump() {
        if (pumping) {
            pumpAgain = true
            return
        }
        pumping = true
        try {
            do {
                pumpAgain = false
                pumpOne()
            } while (pumpAgain)
        } finally {
            pumping = false
        }
    }

    private fun pumpOne() {
        if (!running) return
        val t = transport
        if (t == null) {
            finish("no transport")
            return
        }
        if (!t.isReady()) {
            finish("${t.label} went away")
            return
        }

        val a = queue.takeNext()
        if (a == null) {
            // Nothing sendable. Either everything landed, or what is left already
            // failed this pass -- in which case one more pass is worth it, because
            // the usual cause is a link that came back.
            val stuck = queue.pending().isNotEmpty()
            if (stuck && pass < MAX_PASSES) {
                pass++
                queue.clearErrors()
                pump()
                return
            }
            finish(if (stuck) "gave up after $pass passes" else "everything confirmed")
            return
        }

        val data = bytes.read(a)
        if (data == null) {
            // The file is gone: the wallet changed under the run. Not an error to
            // retry -- the plan is stale and the caller has to rebuild it.
            queue.fail(a.key, "file missing")
            failedThisRun++
            listener.onAssetFailed(a, "file missing")
            listener.onQueueChanged()
            pump()
            return
        }
        if (data.size != a.bytes) {
            queue.fail(a.key, "size changed: ${data.size} on disk, plan says ${a.bytes}")
            failedThisRun++
            listener.onAssetFailed(a, "size changed")
            listener.onQueueChanged()
            pump()
            return
        }

        val gen = generation
        t.send(SendJob(a.relPath, data, a.sha256), object : SendCallback {
            override fun onProgress(sentBytes: Int) {
                if (gen != generation) return
                queue.progress(a.key, sentBytes)
                listener.onAssetProgress(a, sentBytes, a.bytes)
            }

            override fun onConfirmed(detail: String) {
                if (gen != generation) return
                queue.confirm(a, t.name, clock())
                confirmedThisRun++
                persist(queue)
                listener.onAssetConfirmed(a, t.name, detail)
                listener.onQueueChanged()
                pump()
            }

            override fun onFailed(reason: String, retryable: Boolean) {
                if (gen != generation) return
                queue.fail(a.key, reason)
                failedThisRun++
                persist(queue)
                listener.onAssetFailed(a, reason)
                listener.onQueueChanged()
                if (!retryable) {
                    finish("${t.label}: $reason")
                    return
                }
                pump()
            }
        })
    }

    private fun finish(reason: String) {
        running = false
        queue.release()
        listener.onSyncFinished(confirmedThisRun, failedThisRun, queue.pending().size, reason)
        listener.onQueueChanged()
    }
}
