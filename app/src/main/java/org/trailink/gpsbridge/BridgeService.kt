package org.trailink.gpsbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The whole bridge lives here, not in the activity: BLE link, location updates,
 * the 5-second send timer, the counters and the recorder.
 *
 * It is a foreground service because the first field feedback was that sending
 * died the moment the screen locked, and holding a phone screen awake in a
 * handlebar bag is not a plan. The brief listed a background service as a
 * non-goal; that call was reversed by the person who wrote the brief.
 *
 * Foreground service type is `location` plus `connectedDevice`: it reads GPS and
 * it talks to a BLE peripheral. Started while the app is visible and with that
 * type declared, it keeps getting fixes with the screen off and **without**
 * ACCESS_BACKGROUND_LOCATION. A partial wake lock keeps the CPU up so the send
 * timer still fires in Doze.
 */
class BridgeService : Service(), BleLink.Listener, LocationListener {

    companion object {
        private const val TAG = "BridgeService"

        const val ACTION_START = "org.trailink.gpsbridge.START"
        const val ACTION_STOP = "org.trailink.gpsbridge.STOP"
        const val ACTION_START_RECORDING = "org.trailink.gpsbridge.START_REC"
        const val ACTION_STOP_RECORDING = "org.trailink.gpsbridge.STOP_REC"

        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1

        /** Fixed cadence from the brief. No adaptive logic here. */
        const val SEND_INTERVAL_MS = 5000L

        /** Ask Android for fixes faster than we send, so a send is never stale. */
        private const val LOCATION_INTERVAL_MS = 1000L

        private const val MAX_EVENTS = 12

        @Volatile
        var isRunning = false
            private set
    }

    /** Everything the one window needs, snapshotted so the UI holds no state. */
    class Snapshot(
        val bleState: BleLink.State,
        val bleDetail: String?,
        val deviceName: String?,
        val lastFix: Location?,
        val bearingDeg: Float,
        val lastSentAtMs: Long,
        val seq: Int,
        val sentOk: Int,
        val sentFailed: Int,
        val recording: Boolean,
        val logFile: File?,
        val logLines: Long,
        val events: List<String>,
    )

    interface Observer {
        fun onBridgeChanged()
    }

    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var ble: BleLink
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var logger: SessionLogger? = null
    private var lastLogFile: File? = null

    private var bleState = BleLink.State.IDLE
    private var bleDetail: String? = null
    private var lastFix: Location? = null
    private var lastBearingDeg = 0f
    private var lastSentAtMs = 0L
    private var seq = 0
    private var sentOk = 0
    private var sentFailed = 0

    private val events = ArrayDeque<String>()
    private var observer: Observer? = null

    // --- lifecycle ------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ble = BleLink(this, this)
        createChannel()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        addEvent("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
        }

        goForeground()
        acquireWakeLock()
        startLocation()
        ble.start()
        main.removeCallbacks(sender)
        main.postDelayed(sender, SEND_INTERVAL_MS)
        notifyObserver()
        // START_STICKY would resurrect the service with a null intent after a
        // low-memory kill, with no permission grants re-checked and no user
        // watching. A ride that ends in a kill should end, visibly.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacks(sender)
        try {
            unregisterReceiver(btReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterReceiver", t)
        }
        stopLocation()
        ble.stop()
        stopRecording()
        releaseWakeLock()
        super.onDestroy()
    }

    fun setObserver(o: Observer?) {
        observer = o
    }

    private fun notifyObserver() {
        observer?.onBridgeChanged()
        updateNotification()
    }

    fun snapshot(): Snapshot = Snapshot(
        bleState = bleState,
        bleDetail = bleDetail,
        deviceName = ble.connectedName,
        lastFix = lastFix,
        bearingDeg = lastBearingDeg,
        lastSentAtMs = lastSentAtMs,
        seq = seq,
        sentOk = sentOk,
        sentFailed = sentFailed,
        recording = logger != null,
        logFile = logger?.file ?: lastLogFile,
        logLines = logger?.linesWritten ?: 0L,
        events = events.toList(),
    )

    // --- recording ------------------------------------------------------

    val isRecording: Boolean
        get() = logger != null

    fun startRecording() {
        if (logger != null) return
        val dir = getExternalFilesDir(null) ?: filesDir
        val l = SessionLogger(dir, BuildConfig.VERSION_NAME)
        l.open()
        logger = l
        l.logEvent(
            "recording_start",
            null,
            mapOf(
                "ble_state" to bleState.name,
                "device" to ble.connectedName,
                "address" to ble.connectedAddress,
            ),
        )
        addEvent("recording started")
        notifyObserver()
    }

    fun stopRecording() {
        val l = logger ?: return
        l.logEvent("recording_stop", null, mapOf("lines" to l.linesWritten))
        lastLogFile = l.file
        l.close()
        logger = null
        addEvent("recording stopped")
        notifyObserver()
    }

    /** The file the Share button should offer: current recording, else the last. */
    fun shareableFile(): File? = logger?.file ?: lastLogFile

    // --- BLE ------------------------------------------------------------

    fun retry() {
        addEvent("manual retry")
        logger?.logEvent("manual_retry", null)
        startLocation()
        ble.retry()
        main.removeCallbacks(sender)
        main.postDelayed(sender, SEND_INTERVAL_MS)
        notifyObserver()
    }

    override fun onBleState(state: BleLink.State, detail: String?) {
        bleState = state
        bleDetail = detail
        notifyObserver()
    }

    override fun onBleEvent(kind: String, message: String?, extras: Map<String, Any?>?) {
        logger?.logEvent(kind, message, extras)
        addEvent(if (message != null) "$kind: $message" else kind)
        notifyObserver()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    addEvent("Bluetooth on")
                    logger?.logEvent("bluetooth_on", null)
                    ble.start()
                }

                BluetoothAdapter.STATE_OFF -> {
                    addEvent("Bluetooth off")
                    logger?.logEvent("bluetooth_off", null)
                }
            }
            notifyObserver()
        }
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
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
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
        logger?.logFix(location)

        val previous = lastFix
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
        notifyObserver()
    }

    @Deprecated("required by the LocationListener interface on older APIs")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Nothing: provider status is reported through onProviderEnabled/Disabled.
    }

    override fun onProviderEnabled(provider: String) {
        addEvent("provider on: $provider")
        logger?.logEvent("provider_enabled", provider)
        notifyObserver()
    }

    override fun onProviderDisabled(provider: String) {
        addEvent("provider off: $provider")
        logger?.logEvent("provider_disabled", provider)
        notifyObserver()
    }

    // --- sending --------------------------------------------------------

    private val sender = object : Runnable {
        override fun run() {
            trySend()
            main.postDelayed(this, SEND_INTERVAL_MS)
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
            logger?.logPacket(
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
            notifyObserver()
        }
    }

    // --- wake lock ------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // Partial only: the CPU stays up so the 5 s timer and the BLE write
        // still happen with the screen off. The screen is not held on.
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trailink:bridge")
        wl.setReferenceCounted(false)
        wl.acquire()
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "wakeLock release", t)
        }
        wakeLock = null
    }

    // --- notification ---------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "GPS bridge",
            // LOW: it is a status line, not an alert. No sound, no vibration.
            NotificationManager.IMPORTANCE_LOW,
        )
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            if (type != 0) {
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            // Android 14+ throws if the location permission was revoked between
            // launch and here. Nothing to do but log it and keep the service as
            // a plain background one, which the system will soon stop.
            Log.e(TAG, "startForeground refused", t)
        }
    }

    private fun updateNotification() {
        if (!isRunning) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "notify", t)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val recToggle = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java).setAction(
                if (isRecording) ACTION_STOP_RECORDING else ACTION_START_RECORDING
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (bleState) {
            BleLink.State.CONNECTED -> "Connected to ${ble.connectedName ?: BleLink.DEVICE_NAME}"
            BleLink.State.SCANNING -> "Scanning for ${BleLink.DEVICE_NAME}"
            BleLink.State.CONNECTING -> "Connecting"
            BleLink.State.DISCONNECTED -> "Disconnected, rescanning"
            BleLink.State.BLUETOOTH_OFF -> "Bluetooth off"
            BleLink.State.NO_PERMISSION -> "Permission needed"
            BleLink.State.FAILED -> "Link failed"
            BleLink.State.IDLE -> "Idle"
        }
        val rec = if (isRecording) "REC ${logger?.linesWritten ?: 0} lines" else "not recording"
        val text = "sent $sentOk / failed $sentFailed  ·  $rec"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (isRecording) "Stop rec" else "Record",
                    recToggle,
                ).build()
            )
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    // --- events ---------------------------------------------------------

    private fun addEvent(line: String) {
        events.addLast("${timeFmt.format(Date())}  $line")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
