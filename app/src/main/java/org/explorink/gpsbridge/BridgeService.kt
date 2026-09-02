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
import kotlin.math.roundToInt

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
    FreshnessChecker.Listener, PinManager.Listener {

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

        /** App-private, and it holds one number: see [deviceTileFormat]. */
        private const val PREFS = "bridge"
        private const val PREF_TILE_FORMAT = "device_tile_format"
        const val ACTION_START_RECORDING = "org.explorink.gpsbridge.START_REC"
        const val ACTION_STOP_RECORDING = "org.explorink.gpsbridge.STOP_REC"

        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1

        /** How often the policy is re-evaluated. Cheap; the decision is not the cost. */
        private const val TICK_MS = 1000L

        /** Ask Android for fixes faster than we send, so a send is never stale. */
        private const val LOCATION_INTERVAL_MS = 1000L

        /**
         * NETWORK_PROVIDER's cadence -- deliberately far coarser than GPS's.
         * FixGate only ever listens to a network fix once GPS has been quiet
         * past [FixGate.GPS_LIVE_WINDOW_MS] (5 s), so a 1 Hz registration
         * bought nothing but a WiFi scan every second whose result was
         * discarded the whole ride. 30 s / 50 m still covers the indoor and
         * mock-location fallback this provider exists for.
         */
        private const val NETWORK_INTERVAL_MS = 30_000L
        private const val NETWORK_MIN_DISTANCE_M = 50f

        /**
         * How long the bridge keeps looking for the device with nothing connected
         * before it stops itself.
         *
         * Not a battery nicety: without it the service runs until the rider
         * remembers to press Stop, and the only thing the phone shows for it is a
         * status line that reads like it is still sending. It is not -- [trySend]
         * returns on `!ble.isConnected`, and location updates are not even
         * requested while the link is down.
         *
         * Long enough to survive a fuel stop or a dropped link; short enough that
         * a ride that ended an hour ago is not still holding a wake lock.
         * Restarting costs nothing the rider does: the companion association wakes
         * the app again the moment the device opens its map screen
         * ([X4PresenceService]).
         */
        private const val IDLE_STOP_MS = 5 * 60_000L

        private const val MAX_EVENTS = 12

        /** Enough for one whole fetch plus the check that triggered it. */
        private const val MAX_TILE_LINES = 10

        /**
         * Floor between `onTileProgress` propagations to `notifyObserver()`.
         *
         * Every chunk callback lands on the main looper, the same looper that
         * must issue the next chunk inside a ~30 ms budget at the transfer's
         * 15 ms connection interval. `notifyObserver()` rebuilds the
         * notification content and renders the observer -- see
         * [shouldPostProgress] for the decision this bounds. Not private: the
         * throttle decision itself lives in that top-level, unit-testable
         * function, which needs the same constant.
         */
        const val PROGRESS_POST_THROTTLE_MS = 250L

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
        /** The map-square story in plain words, oldest first. Empty until the device asks. */
        val tileLog: List<String>,
        /** The transfer happening right now, or null when nothing is in flight. */
        val tileProgress: TileProgress?,
    )

    /**
     * A fetch in flight, measured in what the device has acknowledged.
     *
     * Two scales, because they answer different questions: [completedSquares] of
     * [totalSquares] is "how much of this ask is left", [sentBytes] of
     * [totalBytes] is "is the current one moving at all". On a ~7 kB/s link the
     * second one is what separates a slow transfer from a dead one.
     *
     * Every number here is stated through [TileFormat], which is a port of the
     * device's own sync screen, so the panel and the phone cannot disagree.
     */
    class TileProgress(
        val z: Int,
        val col: Long,
        val row: Long,
        val sentBytes: Int,
        val totalBytes: Int,
        val completedSquares: Int,
        val skippedSquares: Int,
        val totalSquares: Int,
        /** Bytes of completed squares plus what has landed of the one in flight. */
        val movedBytes: Int,
        /** Completed squares only -- what the rate is computed from. */
        val completedBytes: Int,
        val elapsedMs: Long,
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
    private lateinit var pins: PinManager
    private lateinit var mapsetSource: MapsetSource
    private lateinit var outboxStore: OutboxStore
    private lateinit var outboxController: TileOutboxController

    /**
     * The device's pins as it last reported them, and nothing derived from them.
     *
     * The device is authoritative and this holds no copy of its own: every entry
     * here came out of a `pin list` reply, and a mutation is followed by another
     * one ([PinManager]). Null means "never asked on this link", which the pins
     * screen shows differently from an answered "none saved".
     */
    private var devicePins: List<DevicePin>? = null

    /** One line about the last pin command, for the pins screen. */
    private var pinStatus: String? = null

    /**
     * True once the device answered `pins=unavailable` on this link: its console
     * has no pin store, which means the rider is not on the map screen. Cleared by
     * the next answer that does carry pins.
     */
    private var pinsUnavailable = false

    /** The last history page read, and where it sat. Empty until the rider asks. */
    private var pinHistory: List<PinLogEntry> = emptyList()
    private var pinHistoryOffset = 0
    private var pinHistoryTotal: Int? = null
    private var pinHistoryNext: Int? = null

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
        set(value) {
            if (value == null || value == field) return
            field = value
            // Remembered across restarts, because the pre-trip planner runs with
            // no device in the room at all: the rider picks a city at home and
            // the app has to know which `/v<N>/` tree to read the index out of.
            // Without this it fell back to the compiled-in guess every cold
            // start and reported an empty world (measured on a real phone,
            // 2026-09-02 -- "0 of 26 squares available" where all 26 existed).
            //
            // The device stays authoritative: this is only ever written from
            // something a device said, and a fresh `info` overwrites it.
            runCatching {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(PREF_TILE_FORMAT, value).apply()
            }
        }

    /** What the last device to speak said it reads, or null if none ever has. */
    private fun rememberedTileFormat(): Int? = runCatching {
        val v = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_TILE_FORMAT, 0)
        if (v > 0) v else null
    }.getOrNull()
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

    /** Accuracy of the fix the last packet carried, for [SendPolicy]'s correction check. */
    private var lastSentAccuracyM = 0.0

    /**
     * True from the moment a link comes up until a fix is accepted on it. Holds
     * back the link's FIRST packet so it carries a live position rather than the
     * previous ride's ([onBleState], [acceptFix]).
     */
    private var awaitingFixSinceConnect = false

    /** Consecutive accepted fixes at or under [SendPolicy.PRECISE_ACCURACY_M]. */
    private var preciseFixStreak = 0

    /**
     * The ground metres the X4's screen diagonal currently represents, as the
     * device last stated it (`DIAG_M`, `MissingList.parseDiagonalM`). Null
     * until one is heard on this link -- an older firmware build never sends
     * it -- in which case [SendPolicy.moveThresholdM] falls back to its own
     * constant.
     */
    private var lastKnownDiagonalM: Double? = null

    private val events = ArrayDeque<String>()

    /**
     * The map-square story in plain words, newest last: what the device asked for,
     * which square arrived or did not, and how the whole thing ended.
     *
     * Separate from [events], which is a link-level trace in shorthand. Both were
     * one list before and the tile lines drowned in scan/connect noise -- a rider
     * could not tell from it when the device asked for a download or what came of
     * it, which is the one thing about tiles worth showing on a phone.
     */
    private val tileLog = ArrayDeque<String>()

    /** "on screen" or "whole list", from the ask that started the current fetch. */
    private var fetchScope = "whole list"
    private var fetchStartedAtMs = 0L

    /** Live transfer state, all of it null/zero while nothing is in flight. */
    private var tileProgress: TileProgress? = null
    private var fetchTotal = 0

    /** Last time an `onTileProgress` chunk actually reached [notifyObserver] -- see [shouldPostProgress]. */
    private var lastProgressPostMs = 0L
    private var fetchDone = 0
    private var fetchSkipped = 0
    private var fetchCompletedBytes = 0
    private var observer: Observer? = null

    /** Location updates are actually registered. Guards a re-register on every reconcile. */
    private var locationRunning = false

    /** The 1 Hz send timer is posted. Also the timer's own stop flag, see [sender]. */
    private var ticking = false

    /** An idle [stopSelf] was asked for and may still be pending on a bound client. */
    private var stopRequested = false

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
        pins = PinManager(pinTransport, fetchScheduler, this)
        mapsetSource = CdnMapsetSource()
        // The rider's ask is on disk before this line: a tile item exists
        // nowhere but that file, so it is read at service start rather than when
        // a screen happens to open (`docs/tile-outbox-format.md`).
        outboxStore = OutboxStore(OutboxStore.dirIn(filesDir))
        outboxController = TileOutboxController(
            outbox = outboxStore.load(),
            transport = outboxTransport,
            mapsetSource = mapsetSource,
            indexSource = indexSource,
            pusher = fetcherAsPusher,
            store = object : TileOutboxController.Store {
                override fun save(outbox: TileOutbox) = outboxStore.save(outbox)
            },
            scheduler = fetchScheduler,
            gate = outboxGate,
            listener = outboxListener,
        )
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
        ble.start()
        // No GPS, no wake lock, no timer until there is something on the other end
        // of the link -- see [updatePowerState].
        updatePowerState()
        armIdleStop()
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
        addEvent(if (hasBackground) "woken by the device" else "woken by the device -- no background location")
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

    /**
     * Unpairs: drops the companion association, so the OS stops watching for that
     * X4 and this app stops being woken by it.
     *
     * Everything the association implied goes with it, in one place rather than
     * three: the pin, the remembered address behind a direct reconnect, and the
     * live link to the device being forgotten. Then a clean restart, unpinned, so
     * the rider is left with a working bridge on the old first-match rules instead
     * of nothing.
     */
    fun forgetPairing() {
        CompanionWake.forget(this)
        ble.pinnedAddress = null
        ble.clearRememberedAddress()
        addEvent("pairing forgotten")
        logger?.logEvent("pair_forget", null)
        ble.retry()
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
        stopTicker()
        cancelIdleStop()
        try {
            unregisterReceiver(btReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterReceiver", t)
        }
        stopLocation()
        // Before ble.stop(): the abort frame it may send needs a live link.
        freshness.stop()
        tileFetcher.stop()
        pins.stop()
        outboxController.stop()
        tileSource.close()
        indexSource.close()
        mapsetSource.close()
        ble.stop()
        stopRecording()
        releaseWakeLock()
        super.onDestroy()
    }

    fun setObserver(o: Observer?) {
        observer = o
    }

    /**
     * Drops [o] only if it is still the registered observer.
     *
     * Load-bearing with two windows: Android starts the second one before it stops
     * the first, so the pins screen registers and then the main screen's `onStop`
     * runs. A bare `setObserver(null)` there cleared the *new* observer, and the
     * pins screen went blind to everything after its first render -- every reply it
     * had just asked for landed with nothing listening.
     */
    fun clearObserver(o: Observer) {
        if (observer === o) observer = null
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
        tileLog = tileLog.toList(),
        tileProgress = tileProgress,
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
        // A ride recorder needs fixes whether or not the device is on the other
        // end -- it is the one thing the rider asked for by hand.
        updatePowerState()
        notifyObserver()
    }

    fun stopRecording() {
        val l = logger ?: return
        l.logEvent("recording_stop", null, mapOf("lines" to l.linesWritten))
        lastLogFile = l.file
        l.close()
        logger = null
        addEvent("recording stopped")
        updatePowerState()
        notifyObserver()
    }

    /** The file the Share button should offer: current recording, else the last. */
    fun shareableFile(): File? = logger?.file ?: lastLogFile

    // --- BLE ------------------------------------------------------------

    fun retry() {
        addEvent("manual retry")
        logger?.logEvent("manual_retry", null)
        ble.retry()
        armIdleStop()
        notifyObserver()
    }

    override fun onBleState(state: BleLink.State, detail: String?) {
        val wasConnected = bleState == BleLink.State.CONNECTED
        bleState = state
        bleDetail = detail
        val isConnected = state == BleLink.State.CONNECTED
        // A fetch in flight is dead the moment the link is: the transfer's
        // offsets and the device's own .part file went with it.
        if (wasConnected && !isConnected) {
            tileFetcher.onDisconnected()
            freshness.onDisconnected()
            pins.onDisconnected()
            outboxController.onDisconnected()
            // What the device reported belonged to that link. Keeping it would show
            // the rider a list they could press Delete on, against a device that is
            // not there -- and after a reconnect it may not be on the map screen at
            // all, which is where pins live.
            devicePins = null
            pinsUnavailable = false
            // A deferred ask belonged to this connection's conversation. A
            // reconnected device re-asks (NEED_TILES/CHECK_TILES fire again on
            // resubscribe), so replaying a stale one later would answer a
            // question nobody is asking anymore.
            deferredAsk = null
        }
        if (isConnected != wasConnected) {
            updatePowerState()
            if (isConnected) {
                cancelIdleStop()
                undoStopRequest()
                // The send policy starts over on every link, which is what
                // SendPolicy.Reason.FIRST means -- "nothing has been sent yet on
                // this link". Without this reset the state survived the
                // disconnect, so a parked rider opening the map screen got no
                // packet at all: nothing had moved, the 7 s floor was long past,
                // and the next reason to send was the one-hour keepalive. The
                // device sat on the fix off its card until the rider rode 50 m.
                // One packet per reconnect is the cost, and the device needs it:
                // its BLE server only exists while the map screen is up
                // (MapActivity::onEnter), so a reconnect *is* the rider asking
                // where they are.
                lastSentFix = null
                lastSentAtMs = 0L
                lastSentHeading = -1
                lastSentAccuracyM = 0.0
                // ...but not out of whatever `lastFix` still holds. With the link
                // down and no recording running, GPS is off ([updatePowerState]
                // asks for it on `CONNECTED || isRecording`), so after a break
                // `lastFix` is the last fix of the previous ride -- hours old and
                // kilometres away. That is a worse answer than the fix the device
                // already has off its card, so the FIRST packet waits for a fix
                // accepted on this link ([acceptFix]).
                //
                // A rider who started a recording by hand had GPS running the
                // whole time, so their `lastFix` is fresh and this costs one 1 Hz
                // tick for nothing. Harmless, and not worth a second code path.
                awaitingFixSinceConnect = true
                // The persisted queue drains itself, with the rider re-picking
                // nothing. That is the whole point of persisting it: a city is
                // more connections than one, and a rider who has to press
                // Continue on each of them has a queue in name only.
                outboxController.onConnected()
            } else {
                armIdleStop()
            }
        }
        notifyObserver()
    }

    // --- what costs battery ------------------------------------------------

    /**
     * The one place that decides whether this service is spending power, and the
     * only thing it asks is whether there is work: a live link, or a recording the
     * rider started by hand.
     *
     * Everything expensive hangs off that answer together, because separately they
     * drifted -- GPS at 1 Hz, a partial wake lock and a 1 Hz timer all ran from
     * the first `onStartCommand` until the rider pressed Stop, including the hours
     * after a ride when the device was off and the link was a scan that would
     * never hit. Nothing read a fix in that state ([trySend] returns on
     * `!ble.isConnected`), so all of it was pure drain.
     *
     * What is left running with no work: the BLE scan (throttled to
     * `SCAN_MODE_LOW_POWER` after the first seconds, see [BleLink]) and the
     * [idleStop] timer that ends the service five minutes in.
     */
    private fun updatePowerState() {
        val working = bleState == BleLink.State.CONNECTED || isRecording
        if (working) {
            acquireWakeLock()
            startLocation()
            startTicker()
        } else {
            // Order matters on the way down: stop the things that would ask for the
            // CPU before letting the CPU sleep.
            stopTicker()
            stopLocation()
            releaseWakeLock()
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        main.postDelayed(sender, TICK_MS)
    }

    private fun stopTicker() {
        ticking = false
        main.removeCallbacks(sender)
    }

    // --- idle stop --------------------------------------------------------

    /**
     * Stops the whole service after [IDLE_STOP_MS] with no link, unless a
     * recording is running -- a ride log is the one thing worth keeping alive
     * without the device, and it is the rider who started it.
     */
    private val idleStop = Runnable {
        if (bleState == BleLink.State.CONNECTED) return@Runnable
        if (isRecording) {
            // Re-armed rather than cancelled: the moment the rider stops
            // recording, the idle clock should still be running.
            armIdleStop()
            return@Runnable
        }
        addEvent("idle: no device for ${IDLE_STOP_MS / 60_000} min, stopping")
        logger?.logEvent("idle_stop", null)
        stopRequested = true
        stopSelf()
    }

    private fun armIdleStop() {
        main.removeCallbacks(idleStop)
        main.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun cancelIdleStop() {
        main.removeCallbacks(idleStop)
    }

    /**
     * Takes back an idle [stopSelf] that has not landed yet, because the device
     * came back.
     *
     * `stopSelf()` on a service with a bound client does not destroy it -- it
     * marks it to be destroyed as soon as the last client unbinds. `MainActivity`
     * binds while it is on screen, so the sequence "rider has the app open, X4
     * away five minutes, X4 comes back, rider closes the app" would otherwise kill
     * a working bridge in the middle of a ride. A fresh start command clears the
     * mark; there is no other way to withdraw it.
     */
    private fun undoStopRequest() {
        if (!stopRequested) return
        stopRequested = false
        try {
            startForegroundService(
                Intent(this, BridgeService::class.java).setAction(ACTION_START)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not withdraw the idle stop", t)
        }
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
        // A one-shot value, not a listing -- captured here, ahead of the
        // conversation gate below, so it is never deferred alongside a
        // NEED_TILES/CHECK_TILES ask.
        MissingList.parseDiagonalM(line)?.let { lastKnownDiagonalM = it }

        // One conversation at a time on this channel.
        //
        // Both state machines read every line here, and a listing ends on a plain
        // `OK`, so two open conversations means each one can be ended by the
        // other's terminator. That is not theory: measured on hardware
        // 2026-08-11, the sync screen sent CHECK_TILES and NEED_TILES 15 ms
        // apart, this app answered both, and the fetcher read the 20-tile list as
        // empty -- "list complete: 0 tiles of 20", nothing pushed, no skips sent,
        // and the device left showing 20 rows of "waiting" with no explanation.
        //
        // The device now serializes its asks too, but this side must not depend
        // on that: an older or a differently-timed build would put the app right
        // back in the same hole. A deferred ask is replayed when the channel is
        // free (onFetchFinished / onCheckFinished).
        val needTiles = MissingList.parseNeedTiles(line) != null
        val checkTiles = MissingList.parseCheckTiles(line) != null
        if (needTiles && freshness.phase != FreshnessChecker.Phase.IDLE) {
            addEvent("ask deferred: a freshness check is still running")
            deferredAsk = line
            return
        }
        if (checkTiles && tileFetcher.phase != TileFetcher.Phase.IDLE) {
            addEvent("ask deferred: a fetch is still running")
            deferredAsk = line
            return
        }
        // A pin command is a conversation on this same channel and ends on the
        // same plain `OK`, so it counts here exactly like the other two. The pins
        // screen holds the channel for at most one command at a time and only
        // while the rider is on it, so the deferral it causes is short.
        if ((needTiles || checkTiles) && pins.busy) {
            addEvent("ask deferred: a pin command is still running")
            deferredAsk = line
            return
        }
        // The outbox's `info` and `push <n>` are conversations on this same
        // channel and end on the same plain `OK`, so they count here exactly
        // like the other three. It holds the channel for one command at a time,
        // never across the whole batch: [TileOutboxController.busy] covers the
        // scan too, but a scan is byte-range reads of the CDN and no BLE, so the
        // deferral it causes is seconds, not the twenty minutes the transfer
        // takes.
        if ((needTiles || checkTiles) && outboxController.busy) {
            addEvent("ask deferred: a map-area round is still running")
            deferredAsk = line
            return
        }

        tileFetcher.onCommandLine(line)
        freshness.onCommandLine(line)
        pins.onCommandLine(line)
        outboxController.onCommandLine(line)
    }

    /**
     * An ask that arrived while the other conversation held the channel. At most
     * one: the device only has two kinds of ask, so a second deferral would mean
     * the same kind twice, and the newer one describes the device's state better.
     */
    private var deferredAsk: String? = null

    /** Runs a deferred ask once both state machines are idle again. */
    private fun runDeferredAsk() {
        val line = deferredAsk ?: return
        if (tileFetcher.phase != TileFetcher.Phase.IDLE) return
        if (freshness.phase != FreshnessChecker.Phase.IDLE) return
        if (pins.busy) return
        // The outbox counts here for the same reason it counts in
        // [onCommandLine]: replaying the ask into a live `info` or `push`
        // conversation is the collision, only from the other side.
        if (outboxController.busy) return
        deferredAsk = null
        addEvent("running the deferred ask")
        onCommandLine(line)
    }

    /**
     * The command channel just came free.
     *
     * Order is deliberate: the device's own deferred ask goes first, because it
     * describes what the rider is looking at right now, and the pre-trip queue
     * has all the time in the world. [TileOutboxController.startDraining] is a
     * no-op when there is nothing due, so this cannot loop.
     */
    private fun onChannelFree() {
        runDeferredAsk()
        if (deferredAsk == null) outboxController.startDraining()
    }

    override fun onTransferStatus(line: String) {
        logger?.logEvent("xfer_in", line, null)
        tileFetcher.onStatusLine(line)
    }

    override fun onFetchScope(viewportOnly: Boolean) {
        fetchScope = if (viewportOnly) "on screen" else "whole list"
    }

    override fun onFetchStarted(total: Int) {
        tileFetchStatus = "fetching 0/$total"
        fetchStartedAtMs = System.currentTimeMillis()
        fetchTotal = total
        fetchDone = 0
        fetchSkipped = 0
        fetchCompletedBytes = 0
        tileProgress = null
        lastProgressPostMs = 0L
        addEvent("device asked for $total tiles")
        addTileLine("device asked for $total ${squares(total)} ($fetchScope)")
        logger?.logEvent("fetch_start", "$total tiles", mapOf("total" to total))
        notifyObserver()
    }

    override fun onFetchProgress(sent: Int, skipped: Int, total: Int) {
        tileFetchStatus = "fetching $sent/$total" + if (skipped > 0) ", $skipped skipped" else ""
        notifyObserver()
    }

    override fun onTileSending(z: Int, col: Long, row: Long, bytes: Int, crc32: Long) {
        outboxController.onTileSending(z, col, row, bytes, crc32)
    }

    override fun onTileReceipt(z: Int, col: Long, row: Long, bytes: Int, crc32: Long) {
        outboxController.onTileReceipt(z, col, row, bytes, crc32)
    }

    override fun onTileProgress(z: Int, col: Long, row: Long, sentBytes: Int, totalBytes: Int) {
        outboxController.onTileProgress(z, col, row, sentBytes, totalBytes)
        // Timed from the start of the whole fetch, not of this square, because that
        // is what the device's own summary does -- see TileFormat.
        tileProgress = TileProgress(
            z = z,
            col = col,
            row = row,
            sentBytes = sentBytes,
            totalBytes = totalBytes,
            completedSquares = fetchDone,
            skippedSquares = fetchSkipped,
            totalSquares = fetchTotal,
            movedBytes = fetchCompletedBytes + sentBytes,
            completedBytes = fetchCompletedBytes,
            elapsedMs = System.currentTimeMillis() - fetchStartedAtMs,
        )
        // The state above is always kept current -- only the UI propagation
        // is rate-limited. This callback runs on the main looper at the
        // transfer's chunk interval (~15 ms); notifyObserver() rebuilds the
        // notification content and renders the observer, which does not fit
        // that budget every chunk. Terminal states (done/fail/finish) call
        // notifyObserver() directly from their own handlers below, not
        // through here, so they are never subject to this throttle.
        val now = System.currentTimeMillis()
        if (!shouldPostProgress(now, lastProgressPostMs, terminal = false)) return
        lastProgressPostMs = now
        notifyObserver()
    }

    override fun onTileDone(z: Int, col: Long, row: Long, bytes: Int, ok: Boolean, detail: String) {
        // Only the failures: a success is the outbox's [onTileReceipt], which
        // is the only thing that makes a tile sent. Routing `ok` here too would
        // be a second, weaker path to the same state -- exactly what the receipt
        // law forbids.
        if (!ok) outboxController.onTileSkipped(z, col, row, detail)
        if (ok) {
            fetchDone++
            fetchCompletedBytes += bytes
        } else {
            fetchSkipped++
        }
        tileProgress = null
        // One line per square, with its own name on it. The counters say how many;
        // only this says which, and a rider parked at the edge of coverage needs
        // the difference to tell "my map is short here" from "the link is bad".
        val where = "z$z ${col}/${row}"
        addTileLine(
            if (ok) "$where  ${TileFormat.bytes(bytes)}  arrived"
            else "$where  not available ($detail)"
        )
        notifyObserver()
    }

    override fun onFetchFinished(sent: Int, skipped: Int, total: Int, reason: String) {
        // A no-op unless this fetch was the outbox's own batch; the controller
        // checks its own phase rather than this side guessing whose fetch it was.
        outboxController.onPushFinished(reason)
        tileFetchStatus = "$sent/$total sent" +
            (if (skipped > 0) ", $skipped skipped" else "") +
            " ($reason)"
        addEvent("fetch $reason: $sent sent, $skipped skipped")
        tileProgress = null
        val secs = ((System.currentTimeMillis() - fetchStartedAtMs) / 1000L).coerceAtLeast(0)
        addTileLine(
            "finished: $sent of $total arrived" +
                (if (skipped > 0) ", $skipped not available" else "") +
                ", ${secs}s" +
                (if (reason != "done") " -- $reason" else "")
        )
        logger?.logEvent(
            "fetch_end",
            reason,
            mapOf("sent" to sent, "skipped" to skipped, "total" to total),
        )
        notifyObserver()
        onChannelFree()
    }

    override fun onCheckStarted(count: Int) {
        addTileLine("device asked: are my $count ${squares(count)} still current?")
        notifyObserver()
    }

    override fun onStaleTilesFound(tiles: List<HeldTile>) {
        addTileLine("${tiles.size} ${squares(tiles.size)} out of date -- sending new ones")
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
        addTileLine(
            when {
                // `unknown` is not a result and must not read like one -- the phone
                // could not reach the map server, so nothing was learned.
                stale < 0 -> "could not check ($reason) -- will try again later"
                stale == 0 -> "checked $examined ${squares(examined)}: all current"
                else -> "checked $examined ${squares(examined)}: $stale out of date"
            }
        )
        logger?.logEvent(
            "check_end",
            reason,
            mapOf("examined" to examined, "stale" to stale),
        )
        notifyObserver()
        onChannelFree()
    }

    // --- pins -----------------------------------------------------------

    /** Everything the pins screen needs, snapshotted so it holds no state. */
    class PinsSnapshot(
        /**
         * The device's pins, or null when this link has never answered. Null and
         * empty read differently to a rider: "not asked yet" against "none saved".
         */
        val pins: List<DevicePin>?,
        /** A pin command is in flight or queued. */
        val busy: Boolean,
        /** The last thing that happened, in words. Null before anything has. */
        val status: String?,
        /** The device answered `pins=unavailable`: it is not on the map screen. */
        val unavailable: Boolean,
        val connected: Boolean,
        /** The phone's own last fix -- what "save here" would use. */
        val phoneFix: Location?,
        val history: List<PinLogEntry>,
        val historyOffset: Int,
        val historyTotal: Int?,
        /** Where the next (older) history page starts, or null at the end. */
        val historyNext: Int?,
        /** True while the tile channel is busy, which is when a pin command is refused. */
        val tilesBusy: Boolean,
    )

    fun pinsSnapshot(): PinsSnapshot = PinsSnapshot(
        pins = devicePins,
        busy = pins.busy,
        status = pinStatus,
        unavailable = pinsUnavailable,
        connected = bleState == BleLink.State.CONNECTED,
        phoneFix = lastFix,
        history = pinHistory,
        historyOffset = pinHistoryOffset,
        historyTotal = pinHistoryTotal,
        historyNext = pinHistoryNext,
        tilesBusy = tileChannelBusy(),
    )

    // --- the pre-trip map-area outbox -------------------------------------

    /**
     * Everything the map-area screen needs, snapshotted so the UI holds no
     * state -- the same contract [PinsSnapshot] keeps.
     *
     * Every number in here is already computed by the time it is read: the
     * screen repaints on `onTileProgress`, which fires once per acknowledged
     * chunk, and a snapshot that did work per field would be doing it at the
     * transfer's chunk interval.
     */
    class OutboxSnapshot(
        val connected: Boolean,
        /**
         * What the device last said it is. Null when nothing has answered on
         * this link -- which reads differently from "it is on the map screen",
         * and the screen says so.
         */
        val deviceScreen: DeviceInfo.Screen?,
        /** Why a batch cannot start, or null. Goes straight onto the screen. */
        val blocker: String?,
        val phase: TileOutboxController.Phase,
        /** The last thing that happened, in words. Null before anything has. */
        val status: String?,
        val paused: Boolean,
        val totals: TileOutbox.Totals,
        val zones: List<ZoneRow>,
        /** The square on the wire right now, or null. */
        val current: TileOutboxController.InFlight?,
        /** Measured on this run's batches. Null before a square has been confirmed. */
        val bytesPerSecond: Double?,
        val etaSeconds: Long?,
        /**
         * The queue on disk could not be read, in words. Null when it could.
         *
         * Said out loud because an empty queue after damage looks exactly like
         * an empty queue after a fresh install, and only the loader knows which
         * it was (`docs/tile-outbox-format.md`).
         */
        val queueLost: String?,
    )

    class ZoneRow(val zone: TileZone, val totals: TileOutbox.Totals)

    fun outboxSnapshot(): OutboxSnapshot = OutboxSnapshot(
        connected = bleState == BleLink.State.CONNECTED,
        deviceScreen = outboxController.device?.screen,
        blocker = outboxController.blocker ?: outboxBlocker(),
        phase = outboxController.phase,
        status = outboxController.status,
        paused = outboxController.paused,
        totals = outboxController.totals(),
        zones = outboxController.zones.map { ZoneRow(it, outboxController.zoneTotals(it.zoneId)) },
        current = outboxController.inFlight(),
        bytesPerSecond = outboxController.bytesPerSecond(),
        etaSeconds = outboxController.etaSeconds(),
        queueLost = when (val load = outboxStore.lastLoad) {
            is OutboxJson.Load.Damaged -> "the saved queue could not be read (${load.why})"
            is OutboxJson.Load.UnknownVersion ->
                "the saved queue is version ${load.version}, which this app does not read"
            else -> null
        },
    )

    /** What stops a batch before it has even been attempted, in the rider's words. */
    private fun outboxBlocker(): String? = when {
        bleState != BleLink.State.CONNECTED -> "not connected to the device"
        outboxController.paused -> "paused"
        freshness.phase != FreshnessChecker.Phase.IDLE -> "a freshness check is running"
        pins.busy -> "a pin command is running"
        tileFetcher.phase != TileFetcher.Phase.IDLE &&
            outboxController.phase != TileOutboxController.Phase.PUSHING ->
            "the device is fetching squares of its own"
        else -> null
    }

    /**
     * Queues a box around a point. Returns the new zone's id.
     *
     * The tile list is computed inside the controller, not here: the label, the
     * side and the items all describe one decision, and a caller that passed its
     * own list could store a box that is not the box the label names.
     */
    fun outboxQueueZone(latE7: Int, lonE7: Int, sideKm: Int, label: String): String =
        outboxController.queueZone(latE7, lonE7, sideKm, label)

    fun outboxDropZone(zoneId: String) = outboxController.dropZone(zoneId)

    /** Drops every zone with nothing left to send. Receipts are kept. */
    fun outboxClearFinished(): Int = outboxController.dropFinishedZones()

    /** Starts draining now, or returns the reason it could not. */
    fun outboxStartDraining(): String? = outboxController.startDraining()

    fun outboxPause() = outboxController.pause()

    /**
     * What a box would cost, read off the CDN index. Needs no device and no link.
     *
     * The device's own `.tib` format is passed when it is known, because the
     * index lives under the same `/v<N>/` prefix as the tiles it describes.
     */
    fun outboxPlan(
        latDeg: Double,
        lonDeg: Double,
        sideKm: Double,
        done: (TileOutboxController.Plan) -> Unit,
    ) = outboxController.plan(
        latDeg,
        lonDeg,
        sideKm,
        // This run's answer first, then what any earlier device said. Planning
        // deliberately needs no link ([TileOutboxController.plan]), so on a cold
        // start with the device in a drawer there is nothing else to go on.
        deviceTileFormat ?: outboxController.device?.tileFormat ?: rememberedTileFormat(),
        done,
    )

    fun outboxCancelPlan() = outboxController.cancelPlan()

    private fun tileChannelBusy(): Boolean =
        tileFetcher.phase != TileFetcher.Phase.IDLE ||
            freshness.phase != FreshnessChecker.Phase.IDLE ||
            // The pre-trip outbox is the fourth conversation on this channel.
            // Named here as well as in [onCommandLine] because this is what a
            // pin command is refused against, and a pin sent across an `info`
            // would be answered by that reply's terminator.
            outboxController.busy

    /**
     * Why a pin command cannot be sent right now, or null when it can.
     *
     * The tile conversations own the same channel and end on the same `OK`, so a
     * pin command sent across one of them would be answered by its terminator
     * (the collision measured 2026-08-11, [onCommandLine]). A transfer also has
     * the rider's data and a fast connection interval riding on it, and it is not
     * this screen's place to interrupt one.
     */
    private fun pinBlocker(): String? = when {
        bleState != BleLink.State.CONNECTED -> "not connected to the device"
        tileChannelBusy() -> "map squares are transferring -- try again in a moment"
        else -> null
    }

    /** Reads the device's pins. Returns the reason it could not ask, or null. */
    fun pinsRefresh(): String? {
        pinBlocker()?.let { return it }
        pins.refresh()
        return null
    }

    /**
     * Saves a pin at the phone's own last fix.
     *
     * The phone's position, deliberately, not the device's: the device's is this
     * phone's last *sent* packet, which is up to one send interval behind
     * ([SendPolicy]), and the rider pressing this button means "here, now".
     */
    fun pinsSaveHere(key: String): String? {
        pinBlocker()?.let { return it }
        val fix = lastFix ?: return "no GPS fix on the phone yet"
        return pinsSaveAt(
            key,
            (fix.latitude * 1e7).roundToInt(),
            (fix.longitude * 1e7).roundToInt(),
        )
    }

    /**
     * Saves a pin at a coordinate the rider chose. Returns the reason it could
     * not be sent, or null.
     *
     * The UTC second comes from the phone's clock, always: the device has no RTC
     * and a pin it saves alone carries `0` for "time unknown"
     * (`firmware/explorink/docs/pins.md`). Filling that field is the cheapest
     * thing the phone contributes to this feature.
     */
    fun pinsSaveAt(key: String, latE7: Int, lonE7: Int): String? {
        pinBlocker()?.let { return it }
        pins.save(key, latE7, lonE7, System.currentTimeMillis() / 1000L)
        return null
    }

    fun pinsDelete(key: String): String? {
        pinBlocker()?.let { return it }
        pins.delete(key)
        return null
    }

    /** One page of the device's pin history, newest first. */
    fun pinsHistory(offset: Int): String? {
        pinBlocker()?.let { return it }
        pins.history(offset)
        return null
    }

    override fun onPins(pins: List<DevicePin>) {
        devicePins = pins
        pinsUnavailable = false
        pinStatus = if (pins.isEmpty()) "no pins saved on the device" else "${pins.size} pins"
        logger?.logEvent("pins_list", null, mapOf("count" to pins.size))
        notifyObserver()
    }

    override fun onPinHistory(
        records: List<PinLogEntry>,
        offset: Int,
        total: Int?,
        nextOffset: Int?,
    ) {
        pinHistory = records
        pinHistoryOffset = offset
        pinHistoryTotal = total
        pinHistoryNext = nextOffset
        pinsUnavailable = false
        notifyObserver()
    }

    override fun onPinWrite(key: String, deleting: Boolean, ok: Boolean, reason: String?) {
        val what = if (deleting) "delete" else "save"
        val label = PinKinds.labelFor(key)
        pinStatus = if (ok) {
            "$label ${if (deleting) "deleted" else "saved"}"
        } else {
            "could not $what $label: ${reason ?: "unknown reason"}"
        }
        addEvent("pin $what $key: ${if (ok) "ok" else reason}")
        logger?.logEvent(
            "pin_write",
            key,
            mapOf("delete" to deleting, "ok" to ok, "reason" to reason),
        )
        notifyObserver()
    }

    override fun onPinsUnavailable() {
        pinsUnavailable = true
        // Not an empty list: the device cannot answer, and overwriting what it last
        // reported would tell the rider their pins are gone.
        pinStatus = "the device is not on the map screen"
        notifyObserver()
    }

    override fun onPinsError(reason: String) {
        pinStatus = reason
        addEvent("pins: $reason")
        notifyObserver()
    }

    override fun onPinsBusyChanged() {
        notifyObserver()
        // The channel may have just come free for an ask that arrived mid-command.
        if (!pins.busy) onChannelFree()
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

    /**
     * The command channel, for pins. Separate from [fetchTransport] rather than
     * reusing it: pins send console lines and nothing else, and a transport that
     * also carried frames and the link-priority switch would let a pin command
     * change the connection interval by accident.
     */
    private val pinTransport = object : PinManager.Transport {
        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            ble.writeCommand(line, done)
        }
    }

    /**
     * The command channel, for the outbox's `info` and `push <n>`. A third
     * transport for the same reason [pinTransport] is a second one: these send
     * console lines and nothing else, and a transport that also carried frames
     * and the link-priority switch would let an `info` change the connection
     * interval by accident.
     */
    private val outboxTransport = object : TileOutboxController.Transport {
        override fun sendCommand(line: String, done: (Boolean, String?) -> Unit) {
            ble.writeCommand(line, done)
        }
    }

    /**
     * The one pusher, named for the outbox.
     *
     * Deliberately the very same [tileFetcher] the device's own asks go through,
     * not a second instance: one sender at a time on the transfer characteristic
     * is the rule, because "a status line says nothing about which transfer it
     * is for". Its `phase` is also what [tileChannelBusy] already reads, so a
     * pre-trip batch blocks a pin command and a freshness check for free.
     */
    private val fetcherAsPusher = object : TileOutboxController.Pusher {
        override val idle: Boolean get() = tileFetcher.phase == TileFetcher.Phase.IDLE

        override fun pushTiles(tiles: List<MissingTile>, formatVersion: Int?) {
            tileFetcher.pushTiles(tiles, formatVersion)
        }
    }

    /**
     * Whether the outbox may open a conversation right now.
     *
     * The other half of [tileChannelBusy], which reports the outbox to everybody
     * else. Both halves are needed: `info` and `push <n>` end on the same plain
     * `OK` as `missing`, `have` and every `pin` command, and two open
     * conversations mean each can be ended by the other's terminator -- measured
     * on hardware 2026-08-11 ([onCommandLine]).
     *
     * It deliberately does **not** consult [tileFetcher]: the outbox pushes
     * *through* it, so a batch of its own would be blocked by itself. The
     * controller asks its [TileOutboxController.Pusher] whether it is idle
     * instead, which is the same question asked of the right object.
     */
    private val outboxGate = object : TileOutboxController.Gate {
        override fun blocker(): String? = when {
            bleState != BleLink.State.CONNECTED -> "not connected to the device"
            freshness.phase != FreshnessChecker.Phase.IDLE -> "a freshness check is running"
            pins.busy -> "a pin command is running"
            deferredAsk != null -> "the device has an ask waiting"
            else -> null
        }
    }

    private val outboxListener = object : TileOutboxController.Listener {
        override fun onOutboxChanged() {
            // `info` is the only place a device with nothing missing ever states
            // its tile format, and the pre-trip planner needs that number with
            // no device present at all. Copying it here puts it through
            // [deviceTileFormat]'s setter, which is what remembers it across
            // restarts.
            outboxController.device?.tileFormat?.let { deviceTileFormat = it }
            notifyObserver()
        }

        override fun onRoundFinished(reason: String) {
            addTileLine("map area: $reason")
            logger?.logEvent("outbox_round", reason)
            notifyObserver()
            // Load-bearing, and not covered by [onFetchFinished]: a round that
            // is refused at `info` -- the device on its map screen, which is the
            // common case -- never reaches the pusher, so no fetch ever finishes
            // to replay an ask that was deferred while this round held the
            // channel. Without this the device would wait for its own
            // NEED_TILES to be answered until something unrelated freed the
            // channel.
            //
            // Deliberately [runDeferredAsk] and not [onChannelFree]: this runs
            // from inside the round's own teardown, and starting the next round
            // from there would re-enter the class that is still unwinding.
            runDeferredAsk()
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
                    // Not just a log line: the stack often never reports the
                    // disconnect when the adapter dies, so without this the link
                    // stays CONNECTED/SCANNING in name, holds a dead gatt, and the
                    // STATE_ON start() below returns on that stale state.
                    ble.onAdapterOff()
                    // onAdapterOff's state change already runs updatePowerState via
                    // onBleState; called again because that path only fires on a
                    // change of "connected", and GPS must be off either way.
                    updatePowerState()
                }
            }
            notifyObserver()
        }
    }

    // --- location -------------------------------------------------------

    private fun startLocation() {
        if (locationRunning) return
        val lm = locationManager ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Claim the type before the first fix, not at service start: the claim is
        // for work about to happen. locationRunning flips first so the mask
        // postForeground() computes includes LOCATION -- see [postForeground].
        locationRunning = true
        postForeground()
        // GPS at the recording rate -- every fix from it is logged raw and is
        // the provider FixGate trusts whenever it's live (FixGate.kt:59).
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, this, Looper.getMainLooper()
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestLocationUpdates ${LocationManager.GPS_PROVIDER}", t)
        }
        // NETWORK as well, so an indoor session (or a mock-location app that
        // feeds the network provider) still produces fixes -- but only as a
        // fallback for when GPS goes quiet past FixGate.GPS_LIVE_WINDOW_MS
        // (5 s), not a second live track. At 1 Hz this provider drove a WiFi
        // scan every second whose output FixGate threw away for the entire
        // ride whenever GPS was live (docs/ble-review-2026-08.md, "Power").
        // 30 s / 50 m is plenty for the fallback it exists for; it still logs
        // raw and FixGate still decides trust, unchanged.
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, NETWORK_INTERVAL_MS, NETWORK_MIN_DISTANCE_M, this, Looper.getMainLooper()
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestLocationUpdates ${LocationManager.NETWORK_PROVIDER}", t)
        }
    }

    private fun stopLocation() {
        locationRunning = false
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
        awaitingFixSinceConnect = false

        val isPrecise = location.hasAccuracy() && location.accuracy <= SendPolicy.PRECISE_ACCURACY_M
        preciseFixStreak = if (isPrecise) preciseFixStreak + 1 else 0

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
            if (!ticking) return
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

    private fun moveThreshold(): Double = SendPolicy.moveThresholdM(currentAccuracyM(), lastKnownDiagonalM)

    /** null means "stay quiet"; otherwise the reason, which goes in the log. */
    private fun sendReason(nowMs: Long): SendPolicy.Reason? {
        val hasSent = lastSentFix != null && lastSentAtMs != 0L
        return SendPolicy.decide(
            hasSent = hasSent,
            sinceLastMs = if (hasSent) nowMs - lastSentAtMs else 0L,
            movedM = movedSinceLastSent() ?: 0.0,
            accuracyM = currentAccuracyM(),
            headingChanged = PositionPacket.headingSector(lastBearingDeg) != lastSentHeading,
            lastSentAccuracyM = lastSentAccuracyM,
            consecutivePreciseFixCount = preciseFixStreak,
            diagonalM = lastKnownDiagonalM,
        )
    }

    private fun trySend() {
        val fix = lastFix ?: return
        if (!ble.isConnected) return
        // Left over from a previous link, so not worth sending as this link's
        // position (see [awaitingFixSinceConnect]).
        if (awaitingFixSinceConnect) return

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
        lastSentAccuracyM = accuracyM
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

    /**
     * Posts (or re-posts) the foreground notification with the type mask that
     * matches current state now, not the state at some previous call.
     *
     * `startForeground` *replaces* the declared type set on every call; it does
     * not merge into it. The old code split the two types across two methods --
     * [goForeground] claiming `connectedDevice` only, a separate
     * `addLocationServiceType` claiming both -- on the assumption each ran once.
     * It does not: every `onStartCommand` (a Record tap, a repeated
     * [ACTION_WAKE]) called `goForeground()` again and silently dropped
     * `location`, and fixes then stopped the moment the screen went off on
     * Android 14+. Recomputing the whole mask from [locationRunning] on every
     * call, here, is what keeps that from happening -- there is exactly one
     * `startForeground` call left in the class, and every foreground-state
     * change goes through it.
     *
     * The service starts with nothing connected and no location requested, so
     * the first call (from [goForeground]) claims only `connectedDevice`:
     * claiming `location` for work that is not happening yet is exactly the
     * claim Android 14 refuses for a service woken from the background, which is
     * how most sessions start ([X4PresenceService]). [startLocation] flips
     * [locationRunning] to true and calls back in here once GPS is actually
     * about to be requested, which is what puts the `location` bit in the mask.
     *
     * A refusal of the wider mask is not fatal and is not retried as such: the
     * BLE half -- tile sync, freshness, whatever fixes do arrive -- needs only
     * `connectedDevice`, so half a bridge beats a service the system stops in
     * seconds. One retry with `connectedDevice` only keeps the service alive
     * when the wider claim is refused, and is logged as its own event line so a
     * silent ride has an explanation instead of looking like bad GPS.
     *
     * A refusal with nowhere left to retry is a different case, and gets
     * [stopSelf] instead: the pre-34 branch (one call, no type mask, no
     * fallback) and the `connectedDevice`-only retry's own catch below, both of
     * which are the *last* attempt for this call. Without it the system kills
     * the process ~10 s later on its own, and that death looks like an
     * unrelated crash instead of a foreground-service refusal. The outer catch
     * on the typed call must not get the same treatment while [type] still
     * asks for `location`: that is exactly the branch the retry exists for,
     * and calling [stopSelf] there would kill the bridge on a refusal the
     * retry could still recover from -- undoing the fix `goForeground()`'s
     * doc above describes.
     */
    private fun postForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (t: Throwable) {
                Log.e(TAG, "startForeground refused, stopping", t)
                stopSelf()
            }
            return
        }
        val type = foregroundTypeMask(locationRunning)
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground refused", t)
            if (type != ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } catch (t2: Throwable) {
                    Log.e(TAG, "startForeground refused (BLE only), stopping", t2)
                    stopSelf()
                }
                addEvent("foreground service: BLE only, no location type")
            } else {
                // Already asking for connectedDevice alone -- no narrower type
                // left to fall back to, so this refusal is as final as the
                // retry's own.
                stopSelf()
            }
        }
    }

    /** Every `onStartCommand` goes foreground again -- see [postForeground]. */
    private fun goForeground() = postForeground()

    /**
     * Re-posts the status line, but only when what it says has changed.
     *
     * Every caller of [notifyObserver] used to land in `nm.notify`, and one of
     * them is every accepted fix, so the notification was re-posted about once a
     * second. From Android 14 a foreground-service notification can be swiped
     * away by the user; a re-post brings it straight back, so dismissing it
     * bought a second of quiet. Nothing else re-creates it, so with the link down
     * -- counters frozen, no fixes coming in -- the text stops changing and the
     * dismissal now sticks.
     */
    private fun updateNotification() {
        if (!isRunning) return
        val content = notificationContent()
        if (content == lastNotificationContent) return
        lastNotificationContent = content
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(content))
        } catch (t: Throwable) {
            Log.w(TAG, "notify", t)
        }
    }

    /** Title, body and progress: everything the status line actually shows. */
    private class NotificationContent(
        val title: String,
        val text: String,
        /** 0..100, or -1 for no bar. */
        val progressPercent: Int,
        val recording: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is NotificationContent &&
            other.title == title && other.text == text &&
            other.progressPercent == progressPercent && other.recording == recording

        override fun hashCode(): Int =
            (((title.hashCode() * 31) + text.hashCode()) * 31 + progressPercent) * 31 +
                recording.hashCode()
    }

    private var lastNotificationContent: NotificationContent? = null

    private fun notificationContent(): NotificationContent {
        val title = when (bleState) {
            BleLink.State.CONNECTED -> "Connected to ${ble.connectedName ?: BleLink.DEVICE_NAME}"
            BleLink.State.SCANNING -> "Scanning for map device"
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
        // A transfer takes tens of seconds on this link and the phone is usually in
        // a bag, so while one runs the notification says that instead of the send
        // counters -- it is the only surface the rider can see without unlocking.
        val p = tileProgress
        val text = when {
            p != null -> "map squares  " + TileFormat.summary(
                p.completedSquares, p.skippedSquares, p.totalSquares,
                p.movedBytes, p.completedBytes, p.elapsedMs,
            )
            // Says what is true with no device on the line: GPS is not running.
            // The send counters here read as ongoing work and there is none --
            // trySend returns on the first line, and no fixes are being asked for.
            bleState != BleLink.State.CONNECTED -> "GPS off until connected  ·  $rec"
            else -> "sent $sentOk / failed $sentFailed  ·  $policy  ·  $rec"
        }
        val percent = if (p != null && p.totalBytes > 0) {
            p.sentBytes * 100 / p.totalBytes
        } else {
            -1
        }
        return NotificationContent(title, text, percent, isRecording)
    }

    private fun buildNotification(
        content: NotificationContent = notificationContent(),
    ): Notification {
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

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .apply {
                if (content.progressPercent >= 0) {
                    setProgress(100, content.progressPercent, false)
                }
            }
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (content.recording) "Stop rec" else "Record",
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

    private fun addTileLine(line: String) {
        tileLog.addLast("${timeFmt.format(Date())}  $line")
        while (tileLog.size > MAX_TILE_LINES) tileLog.removeFirst()
    }

    /** "square" / "squares" -- the word a rider uses, not "tile". */
    private fun squares(n: Int): String = if (n == 1) "square" else "squares"


    fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

/**
 * The foreground service type mask for the bridge's current state: always
 * `connectedDevice` (it talks BLE to the X4), plus `location` once location
 * updates are actually registered ([BridgeService.locationRunning]).
 *
 * Top-level and framework-call-free -- it only reads two `ServiceInfo` int
 * constants and ORs them -- so it is unit-testable without a `Service` or
 * Robolectric. `BridgeService.postForeground()` is the only caller and the
 * only place that still calls `startForeground` directly; see
 * `BridgeForegroundTest` for the two cases this covers.
 */
fun foregroundTypeMask(locationRunning: Boolean): Int {
    var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (locationRunning) {
        mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }
    return mask
}

/**
 * Whether an `onTileProgress` chunk callback should actually reach
 * `notifyObserver()` -- the propagation this throttles, not the callback
 * itself, which must keep firing every chunk so [BridgeService.tileProgress]
 * stays current.
 *
 * `notifyObserver()` rebuilds the notification content and renders the
 * observer; at the transfer's ~15 ms chunk interval that does not fit the
 * main looper's ~30 ms budget to issue the next chunk. So progress posts are
 * floored to [BridgeService.PROGRESS_POST_THROTTLE_MS], except a terminal
 * state (tile done, tile failed, fetch finished) always posts -- those are
 * not chunk callbacks and must render regardless of timing.
 *
 * Framework-call-free and side-effect-free, so it is unit-testable without a
 * `Service`; see `BridgeProgressThrottleTest` for the three cases.
 */
fun shouldPostProgress(nowMs: Long, lastMs: Long, terminal: Boolean): Boolean {
    if (terminal) return true
    return nowMs - lastMs >= BridgeService.PROGRESS_POST_THROTTLE_MS
}
