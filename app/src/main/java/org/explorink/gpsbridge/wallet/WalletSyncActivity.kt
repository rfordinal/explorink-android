package org.explorink.gpsbridge.wallet

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.explorink.gpsbridge.BridgeService
import org.explorink.gpsbridge.MainThread
import org.explorink.gpsbridge.R

/**
 * The Wallet Sync screen (brief section 56): what is pending, on which transport,
 * how far it has got, and what remains.
 *
 * Two transports, one queue. Pressing the other one mid-sync **continues** -- the
 * asset in flight was never confirmed, so it is simply the next pending asset on
 * the new pipe (brief section 30). Nothing restarts.
 *
 * Wi-Fi first, on purpose: 199-236 kB/s measured against BLE's 8-9, so a whole A4
 * page is ~4.3 s there and ~2 minutes here. The BLE button says "continue" rather
 * than "sync" for the same reason -- it is the background trickle, not the way to
 * move a wallet.
 *
 * Two things the screen will not do:
 *
 *  - **No progress bar percentage on BLE that it cannot stand behind.** The
 *    estimate is a phrase, never a countdown (brief section 38: the time "nesmie
 *    vytvárať falošnú presnosť").
 *  - **No item shown as synced without a device confirmation.** Every state on this
 *    screen comes out of the ledger, and the ledger only grows when the device says
 *    what it holds.
 */
class WalletSyncActivity : Activity(), WalletSyncController.Listener {

    companion object {
        private const val TAG = "WalletSync"
        private const val PREF = "wallet_sync"
        private const val PREF_HOST = "host"
        private const val LOG_LINES = 200
    }

    private lateinit var tvHead: TextView
    private lateinit var tvPending: TextView
    private lateinit var tvRemains: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvLog: TextView
    private lateinit var etHost: EditText
    private lateinit var logScroll: ScrollView

    private val store: WalletStore by lazy { WalletImporter.store(this) }
    private lateinit var controller: WalletSyncController

    private var bridge: BridgeService? = null
    private var bleTransport: WalletBleTransport? = null
    private val lines = ArrayList<String>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridge = (binder as? BridgeService.LocalBinder)?.service
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet_sync)
        tvHead = findViewById(R.id.tvSyncHead)
        tvPending = findViewById(R.id.tvSyncPending)
        tvRemains = findViewById(R.id.tvSyncRemains)
        tvProgress = findViewById(R.id.tvSyncProgress)
        tvLog = findViewById(R.id.tvSyncLog)
        etHost = findViewById(R.id.etSyncHost)
        logScroll = findViewById(R.id.syncLogScroll)

        etHost.setText(getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(PREF_HOST, WalletWifiTransport.DEFAULT_HOST))

        controller = WalletSyncController(store, this)

        findViewById<Button>(R.id.btnSyncWifi).setOnClickListener { startWifi() }
        findViewById<Button>(R.id.btnSyncBle).setOnClickListener { startBle() }
        findViewById<Button>(R.id.btnSyncStop).setOnClickListener {
            controller.engine.stop("stopped by the rider")
        }
        findViewById<Button>(R.id.btnSyncQueueAll).setOnClickListener {
            store.queueAll()
            controller.rebuildPlan()
        }

        bindService(Intent(this, BridgeService::class.java), conn, Context.BIND_AUTO_CREATE)
        controller.rebuildPlan()
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge?.attachWalletTransport(null)
        controller.shutdown()
        try {
            unbindService(conn)
        } catch (t: IllegalArgumentException) {
            // Never bound. Nothing to undo.
        }
    }

    // --- starting a transport ----------------------------------------------

    private fun startWifi() {
        val host = etHost.text.toString().trim().ifEmpty { WalletWifiTransport.DEFAULT_HOST }
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(PREF_HOST, host).apply()
        if (controller.engine.queue.pending().isEmpty()) {
            note("nothing pending -- queue an item first")
            return
        }
        val t = controller.wifiTransport(host)
        append("wifi host=$host")
        controller.engine.useTransport(t)
        controller.engine.start()
    }

    private fun startBle() {
        val b = bridge
        if (b == null) {
            note("bridge service not bound yet")
            return
        }
        // One transfer channel, and a status line does not say which transfer it is
        // for. So the two senders take turns rather than racing.
        if (b.bleBusyWithTiles()) {
            note("a tile fetch is using the transfer channel")
            return
        }
        if (controller.engine.queue.pending().isEmpty()) {
            note("nothing pending -- queue an item first")
            return
        }
        val t = bleTransport ?: controller.bleTransport(b.walletFrameSink()).also {
            bleTransport = it
        }
        b.attachWalletTransport(t)
        if (!t.isReady()) {
            note("the device is not connected, or its map screen is not open")
            return
        }
        append("ble mtu-chunk=${b.walletFrameSink().maxChunkPayload()} B")
        controller.engine.useTransport(t)
        controller.engine.start()
    }

    private fun note(text: String) {
        append(text)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // --- controller callbacks (all on the main thread) ----------------------

    override fun onPlanReady(queue: WalletSyncQueue) {
        render()
    }

    override fun onChanged() {
        render()
    }

    override fun onLine(line: String) {
        Log.i(TAG, line)
        append(line)
    }

    override fun onFinished(confirmed: Int, failed: Int, remaining: Int, reason: String) {
        render()
    }

    // --- drawing ------------------------------------------------------------

    private fun render() {
        val q = controller.engine.queue
        val t = controller.engine.transport
        val totals = q.totals()
        val wallet = store.load()

        val link = bridge?.snapshot()?.bleState?.name ?: "no service"
        tvHead.text = "device: BLE $link" +
            (bridge?.snapshot()?.deviceName?.let { " ($it)" } ?: "") +
            "\nwallet version ${wallet.walletVersion}, ${wallet.items.size} item(s), " +
            "panel ${wallet.panelName}" +
            "\ntransport: ${t?.label ?: "none"}" +
            (if (controller.engine.running) " -- syncing" else "")

        tvPending.text = "${bytes(totals.pendingBytes)} pending"
        val eta = t?.estimateText(totals.pendingBytes) ?: ""
        val byClass = q.pendingByClass().entries.joinToString(", ") {
            "${it.key.label} ${bytes(it.value)}"
        }
        tvRemains.text = buildString {
            append("${totals.pendingAssets} of ${totals.totalAssets} assets left")
            append(", ${totals.confirmedAssets} confirmed by the device")
            if (totals.failedAssets > 0) append(", ${totals.failedAssets} failed")
            if (byClass.isNotEmpty()) append("\nremaining: $byClass")
            if (eta.isNotEmpty()) append("\n${t?.label}: $eta")
        }

        val inFlight = q.inFlight
        tvProgress.text = if (inFlight == null) {
            // Running with nothing in flight is the gap between two assets, which a
            // redraw can genuinely land in. Saying "waiting for the transport" there
            // would blame the wire for the app's own handover.
            if (controller.engine.running) "starting the next asset" else "idle"
        } else {
            val a = q.plan.firstOrNull { it.key == inFlight }
            val sent = q.sentBytes(inFlight)
            "sending ${a?.cls?.label ?: "?"} $inFlight: " +
                "${sent} of ${a?.bytes ?: 0} B"
        }

        // Per-item states, so the screen and the list can never disagree: both read
        // the same derivation.
        val sb = StringBuilder()
        for (item in wallet.items) {
            val st = q.statusOf(item.id)
            sb.append("%-16s %-22s %d/%d assets%s\n".format(
                item.title.take(16), st.state.label(), st.confirmedAssets, st.assets,
                if (st.failedAssets > 0) " ${st.failedAssets} failed" else ""))
        }
        sb.append('\n')
        sb.append(lines.joinToString("\n"))
        tvLog.text = sb.toString()
    }

    private fun append(line: String) {
        lines.add(line)
        while (lines.size > LOG_LINES) lines.removeAt(0)
        MainThread.post {
            render()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun bytes(n: Long): String = when {
        n >= 1024L * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
        n >= 1024 -> "%d kB".format(n / 1024)
        else -> "$n B"
    }
}
