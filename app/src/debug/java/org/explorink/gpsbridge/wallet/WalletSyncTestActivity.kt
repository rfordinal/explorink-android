package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import org.explorink.gpsbridge.BridgeService
import java.io.File

/**
 * **Debug builds only.** Starts a real wallet sync from `adb`, with no tapping,
 * and reports what the **device confirmed**.
 *
 * Same pattern as [WalletCodeSelfTestActivity] and the same enforcement: the class
 * lives in `src/debug/java`, so it is not compiled into a release build at all,
 * and it is declared and exported only in `src/debug/AndroidManifest.xml`, so a
 * release APK has neither the class nor the entry point. `assembleRelease` cannot
 * pick it up even by accident.
 *
 * ## Driving it
 *
 *     # Wi-Fi, the whole wallet, device in station mode
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletSyncTestActivity \
 *         --es transport wifi --es host 192.168.69.20
 *
 *     # Wi-Fi, one item only (index into the manifest's item list)
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletSyncTestActivity \
 *         --es transport wifi --es host 192.168.69.20 --ei item 0
 *
 *     # BLE. The device must be sitting on its map or tile-sync screen, because
 *     # that is where the transfer receiver is attached, and it holds ONE
 *     # connection -- nothing else may be connected to it.
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletSyncTestActivity \
 *         --es transport ble
 *
 *     # What happened
 *     adb logcat -d -s WalletSync
 *     adb exec-out run-as org.explorink.gpsbridge cat files/walletsync/result.txt
 *
 *     # Plan only, no transfer: what is pending and why
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletSyncTestActivity \
 *         --es transport none
 *
 *     # Forget every confirmation, so the next run sends the whole wallet again
 *     adb shell am start -n org.explorink.gpsbridge/.wallet.WalletSyncTestActivity \
 *         --es transport none --ez reset true
 *
 * Extras, all optional: `--es transport wifi|ble|none` (default wifi),
 * `--es host <ip>` (default 192.168.69.20), `--ei item <index>` (default: every
 * item), `--ez reset true` (clear the ledger first), `--ei timeout <seconds>`
 * (default 600), `--ez anyway true` (sync even though the card holds the other kind
 * of manifest -- see below).
 *
 * ## The manifest-kind check
 *
 * Before any byte moves, the run reports what the card holds:
 *
 *     card manifest=encrypted local=cleartext
 *     manifest conflict local=cleartext card=encrypted invisible=true
 *     done ... reason="manifest conflict: ..."
 *
 * and **stops**, unless `--ez anyway true` is passed. Wi-Fi can ask the card
 * (`/api/hash`); BLE cannot read anything, so it reports `card manifest=unknown` and
 * proceeds. This exists because on 2026-08-19 a cleartext sync onto a card holding
 * `manifest.enc` confirmed all 25 files in 6.5 minutes and the rider still saw the
 * old wallet (`docs/wallet-plan.md` 7l).
 *
 * ## What the output means
 *
 * Every `asset` line with `state=confirmed` carries the **device's own verdict** in
 * `device=`, not the phone's opinion: `OK <bytes> <crc32hex>` from the BLE status
 * characteristic, or the `size`/`sha256` that `GET /api/hash` streamed off the
 * card. A 200 from `/upload` never produces one of these lines.
 */
class WalletSyncTestActivity : Activity(), WalletSyncController.Listener {

    companion object {
        /** Same tag the real sync screen logs under, so one filter catches both. */
        private const val TAG = "WalletSync"
        private const val DIR = "walletsync"
    }

    private val main = Handler(Looper.getMainLooper())
    private val store: WalletStore by lazy { WalletImporter.store(this) }
    private lateinit var controller: WalletSyncController
    private lateinit var out: TextView
    private val lines = ArrayList<String>()

    private var transportName = "wifi"
    private var host = WalletWifiTransport.DEFAULT_HOST
    private var itemIndex = -1
    private var timeoutMs = 600_000L
    private var syncAnyway = false
    private var startedAt = 0L
    private var finished = false

    private var bridge: BridgeService? = null
    private var bleTransport: WalletBleTransport? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridge = (binder as? BridgeService.LocalBinder)?.service
            if (transportName == "ble") main.post { beginBle(0) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setPadding(16, 16, 16, 16)
        }
        setContentView(ScrollView(this).apply { addView(out) })

        transportName = intent.getStringExtra("transport") ?: "wifi"
        host = intent.getStringExtra("host") ?: WalletWifiTransport.DEFAULT_HOST
        itemIndex = intent.getIntExtra("item", -1)
        timeoutMs = intent.getIntExtra("timeout", 600).toLong() * 1000L
        syncAnyway = intent.getBooleanExtra("anyway", false)
        val reset = intent.getBooleanExtra("reset", false)

        controller = WalletSyncController(store, this)
        startedAt = System.currentTimeMillis()

        val wallet = store.load()
        if (reset) {
            // Forget every confirmation. The ledger is the only thing that says an
            // asset is on the card, so this is how a full resend is arranged --
            // nothing on the device is touched, and the next run's hashes decide.
            store.saveSyncState(WalletSyncQueue(emptyList()))
            emit("reset ledger cleared")
        }
        // Queue what was asked for. This is intent, not progress.
        if (itemIndex >= 0) {
            val item = wallet.items.getOrNull(itemIndex)
            if (item == null) {
                emit("error no item at index $itemIndex (wallet has ${wallet.items.size})")
                writeResult()
                return
            }
            store.setQueued(item.id, true)
            emit("queue item=$itemIndex id=${item.id} title=\"${item.title}\"")
        } else {
            store.queueAll()
            emit("queue all items=${wallet.items.size}")
        }

        emit("wallet version=${wallet.walletVersion} items=${wallet.items.size} " +
            "panel=${wallet.panelName} manifest=${wallet.manifestKind.label} " +
            "keys=\"${store.keys.description}\"")
        controller.rebuildPlan()

        main.postDelayed({
            if (!finished) {
                emit("timeout after ${timeoutMs / 1000} s")
                controller.engine.stop("adb timeout")
                writeResult()
            }
        }, timeoutMs)
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge?.attachWalletTransport(null)
        controller.shutdown()
        try {
            unbindService(conn)
        } catch (t: IllegalArgumentException) {
            // never bound
        }
    }

    // --- the run ------------------------------------------------------------

    override fun onPlanReady(queue: WalletSyncQueue) {
        val totals = queue.totals()
        emit("plan assets=${totals.totalAssets} pending=${totals.pendingAssets} " +
            "pending_bytes=${totals.pendingBytes} confirmed=${totals.confirmedAssets} " +
            "total_bytes=${totals.totalBytes}")
        for ((cls, n) in queue.pendingByClass()) emit("pending class=${cls.name} bytes=$n")

        // Nothing to do is a verdict, not a reason to wait 30 s for a BLE link.
        if (transportName != "none" && totals.pendingAssets == 0) {
            emit("done transport=$transportName confirmed=0 failed=0 remaining=0 " +
                "pending_bytes=0 elapsed_ms=${System.currentTimeMillis() - startedAt} " +
                "reason=\"nothing pending; pass --ez reset true to send it all again\"")
            writeResult()
            return
        }
        when (transportName) {
            "none" -> {
                emit("transport none -- plan only")
                writeResult()
            }
            "wifi" -> beginWifi()
            "ble" -> {
                bindService(Intent(this, BridgeService::class.java), conn,
                    Context.BIND_AUTO_CREATE)
                // The service may already be up; onServiceConnected fires either way.
            }
            else -> {
                emit("error unknown transport '$transportName'")
                writeResult()
            }
        }
    }

    private fun beginWifi() {
        val t = controller.wifiTransport(host)
        emit("transport wifi host=$host")
        // Probe first, so a dead host is one clear line rather than 25 failures. The
        // probe is blocking, so it runs on the controller's worker.
        Thread({
            val ok = t.probe()
            main.post {
                emit("probe ok=$ok detail=\"${t.lastProbeDetail}\"")
                if (!ok) {
                    emit("error host $host did not answer /api/status")
                    writeResult()
                    return@post
                }
                controller.engine.useTransport(t)
                startAfterProbe(t)
            }
        }, "wallet-sync-probe").start()
    }

    private fun beginBle(attempt: Int) {
        val b = bridge
        if (b == null) {
            emit("error bridge service not bound")
            writeResult()
            return
        }
        if (b.bleBusyWithTiles()) {
            emit("error a tile fetch owns the transfer channel")
            writeResult()
            return
        }
        val t = bleTransport ?: controller.bleTransport(b.walletFrameSink()).also {
            bleTransport = it
        }
        b.attachWalletTransport(t)
        if (!t.isReady()) {
            // The link comes up asynchronously and the device's BLE server only
            // exists while its map screen is open, so waiting a little beats failing
            // on a race. 30 x 1 s, then a clear verdict.
            if (attempt < 30) {
                emit("waiting for ble link attempt=$attempt state=" +
                    "${b.snapshot().bleState.name}")
                main.postDelayed({ beginBle(attempt + 1) }, 1_000L)
                return
            }
            emit("error ble not ready: ${b.snapshot().bleState.name} -- " +
                "is the device on its map screen?")
            writeResult()
            return
        }
        emit("transport ble chunk=${b.walletFrameSink().maxChunkPayload()} " +
            "device=\"${b.snapshot().deviceName}\"")
        controller.engine.useTransport(t)
        startAfterProbe(t)
    }

    /**
     * Ask the card which manifest it holds, then start -- or refuse and say why.
     *
     * A refusal here is a **result**, not an error to retry: the sync would have
     * completed and changed nothing visible. `--ez anyway true` is the explicit
     * override, which is what a rider tapping sync a second time does in the real
     * screen.
     */
    private fun startAfterProbe(t: WalletTransport) {
        controller.probeCardManifest(t) { conflict ->
            if (conflict != null && !syncAnyway) {
                emit("refused invisible=${conflict.invisible} remedy=\"${conflict.remedy}\"")
            }
            controller.engine.start(ignoreManifestConflict = syncAnyway)
        }
    }

    override fun onChanged() {
        // Progress is not logged per callback: a 48 kB asset over BLE is hundreds of
        // write callbacks and the log would be useless. Per-asset lines are enough.
    }

    override fun onLine(line: String) {
        // The controller's own lines already carry the device's verdict.
        emit(line)
    }

    override fun onFinished(confirmed: Int, failed: Int, remaining: Int, reason: String) {
        val q = controller.engine.queue
        for (item in store.load().items) {
            val st = q.statusOf(item.id)
            emit("item id=${item.id} title=\"${item.title}\" state=${st.state.name} " +
                "confirmed=${st.confirmedAssets}/${st.assets} failed=${st.failedAssets} " +
                "usable=${st.usable} grey=${item.grey}")
        }
        val t = q.totals()
        emit("done transport=$transportName confirmed=$confirmed failed=$failed " +
            "remaining=$remaining pending_bytes=${t.pendingBytes} " +
            "elapsed_ms=${System.currentTimeMillis() - startedAt} reason=\"$reason\"")
        writeResult()
    }

    // --- output -------------------------------------------------------------

    private fun emit(line: String) {
        Log.i(TAG, line)
        lines.add(line)
        out.text = lines.joinToString("\n")
    }

    /**
     * Machine-readable, and pullable without root:
     * `adb exec-out run-as org.explorink.gpsbridge cat files/walletsync/result.txt`.
     */
    private fun writeResult() {
        if (finished) return
        finished = true
        val dir = File(filesDir, DIR)
        dir.mkdirs()
        val f = File(dir, "result.txt")
        try {
            f.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
            Log.i(TAG, "result written to ${f.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "cannot write result", t)
        }
    }
}
