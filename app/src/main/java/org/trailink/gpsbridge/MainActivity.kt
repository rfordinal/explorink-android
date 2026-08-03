package org.trailink.gpsbridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
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
import java.util.TimeZone
import kotlin.math.abs

/**
 * The one window. Connection state, current fix, packet counters, the log file
 * it is writing, and two buttons: retry the scan, share the log.
 *
 * Logging is on for the whole app lifetime rather than behind a toggle -- the
 * brief allows either and one less piece of state is one less thing to get
 * wrong in an unattended build. Every session gets its own file.
 */
class MainActivity : Activity(), BleLink.Listener, LocationListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 1

        /** Fixed cadence from the brief. No adaptive logic here. */
        private const val SEND_INTERVAL_MS = 5000L

        /** Ask Android for fixes faster than we send, so a send is never stale. */
        private const val LOCATION_INTERVAL_MS = 1000L

        private val REQUIRED = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private lateinit var tvState: TextView
    private lateinit var tvProblem: TextView
    private lateinit var tvFix: TextView
    private lateinit var tvCounters: TextView
    private lateinit var tvLogFile: TextView
    private lateinit var tvEvents: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnExport: Button

    private lateinit var ble: BleLink
    private lateinit var logger: SessionLogger
    private var locationManager: LocationManager? = null

    private val main = Handler(Looper.getMainLooper())

    private var lastFix: Location? = null
    private var prevFix: Location? = null
    private var lastBearingDeg = 0f
    private var lastSentAtMs = 0L

    private var seq = 0
    private var sentOk = 0
    private var sentFailed = 0

    private var bleState = BleLink.State.IDLE
    private var bleStateText = "starting"
    private var bleDetail: String? = null
    private var permissionsDenied = false
    private var running = false

    private val events = ArrayDeque<String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

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

        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        logger = SessionLogger(getExternalFilesDir(null) ?: filesDir, BuildConfig.VERSION_NAME)
        logger.open()
        ble = BleLink(this, this)

        btnRetry.setOnClickListener { onRetryPressed() }
        btnExport.setOnClickListener { shareLog() }

        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        addEvent("session started")
        render()

        if (missingPermissions().isEmpty()) {
            permissionsDenied = false
        } else {
            requestPermissions(REQUIRED, REQ_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        running = true
        logger.logEvent("app_foreground", null)
        if (missingPermissions().isEmpty()) {
            startLocation()
            ble.start()
            main.removeCallbacks(sender)
            main.postDelayed(sender, SEND_INTERVAL_MS)
        }
        render()
    }

    override fun onStop() {
        super.onStop()
        running = false
        main.removeCallbacks(sender)
        stopLocation()
        // Foreground-only by design (see the brief's non-goals): nothing keeps
        // sending with the app in the background. The BLE link is left up so a
        // quick return to the app resumes without a rescan.
        logger.logEvent("app_background", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(sender)
        try {
            unregisterReceiver(btReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterReceiver", t)
        }
        stopLocation()
        ble.stop()
        logger.logEvent("session_end", null)
        logger.close()
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
        val missing = missingPermissions()
        permissionsDenied = missing.isNotEmpty()
        logger.logEvent(
            if (permissionsDenied) "permissions_denied" else "permissions_granted",
            missing.joinToString(",").ifEmpty { null },
        )
        if (!permissionsDenied) {
            addEvent("permissions granted")
            startLocation()
            ble.start()
            main.removeCallbacks(sender)
            main.postDelayed(sender, SEND_INTERVAL_MS)
        } else {
            addEvent("permissions denied")
        }
        render()
    }

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
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        addEvent("manual retry")
        logger.logEvent("manual_retry", null)
        startLocation()
        ble.retry()
        main.removeCallbacks(sender)
        main.postDelayed(sender, SEND_INTERVAL_MS)
    }

    // --- location -------------------------------------------------------

    private fun startLocation() {
        val lm = locationManager ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        stopLocation()
        // GPS first; NETWORK as well so an indoor session (or a mock-location
        // app that feeds the network provider) still produces fixes. Every fix
        // from either provider is logged raw and the newest one is what gets
        // sent -- no filtering, per the recording rules.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, LOCATION_INTERVAL_MS, 0f, this, Looper.getMainLooper())
            } catch (t: Throwable) {
                Log.w(TAG, "requestLocationUpdates $p", t)
            }
        }
    }

    private fun stopLocation() {
        try {
            locationManager?.removeUpdates(this)
        } catch (t: Throwable) {
            Log.w(TAG, "removeUpdates", t)
        }
    }

    override fun onLocationChanged(location: Location) {
        logger.logFix(location)

        val previous = lastFix
        prevFix = previous
        lastFix = location

        // Platform bearing when the phone is actually moving; otherwise the
        // bearing between consecutive fixes, which is what a stationary or
        // bearing-less provider leaves us. Last known bearing is kept when
        // neither is usable, rather than snapping back to North.
        val speedMps = if (location.hasSpeed()) location.speed else 0f
        if (location.hasBearing() && speedMps > 0.5f) {
            lastBearingDeg = location.bearing
        } else if (previous != null && previous.distanceTo(location) >= 3f) {
            lastBearingDeg = previous.bearingTo(location)
        }
        render()
    }

    @Deprecated("required by the LocationListener interface on older APIs")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Nothing: provider status is reported through onProviderEnabled/Disabled.
    }

    override fun onProviderEnabled(provider: String) {
        addEvent("provider on: $provider")
        logger.logEvent("provider_enabled", provider)
        render()
    }

    override fun onProviderDisabled(provider: String) {
        addEvent("provider off: $provider")
        logger.logEvent("provider_disabled", provider)
        render()
    }

    // --- sending --------------------------------------------------------

    private val sender = object : Runnable {
        override fun run() {
            trySend()
            if (running) main.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    private fun trySend() {
        val fix = lastFix ?: return
        if (!ble.isConnected) return

        val nowMs = System.currentTimeMillis()
        val heading = PositionPacket.headingSector(lastBearingDeg)
        val accuracyM = if (fix.hasAccuracy()) fix.accuracy.toDouble() else 0.0
        val speedKmh = if (fix.hasSpeed()) fix.speed.toDouble() * 3.6 else 0.0
        val tzOffsetMin = TimeZone.getDefault().getOffset(nowMs) / 60000

        seq = (seq + 1) and 0xFF
        val thisSeq = seq
        val bytes = PositionPacket.build(
            latDeg = fix.latitude,
            lonDeg = fix.longitude,
            utcSeconds = nowMs / 1000L,
            tzOffsetMinutes = tzOffsetMin,
            heading = heading,
            seq = thisSeq,
            flags = 0, // no route in this app, so no off-route bit
            accuracyMetres = accuracyM,
            speedKmh = speedKmh,
        )

        ble.write(bytes) { ok, error ->
            if (ok) sentOk++ else sentFailed++
            lastSentAtMs = System.currentTimeMillis()
            logger.logPacket(
                bytes = bytes,
                ok = ok,
                seq = thisSeq,
                heading = heading,
                latDeg = fix.latitude,
                lonDeg = fix.longitude,
                accuracyM = accuracyM,
                speedKmh = speedKmh,
                error = error,
            )
            if (!ok) addEvent("write failed: ${error ?: "unknown"}")
            render()
        }
    }

    // --- BLE callbacks --------------------------------------------------

    override fun onBleState(state: BleLink.State, detail: String?) {
        bleState = state
        bleStateText = when (state) {
            BleLink.State.IDLE -> "idle"
            BleLink.State.NO_PERMISSION -> "permission needed"
            BleLink.State.BLUETOOTH_OFF -> "Bluetooth off"
            BleLink.State.SCANNING -> "scanning"
            BleLink.State.CONNECTING -> "connecting"
            BleLink.State.CONNECTED -> "connected to ${ble.connectedName ?: BleLink.DEVICE_NAME}"
            BleLink.State.DISCONNECTED -> "disconnected"
            BleLink.State.FAILED -> "failed"
        }
        bleDetail = detail
        render()
    }

    override fun onBleEvent(kind: String, message: String?, extras: Map<String, Any?>?) {
        logger.logEvent(kind, message, extras)
        addEvent(if (message != null) "$kind: $message" else kind)
        render()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    addEvent("Bluetooth on")
                    logger.logEvent("bluetooth_on", null)
                    if (running && missingPermissions().isEmpty()) ble.start()
                }

                BluetoothAdapter.STATE_OFF -> {
                    addEvent("Bluetooth off")
                    logger.logEvent("bluetooth_off", null)
                }
            }
            render()
        }
    }

    // --- UI -------------------------------------------------------------

    private fun addEvent(line: String) {
        events.addLast("${timeFmt.format(Date())}  $line")
        while (events.size > 12) events.removeFirst()
    }

    private fun render() {
        // A healthy state's detail belongs on the state line; only a broken
        // state's detail belongs in the red block below it.
        val healthy = bleState == BleLink.State.SCANNING ||
            bleState == BleLink.State.CONNECTING ||
            bleState == BleLink.State.CONNECTED
        tvState.text = if (healthy && bleDetail != null) {
            "$bleStateText\n${bleDetail}"
        } else {
            bleStateText
        }

        val problems = ArrayList<String>()
        if (permissionsDenied) {
            problems.add("Permission denied. Press Retry scan to open app settings and grant Nearby devices + Location.")
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null) {
            problems.add("No Bluetooth adapter.")
        } else if (!adapter.isEnabled) {
            problems.add("Bluetooth is off. Press Retry scan to open Bluetooth settings.")
        }
        val lm = locationManager
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            problems.add("Location (GPS) is off in phone settings.")
        }
        if (!healthy) bleDetail?.let { problems.add(it) }
        if (problems.isEmpty()) {
            tvProblem.visibility = View.GONE
        } else {
            tvProblem.visibility = View.VISIBLE
            tvProblem.text = problems.joinToString("\n")
        }

        val fix = lastFix
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
                append("\nheading sector ").append(PositionPacket.headingSector(lastBearingDeg))
                append(" (").append(String.format(Locale.US, "%.0f", lastBearingDeg)).append("°)")
                append("\nprovider ").append(fix.provider ?: "?")
                append("   age ").append(ageMs / 1000).append(" s")
                append("\nlast sent ")
                append(if (lastSentAtMs == 0L) "never" else timeFmt.format(Date(lastSentAtMs)))
            }
        }

        tvCounters.text = "packets: sent $sentOk / failed $sentFailed   seq $seq   every ${SEND_INTERVAL_MS / 1000}s"

        val f = logger.file
        tvLogFile.text = if (f == null) "log: opening..." else "log: ${f.name}\n${f.parent}"

        tvEvents.text = events.joinToString("\n")
    }

    // --- export ---------------------------------------------------------

    private fun shareLog() {
        val f = logger.file
        if (f == null || !f.exists()) {
            Toast.makeText(this, "No log file yet", Toast.LENGTH_SHORT).show()
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
            logger.logEvent("log_shared", f.name)
        } catch (t: Throwable) {
            Log.e(TAG, "share failed", t)
            Toast.makeText(this, "Share failed: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
