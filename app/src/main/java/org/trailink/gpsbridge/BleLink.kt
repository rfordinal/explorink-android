package org.trailink.gpsbridge

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
import android.util.Log
import java.util.UUID

/**
 * BLE central for the TrailInk X4's map service: position out, console and tile
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
 * position writes, console writes, transfer chunks and CCCD writes all in play,
 * that is no longer something a single "is a write pending" flag can express, so
 * every operation goes through one queue ([enqueue]) and the completion callback
 * pumps the next.
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

        /** A write that gets no callback in this long counts as failed. */
        private const val WRITE_TIMEOUT_MS = 3000L

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

    private var scanning = false
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

    var state: State = State.IDLE
        private set

    var connectedName: String? = null
        private set

    var connectedAddress: String? = null
        private set

    /**
     * One queued GATT operation. Either a characteristic write or a descriptor
     * write; Android reports both on their own callback and permits exactly one
     * outstanding at a time per connection.
     */
    private class Op(
        val label: String,
        val bytes: ByteArray,
        val characteristic: BluetoothGattCharacteristic?,
        val descriptor: BluetoothGattDescriptor?,
        val done: (Boolean, String?) -> Unit,
    )

    private val ops = ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var opTimeout: Runnable? = null

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

    /** Manual retry from the UI: tear everything down and start clean. */
    fun retry() {
        teardown()
        directReconnects = 0
        main.postDelayed({ if (wantRunning) start() }, 200)
    }

    fun stop() {
        wantRunning = false
        teardown()
        setState(State.IDLE, null)
    }

    private fun teardown() {
        stopScan()
        cancelConnectTimeout()
        failAllOps("torn down")
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
    }

    // --- scanning -------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
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
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
            setState(State.SCANNING, "looking for $DEVICE_NAME")
            listener.onBleEvent("scan_start", null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "startScan", t)
            setState(State.FAILED, "scan could not start: ${t.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
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
        val byName = advName == DEVICE_NAME || gattName == DEVICE_NAME
        val byUuid = rec?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
        if (!byName && !byUuid) return

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
                        failAllOps("disconnected")
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
                if (inFlight?.descriptor !== descriptor) return@post
                val ok = status == BluetoothGatt.GATT_SUCCESS
                completeOp(ok, if (ok) null else "descriptor status $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "MTU request failed, status $status; staying at $mtu")
                    return@post
                }
                mtu = newMtu
                listener.onBleEvent(
                    "mtu",
                    "$newMtu bytes, ${maxChunkPayload()} per chunk",
                    mapOf("mtu" to newMtu),
                )
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                // Matched against the op in flight, not against one UUID: the
                // same callback now carries position writes, console lines and
                // transfer chunks.
                if (inFlight?.characteristic !== characteristic) return@post
                val ok = status == BluetoothGatt.GATT_SUCCESS
                completeOp(ok, if (ok) null else "gatt status $status")
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
                    // in place anyway. Chaining them keeps one GATT operation in
                    // flight at a time, which is all the stack allows.
                    requestBiggerMtu(g)
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
        enqueue(
            Op(
                label = "subscribe ${ch.uuid}",
                bytes = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                characteristic = null,
                descriptor = cccd,
                done = done,
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestBiggerMtu(g: BluetoothGatt) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            if (!g.requestMtu(DESIRED_MTU)) Log.w(TAG, "requestMtu refused; staying at $mtu")
        } catch (t: Throwable) {
            Log.w(TAG, "requestMtu", t)
        }
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
        main.postDelayed({
            if (wantRunning && !isConnected) start()
        }, RECONNECT_DELAY_MS)
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
        if (ops.any { it.characteristic === ch } || inFlight?.characteristic === ch) {
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
        enqueue(Op(label, bytes, ch, null, done))
        return true
    }

    // --- the GATT operation queue ---------------------------------------

    private fun enqueue(op: Op) {
        ops.addLast(op)
        pumpOps()
    }

    private fun pumpOps() {
        if (inFlight != null) return
        val next = ops.removeFirstOrNull() ?: return
        inFlight = next
        val timeout = Runnable { completeOp(false, "${next.label} timed out") }
        opTimeout = timeout
        main.postDelayed(timeout, WRITE_TIMEOUT_MS)
        if (!startOp(next)) {
            // startOp already completed it with the failure.
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOp(op: Op): Boolean {
        val g = gatt
        if (g == null) {
            completeOp(false, "not connected")
            return false
        }
        return try {
            val started = when {
                op.descriptor != null -> writeDescriptor(g, op)
                op.characteristic != null -> writeCharacteristic(g, op)
                else -> false
            }
            if (!started) completeOp(false, "${op.label} refused by the stack")
            started
        } catch (t: Throwable) {
            Log.e(TAG, "startOp ${op.label}", t)
            completeOp(false, "${op.label} threw ${t.javaClass.simpleName}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(g: BluetoothGatt, op: Op): Boolean {
        val ch = op.characteristic ?: return false
        // WRITE_TYPE_DEFAULT (a write request, acknowledged) because every
        // firmware characteristic here declares plain WRITE, not WRITE_NR -- and
        // on the transfer channel that acknowledgement is the flow control: the
        // device answers it only once the bytes are on the SD card.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, op.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                completeOp(false, "${op.label} rc $rc")
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
    private fun writeDescriptor(g: BluetoothGatt, op: Op): Boolean {
        val d = op.descriptor ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(d, op.bytes)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                completeOp(false, "${op.label} rc $rc")
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

    private fun completeOp(ok: Boolean, error: String?) {
        val op = inFlight ?: return
        inFlight = null
        opTimeout?.let { main.removeCallbacks(it) }
        opTimeout = null
        op.done(ok, error)
        // After the callback, not before: a callback that queues the next chunk
        // (the transfer path does exactly that) must find the slot free.
        pumpOps()
    }

    /**
     * Fails everything outstanding. Called from teardown, so a caller waiting on
     * a write always hears an outcome instead of hanging on a dead link.
     */
    private fun failAllOps(reason: String) {
        val queued = ops.toList()
        ops.clear()
        completeOp(false, reason)
        queued.forEach { it.done(false, reason) }
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
