package org.trailink.gpsbridge

import android.Manifest
import android.app.Activity
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
    private lateinit var tvFix: TextView
    private lateinit var tvCounters: TextView
    private lateinit var tvLogFile: TextView
    private lateinit var tvEvents: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnExport: Button
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button

    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var service: BridgeService? = null
    private var bound = false
    private var permissionsDenied = false

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
        tvFix = findViewById(R.id.tvFix)
        tvCounters = findViewById(R.id.tvCounters)
        tvLogFile = findViewById(R.id.tvLogFile)
        tvEvents = findViewById(R.id.tvEvents)
        btnRetry = findViewById(R.id.btnRetry)
        btnExport = findViewById(R.id.btnExport)
        btnRecord = findViewById(R.id.btnRecord)
        btnStop = findViewById(R.id.btnStop)

        btnRetry.setOnClickListener { onRetryPressed() }
        btnExport.setOnClickListener { shareLog() }
        btnRecord.setOnClickListener { onRecordPressed() }
        btnStop.setOnClickListener { onStopPressed() }

        render()

        if (missingPermissions().isEmpty()) {
            startBridge()
        } else {
            requestPermissions(REQUIRED, REQ_PERMISSIONS)
        }
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

    private fun render() {
        val snap = service?.snapshot()

        if (snap == null) {
            tvState.text = if (permissionsDenied) "permission needed" else "starting"
            tvFix.text = "fix: none yet"
            tvCounters.text = "packets: sent 0 / failed 0"
            tvLogFile.text = "recording: off"
            tvEvents.text = ""
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

        tvCounters.text = "packets: sent ${snap.sentOk} / failed ${snap.sentFailed}" +
            "   seq ${snap.seq}   every ${BridgeService.SEND_INTERVAL_MS / 1000}s"

        val f = snap.logFile
        tvLogFile.text = when {
            snap.recording && f != null ->
                "recording: ${f.name}\n${snap.logLines} lines  ·  ${f.parent}"
            snap.recording -> "recording: opening file..."
            f != null -> "recording: off\nlast file: ${f.name}"
            else -> "recording: off"
        }

        btnRecord.text = if (snap.recording) "Stop recording" else "Start recording"
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
