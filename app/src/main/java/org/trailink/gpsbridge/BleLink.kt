package org.trailink.gpsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
 * Connect-and-write BLE central for the TrailInk X4's position characteristic.
 *
 * No pairing, no bonding, no encryption -- the firmware advertises the service
 * with no security (see `BlePositionServer.h`), and the channel is 0.5-1 m of
 * air inside a handlebar bag. Scan, connect, write 19 bytes, nothing else. The
 * command characteristic (`...0003`) is deliberately not touched.
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

        /** A write that gets no callback in this long counts as failed. */
        private const val WRITE_TIMEOUT_MS = 3000L

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
    }

    private val main = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var posChar: BluetoothGattCharacteristic? = null
    private var wantRunning = false

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

    /** Set while a write is in flight; only one GATT op at a time is legal. */
    private var pendingWrite: ((Boolean, String?) -> Unit)? = null
    private var writeTimeout: Runnable? = null

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
        cancelPendingWrite("torn down")
        val g = gatt
        gatt = null
        posChar = null
        connectedName = null
        connectedAddress = null
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
                        cancelPendingWrite("disconnected")
                        posChar = null
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
                cancelConnectTimeout()
                directReconnects = 0
                lastAddress = connectedAddress ?: lastAddress
                // The state line already carries the name, so the detail is
                // just the address.
                setState(State.CONNECTED, connectedAddress)
                listener.onBleEvent("ready", "position characteristic found", null)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                if (characteristic.uuid != POSITION_CHAR_UUID) return@post
                val ok = status == BluetoothGatt.GATT_SUCCESS
                finishPendingWrite(ok, if (ok) null else "gatt status $status")
            }
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
     * Writes one 19-byte packet. [done] is called exactly once, on the main
     * thread, with the outcome. Returns false if the write could not even be
     * attempted, in which case [done] has already been called.
     */
    @SuppressLint("MissingPermission")
    fun write(bytes: ByteArray, done: (Boolean, String?) -> Unit): Boolean {
        val g = gatt
        val ch = posChar
        if (g == null || ch == null || state != State.CONNECTED) {
            done(false, "not connected")
            return false
        }
        if (pendingWrite != null) {
            done(false, "previous write still in flight")
            return false
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            done(false, "BLUETOOTH_CONNECT not granted")
            return false
        }

        pendingWrite = done
        val timeout = Runnable { finishPendingWrite(false, "write timed out") }
        writeTimeout = timeout
        main.postDelayed(timeout, WRITE_TIMEOUT_MS)

        // WRITE_TYPE_DEFAULT (a write request, acknowledged) because the
        // firmware characteristic declares plain WRITE, not WRITE_NR.
        val started: Boolean = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeCharacteristic(
                    ch,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                if (rc != BluetoothStatusCodes.SUCCESS) {
                    finishPendingWrite(false, "writeCharacteristic rc $rc")
                    return false
                }
                true
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = bytes
                    g.writeCharacteristic(ch)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "writeCharacteristic", t)
            finishPendingWrite(false, "write threw ${t.javaClass.simpleName}")
            return false
        }

        if (!started) {
            finishPendingWrite(false, "writeCharacteristic returned false")
            return false
        }
        return true
    }

    private fun finishPendingWrite(ok: Boolean, error: String?) {
        val cb = pendingWrite ?: return
        pendingWrite = null
        writeTimeout?.let { main.removeCallbacks(it) }
        writeTimeout = null
        cb(ok, error)
    }

    private fun cancelPendingWrite(reason: String) {
        finishPendingWrite(false, reason)
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
