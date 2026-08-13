package org.explorink.gpsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * BLE central for the ExplorInk X4's map service: position out, console and tile
 * transfers both ways.
 *
 * No pairing, no bonding, no encryption -- the firmware advertises the service
 * with no security (see `BlePositionServer.h`), and the channel is 0.5-1 m of
 * air inside a handlebar bag.
 *
 * Four characteristics, all on the one connection (`docs/ble-map-transfer-protocol.md`):
 *
 *  - `...0002` position, write only. 21 bytes per fix.
 *  - `...0003` command console, write out and **indicate in**. The device sends
 *    `NEED_TILES <n>` here unprompted when the rider asks for missing tiles.
 *  - `...0004` transfer frames, write only.
 *  - `...0005` transfer status, indicate in: `RDY`/`OK`/`ERR`.
 *
 * The last three are optional at discovery: an older firmware build has only
 * the position characteristic, and the bridge must still work against it.
 *
 * ## One GATT operation at a time
 *
 * Android runs exactly one GATT operation per connection and reports it on a
 * callback; issuing a second before the first completes loses it silently. With
 * position writes, console writes, transfer chunks, CCCD writes and the MTU
 * exchange all in play, that is no longer something a single "is a write
 * pending" flag can express, so every operation goes through one queue
 * ([opQueue], a [GattOpQueue]) and the completion callback pumps the next.
 *
 * This class keeps the enqueue call sites, the actual BLE calls and the GATT
 * callbacks; the queue keeps the ordering, the timeouts and the completion
 * matching. Nothing else here completes an op -- every callback forwards into
 * the queue. `docs/ble-gatt-op-queue.md` has the tombstone rule and why a
 * timeout must not pump.
 *
 * A transfer does not starve the position channel: the fetcher sends its next
 * chunk only from the previous chunk's callback, so a position write waits at
 * most one chunk.
 *
 * Everything public runs on the main thread; GATT callbacks arrive on binder
 * threads and are immediately reposted to the main looper, so there is one
 * owner of all state.
 */
class BleLink(
    private val context: Context,
    private val listener: Listener,
) {

    companion object {
        private const val TAG = "BleLink"

        const val DEVICE_NAME = "XteinkX4Map"
        val SERVICE_UUID: UUID = UUID.fromString("5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0001")
        val POSITION_CHAR_UUID: UUID = UUID.fromString("5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0002")
        val COMMAND_CHAR_UUID: UUID = UUID.fromString("5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0003")
        val TRANSFER_CHAR_UUID: UUID = UUID.fromString("5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0004")
        val TRANSFER_STATUS_CHAR_UUID: UUID = UUID.fromString("5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0005")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * MTU asked for once the service is found. A tile is kilobytes and the
         * default 23-byte MTU leaves 15 payload bytes per write, measured at
         * 0.2 KB/s against the X4 (`tools/blepush.py`). 517 is the ATT maximum;
         * the stack and the device negotiate it down to whatever they support,
         * and [mtu] holds the answer.
         */
        private const val DESIRED_MTU = 517

        /** Delay before a reconnect or rescan after a drop. */
        private const val RECONNECT_DELAY_MS = 1500L

        /**
         * How long a direct reconnect to a known address is given before we fall
         * back to scanning. `autoConnect = true` never reports failure -- the
         * stack just keeps waiting -- so the only way out is a timeout.
         */
        private const val RECONNECT_TIMEOUT_MS = 25_000L

        /** A fresh connect after a scan hit should be quick or not at all. */
        private const val CONNECT_TIMEOUT_MS = 12_000L

        /** Direct reconnects tried before falling back to a scan. */
        private const val MAX_DIRECT_RECONNECTS = 3

        /**
         * How long a scan runs at `SCAN_MODE_LOW_LATENCY` before dropping to
         * `SCAN_MODE_LOW_POWER`.
         *
         * Low latency means a continuous radio, and it is worth it for the seconds
         * right after the rider opens the map on the device -- that is the case the
         * whole wake path exists for and it should feel instant. Beyond that the
         * scan is a search for a device that is probably off, and a 100% duty cycle
         * for it is the single most expensive thing this app can do. Low power is
         * roughly a tenth of the radio time, so a device that starts advertising is
         * found within seconds instead of instantly -- the right trade once the
         * first window has passed.
         *
         * Unverified: no on-phone battery measurement of either mode yet. The duty
         * cycles are Android's documented scan windows, not something measured
         * here. Open -- a run with the app scanning for an hour in each mode,
         * against Battery Historian, would settle the real cost.
         */
        private const val SCAN_FAST_MS = 20_000L
    }

    enum class State {
        IDLE,
        NO_PERMISSION,
        BLUETOOTH_OFF,
        SCANNING,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        FAILED,
    }

    interface Listener {
        fun onBleState(state: State, detail: String?)
        fun onBleEvent(kind: String, message: String?, extras: Map<String, Any?>?)

        /** One line indicated on the command characteristic (`...0003`). */
        fun onCommandLine(line: String) {}

        /** One line indicated on the transfer status characteristic (`...0005`). */
        fun onTransferStatus(line: String) {}
    }

    private val main = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    /**
     * The MAC address of the one X4 this phone is paired with, or null while
     * unpaired. Set from the CompanionDeviceManager association
     * ([CompanionWake.pairedAddress]).
     *
     * When set, it is the only device this link will look at or talk to. That
     * matters because every X4 running this firmware advertises the same name and
     * the same service UUID, so the name/UUID filters below cannot tell one from
     * another: unpaired, the app connects to whichever advertises first, which
     * with two devices in range is a coin flip. Pairing removes the guess.
     */
    var pinnedAddress: String? = null
        set(value) {
            field = value?.uppercase()
        }

    private var scanning = false

    /** The `ScanSettings` mode the running scan was started with, for [scanDownshift]. */
    private var scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
    private var gatt: BluetoothGatt? = null
    private var posChar: BluetoothGattCharacteristic? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var transferChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var wantRunning = false

    /**
     * The link's negotiated ATT MTU, or the 23-byte default until the device
     * agrees to more. [maxChunkPayload] is what callers actually want.
     */
    var mtu: Int = 23
        private set

    /** Payload bytes that fit in one transfer chunk on this link. */
    fun maxChunkPayload(): Int = TransferFrames.maxChunkPayload(mtu)

    /**
     * Asks the stack for a fast connection interval while a tile sync runs, and
     * gives it back the moment the sync ends.
     *
     * Measured on the first real fetch: 450 kB over nine tiles took 183 s, a
     * steady 2.4 kB/s. The link negotiated a 256-byte MTU, so a chunk carries
     * 248 bytes -- but the connection interval was 50 ms and a chunk is
     * write-with-response, which costs one interval out and one back. That is
     * the whole ceiling: 248 B per 100 ms. At the ~15 ms this asks for, the same
     * transfer is roughly three times quicker.
     *
     * **Scoped to the sync on purpose.** A high-priority connection holds the
     * radio at a fast interval continuously, which is battery a rider is
     * spending for nothing once the tiles have landed. Position packets go out
     * every few seconds at most and do not care about interval at all.
     *
     * Android usually ignores a peripheral's own request for faster parameters,
     * so this has to come from the central -- there is no device-side substitute
     * (`firmware/explorink/docs/optimization/03-ble-link.md`).
     */
    @SuppressLint("MissingPermission")
    fun requestHighPriority(high: Boolean) {
        val g = gatt ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val priority = if (high) {
            BluetoothGatt.CONNECTION_PRIORITY_HIGH
        } else {
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }
        val ok = try {
            g.requestConnectionPriority(priority)
        } catch (t: Throwable) {
            Log.w(TAG, "requestConnectionPriority", t)
            false
        }
        // Logged either way: the stack may refuse, and the real interval only
        // shows up in the device's own onConnect log.
        listener.onBleEvent(
            "conn_priority",
            (if (high) "high" else "balanced") + (if (ok) "" else " (refused)"),
            null,
        )
    }

    /** True once both indicate channels are subscribed -- a fetch needs them. */
    var tileChannelReady: Boolean = false
        private set

    /**
     * Indications arrive as whole `\n`-terminated lines today, but nothing in
     * GATT guarantees one line per indication, so each channel keeps a small
     * assembler. A partial line at a disconnect is dropped with the buffer.
     */
    private val commandBuffer = StringBuilder()
    private val statusBuffer = StringBuilder()

    /**
     * Address of the device we last talked to. A direct reconnect to it beats a
     * rescan: it works with the screen off and costs no scan duty cycle.
     */
    private var lastAddress: String? = null
    private var directReconnects = 0
    private var connectTimeout: Runnable? = null

    /**
     * The delayed [start] armed by [scheduleReconnect] or [retry], kept so it can
     * be cancelled.
     *
     * It has to be cancellable, not fire-and-forget: [onAdapterOff] tears the link
     * down while a reconnect delay may already be running, and a `start()` that
     * lands after the teardown would re-arm scans and connects against an adapter
     * that is gone. One field for both callers -- the later one replaces the
     * earlier, which is what was wanted anyway (two pending starts only ever meant
     * the second one no-opped).
     */
    private var pendingStart: Runnable? = null

    var state: State = State.IDLE
        private set

    var connectedName: String? = null
        private set

    var connectedAddress: String? = null
        private set

    /**
     * Delayed work for [opQueue], on the same main looper as everything else
     * here.
     */
    private val opScheduler = object : GattOpQueue.Scheduler {
        override fun postDelayed(delayMs: Long, action: () -> Unit): GattOpQueue.Scheduler.Cancellable {
            val r = Runnable { action() }
            main.postDelayed(r, delayMs)
            return object : GattOpQueue.Scheduler.Cancellable {
                override fun cancel() {
                    main.removeCallbacks(r)
                }
            }
        }
    }

    /**
     * The one GATT operation queue. Owns the ordering, the per-op timeouts and
     * the completion matching; this class only issues the BLE calls it asks for
     * ([executeOp]) and forwards the callbacks into it.
     */
    private val opQueue = GattOpQueue(
        scheduler = opScheduler,
        clock = { SystemClock.uptimeMillis() },
        execute = { op -> executeOp(op) },
        onEvent = { kind, message -> listener.onBleEvent(kind, message, null) },
        onLinkDead = { reason -> handleLinkDead(reason) },
    )

    val isConnected: Boolean
        get() = state == State.CONNECTED && posChar != null

    // --- lifecycle ------------------------------------------------------

    fun start() {
        wantRunning = true
        if (!hasPermissions()) {
            setState(State.NO_PERMISSION, "grant Bluetooth + location, then Retry")
            return
        }
        val a = adapter
        if (a == null) {
            setState(State.FAILED, "no Bluetooth adapter on this phone")
            return
        }
        if (!a.isEnabled) {
            setState(State.BLUETOOTH_OFF, "turn Bluetooth on, then Retry")
            return
        }
        if (isConnected) return
        if (state == State.CONNECTING) return
        // A known address is worth a direct connect before a scan: BLE scanning
        // is throttled to nothing while the screen is off, so after the device
        // drops the link mid-ride a rescan would never find it again.
        if (lastAddress != null && directReconnects < MAX_DIRECT_RECONNECTS) {
            reconnectDirect()
        } else {
            startScan()
        }
    }

    /**
     * Forgets the address remembered for a direct reconnect.
     *
     * Needed when the rider unpairs: [lastAddress] is a device this phone was
     * talking to a moment ago, and [reconnectDirect] does no filtering, so without
     * this an unpair would be followed by a direct connect straight back to the
     * device just forgotten -- which is the opposite of unpairing to pick another
     * one.
     */
    fun clearRememberedAddress() {
        lastAddress = null
        directReconnects = 0
    }

    /** Manual retry from the UI: tear everything down and start clean. */
    fun retry() {
        teardown()
        directReconnects = 0
        scheduleStart(200)
    }

    fun stop() {
        wantRunning = false
        teardown()
        setState(State.IDLE, null)
    }

    private fun teardown() {
        stopScan()
        cancelConnectTimeout()
        val g = gatt
        gatt = null
        posChar = null
        cmdChar = null
        transferChar = null
        statusChar = null
        tileChannelReady = false
        mtu = 23
        commandBuffer.setLength(0)
        statusBuffer.setLength(0)
        connectedName = null
        connectedAddress = null
        // Not just tidying: start() bails out early while state == CONNECTING,
        // so a caller that tears down a stuck connect and then calls start()
        // again (retry, connect timeout) needs this cleared first -- else the
        // link sits in CONNECTING forever with no scan and no reconnect.
        state = State.IDLE
        if (g != null && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            try {
                g.disconnect()
                g.close()
            } catch (t: Throwable) {
                Log.w(TAG, "gatt teardown", t)
            }
        }
        // Last, like onAdapterOff: a done-callback runs arbitrary caller code
        // (the fetcher's abort + skip), and that code must find the link
        // already down -- state IDLE, gatt null -- instead of enqueueing onto
        // a gatt that is closing. enqueueWrite() then refuses it at once with
        // "not connected" before it ever reaches the op queue.
        failAllOps("torn down")
    }

    /**
     * The adapter just went off (airplane mode, the rider's toggle, a stack
     * restart). Tears the link down without touching the Bluetooth API.
     *
     * Needed because Android does not reliably deliver
     * `onConnectionStateChange(DISCONNECTED)` when the adapter dies: [state] would
     * stay CONNECTED or SCANNING and [gatt] would stay set and never be closed.
     * The next [start] -- the one the adapter's STATE_ON broadcast makes -- then
     * returns immediately on that stale state (`isConnected`, or
     * `state == CONNECTING`), and the bridge stays dead for the rest of the ride
     * until the rider finds the Retry button. Verified by reading the code, not
     * measured on a phone.
     *
     * Deliberately not [teardown]: that one calls `stopScan` and
     * `gatt.disconnect()`, both calls into a stack that is gone. The scan
     * registration is already invalidated by the OS, so it is dropped by clearing
     * the flag; `gatt.close()` is the one call still worth making, because it is
     * what releases the client interface, and it is guarded.
     *
     * Main thread only, like everything else public here.
     */
    fun onAdapterOff() {
        // Every delayed runnable that would touch the adapter, by its token.
        cancelConnectTimeout()
        cancelPendingStart()
        main.removeCallbacks(scanDownshift)
        // The scan is gone with the adapter; stopScan() would be a BT call into a
        // dead stack.
        scanning = false
        val g = gatt
        gatt = null
        posChar = null
        cmdChar = null
        transferChar = null
        statusChar = null
        tileChannelReady = false
        mtu = 23
        commandBuffer.setLength(0)
        statusBuffer.setLength(0)
        connectedName = null
        connectedAddress = null
        // Before failing the ops, so a done-callback that tries to enqueue is
        // refused on a clean state instead of writing onto a dead gatt. The
        // listener hears about it below, once there is nothing left in flight.
        state = State.BLUETOOTH_OFF
        // lastAddress is kept on purpose: it is what makes the reconnect after
        // STATE_ON a direct connect instead of a scan.
        directReconnects = 0
        if (g != null) {
            try {
                g.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close on adapter off", t)
            }
        }
        // Last: their done-callbacks run arbitrary caller code (the fetcher aborts
        // and skips), and that code must find the link already down.
        failAllOps("Bluetooth off")
        // Own kind, not "bluetooth_off": that one is the receiver's log of the
        // broadcast, this is the teardown that followed it.
        listener.onBleEvent("adapter_off", "link torn down", null)
        setState(State.BLUETOOTH_OFF, "turn Bluetooth on")
    }

    // --- scanning -------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() = startScan(ScanSettings.SCAN_MODE_LOW_LATENCY)

    @SuppressLint("MissingPermission")
    private fun startScan(mode: Int) {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            setState(State.FAILED, "BLE scanner unavailable")
            return
        }
        // Filters are mandatory in practice, not an optimisation: since Android
        // 8.1 an unfiltered scan returns nothing at all while the screen is off,
        // which is exactly the case that matters here -- phone locked in a bag,
        // device drops the link, app has to find it again. Two filters, OR'd:
        // by name, and by advertised service UUID for a device that leaves its
        // name out of the advertisement.
        // A paired X4 is filtered by address instead: exact, offloadable to the
        // controller, and it cannot match the X4 in the next room.
        val pinned = pinnedAddress
        val filters = if (pinned != null) {
            listOf(ScanFilter.Builder().setDeviceAddress(pinned).build())
        } else {
            listOf(
                ScanFilter.Builder().setDeviceName(DEVICE_NAME).build(),
                ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            )
        }
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            scanMode = mode
            if (mode == ScanSettings.SCAN_MODE_LOW_LATENCY) {
                main.removeCallbacks(scanDownshift)
                main.postDelayed(scanDownshift, SCAN_FAST_MS)
            }
            setState(State.SCANNING, if (pinned != null) "looking for $pinned" else "looking for $DEVICE_NAME")
            listener.onBleEvent("scan_start", if (mode == ScanSettings.SCAN_MODE_LOW_LATENCY) "fast" else "low power", null)
        } catch (t: Throwable) {
            Log.e(TAG, "startScan", t)
            setState(State.FAILED, "scan could not start: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Restarts the running scan in low power once the fast window is spent.
     *
     * A restart, not a setting change: Android has no way to re-tune a scan in
     * place, so the only path is stop then start with new [ScanSettings].
     */
    private val scanDownshift = Runnable {
        if (!scanning || scanMode != ScanSettings.SCAN_MODE_LOW_LATENCY) return@Runnable
        stopScan()
        startScan(ScanSettings.SCAN_MODE_LOW_POWER)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(scanDownshift)
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "stopScan", t)
        }
        listener.onBleEvent("scan_stop", null, null)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach { handleScanResult(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                setState(State.FAILED, "scan failed, code $errorCode -- press Retry")
                listener.onBleEvent("scan_failed", "code $errorCode", null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        if (!wantRunning || state == State.CONNECTING || state == State.CONNECTED) return
        val rec = result.scanRecord
        val advName = rec?.deviceName
        val gattName = try {
            result.device.name
        } catch (t: Throwable) {
            null
        }
        // Checked here as well as in the scan filter: a filter is a request to the
        // controller and a batch result can carry more than was asked for, and this
        // is the one decision where the wrong device means sending a stranger's
        // position to a stranger's map.
        val pinned = pinnedAddress
        if (pinned != null && !result.device.address.equals(pinned, ignoreCase = true)) return

        val byName = advName == DEVICE_NAME || gattName == DEVICE_NAME
        val byUuid = rec?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
        if (pinned == null && !byName && !byUuid) return

        stopScan()
        val device = result.device
        connectedName = advName ?: gattName ?: DEVICE_NAME
        connectedAddress = device.address
        lastAddress = device.address
        setState(State.CONNECTING, "${connectedName} (${device.address})")
        listener.onBleEvent(
            "found",
            connectedName,
            mapOf("address" to device.address, "rssi" to result.rssi),
        )
        connect(device, autoConnect = false)
    }

    // --- connection -----------------------------------------------------

    /**
     * Reconnects to [lastAddress] without scanning. `autoConnect = true` hands
     * the waiting to the Bluetooth stack, which keeps trying at a low duty cycle
     * and does it with the screen off -- the case a scan cannot cover.
     */
    @SuppressLint("MissingPermission")
    private fun reconnectDirect() {
        // Pairing wins over history: an address remembered from before the rider
        // paired can be the other X4, and a direct connect does no filtering.
        pinnedAddress?.let { if (lastAddress != it) lastAddress = it }
        val addr = lastAddress ?: return startScan()
        val a = adapter ?: return
        val device = try {
            a.getRemoteDevice(addr)
        } catch (t: Throwable) {
            Log.w(TAG, "getRemoteDevice", t)
            lastAddress = null
            return startScan()
        }
        directReconnects++
        connectedAddress = addr
        setState(State.CONNECTING, "reconnecting to $addr (try $directReconnects)")
        listener.onBleEvent(
            "reconnect_direct",
            addr,
            mapOf("attempt" to directReconnects),
        )
        connect(device, autoConnect = true)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        try {
            gatt = device.connectGatt(
                context,
                autoConnect,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
            if (gatt == null) {
                setState(State.FAILED, "connectGatt returned null")
                scheduleReconnect()
                return
            }
            armConnectTimeout(
                if (autoConnect) RECONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            )
        } catch (t: Throwable) {
            Log.e(TAG, "connectGatt", t)
            setState(State.FAILED, "connect threw ${t.javaClass.simpleName}")
            scheduleReconnect()
        }
    }

    /**
     * A connect that never completes has to be abandoned, or the link sits in
     * CONNECTING for the rest of the ride.
     */
    private fun armConnectTimeout(ms: Long) {
        cancelConnectTimeout()
        val r = Runnable {
            if (isConnected) return@Runnable
            listener.onBleEvent("connect_timeout", "after ${ms / 1000}s", null)
            teardown()
            if (!wantRunning) return@Runnable
            // Direct reconnect had its chances; fall back to a filtered scan.
            if (directReconnects >= MAX_DIRECT_RECONNECTS) {
                setState(State.DISCONNECTED, "reconnect gave up, scanning")
                startScan()
            } else {
                scheduleReconnect()
            }
        }
        connectTimeout = r
        main.postDelayed(r, ms)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        listener.onBleEvent(
                            "connected",
                            connectedName,
                            mapOf("address" to connectedAddress, "gatt_status" to status),
                        )
                        try {
                            g.discoverServices()
                        } catch (t: Throwable) {
                            Log.e(TAG, "discoverServices", t)
                            setState(State.FAILED, "service discovery threw")
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        posChar = null
                        cmdChar = null
                        transferChar = null
                        statusChar = null
                        tileChannelReady = false
                        mtu = 23
                        commandBuffer.setLength(0)
                        statusBuffer.setLength(0)
                        try {
                            g.close()
                        } catch (t: Throwable) {
                            Log.w(TAG, "close", t)
                        }
                        if (gatt === g) gatt = null
                        // Raw field first, like onAdapterOff (:447): failAllOps
                        // below runs the fetcher's abort/skip done-callbacks, and
                        // enqueueWrite()'s own "not connected" check must see the
                        // link already down, not a stale CONNECTED lagging behind
                        // the op queue's own linkUp guard.
                        state = if (wantRunning) State.DISCONNECTED else State.IDLE
                        // Last, like onAdapterOff and teardown(): a done-callback
                        // runs arbitrary caller code (the fetcher's abort + skip),
                        // and that code must find the link already down instead
                        // of enqueueing onto a gatt that is closing.
                        failAllOps("disconnected")
                        listener.onBleEvent(
                            "disconnected",
                            "gatt status $status",
                            mapOf("gatt_status" to status),
                        )
                        if (wantRunning) {
                            setState(State.DISCONNECTED, "lost link, reconnecting")
                            scheduleReconnect()
                        } else {
                            setState(State.IDLE, null)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setState(State.FAILED, "service discovery failed, status $status")
                    listener.onBleEvent("discovery_failed", "status $status", null)
                    return@post
                }
                val svc = g.getService(SERVICE_UUID)
                if (svc == null) {
                    setState(State.FAILED, "device has no map service")
                    listener.onBleEvent("service_missing", SERVICE_UUID.toString(), null)
                    return@post
                }
                val ch = svc.getCharacteristic(POSITION_CHAR_UUID)
                if (ch == null) {
                    setState(State.FAILED, "device has no position characteristic")
                    listener.onBleEvent("characteristic_missing", POSITION_CHAR_UUID.toString(), null)
                    return@post
                }
                posChar = ch
                // Optional: an older firmware build has only the position
                // characteristic, and the bridge's whole job -- forwarding
                // fixes -- still works against it. A missing one costs the tile
                // feature, not the connection.
                cmdChar = svc.getCharacteristic(COMMAND_CHAR_UUID)
                transferChar = svc.getCharacteristic(TRANSFER_CHAR_UUID)
                statusChar = svc.getCharacteristic(TRANSFER_STATUS_CHAR_UUID)
                cancelConnectTimeout()
                directReconnects = 0
                lastAddress = connectedAddress ?: lastAddress
                // The queue is closed from every teardown onwards and takes no
                // operations until here: a write issued on a gatt that is about
                // to be closed gets no callback and costs a timeout.
                opQueue.open()
                // The state line already carries the name, so the detail is
                // just the address.
                setState(State.CONNECTED, connectedAddress)
                listener.onBleEvent("ready", "position characteristic found", null)
                subscribeTileChannels(g)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            main.post { handleIndication(characteristic.uuid, copy) }
        }

        // The pre-Android-13 form, where the value lives on the characteristic
        // instead of arriving as a parameter. Not optional: minSdk is 31, and on
        // Android 12 this is the only one the framework calls.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val copy = characteristic.value?.copyOf() ?: return
            main.post { handleIndication(characteristic.uuid, copy) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            main.post {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                opQueue.onDescriptorComplete(
                    descriptor,
                    ok,
                    if (ok) null else "descriptor status $status",
                )
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                if (ok) {
                    // Recorded before the op is completed: completing it pumps
                    // the next operation, and a transfer frame sizes itself off
                    // [mtu].
                    mtu = newMtu
                    listener.onBleEvent(
                        "mtu",
                        "$newMtu bytes, ${maxChunkPayload()} per chunk",
                        mapOf("mtu" to newMtu),
                    )
                } else {
                    Log.w(TAG, "MTU request failed, status $status; staying at $mtu")
                }
                opQueue.onMtuComplete(ok, if (ok) null else "mtu status $status")
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                // Matching is the queue's job: the same callback carries
                // position writes, console lines and transfer chunks, and the
                // characteristic alone does not identify the op (every transfer
                // frame shares one).
                val ok = status == BluetoothGatt.GATT_SUCCESS
                opQueue.onWriteComplete(
                    characteristic,
                    ok,
                    if (ok) null else "gatt status $status",
                )
            }
        }

        // No `override`: this is a hidden framework callback (not part of the
        // public BluetoothGattCallback surface on every SDK this app compiles
        // against), dispatched by the stack purely by method signature. It
        // fires whenever the connection interval/latency/timeout actually
        // change -- the one place that turns "we asked for HIGH" into "we are
        // at N units", settling whether [requestHighPriority] was honoured.
        // `interval` is in 1.25 ms units (BLE core spec); `latency` is a
        // connection-event count; `timeout` is in 10 ms units.
        fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            main.post {
                listener.onBleEvent(
                    "conn_params",
                    "interval=$interval latency=$latency timeout=$timeout status=$status",
                    null,
                )
            }
        }
    }

    // --- indicate channels ----------------------------------------------

    /**
     * Subscribes to the command and transfer-status characteristics.
     *
     * Indications, not notifications: the device sends multi-line replies faster
     * than the connection interval drains them, and an unacknowledged
     * notification can have its tail dropped by the controller with no error --
     * measured on real hardware, see `BlePositionServer::sendCommandReply()`.
     *
     * The device refuses to start a transfer with nobody subscribed to the
     * status channel (`ERR status not subscribed`), so this has to be done up
     * front, not when the first tile is asked for.
     */
    @SuppressLint("MissingPermission")
    private fun subscribeTileChannels(g: BluetoothGatt) {
        val command = cmdChar
        val status = statusChar
        if (command == null || status == null || transferChar == null) {
            listener.onBleEvent("tiles_unavailable", "firmware has no transfer channel", null)
            return
        }

        var pending = 2
        var failures = 0
        val each = { ok: Boolean, error: String? ->
            if (!ok) {
                failures++
                Log.w(TAG, "subscribe failed: $error")
            }
            pending--
            if (pending == 0) {
                tileChannelReady = failures == 0
                if (tileChannelReady) {
                    listener.onBleEvent("tiles_ready", "command + status subscribed", null)
                    // After subscribing, not before: the MTU only affects chunk
                    // size, and a fetch cannot start until the subscriptions are
                    // in place anyway. Queued, not called: it takes the stack's
                    // busy flag like any other operation.
                    enqueueBiggerMtu()
                } else {
                    listener.onBleEvent("tiles_unavailable", "$failures subscribe(s) failed", null)
                }
            }
        }

        enableIndications(g, command, each)
        enableIndications(g, status, each)
    }

    @SuppressLint("MissingPermission")
    private fun enableIndications(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        done: (Boolean, String?) -> Unit,
    ) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            done(false, "BLUETOOTH_CONNECT not granted")
            return
        }
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            done(false, "no CCCD on ${ch.uuid}")
            return
        }
        // Both halves are needed: the local flag routes incoming packets to the
        // callback, the CCCD write tells the device to send them at all.
        val localOk = try {
            g.setCharacteristicNotification(ch, true)
        } catch (t: Throwable) {
            Log.w(TAG, "setCharacteristicNotification", t)
            false
        }
        if (!localOk) {
            done(false, "setCharacteristicNotification refused")
            return
        }
        opQueue.enqueue(
            GattOpQueue.Op.descriptor(
                label = "subscribe ${ch.uuid}",
                d = cccd,
                bytes = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                done = done,
            )
        )
    }

    /**
     * Queues the MTU exchange, rather than calling `requestMtu` straight from the
     * second CCCD write's completion.
     *
     * `requestMtu` takes the stack's busy flag exactly like a write does. Called
     * from a completion callback it was issued *before* the queue pumped the next
     * op, so that op went into a busy stack and came back refused -- and the one
     * op most likely to be there is the fetcher's `missing` ask, because the
     * device fires `NEED_TILES` the moment the command channel is subscribed
     * (`docs/ble-map-transfer-protocol.md`). The fetch died with "could not ask
     * for the list", or the MTU exchange itself was the refused one and the link
     * stayed at MTU 23 -- 15 payload bytes per write -- for the whole connection.
     *
     * Queued, a command write enqueued by a mid-subscription `NEED_TILES` simply
     * waits its turn.
     */
    private fun enqueueBiggerMtu() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        opQueue.enqueue(
            GattOpQueue.Op.mtu("mtu $DESIRED_MTU") { ok, error ->
                // Not fatal: MTU 23 is slow, not broken. But it is the whole
                // explanation for a 0.2 kB/s transfer, so it goes in the ride log
                // rather than only into logcat.
                if (!ok) {
                    Log.w(TAG, "MTU request failed ($error); staying at $mtu")
                    listener.onBleEvent(
                        "mtu_failed",
                        "${error ?: "refused"}, staying at $mtu",
                        mapOf("mtu" to mtu),
                    )
                }
            }
        )
    }

    /**
     * Splits an indication's bytes into whole lines and hands them to the
     * listener. Each channel keeps its own assembler: a line split across two
     * indications must not be spliced onto the other channel's half-line.
     */
    private fun handleIndication(uuid: UUID, data: ByteArray) {
        val buffer = when (uuid) {
            COMMAND_CHAR_UUID -> commandBuffer
            TRANSFER_STATUS_CHAR_UUID -> statusBuffer
            else -> return
        }
        buffer.append(String(data, Charsets.US_ASCII))
        while (true) {
            val nl = buffer.indexOf("\n")
            if (nl < 0) break
            val line = buffer.substring(0, nl).trim()
            buffer.delete(0, nl + 1)
            if (line.isEmpty()) continue
            if (uuid == COMMAND_CHAR_UUID) listener.onCommandLine(line) else listener.onTransferStatus(line)
        }
    }

    private fun scheduleReconnect() {
        if (!wantRunning) return
        scheduleStart(RECONNECT_DELAY_MS)
    }

    /** Arms a delayed [start], replacing any delayed start already pending. */
    private fun scheduleStart(delayMs: Long) {
        cancelPendingStart()
        val r = Runnable {
            pendingStart = null
            if (wantRunning && !isConnected) start()
        }
        pendingStart = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelPendingStart() {
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = null
    }

    // --- writing --------------------------------------------------------

    /**
     * Writes one 21-byte position packet. [done] is called exactly once, on the
     * main thread. Returns false if the write could not even be queued, in which
     * case [done] has already been called.
     *
     * A position write in flight is not queued behind again: the queue would
     * fill with fixes that are stale by the time they go out, and the newest
     * position is the only one worth sending. Everything else -- a console line,
     * a transfer chunk -- does queue.
     */
    fun write(bytes: ByteArray, done: (Boolean, String?) -> Unit): Boolean {
        val ch = posChar
        if (ch == null) {
            done(false, "not connected")
            return false
        }
        if (opQueue.hasPendingFor(ch)) {
            done(false, "previous write still in flight")
            return false
        }
        return enqueueWrite("position", ch, bytes, done)
    }

    /** Writes one ASCII console line to `...0003`. The newline is added here. */
    fun writeCommand(line: String, done: (Boolean, String?) -> Unit): Boolean {
        val ch = cmdChar
        if (ch == null) {
            done(false, "no command characteristic")
            return false
        }
        return enqueueWrite("cmd", ch, (line + "\n").toByteArray(Charsets.US_ASCII), done)
    }

    /** Writes one binary transfer frame to `...0004`. */
    fun writeTransferFrame(frame: ByteArray, done: (Boolean, String?) -> Unit): Boolean {
        val ch = transferChar
        if (ch == null) {
            done(false, "no transfer characteristic")
            return false
        }
        return enqueueWrite("frame", ch, frame, done)
    }

    /**
     * Queues one characteristic write. Never queues on a link that is not
     * connected: [done] is called at once with the failure instead, so a caller
     * cannot end up waiting on a write that will never be issued.
     */
    private fun enqueueWrite(
        label: String,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
        done: (Boolean, String?) -> Unit,
    ): Boolean {
        if (state != State.CONNECTED || gatt == null) {
            done(false, "not connected")
            return false
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            done(false, "BLUETOOTH_CONNECT not granted")
            return false
        }
        opQueue.enqueue(
            GattOpQueue.Op.write(
                label = label,
                ch = ch,
                bytes = bytes,
                // A transfer frame's ATT response is SD-bound by design, so it
                // gets its own, longer budget.
                timeoutMs = GattOpQueue.writeTimeoutFor(ch, transferChar),
                done = done,
            )
        )
        return true
    }

    // --- issuing what the queue asks for --------------------------------

    /**
     * [GattOpQueue]'s `execute`: the one place a GATT operation is actually
     * issued. Returns false when the stack would not take it -- the queue then
     * completes the op with a failure, and nothing here does.
     */
    @SuppressLint("MissingPermission")
    private fun executeOp(op: GattOpQueue.Op): Boolean {
        val g = gatt ?: return false
        return try {
            when (op.kind) {
                GattOpQueue.Kind.DESCRIPTOR -> writeDescriptor(g, op)
                GattOpQueue.Kind.WRITE -> writeCharacteristic(g, op)
                // Also takes the stack's busy flag, which is why it is queued
                // rather than called straight from a completion callback.
                GattOpQueue.Kind.MTU -> g.requestMtu(DESIRED_MTU)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "executeOp ${op.label}", t)
            false
        }
    }

    /**
     * The stack stopped answering: an operation got no callback at all inside
     * the ATT transaction timeout ([GattOpQueue.STACK_DEAD_TIMEOUT_MS]).
     *
     * Treated exactly like a disconnect, because that is what it is -- the link
     * is gone whether or not Android says so. Pumping more operations into it
     * would only produce refusals.
     */
    private fun handleLinkDead(reason: String) {
        listener.onBleEvent("link_dead", reason, null)
        teardown()
        if (!wantRunning) return
        setState(State.DISCONNECTED, "link stopped answering, reconnecting")
        scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(g: BluetoothGatt, op: GattOpQueue.Op): Boolean {
        val ch = op.characteristic ?: return false
        // WRITE_TYPE_DEFAULT (a write request, acknowledged) because every
        // firmware characteristic here declares plain WRITE, not WRITE_NR -- and
        // on the transfer channel that acknowledgement is the flow control: the
        // device answers it only once the bytes are on the SD card.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, op.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                // Logged here because the queue's failure reason is generic and
                // the return code is the only thing that says *why* the stack
                // refused (busy, not connected, no permission).
                Log.w(TAG, "${op.label} refused, rc $rc")
                false
            } else {
                true
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = op.bytes
                g.writeCharacteristic(ch)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(g: BluetoothGatt, op: GattOpQueue.Op): Boolean {
        val d = op.descriptor ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(d, op.bytes)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                Log.w(TAG, "${op.label} refused, rc $rc")
                false
            } else {
                true
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = op.bytes
                g.writeDescriptor(d)
            }
        }
    }

    /**
     * Fails everything outstanding and closes the queue. Called from teardown, so
     * a caller waiting on a write always hears an outcome instead of hanging on a
     * dead link.
     *
     * Still the last thing [onAdapterOff] does, and now [teardown] and the
     * `STATE_DISCONNECTED` branch of the GATT callback do too, for the same
     * reason: the done-callbacks run caller code that enqueues, and with state
     * and refs already cleared it is refused right there in `enqueueWrite`,
     * before it ever reaches the queue's own `linkUp` guard.
     */
    private fun failAllOps(reason: String) {
        opQueue.failAll(reason)
    }

    // --- permissions ----------------------------------------------------

    private fun hasPermission(p: String): Boolean =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun hasPermissions(): Boolean =
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun setState(s: State, detail: String?) {
        state = s
        listener.onBleState(s, detail)
    }
}
