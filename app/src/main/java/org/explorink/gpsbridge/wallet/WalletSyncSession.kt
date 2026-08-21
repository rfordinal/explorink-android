package org.explorink.gpsbridge.wallet

/**
 * The sync that is running **right now**, so any screen can show it.
 *
 * Why it exists: [WalletActivity] builds its own [WalletSyncQueue] from the ledger on
 * disk, which is enough to say what the device has confirmed and useless for saying
 * whether anything is happening. Bytes on the wire live only in the running queue, so
 * without this the list stands still through a ten-minute transfer and the rider
 * cannot tell a stalled sync from a working one. That was the complaint that produced
 * this file.
 *
 * **Main thread only, and that is what makes it safe without a lock.** Every queue
 * mutation happens on the main looper: the engine is single-threaded and both
 * transports post their callbacks back through
 * `WalletSyncController` (`poster = { main.post(it) }`), which is where
 * `queue.progress()` and `queue.confirm()` are reached from. A reader on the main
 * thread therefore sees a consistent queue, and nothing else may touch this object.
 *
 * It holds no `Context` and no view, so it cannot leak an activity. It does hold the
 * queue, which is plain data.
 *
 * Not a service. The sync still lives and dies with [WalletSyncActivity]
 * (`onDestroy` calls `controller.shutdown()`), so this reports a sync, it does not
 * keep one alive. Moving the transfer into `BridgeService` so it survives leaving the
 * screen is a separate decision, written up in `docs/android-wallet.md`.
 */
object WalletSyncSession {

    /** The live queue while a sync screen is up, else null. */
    var queue: WalletSyncQueue? = null
        private set

    /** `"BLE"` or `"Wi-Fi"` while running, else null. */
    var transport: String? = null
        private set

    /** True while the engine is actually sending, not merely while a screen is open. */
    var running: Boolean = false
        private set

    /** How many times a caller has published something. Test hook, and a cheap dirty flag. */
    var generation: Int = 0
        private set

    /** The run's own measured rate, for the ETA. Null when no sync is up. */
    var rate: WalletSyncRate? = null
        private set

    fun publish(queue: WalletSyncQueue?, transport: String?, running: Boolean,
                rate: WalletSyncRate? = null) {
        this.queue = queue
        this.transport = transport
        this.running = running
        this.rate = rate
        generation++
    }

    /**
     * Forget the sync. Called when the sync screen goes away — a stale queue would
     * report a transfer that no longer exists, which is the same class of lie as a
     * stale state.
     */
    fun clear() {
        queue = null
        transport = null
        running = false
        rate = null
        generation++
    }

    /**
     * What the measured rate says about the bytes still to go, or null before there is
     * enough of a measurement to say anything.
     *
     * **Measured, never assumed.** The figure this replaces came from a constant
     * (`WalletTransport.bytesPerSecond`, 8-9 kB/s for BLE) and read "roughly a minute or
     * two" while the real link was doing 0.33 kB/s with the phone's screen off -- the
     * arithmetic said forty minutes and the words said two.
     */
    fun etaText(pendingBytes: Long): String? {
        val r = rate ?: return null
        if (r.stalledFor(WalletSyncRate.STALL_MS)) return "stalled, nothing confirmed lately"
        val secs = r.secondsFor(pendingBytes) ?: return null
        val rateNow = r.bytesPerSecond() ?: return null
        return "${WalletSyncRate.clock(secs)} left at ${WalletSyncRate.rateText(rateNow)}"
    }

    /**
     * One line for a screen that is not the sync screen, or null when there is
     * nothing to say.
     */
    fun statusLine(): String? {
        val q = queue ?: return null
        val t = q.totals()
        val pct = (t.fraction * 100).toInt()
        val where = transport ?: "?"
        return if (running) {
            "syncing over $where: $pct%, ${t.confirmedAssets} of ${t.totalAssets} assets" +
                (etaText(t.pendingBytes)?.let { ", $it" } ?: "")
        } else {
            "sync screen open over $where, not sending"
        }
    }
}
