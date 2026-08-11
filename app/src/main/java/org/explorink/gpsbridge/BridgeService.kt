package org.explorink.gpsbridge

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
 * the send policy, the counters and the recorder.
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
class BridgeService : Service(), BleLink.Listener, LocationListener, TileFetcher.Listener,
    FreshnessChecker.Listener {

    companion object {
        private const val TAG = "BridgeService"

        const val ACTION_START = "org.explorink.gpsbridge.START"

        /**
         * Started by [X4PresenceService] because the paired X4 began advertising,
         * i.e. the rider opened the map screen. Same work as [ACTION_START]; the
         * separate action exists so the log says who asked and so the
         * background-location check below can explain a silent ride.
         */
        const val ACTION_WAKE = "org.explorink.gpsbridge.WAKE"
        const val ACTION_STOP = "org.explorink.gpsbridge.STOP"
        const val ACTION_START_RECORDING = "org.explorink.gpsbridge.START_REC"
        const val ACTION_STOP_RECORDING = "org.explorink.gpsbridge.STOP_REC"

        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1

        /** How often the policy is re-evaluated. Cheap; the decision is not the cost. */
        private const val TICK_MS = 1000L

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
        /** Metres from the last sent position, or null before the first send. */
        val movedSinceSentM: Double?,
        /** Metres of movement that would trigger the next send. */
        val moveThresholdM: Double,
        /** Why the last packet went out: moved / heading / keepalive / first. */
        val lastSendReason: String?,
        /** One line about the last or current tile fetch, or null if there has been none. */
        val tileFetchStatus: String?,
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
    private lateinit var tileFetcher: TileFetcher
    private lateinit var tileSource: TileSource
    private lateinit var indexSource: IndexSource
    private lateinit var freshness: FreshnessChecker

    /**
     * Content ids the freshness check read out of the CDN's index, shared with
     * the fetcher so a stale tile is fetched as the version the index promised
     * and not the one the edge still has cached.
     */
    private val expectedContentIds = ExpectedContentIds()

    /**
     * The `.tib` format version the device last stated, in `NEED_TILES` or
     * `CHECK_TILES`, whichever was last. Needed for a stale-tile push, which
     * has no fetch-ask of its own to carry it.
     *
     * A device with nothing missing never sends `NEED_TILES` at all, so this
     * used to stay null for the whole connection whenever a push was
     * triggered by the freshness check instead -- the CDN source then fell
     * back to its compiled-in guess (`CdnTileSource.DEFAULT_FORMAT_VERSION`),
     * one version behind, and every such push was refused `skip nosource`
     * (found alongside the matching gap in [FreshnessChecker]).
     */
    private var deviceTileFormat: Int? = null
    /** Last line about a tile fetch, for the one window. Null until one happens. */
    private var tileFetchStatus: String? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var logger: SessionLogger? = null
    private var lastLogFile: File? = null

    private var bleState = BleLink.State.IDLE
    private var bleDetail: String? = null
    private var lastFix: Location? = null
    private var lastBearingDeg = 0f

    /** Recent accepted fixes, oldest first, for [HeadingTrend]; capped at its window size. */
    private val headingHistory = ArrayDeque<HeadingTrend.Point>()

    /** A fix that jumped too far too fast from [lastFix] to trust yet; see [FixGate]. */
    private var pendingFix: Location? = null

    /** elapsed-realtime nanos of the last GPS_PROVIDER fix, of any trust status; see [FixGate.isNetworkFixIgnorable]. */
    private var lastGpsFixElapsedNanos: Long? = null
    private var lastSentAtMs = 0L
    private var seq = 0
    private var sentOk = 0
    private var sentFailed = 0

    /** The position the last packet carried, for the distance-driven policy. */
    private var lastSentFix: Location? = null
    private var lastSentHeading = -1
    private var lastSendReason: String? = null

    private val events = ArrayDeque<String>()
    private var observer: Observer? = null

    // --- lifecycle ------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ble = BleLink(this, this)
        // Straight to the CDN. Nothing is kept on the phone: it is the pipe
        // between the CDN and the X4 (TileSource).
        tileSource = CdnTileSource()
        indexSource = CdnIndexSource()
        tileFetcher = TileFetcher(tileSource, fetchTransport, fetchScheduler, this, expectedContentIds)
        freshness = FreshnessChecker(indexSource, expectedContentIds, fetchTransport, fetchScheduler, this)
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
            ACTION_WAKE -> onWoken()
        }

        // The paired X4's address, re-read on every start: the rider can pair (or
        // forget) between two starts of this service, and an unpinned link would
        // connect to the first X4 that advertises.
        ble.pinnedAddress = CompanionWake.pairedAddress(this)

        goForeground()
        acquireWakeLock()
        startLocation()
        ble.start()
        main.removeCallbacks(sender)
        main.postDelayed(sender, TICK_MS)
        notifyObserver()
        // START_STICKY would resurrect the service with a null intent after a
        // low-memory kill, with no permission grants re-checked and no user
        // watching. A ride that ends in a kill should end, visibly.
        return START_NOT_STICKY
    }

    /**
     * The X4 opened its map screen and the OS started this service for it, with
     * nobody looking at the phone.
     *
     * The one thing that can quietly ruin this: location. A service started while
     * the app is invisible does not hold while-in-use location, so without
     * ACCESS_BACKGROUND_LOCATION the link comes up and no fix ever arrives. That
     * looks identical to bad GPS from the device's side, so it is logged as what it
     * is instead.
     */
    private fun onWoken() {
        val hasBackground = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        addEvent(if (hasBackground) "woken by the X4" else "woken by the X4 -- no background location")
        logger?.logEvent("woken", if (hasBackground) "ok" else "no_background_location")
        if (!hasBackground) {
            Log.w(TAG, "woken without ACCESS_BACKGROUND_LOCATION: the link will come up with no fixes")
        }
    }

    /**
     * Drops the BLE link so the X4 goes back to advertising, and stops scanning so
     * it is not grabbed again immediately.
     *
     * Needed because the two things fight: the X4 stops advertising the moment a
     * central connects and only resumes on disconnect
     * (`BlePositionServer.cpp`, `onDisconnect`), while Android's companion pairing
     * dialog can only offer devices it can *see advertising*. So a running bridge
     * makes the device unpairable -- measured on hardware 2026-08-11, the dialog
     * sat empty at "make sure the device is nearby" with the link up the whole
     * time.
     *
     * Paired only through [resumeLink], never automatically: the pause has to
     * outlive the dialog, which is another process.
     */
    fun pauseLinkForPairing() {
        addEvent("link released for pairing")
        logger?.logEvent("pair_pause", null)
        ble.stop()
        notifyObserver()
    }

    /** Re-reads the paired address and starts looking again. */
    fun resumeLink() {
        ble.pinnedAddress = CompanionWake.pairedAddress(this)
        addEvent("link resumed")
        logger?.logEvent("pair_resume", ble.pinnedAddress)
        ble.start()
        notifyObserver()
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
        // Before ble.stop(): the abort frame it may send needs a live link.
        freshness.stop()
        tileFetcher.stop()
        tileSource.close()
        indexSource.close()
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
        movedSinceSentM = movedSinceLastSent(),
        moveThresholdM = moveThreshold(),
        lastSendReason = lastSendReason,
        tileFetchStatus = tileFetchStatus,
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
        main.postDelayed(sender, TICK_MS)
        notifyObserver()
    }

    override fun onBleState(state: BleLink.State, detail: String?) {
        val wasConnected = bleState == BleLink.State.CONNECTED
        bleState = state
        bleDetail = detail
        // A fetch in flight is dead the moment the link is: the transfer's
        // offsets and the device's own .part file went with it.
        if (wasConnected && state != BleLink.State.CONNECTED) {
            tileFetcher.onDisconnected()
            freshness.onDisconnected()
        }
        notifyObserver()
    }

    override fun onBleEvent(kind: String, message: String?, extras: Map<String, Any?>?) {
        logger?.logEvent(kind, message, extras)
        addEvent(if (message != null) "$kind: $message" else kind)
        notifyObserver()
    }

    // --- tile fetch -----------------------------------------------------

    override fun onCommandLine(line: String) {
        // Logged whole: this channel is how the device asks for tiles, and a
        // ride log without the ask is a log that cannot explain the transfers
        // that followed it.
        logger?.logEvent("cmd_in", line, null)
        MissingList.parseNeedTiles(line)?.formatVersion?.let { deviceTileFormat = it }
        MissingList.parseCheckTiles(line)?.formatVersion?.let { deviceTileFormat = it }
        tileFetcher.onCommandLine(line)
        // Both read this channel, and their asks never overlap: NEED_TILES is
        // about tiles the device does not have, CHECK_TILES about tiles it does.
        freshness.onCommandLine(line)
    }

    override fun onTransferStatus(line: String) {
        logger?.logEvent("xfer_in", line, null)
        tileFetcher.onStatusLine(line)
    }

    override fun onFetchStarted(total: Int) {
        tileFetchStatus = "fetching 0/$total"
        addEvent("device asked for $total tiles")
        logger?.logEvent("fetch_start", "$total tiles", mapOf("total" to total))
        notifyObserver()
    }

    override fun onFetchProgress(sent: Int, skipped: Int, total: Int) {
        tileFetchStatus = "fetching $sent/$total" + if (skipped > 0) ", $skipped skipped" else ""
        notifyObserver()
    }

    override fun onFetchFinished(sent: Int, skipped: Int, total: Int, reason: String) {
        tileFetchStatus = "$sent/$total sent" +
            (if (skipped > 0) ", $skipped skipped" else "") +
            " ($reason)"
        addEvent("fetch $reason: $sent sent, $skipped skipped")
        logger?.logEvent(
            "fetch_end",
            reason,
            mapOf("sent" to sent, "skipped" to skipped, "total" to total),
        )
        notifyObserver()
    }

    override fun onStaleTilesFound(tiles: List<HeldTile>) {
        // Pushed without a further ask: this phone found them and holds their
        // expected content ids, so a round trip through the device would only
        // lose that. The transfer channel accepts an unsolicited push while the
        // map or the sync screen is up.
        tileFetcher.pushTiles(tiles.map { MissingTile(it.z, it.col, it.row, 0) }, deviceTileFormat)
    }

    override fun onCheckFinished(examined: Int, stale: Int, reason: String) {
        tileFetchStatus = when {
            stale < 0 -> "freshness unknown ($reason)"
            stale == 0 -> "$examined tiles current"
            else -> "$stale of $examined tiles out of date"
        }
        addEvent("freshness check: $reason")
        logger?.logEvent(
            "check_end",
            reason,
            mapOf("examined" to examined, "stale" to stale),
        )
        notifyObserver()
    }

    /**
     * Everything the fetcher needs from BLE, and nothing more. Written here
     * rather than handing the fetcher a [BleLink] so the fetcher stays testable
     * without a Bluetooth stack.
     */
    private val fetchTransport = object : TileFetcher.Transport {
        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            ble.writeCommand(line, done)
        }

        override fun sendFrame(frame: ByteArray, done: (Boolean, String?) -> Unit) {
            ble.writeTransferFrame(frame, done)
        }

        override fun maxChunkPayload(): Int = ble.maxChunkPayload()

        override fun setFastLink(fast: Boolean) {
            ble.requestHighPriority(fast)
        }
    }

    private val fetchScheduler = object : TileFetcher.Scheduler {
        override fun postDelayed(delayMs: Long, action: () -> Unit): TileFetcher.Scheduler.Cancellable {
            val r = Runnable { action() }
            main.postDelayed(r, delayMs)
            return object : TileFetcher.Scheduler.Cancellable {
                override fun cancel() = main.removeCallbacks(r)
            }
        }
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
        // from either provider is logged raw, per the recording rules -- but
        // which one becomes the trusted position goes through FixGate first,
        // so a wide-radius network fix racing a live GPS can't look like a
        // teleport on the map.
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

        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixElapsedNanos = location.elapsedRealtimeNanos
        } else if (location.provider == LocationManager.NETWORK_PROVIDER) {
            val msSinceGps = lastGpsFixElapsedNanos?.let {
                (location.elapsedRealtimeNanos - it) / 1_000_000
            }
            if (FixGate.isNetworkFixIgnorable(msSinceGps)) {
                logger?.logEvent("fix_network_ignored", null)
                return
            }
        }

        val pending = pendingFix
        if (pending != null) {
            pendingFix = null
            val waitedMs = (location.elapsedRealtimeNanos - pending.elapsedRealtimeNanos) / 1_000_000
            if (waitedMs > FixGate.CONFIRM_TIMEOUT_MS) {
                // Nothing arrived in time to confirm or refute it. Drop it and
                // judge this fix fresh against the position we still trust.
                logger?.logEvent("fix_jump_timeout", null)
                evaluateAndAccept(location)
                return
            }
            val distToPending = pending.distanceTo(location).toDouble()
            val distToAccepted = lastFix?.distanceTo(location)?.toDouble() ?: Double.MAX_VALUE
            if (FixGate.jumpConfirmedBy(distToPending, distToAccepted)) {
                // The phone kept going that way: it was real movement, not noise.
                logger?.logEvent("fix_jump_confirmed", null)
                acceptFix(pending)
                acceptFix(location)
            } else {
                logger?.logEvent("fix_jump_rejected", null)
                evaluateAndAccept(location)
            }
            return
        }

        evaluateAndAccept(location)
    }

    /** Accepts [location] outright, or holds it as [pendingFix] if it is too big a jump to trust yet. */
    private fun evaluateAndAccept(location: Location) {
        val previous = lastFix
        if (previous != null) {
            val distanceM = previous.distanceTo(location).toDouble()
            val dtMs = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000
            val prevAccuracyM = if (previous.hasAccuracy()) previous.accuracy.toDouble() else 0.0
            if (FixGate.isImplausibleJump(distanceM, dtMs, prevAccuracyM)) {
                pendingFix = location
                logger?.logEvent("fix_jump_suspect", null, mapOf("distance_m" to distanceM))
                notifyObserver()
                return
            }
        }
        acceptFix(location)
    }

    /** Makes [location] the trusted position: [lastFix], bearing, and the UI/notification. */
    private fun acceptFix(location: Location) {
        lastFix = location

        headingHistory.addLast(
            HeadingTrend.Point(location.latitude, location.longitude, location.elapsedRealtimeNanos)
        )
        while (headingHistory.size > HeadingTrend.WINDOW_SIZE) headingHistory.removeFirst()

        // Never the phone's own orientation -- it rides in a backpack or tank
        // bag. Last known bearing is kept when the window isn't a confident
        // trend yet, rather than snapping back to North.
        HeadingTrend.heading(headingHistory)?.let { lastBearingDeg = it.toFloat() }
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
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Metres between the last sent position and the current fix. */
    private fun movedSinceLastSent(): Double? {
        val from = lastSentFix ?: return null
        val to = lastFix ?: return null
        return from.distanceTo(to).toDouble()
    }

    private fun currentAccuracyM(): Double =
        lastFix?.let { if (it.hasAccuracy()) it.accuracy.toDouble() else 0.0 } ?: 0.0

    private fun moveThreshold(): Double = SendPolicy.moveThresholdM(currentAccuracyM())

    /** null means "stay quiet"; otherwise the reason, which goes in the log. */
    private fun sendReason(nowMs: Long): SendPolicy.Reason? {
        val hasSent = lastSentFix != null && lastSentAtMs != 0L
        return SendPolicy.decide(
            hasSent = hasSent,
            sinceLastMs = if (hasSent) nowMs - lastSentAtMs else 0L,
            movedM = movedSinceLastSent() ?: 0.0,
            accuracyM = currentAccuracyM(),
            headingChanged = PositionPacket.headingSector(lastBearingDeg) != lastSentHeading,
        )
    }

    private fun trySend() {
        val fix = lastFix ?: return
        if (!ble.isConnected) return

        val nowMs = System.currentTimeMillis()
        val reason = sendReason(nowMs) ?: return
        val movedM = movedSinceLastSent()
        val sinceLastMs = if (lastSentAtMs == 0L) -1L else nowMs - lastSentAtMs
        val heading = PositionPacket.headingSector(lastBearingDeg)
        val accuracyM = if (fix.hasAccuracy()) fix.accuracy.toDouble() else 0.0
        val speedKmh = if (fix.hasSpeed()) fix.speed.toDouble() * 3.6 else 0.0
        val altitudeM = if (fix.hasAltitude()) fix.altitude else null
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
            altitudeMetres = altitudeM,
        )

        // The policy advances on the attempt, not on the ack: a failed write
        // that reset nothing would be retried every tick against a device that
        // just told us it cannot take it.
        lastSentAtMs = nowMs
        // Copied, not aliased: the policy compares against this for up to an
        // hour and must not share an object with whatever the provider hands
        // back next.
        lastSentFix = Location(fix)
        lastSentHeading = heading
        lastSendReason = reason.logName

        ble.write(bytes) { ok, error ->
            if (ok) sentOk++ else sentFailed++
            logger?.logPacket(
                bytes = bytes,
                ok = ok,
                seq = thisSeq,
                heading = heading,
                latDeg = fix.latitude,
                lonDeg = fix.longitude,
                accuracyM = accuracyM,
                speedKmh = speedKmh,
                altitudeM = altitudeM,
                error = error,
                reason = reason.logName,
                movedM = movedM,
                sinceLastMs = sinceLastMs,
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
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "explorink:bridge")
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
            // launch and here, and the same call is refused for a wake-started
            // service that may not claim location.
            Log.e(TAG, "startForeground refused", t)
            // Retry without the location type. The BLE half -- position packets
            // from whatever fix arrives, tile sync, freshness -- needs only
            // connectedDevice, so half a bridge beats a service the system stops
            // in seconds. If this throws too there is nothing left to try.
            if (type != 0) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                    addEvent("foreground service: BLE only, no location type")
                } catch (t2: Throwable) {
                    Log.e(TAG, "startForeground refused twice", t2)
                }
            }
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
        val moved = movedSinceLastSent()
        val policy = if (moved == null) {
            "no send yet"
        } else {
            "${moved.toInt()}/${moveThreshold().toInt()} m"
        }
        val text = "sent $sentOk / failed $sentFailed  ·  $policy  ·  $rec"

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
