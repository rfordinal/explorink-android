package org.explorink.gpsbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The one window. It owns no bridge state: everything lives in
 * [BridgeService], which keeps running with the screen off. The activity binds,
 * renders a snapshot, and forwards four buttons.
 */
class MainActivity : Activity(), BridgeService.Observer {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 1

        /**
         * Background location is requested on its own, after the foreground
         * grants: Android refuses a request that bundles it with them, and it is
         * only needed for the wake path (docs/ble-app-wake.md).
         */
        private const val REQ_BACKGROUND_LOCATION = 2

        /** Redraw this often while visible, so the fix "age" line stays honest. */
        private const val UI_TICK_MS = 1000L

        private val REQUIRED: Array<String> = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Without it the foreground service still runs, but its status line
            // is invisible, which is worse than asking.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private lateinit var tvState: TextView
    private lateinit var tvProblem: TextView
    private lateinit var tvWake: TextView
    private lateinit var tvTilesHead: TextView
    private lateinit var tvTiles: TextView
    private lateinit var tvTileNow: TextView
    private lateinit var pbTileBytes: ProgressBar
    private lateinit var pbTileSquares: ProgressBar
    private lateinit var tvFix: TextView
    private lateinit var tvCounters: TextView
    private lateinit var tvLogFile: TextView
    private lateinit var tvEvents: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnWake: Button
    private lateinit var btnForget: Button
    private lateinit var btnRetry: Button
    private lateinit var btnExport: Button
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button

    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var service: BridgeService? = null
    private var bound = false
    private var permissionsDenied = false

    /** So a second press of the wake button goes to Settings, not to a dialog
     * Android will not show twice. */
    private var backgroundLocationAsked = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            val s = (b as? BridgeService.LocalBinder)?.service ?: return
            service = s
            s.setObserver(this@MainActivity)
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            render()
        }
    }

    // --- lifecycle ------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvState = findViewById(R.id.tvState)
        tvProblem = findViewById(R.id.tvProblem)
        tvWake = findViewById(R.id.tvWake)
        tvTilesHead = findViewById(R.id.tvTilesHead)
        tvTiles = findViewById(R.id.tvTiles)
        tvTileNow = findViewById(R.id.tvTileNow)
        pbTileBytes = findViewById(R.id.pbTileBytes)
        pbTileSquares = findViewById(R.id.pbTileSquares)
        tvFix = findViewById(R.id.tvFix)
        tvCounters = findViewById(R.id.tvCounters)
        tvLogFile = findViewById(R.id.tvLogFile)
        tvEvents = findViewById(R.id.tvEvents)
        tvVersion = findViewById(R.id.tvVersion)
        btnWake = findViewById(R.id.btnWake)
        btnForget = findViewById(R.id.btnForget)
        btnRetry = findViewById(R.id.btnRetry)
        btnExport = findViewById(R.id.btnExport)
        btnRecord = findViewById(R.id.btnRecord)
        btnStop = findViewById(R.id.btnStop)

        btnWake.setOnClickListener { onWakePressed() }
        btnForget.setOnClickListener { onForgetPressed() }
        btnRetry.setOnClickListener { onRetryPressed() }
        btnExport.setOnClickListener { shareLog() }
        btnRecord.setOnClickListener { onRecordPressed() }
        btnStop.setOnClickListener { onStopPressed() }

        // Version, once, at the bottom. Debug builds carry the commit they were
        // built from, so "which build is on the phone" is answerable.
        tvVersion.text = "ExplorInk GPS ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        render()

        if (missingPermissions().isEmpty()) {
            startBridge()
        } else {
            requestPermissions(REQUIRED, REQ_PERMISSIONS)
        }

        // Re-arm the OS-side presence watch on every launch. Idempotent, and an
        // association nobody observes wakes nothing.
        CompanionWake.startObserving(this)
    }

    override fun onStart() {
        super.onStart()
        // Bind only to a bridge that is already up. Binding must never be what
        // creates it: a service created by a bind alone never went through
        // onStartCommand, so it would sit there not foreground and not sending.
        if (BridgeService.isRunning) bindBridge(create = false)
        main.removeCallbacks(uiTick)
        main.postDelayed(uiTick, UI_TICK_MS)
        render()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(uiTick)
        service?.setObserver(null)
        if (bound) {
            try {
                unbindService(connection)
            } catch (t: Throwable) {
                Log.w(TAG, "unbind", t)
            }
            bound = false
        }
        service = null
        // The service is NOT stopped here. That is the point of the change:
        // sending survives the screen locking and the app being swiped away.
    }

    private val uiTick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, UI_TICK_MS)
        }
    }

    /**
     * Starts the bridge and binds to it. `startForegroundService` is called from
     * a visible activity, which is what lets the location-typed foreground
     * service read GPS later with the screen off and no background-location
     * permission.
     */
    private fun startBridge(action: String = BridgeService.ACTION_START) {
        startForegroundService(Intent(this, BridgeService::class.java).setAction(action))
        // BIND_AUTO_CREATE here only removes a race: the service is being
        // created by the start above either way, and this way the binding lands
        // whether or not it is up yet.
        bindBridge(create = true)
    }

    /**
     * Restores the link after [service]`.pauseLinkForPairing()`, whichever way
     * the pairing dialog ended -- cancelled, failed, or the activity having
     * unbound from the service while the dialog (another process) had focus.
     *
     * `service` going null across that dialog is routine, not an edge case: any
     * config change or the activity being backgrounded during it drops the
     * binding (`onStop`, `:171-186`), and pausing the link is exactly a call
     * that leaves nothing running to rebind to. `service?.resumeLink()` alone
     * then does nothing silently -- the rider is left with no bridge and no
     * indication why. [startBridge] here is [onRetryPressed]'s own answer to
     * "no service": send [BridgeService.ACTION_START] and bind.
     */
    private fun resumeLinkOrStart() {
        val s = service
        if (s != null) s.resumeLink() else startBridge()
    }

    private fun bindBridge(create: Boolean) {
        if (bound) return
        val flags = if (create) Context.BIND_AUTO_CREATE else 0
        // bindService can return false and still leave a binding behind, so the
        // unbind in onStop is driven by "did we ask", not by "did it work".
        bound = true
        if (!bindService(Intent(this, BridgeService::class.java), connection, flags)) {
            Log.w(TAG, "bindService returned false")
        }
    }

    override fun onBridgeChanged() {
        main.post { render() }
    }

    // --- buttons --------------------------------------------------------

    private fun onRetryPressed() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            // Second denial hides the system dialog, so send the user to the
            // app's settings page instead of silently doing nothing.
            if (permissionsDenied) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
            } else {
                requestPermissions(REQUIRED, REQ_PERMISSIONS)
            }
            return
        }
        val adapter = service?.bluetoothAdapter()
        if (adapter != null && !adapter.isEnabled) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        val s = service
        if (s == null) {
            startBridge()
        } else {
            s.retry()
        }
    }

    /**
     * One button for the whole auto-start setup, doing whichever step is missing.
     * Two steps, in this order because the second is pointless without the first:
     * pair the X4, then grant background location.
     */
    private fun onWakePressed() {
        if (!CompanionWake.isPaired(this)) {
            // The link has to go first. A connected X4 stops advertising, and the
            // pairing dialog can only list what it sees advertising -- with the
            // bridge up the dialog stays empty forever. Restored in
            // onActivityResult, whichever way the dialog ends.
            service?.pauseLinkForPairing()
            CompanionWake.requestAssociation(this) { error ->
                main.post {
                    Toast.makeText(
                        this,
                        "pairing failed: ${error ?: "no X4 found -- open the map screen on it"}",
                        Toast.LENGTH_LONG,
                    ).show()
                    resumeLinkOrStart()
                }
            }
            return
        }
        if (!hasBackgroundLocation()) {
            // On API 30+ this dialog is shown once. After that the only route is
            // the app's settings page, which is where a second press goes.
            if (backgroundLocationAsked) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
            } else {
                backgroundLocationAsked = true
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQ_BACKGROUND_LOCATION,
                )
            }
        }
    }

    /**
     * Unpairing, behind a confirm. Not because it destroys data -- it does not --
     * but because it silently ends auto-start, and a rider who taps it by mistake
     * finds out days later when a ride starts with no position on the panel.
     */
    private fun onForgetPressed() {
        val addr = CompanionWake.pairedAddress(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Forget $addr?")
            .setMessage(
                "The map screen will stop starting this app on its own, and the app " +
                    "will connect to whichever X4 answers first until you pair again."
            )
            .setPositiveButton("Forget") { _, _ ->
                service?.forgetPairing()
                Toast.makeText(this, "unpaired", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CompanionWake.REQ_ASSOCIATE) return
        // Unconditionally, before anything else: the link was released for the
        // dialog and a cancelled pairing must not leave the rider with no bridge.
        resumeLinkOrStart()
        if (resultCode != RESULT_OK) {
            render()
            return
        }
        val addr = CompanionWake.onAssociationResult(this)
        Toast.makeText(
            this,
            if (addr != null) "paired with $addr" else "pairing did not complete",
            Toast.LENGTH_LONG,
        ).show()
        render()
    }

    private fun hasBackgroundLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun onRecordPressed() {
        val s = service
        if (s == null) {
            // No bridge yet: start it, then arm the recorder in the same gesture.
            startBridge(BridgeService.ACTION_START_RECORDING)
            return
        }
        if (s.isRecording) s.stopRecording() else s.startRecording()
        render()
    }

    private fun onStopPressed() {
        startService(
            Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP)
        )
        service?.setObserver(null)
        if (bound) {
            try {
                unbindService(connection)
            } catch (t: Throwable) {
                Log.w(TAG, "unbind", t)
            }
            bound = false
        }
        service = null
        render()
        finish()
    }

    // --- permissions ----------------------------------------------------

    private fun missingPermissions(): List<String> =
        REQUIRED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BACKGROUND_LOCATION) {
            // Nothing to start or stop either way: the grant only matters the next
            // time the X4 wakes this app. Just redraw the auto-start line.
            render()
            return
        }
        if (requestCode != REQ_PERMISSIONS) return
        // The notification permission is a nice-to-have; the bridge can run
        // without it. Only the BLE and location grants are load-bearing.
        val blocking = missingPermissions().filter {
            it != Manifest.permission.POST_NOTIFICATIONS
        }
        permissionsDenied = blocking.isNotEmpty()
        if (!permissionsDenied) startBridge()
        render()
    }

    // --- UI -------------------------------------------------------------

    /**
     * The auto-start line: whether opening the map on the X4 will start this app
     * on its own, and if not, what is missing. Two states are not the same
     * failure, so they do not share a message -- unpaired means no wake at all,
     * paired-without-background-location means the wake fires and then has no
     * fixes to send.
     */
    private fun renderWake() {
        val addr = CompanionWake.pairedAddress(this)
        when {
            addr == null -> {
                tvWake.text = "auto-start: off -- X4 not paired"
                btnWake.text = "Pair the X4"
                btnWake.visibility = View.VISIBLE
            }

            !hasBackgroundLocation() -> {
                tvWake.text = "auto-start: paired $addr, but no background location"
                btnWake.text = "Allow location all the time"
                btnWake.visibility = View.VISIBLE
            }

            else -> {
                tvWake.text = "auto-start: on -- paired $addr"
                btnWake.visibility = View.GONE
            }
        }
        // Unpairing stays reachable in every paired state, including the one where
        // background location is still missing -- otherwise pairing the wrong device
        // is only undoable by granting a permission first.
        btnForget.visibility = if (addr != null) View.VISIBLE else View.GONE
    }

    /**
     * The map-square block, hidden entirely until the device has asked for
     * something. An empty labelled box reads like a broken feature; no box reads
     * like a feature that has not happened yet, which is the truth.
     */
    private fun renderTiles(lines: List<String>, now: BridgeService.TileProgress?) {
        val show = lines.isNotEmpty() || now != null
        tvTilesHead.visibility = if (show) View.VISIBLE else View.GONE
        tvTiles.visibility = if (lines.isNotEmpty()) View.VISIBLE else View.GONE
        if (lines.isNotEmpty()) tvTiles.text = lines.joinToString("\n")

        val live = if (now != null) View.VISIBLE else View.GONE
        tvTileNow.visibility = live
        pbTileBytes.visibility = live
        pbTileSquares.visibility = live
        if (now == null) return

        // Both lines read the way the device's sync screen reads, on purpose:
        // TileFormat is a port of it (decimal kB, rate over the whole fetch from
        // completed squares only, skips counted apart).
        tvTileNow.text = "now: z${now.z} ${now.col}/${now.row}   " +
            "${TileFormat.bytes(now.sentBytes)} / ${TileFormat.bytes(now.totalBytes)}\n" +
            TileFormat.summary(
                now.completedSquares, now.skippedSquares, now.totalSquares,
                now.movedBytes, now.completedBytes, now.elapsedMs,
            )
        // Percent, not bytes, on both bars: setMax per frame makes the bar jump
        // when one square is bigger than the last.
        pbTileBytes.progress =
            if (now.totalBytes > 0) now.sentBytes * 100 / now.totalBytes else 0
        // Skips fill the ask too -- they are settled, just not arrived -- or the
        // bar stalls at a square nobody can supply.
        pbTileSquares.progress =
            if (now.totalSquares > 0)
                (now.completedSquares + now.skippedSquares) * 100 / now.totalSquares
            else 0
    }

    private fun render() {
        renderWake()
        val snap = service?.snapshot()

        if (snap == null) {
            tvState.text = if (permissionsDenied) "permission needed" else "starting"
            tvFix.text = "fix: none yet"
            tvCounters.text = "packets: sent 0 / failed 0"
            tvLogFile.text = "recording: off"
            tvEvents.text = ""
            renderTiles(emptyList(), null)
            btnRecord.text = "Start recording"
            renderProblems(null)
            return
        }

        val healthy = snap.bleState == BleLink.State.SCANNING ||
            snap.bleState == BleLink.State.CONNECTING ||
            snap.bleState == BleLink.State.CONNECTED
        val stateText = when (snap.bleState) {
            BleLink.State.IDLE -> "idle"
            BleLink.State.NO_PERMISSION -> "permission needed"
            BleLink.State.BLUETOOTH_OFF -> "Bluetooth off"
            BleLink.State.SCANNING -> "scanning"
            BleLink.State.CONNECTING -> "connecting"
            BleLink.State.CONNECTED -> "connected to ${snap.deviceName ?: BleLink.DEVICE_NAME}"
            BleLink.State.DISCONNECTED -> "disconnected"
            BleLink.State.FAILED -> "failed"
        }
        // A healthy state's detail belongs on the state line; only a broken
        // state's detail belongs in the red block below it.
        tvState.text = if (healthy && snap.bleDetail != null) {
            "$stateText\n${snap.bleDetail}"
        } else {
            stateText
        }
        renderProblems(if (healthy) null else snap.bleDetail)

        val fix = snap.lastFix
        tvFix.text = if (fix == null) {
            "fix: none yet"
        } else {
            val ageMs = abs(System.currentTimeMillis() - fix.time)
            buildString {
                append("lat ").append(String.format(Locale.US, "%.7f", fix.latitude))
                append("   lon ").append(String.format(Locale.US, "%.7f", fix.longitude))
                append("\nacc ")
                append(if (fix.hasAccuracy()) String.format(Locale.US, "%.1f m", fix.accuracy) else "-")
                append("   speed ")
                append(if (fix.hasSpeed()) String.format(Locale.US, "%.1f km/h", fix.speed * 3.6f) else "-")
                // Normalised for display: Location.getBearing() and bearingTo()
                // both hand back negative degrees, which reads like a bug.
                val shown = ((snap.bearingDeg % 360f) + 360f) % 360f
                append("\nheading sector ").append(PositionPacket.headingSector(snap.bearingDeg))
                append(" (").append(String.format(Locale.US, "%.0f", shown)).append("°)")
                append("\nprovider ").append(fix.provider ?: "?")
                append("   age ").append(ageMs / 1000).append(" s")
                append("\nlast sent ")
                append(if (snap.lastSentAtMs == 0L) "never" else timeFmt.format(Date(snap.lastSentAtMs)))
            }
        }

        tvCounters.text = buildString {
            append("packets: sent ").append(snap.sentOk)
            append(" / failed ").append(snap.sentFailed)
            append("   seq ").append(snap.seq)
            append("\nsend when moved ")
            if (snap.movedSinceSentM == null) {
                append("-")
            } else {
                append(snap.movedSinceSentM.toInt())
            }
            append(" / ").append(snap.moveThresholdM.toInt()).append(" m")
            append("   min ").append(SendPolicy.MIN_INTERVAL_MS / 1000).append("s")
            append("   keep-alive ").append(SendPolicy.KEEPALIVE_INTERVAL_MS / 60000).append(" min")
            snap.lastSendReason?.let { append("\nlast send reason: ").append(it) }
            // Only once a fetch has happened: the rider starts one from the
            // device's own menu, so an idle line here would be noise on every
            // ride that never asks for tiles.
            snap.tileFetchStatus?.let { append("\ntiles: ").append(it) }
        }

        val f = snap.logFile
        tvLogFile.text = when {
            snap.recording && f != null ->
                "recording: ${f.name}\n${snap.logLines} lines  ·  ${f.parent}"
            snap.recording -> "recording: opening file..."
            f != null -> "recording: off\nlast file: ${f.name}"
            else -> "recording: off"
        }

        btnRecord.text = if (snap.recording) "Stop recording" else "Start recording"
        renderTiles(snap.tileLog, snap.tileProgress)
        tvEvents.text = snap.events.joinToString("\n")
    }

    private fun renderProblems(bleDetail: String?) {
        val problems = ArrayList<String>()
        if (permissionsDenied) {
            problems.add(
                "Permission denied. Press Retry scan to open app settings and grant " +
                    "Nearby devices + Location (Precise, while using the app)."
            )
        }
        val adapter = service?.bluetoothAdapter()
        if (adapter != null && !adapter.isEnabled) {
            problems.add("Bluetooth is off. Press Retry scan to open Bluetooth settings.")
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            problems.add("Location (GPS) is off in phone settings.")
        }
        bleDetail?.let { problems.add(it) }

        if (problems.isEmpty()) {
            tvProblem.visibility = View.GONE
        } else {
            tvProblem.visibility = View.VISIBLE
            tvProblem.text = problems.joinToString("\n")
        }
    }

    // --- export ---------------------------------------------------------

    private fun shareLog() {
        val f = service?.shareableFile()
        if (f == null || !f.exists()) {
            Toast.makeText(this, "No recording yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.logs", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                // text/plain rather than a JSON Lines mime type: every mail,
                // chat and cloud target accepts it, and the file keeps its
                // .jsonl name either way.
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share ${f.name}"))
        } catch (t: Throwable) {
            Log.e(TAG, "share failed", t)
            Toast.makeText(this, "Share failed: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
