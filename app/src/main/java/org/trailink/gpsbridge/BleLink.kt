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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

        /** Delay before a scan restarts after a drop; also the retry backoff. */
        private const val RESCAN_DELAY_MS = 1500L
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
        startScan()
    }

    /** Manual retry from the UI: tear everything down and start clean. */
    fun retry() {
        teardown()
        main.postDelayed({ if (wantRunning) start() }, 200)
    }

    fun stop() {
        wantRunning = false
        teardown()
        setState(State.IDLE, null)
    }

    private fun teardown() {
        stopScan()
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
        // No ScanFilter: filtering server-side on the service UUID misses
        // devices that keep the UUID out of the advertisement and only expose it
        // after connect. Matching by name and by advertised UUID here covers
        // both, and there is exactly one device to look for.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
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
        setState(State.CONNECTING, "${connectedName} (${device.address})")
        listener.onBleEvent(
            "found",
            connectedName,
            mapOf("address" to device.address, "rssi" to result.rssi),
        )
        connect(device)
    }

    // --- connection -----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                setState(State.FAILED, "connectGatt returned null")
                scheduleRescan()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "connectGatt", t)
            setState(State.FAILED, "connect threw ${t.javaClass.simpleName}")
            scheduleRescan()
        }
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
                            setState(State.DISCONNECTED, "lost link, rescanning")
                            scheduleRescan()
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

    private fun scheduleRescan() {
        if (!wantRunning) return
        main.postDelayed({
            if (wantRunning && !isConnected) start()
        }, RESCAN_DELAY_MS)
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
