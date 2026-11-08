package org.explorink.gpsbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
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

    private lateinit var devicePlate: View
    private lateinit var instrumentRow: View
    private lateinit var deviceIcon: ImageView
    private lateinit var deviceNameView: TextView
    private lateinit var deviceMeta: TextView
    private lateinit var speedValue: TextView
    private lateinit var compassNeedle: ImageView
    private lateinit var nextSendKey: TextView
    private lateinit var nextSendValue: TextView
    private lateinit var nextSendSub: TextView
    private lateinit var recordingRow: View
    private lateinit var recordingSub: TextView
    private lateinit var transfersCount: TextView
    private lateinit var transferTilesRow: View
    private lateinit var transferTilesIcon: ImageView
    private lateinit var transferTilesTitle: TextView
    private lateinit var transferTilesValue: TextView

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
        // The theme's android:forceDarkAllowed=false alone did not stop
        // Android's Force Dark from inverting plain TextViews to near-white
        // on a phone with system dark mode on (confirmed on a real S24:
        // identical build, system light mode, text renders correctly dark).
        // forceDarkAllowed is a View property, not a Window one; it has to
        // be set on the decor view before the layout inflates underneath it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.isForceDarkAllowed = false
        }
        setContentView(R.layout.activity_main)

        devicePlate = findViewById(R.id.devicePlate)
        instrumentRow = findViewById(R.id.instrumentRow)
        deviceIcon = findViewById(R.id.deviceIcon)
        deviceNameView = findViewById(R.id.deviceName)
        deviceMeta = findViewById(R.id.deviceMeta)
        speedValue = findViewById(R.id.speedValue)
        compassNeedle = findViewById(R.id.compassNeedle)
        nextSendKey = findViewById(R.id.nextSendKey)
        nextSendValue = findViewById(R.id.nextSendValue)
        nextSendSub = findViewById(R.id.nextSendSub)
        recordingRow = findViewById(R.id.recordingRow)
        recordingSub = findViewById(R.id.recordingSub)
        transfersCount = findViewById(R.id.transfersCount)
        transferTilesRow = findViewById(R.id.transferTilesRow)
        transferTilesIcon = findViewById(R.id.transferTilesIcon)
        transferTilesTitle = findViewById(R.id.transferTilesTitle)
        transferTilesValue = findViewById(R.id.transferTilesValue)
        transferTilesRow.setOnClickListener { onTransferTilesPressed() }
        val navMapAreas = findViewById<View>(R.id.navMapAreas)
        val navPins = findViewById<View>(R.id.navPins)
        val btnOverflow = findViewById<View>(R.id.btnOverflow)

        devicePlate.setOnClickListener { onDevicePlatePressed() }
        instrumentRow.setOnClickListener { onInstrumentRowPressed() }
        recordingRow.setOnClickListener { onRecordPressed() }
        // Pins and Map areas: the old main menu's two real destinations,
        // moved into bottom nav per the user's "rovnake, co sme tam mali"
        // call, 2026-11-07 -- not the Outline mockup's illustrative Wallet/
        // Map data labels, which name screens this app does not have.
        navMapAreas.setOnClickListener { startActivity(Intent(this, TileQueueActivity::class.java)) }
        navPins.setOnClickListener { startActivity(Intent(this, PinsActivity::class.java)) }
        // Retry scan / Share log / Stop service: not one of the Outline
        // composition's seven Home elements. Lives only in the app bar's
        // ellipsis, not duplicated into a bottom-nav "More" too -- the
        // user's call, 2026-11-07.
        btnOverflow.setOnClickListener { onOverflowPressed() }

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
        service?.clearObserver(this)
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
                        "pairing failed: ${error ?: "no device found -- open the map screen on it"}",
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
                    "will connect to whichever device answers first until you pair again."
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
     * The four link states Outline's "Dizajn systém" splits across the
     * device plate and the instrument row ("Styri stavy linky, nesene
     * dvoma prvkami"): which one is broken is a question only the plate
     * can answer by name, but both elements render from this one source of
     * truth, never two separately derived guesses at the same state.
     */
    private enum class LinkState { CONNECTED, RESTING, NEVER_PAIRED, HALT }

    /**
     * BLE state is checked before pairing state, not after: a companion
     * pairing (CompanionWake) only controls auto-wake, not the BLE link
     * itself -- this firmware's advertising has no pairing/bonding at all
     * (docs/ble-advertising.md), so the phone can be mid-scan or already
     * CONNECTED to a device it has never companion-paired with. Checking
     * addr == null first showed "Connect a device" while the status line
     * underneath plainly said "connecting LilyGoT5S3Map" -- caught on the
     * real phone, 2026-11-07.
     *
     * The app does not yet distinguish an expected idle disconnect from a
     * real link failure at the BLE-state level -- only BleLink.State.FAILED
     * and a devicePlateBlocker() reason map to HALT; every other
     * non-connected, paired state reads as RESTING.
     *
     * Returns the paired address alongside the state (rather than letting
     * renderDevicePlate() read CompanionWake.pairedAddress() a second time)
     * for the same reason render() already computes this once per frame --
     * see render()'s comment.
     */
    private fun currentLinkState(snap: BridgeService.Snapshot?): Triple<LinkState, String?, String?> {
        val addr = CompanionWake.pairedAddress(this)
        val blocker = devicePlateBlocker()
        val state = when {
            blocker != null -> LinkState.HALT to blocker
            snap?.bleState == BleLink.State.CONNECTED -> LinkState.CONNECTED to null
            snap?.bleState == BleLink.State.FAILED -> LinkState.HALT to "Not reaching the device"
            addr == null && snap?.deviceName == null -> LinkState.NEVER_PAIRED to null
            else -> LinkState.RESTING to null
        }
        return Triple(state.first, state.second, addr)
    }

    /**
     * The device plate's four states, per Outline "Dizajn systém" ("Plocha
     * zariadenia") and the device-plate component sheet: connected (solid
     * ink border), resting (dashed, "normal - wakes when you follow"),
     * never paired (dashed, "Connect a device"), and halt / "Not reaching
     * the device" (ink border plus the diagonal hatch, stop-colored
     * detail). A background-location gap (paired, BLE fine, but the wake
     * would silently have no fix to send) is not one of these four Outline
     * states; it stays reachable only from the tap dialog's first item,
     * not as a fifth visual state here.
     *
     * [link] and [haltName] come from one currentLinkState() call shared
     * with renderInstrumentRow() (render() computes it once) -- calling it
     * twice per frame used to mean two LocationManager/CompanionDeviceManager
     * IPCs and a frame where the two elements could disagree if a toggle
     * landed between the calls. Caught by Opus review, 2026-11-07.
     *
     * RESTING shows a short suffix of [addr]: without it this state reads
     * identical whether the rider is paired with the right X4 or with a
     * stale one from a previous device, and the only way to tell was to
     * tap through to the detail dialog. Reported 2026-11-08.
     *
     * NEVER_PAIRED reads [snap.unpairedDeviceFound] to tell "nothing is out
     * there" from "something answered, go pick it" -- without it the plate
     * stayed mute even once a board was in range, and the only other option
     * (connect to it on its own) cannot say *which* board, since every one
     * of them advertises the same name and UUID (Outline "Obrazovky",
     * screen 6). Tapping either reading still opens the same system picker.
     * Reported 2026-11-08.
     */
    private fun renderDevicePlate(snap: BridgeService.Snapshot?, link: LinkState, haltName: String?, addr: String?) {
        val icon: Int
        val background: Int
        val nameColor: Int
        val metaColor: Int
        var name: String
        var meta: String? = null

        when (link) {
            LinkState.HALT -> {
                icon = R.drawable.ic_triangle_alert
                background = R.drawable.bg_device_plate_halt
                nameColor = R.color.ui_ink
                metaColor = R.color.ui_stop
                name = haltName ?: "Not reaching the device"
                meta = when {
                    haltName != "Not reaching the device" -> "tap to fix"
                    // "the ride log keeps recording" used to print
                    // unconditionally, including while nothing was being
                    // recorded -- caught by Opus review, 2026-11-07.
                    snap?.recording == true -> "the ride log keeps recording"
                    else -> null
                }
            }

            LinkState.CONNECTED -> {
                icon = R.drawable.ic_bluetooth
                background = R.drawable.bg_device_plate_connected
                nameColor = R.color.ui_ink
                metaColor = R.color.ui_ink
                name = snap?.deviceName ?: BleLink.DEVICE_NAME
                meta = if (snap?.rssi != null) "connected - ${snap.rssi} dBm" else "connected"
            }

            LinkState.NEVER_PAIRED -> {
                icon = R.drawable.ic_bluetooth_off
                background = R.drawable.bg_device_plate_idle
                if (snap?.unpairedDeviceFound == true) {
                    nameColor = R.color.ui_ink
                    metaColor = R.color.ui_ink
                    name = "Device found"
                    meta = "tap to connect"
                } else {
                    nameColor = R.color.ui_ash
                    metaColor = R.color.ui_ash
                    name = "Connect a device"
                }
            }

            LinkState.RESTING -> {
                icon = R.drawable.ic_moon
                background = R.drawable.bg_device_plate_idle
                nameColor = R.color.ui_ash
                metaColor = R.color.ui_ash
                name = "Device resting"
                meta = if (addr != null) {
                    "normal - wakes when you follow · paired ..${addr.takeLast(5)}"
                } else {
                    "normal - wakes when you follow"
                }
            }
        }

        deviceIcon.setImageResource(icon)
        deviceIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, nameColor))
        devicePlate.background = ContextCompat.getDrawable(this, background)
        deviceNameView.text = name
        deviceNameView.setTextColor(ContextCompat.getColor(this, nameColor))
        if (meta != null) {
            deviceMeta.text = meta
            deviceMeta.setTextColor(ContextCompat.getColor(this, metaColor))
            deviceMeta.visibility = View.VISIBLE
        } else {
            deviceMeta.visibility = View.GONE
        }
    }

    /**
     * Instrument row, per Outline "Dizajn systém": speed plus inline
     * compass in the left cell, an adaptive right cell that answers why
     * nothing has gone out yet (NEXT SEND / NO FIX / WAITING / LINK LOST).
     * Lit (ui_ink) only while position is actually going out; grey
     * (ui_ash) otherwise -- same rule as the device plate, same
     * currentLinkState() call (passed in, see renderDevicePlate()'s doc)
     * so the two never disagree.
     */
    private fun renderInstrumentRow(snap: BridgeService.Snapshot?, link: LinkState, haltName: String?) {
        val fix = snap?.lastFix
        val sending = link == LinkState.CONNECTED && fix != null

        val speedColor = ContextCompat.getColor(this, if (sending) R.color.ui_ink else R.color.ui_ash)
        speedValue.setTextColor(speedColor)
        speedValue.text = if (fix != null && fix.hasSpeed()) {
            "${(fix.speed * 3.6f).toInt()} km/h"
        } else {
            "-"
        }
        // The needle is the one hand-drawn exception to "icons never carry
        // a live value" (Outline "Ikony"); the ring stays, the needle is
        // only hidden when there is no fix to point from.
        compassNeedle.visibility = if (fix != null) View.VISIBLE else View.GONE
        compassNeedle.rotation = snap?.bearingDeg ?: 0f
        compassNeedle.imageTintList = ColorStateList.valueOf(speedColor)

        val key: String
        val value: String
        val sub: String?
        val valueColor: Int
        val subColor: Int

        when {
            // The halt case is split in two: a real severed link (FAILED)
            // vs. a setup blocker (permission/Bluetooth/GPS). Both used to
            // print "LINK LOST" / "log still recording" unconditionally --
            // turning off Bluetooth while connected made the row claim the
            // link was lost and a ride was being logged when neither was
            // true. Caught by Opus review, 2026-11-07.
            link == LinkState.HALT && haltName == "Not reaching the device" -> {
                key = "LINK LOST"
                value = if (snap != null && snap.lastSentAtMs > 0) {
                    "${(System.currentTimeMillis() - snap.lastSentAtMs) / 1000} s"
                } else {
                    "-"
                }
                sub = if (snap?.recording == true) "log still recording" else null
                valueColor = R.color.ui_ink
                subColor = R.color.ui_stop
            }

            link == LinkState.HALT -> {
                key = haltName?.uppercase(Locale.US) ?: "NOT READY"
                value = "-"
                sub = "tap device to fix"
                valueColor = R.color.ui_ash
                subColor = R.color.ui_warn
            }

            link == LinkState.RESTING || link == LinkState.NEVER_PAIRED -> {
                key = "WAITING"
                value = "-"
                sub = "device asleep"
                valueColor = R.color.ui_ash
                subColor = R.color.ui_ash
            }

            fix == null -> {
                key = "NO FIX"
                value = "-"
                sub = "nothing sent"
                valueColor = R.color.ui_ash
                subColor = R.color.ui_warn
            }

            else -> {
                key = "NEXT SEND"
                // snap is non-null here: fix came from snap?.lastFix and the
                // fix == null branch above already caught the other case.
                val moved = snap?.movedSinceSentM?.toInt()?.toString() ?: "-"
                val threshold = snap?.moveThresholdM?.toInt() ?: 0
                value = "$moved/$threshold m"
                val elapsedS = if (snap!!.lastSentAtMs > 0) {
                    (System.currentTimeMillis() - snap.lastSentAtMs) / 1000
                } else 0
                sub = "${elapsedS} s - floor ${SendPolicy.MIN_INTERVAL_MS / 1000} s"
                valueColor = R.color.ui_ink
                subColor = R.color.ui_ink
            }
        }

        nextSendKey.text = key
        nextSendValue.text = value
        nextSendValue.setTextColor(ContextCompat.getColor(this, valueColor))
        nextSendSub.text = sub
        nextSendSub.setTextColor(ContextCompat.getColor(this, subColor))
    }

    /**
     * One dialog shape for every tap-for-detail surface on Home (device
     * plate, instrument row, and whatever else needs one later): own card
     * (bg_dialog_card, ui_paper, 2dp radius), own title, a mono scrollable
     * body, and an optional list of full-width action rows -- no leftover
     * system AlertDialog chrome. [options] is empty for a pure info sheet.
     */
    private fun showDetailDialog(
        title: String,
        bodyText: String,
        options: List<Triple<String, Boolean, () -> Unit>> = emptyList(),
    ) {
        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()
        fun color(id: Int) = ContextCompat.getColor(this, id)
        val mono = ResourcesCompat.getFont(this, R.font.ibm_plex_mono_regular)

        val titleView = TextView(this).apply {
            text = title
            setTextColor(color(R.color.ui_ink))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val bodyView = TextView(this).apply {
            typeface = mono
            textSize = 11f
            setTextColor(color(R.color.ui_ash))
            text = bodyText
        }
        val bodyScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160))
            setPadding(dp(16), 0, dp(16), dp(12))
            addView(bodyView)
        }
        fun hairline() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(color(R.color.ui_rule))
        }

        val dialog = AlertDialog.Builder(this).create()

        /** One row, same shape as a list row elsewhere: 48dp min, ink text,
            stop-colored for a destructive action, no caps, no ripple accent
            beyond the platform's default selectable background. */
        fun actionRow(label: String, destructive: Boolean, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(color(if (destructive) R.color.ui_stop else R.color.ui_ink))
            minHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { action(); dialog.dismiss() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dialog_card)
            addView(titleView)
            addView(bodyScroll)
            if (options.isNotEmpty()) {
                addView(hairline())
                options.forEachIndexed { index, (label, destructive, action) ->
                    addView(actionRow(label, destructive, action))
                    if (index != options.lastIndex) addView(hairline())
                }
            }
        }

        dialog.setView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun onDevicePlatePressed() {
        val addr = CompanionWake.pairedAddress(this)
        val snap = service?.snapshot()
        val blocker = devicePlateBlocker()
        // Only jump straight to the system pairing picker when nothing else
        // is standing in the way: the plate's halt state says "tap to fix"
        // for a permission/Bluetooth/GPS blocker, and this used to ignore
        // that and still open pairing, which can't fix any of the three.
        // Caught by Opus review, 2026-11-07.
        if (blocker == null && addr == null && snap?.deviceName == null) {
            onWakePressed()
            return
        }

        val options = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        if (blocker != null) {
            options.add(Triple("Fix: $blocker", false) { onRetryPressed() })
        }
        if (addr != null && !hasBackgroundLocation()) {
            options.add(Triple("Allow location all the time", false) { onWakePressed() })
        }
        if (blocker == null && snap?.bleState != BleLink.State.CONNECTED) {
            options.add(Triple("Connect now", false) { onRetryPressed() })
        }
        // Starting a recording from here too, not only the app bar's
        // ellipsis: the device plate is the natural place to ask for it,
        // since a ride needs the link up anyway. Per the user's call,
        // 2026-11-07, that removed the permanent "Start recording" button.
        if (snap?.recording != true) {
            options.add(Triple("Start recording", false) { onRecordPressed() })
        }
        if (addr != null) {
            options.add(Triple("Forget device", true) { onForgetPressed() })
        }
        options.add(Triple("Stop service", true) { onStopPressed() })

        // Leads with the paired address, not just the generic device name:
        // when the plate reads "Device resting" because the paired X4 is
        // not the one in the room, this is the only place that ever named
        // which address it actually is. Reported 2026-11-08.
        val header = if (addr != null) "paired with $addr" else "not paired"
        val events = snap?.events?.takeLast(20) ?: emptyList()
        val bodyText = if (events.isEmpty()) header else (listOf(header, "") + events).joinToString("\n")
        showDetailDialog(
            title = snap?.deviceName ?: BleLink.DEVICE_NAME,
            bodyText = bodyText,
            options = options,
        )
    }

    /**
     * The instrument row's own detail: the exact fix (lat/lon/accuracy/
     * provider/age) and the send counters that used to sit permanently
     * under the row as tvFix/tvCounters, duplicating what the row itself
     * already shows in rounded form. Folded into a tap sheet instead, per
     * the user's call, 2026-11-07 -- same move as the device plate's own
     * log getting pulled off the screen and into its dialog.
     */
    private fun onInstrumentRowPressed() {
        val snap = service?.snapshot()
        val body = buildString {
            val fix = snap?.lastFix
            if (fix == null) {
                append("fix: none yet")
            } else {
                val ageMs = abs(System.currentTimeMillis() - fix.time)
                append("lat ").append(String.format(Locale.US, "%.7f", fix.latitude))
                append("   lon ").append(String.format(Locale.US, "%.7f", fix.longitude))
                append("\nacc ")
                append(if (fix.hasAccuracy()) String.format(Locale.US, "%.1f m", fix.accuracy) else "-")
                append("\nprovider ").append(fix.provider ?: "?")
                append("   age ").append(ageMs / 1000).append(" s")
                append("\nlast sent ")
                append(if (snap.lastSentAtMs == 0L) "never" else timeFmt.format(Date(snap.lastSentAtMs)))
            }
            if (snap != null) {
                append("\n\npackets: sent ").append(snap.sentOk)
                append(" / failed ").append(snap.sentFailed)
                append("   seq ").append(snap.seq)
                append("\nkeep-alive ").append(SendPolicy.KEEPALIVE_INTERVAL_MS / 60000).append(" min")
                snap.lastSendReason?.let { append("\nlast send reason: ").append(it) }
                snap.tileFetchStatus?.let { append("\ntiles: ").append(it) }
                snap.pointSyncStatus?.let { append("\npoints: ").append(it) }
            }
        }
        showDetailDialog(title = "Position", bodyText = body)
    }

    /**
     * Transfers, per Outline "Dizajn systém": one row, lit while a fetch is
     * moving, grey otherwise -- never hidden outright, since "IDLE / up to
     * date" is itself one of the row's defined states, not an empty state
     * to collapse away. No progress bars in the collapsed row (the detail
     * dialog is where those would live, per Outline's "rozbalitelny
     * riadok," not built here); the rate line already answers "is this
     * moving."
     */
    private fun renderTransfers(snap: BridgeService.Snapshot?) {
        val now = snap?.tileProgress
        val lit = now != null
        val color = ContextCompat.getColor(this, if (lit) R.color.ui_ink else R.color.ui_ash)
        transferTilesTitle.setTextColor(color)
        transferTilesIcon.imageTintList = ColorStateList.valueOf(color)
        transferTilesValue.setTextColor(color)

        if (now != null) {
            transferTilesTitle.text = "Map squares z${now.z}"
            val left = (now.totalSquares - now.completedSquares - now.skippedSquares).coerceAtLeast(0)
            val rate = TileFormat.ratePerSec(now.completedBytes, now.elapsedMs, now.completedSquares)
            transferTilesValue.text = if (rate != null) "$left left   ${TileFormat.rate(rate)}" else "$left left"
            transfersCount.text = "1 running"
        } else {
            transferTilesTitle.text = "Map squares"
            transferTilesValue.text = snap?.tileFetchStatus ?: "up to date"
            transfersCount.text = ""
        }
    }

    /** The tile row's own detail: the exact in-flight square and the map-square
        log that used to sit permanently under it as tvTilesHead/tvTileNow/
        pbTileBytes/pbTileSquares/tvTiles. */
    private fun onTransferTilesPressed() {
        val snap = service?.snapshot()
        val now = snap?.tileProgress
        val body = buildString {
            if (now != null) {
                // Reads the way the device's own sync screen reads, on purpose:
                // TileFormat is a port of it.
                append("now: z${now.z} ${now.col}/${now.row}   ")
                append(TileFormat.bytes(now.sentBytes)).append(" / ").append(TileFormat.bytes(now.totalBytes))
                append("\n")
                append(
                    TileFormat.summary(
                        now.completedSquares, now.skippedSquares, now.totalSquares,
                        now.movedBytes, now.completedBytes, now.elapsedMs,
                    )
                )
            } else {
                append(snap?.tileFetchStatus ?: "up to date")
            }
            val lines = snap?.tileLog ?: emptyList()
            if (lines.isNotEmpty()) {
                append("\n\n").append(lines.joinToString("\n"))
            }
        }
        showDetailDialog(title = "Map squares", bodyText = body)
    }

    /**
     * Retry scan / Share log / Stop service: none of the three is one of
     * the Outline composition's seven Home elements, so none gets a
     * standalone button. Lives only in the app bar's ellipsis -- not
     * duplicated into a bottom-nav "More" too, per the user's call,
     * 2026-11-07.
     */
    private fun onOverflowPressed() {
        val snap = service?.snapshot()
        val options = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        // Per the user's call, 2026-11-07: "Start recording" is no longer a
        // permanent Home button, so it needs a way to be started from here
        // too (the other way is the device plate's own dialog).
        if (snap?.recording != true) {
            options.add(Triple("Start recording", false) { onRecordPressed() })
        }
        options.add(Triple("Retry scan", false) { onRetryPressed() })
        options.add(Triple("Share log", false) { shareLog() })
        options.add(Triple("About", false) { startActivity(Intent(this, AboutActivity::class.java)) })
        options.add(Triple("Stop service", true) { onStopPressed() })
        showDetailDialog(
            title = "More",
            bodyText = "ExplorInk GPS ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            options = options,
        )
    }

    private fun render() {
        val snap = service?.snapshot()
        // Computed once, not once per element: each call hits
        // CompanionWake.pairedAddress() (a CompanionDeviceManager IPC) and
        // LocationManager.isProviderEnabled() (another IPC), and calling it
        // twice per frame left a window where the two elements could read
        // different answers. Caught by Opus review, 2026-11-07.
        val (link, haltName, addr) = currentLinkState(snap)
        renderDevicePlate(snap, link, haltName, addr)
        renderInstrumentRow(snap, link, haltName)
        renderTransfers(snap)
        renderRecordingRow(snap)

        // Exact fix (lat/lon/accuracy/provider/age) and the send counters
        // live in onInstrumentRowPressed()'s detail sheet now, not as a
        // permanent block here -- see that function's doc comment. The raw
        // BLE event log (tvEvents) and the version string (tvVersion) are
        // gone the same way: both duplicated content already shown on
        // request (device plate's dialog, app bar ellipsis's dialog).
    }

    /**
     * Recording row, per Outline "Zvysok skladby": visible only while a
     * ride is actually recording, per the user's call, 2026-11-07 --
     * starting one is not a Home-screen action any more (see
     * onDevicePlatePressed() / onOverflowPressed()). Tapping this row
     * stops recording only, never the whole bridge service. The "since
     * HH:MM - X MB" sub-line replaces tvLogFile's permanent text (file
     * name, line count, "last file" when idle); the idle/"last file"
     * detail has no Outline-composition home, so it is dropped rather than
     * kept duplicated nowhere useful.
     */
    private fun renderRecordingRow(snap: BridgeService.Snapshot?) {
        val recording = snap?.recording == true
        recordingRow.visibility = if (recording) View.VISIBLE else View.GONE
        if (!recording) return

        val f = snap?.logFile
        val since = f?.let { startTimeFromFileName(it.name) }
        val sizeMb = f?.length()?.let { it / 1_000_000.0 }
        recordingSub.text = when {
            since != null && sizeMb != null -> "since %s - %.1f MB".format(Locale.US, since, sizeMb)
            sizeMb != null -> "%.1f MB".format(Locale.US, sizeMb)
            else -> "opening file..."
        }
    }

    /** "explorink-gps-20261107-153446.jsonl" -> "15:34", for the ride log
        row's "since HH:MM" line (SessionLogger.kt names the file this way;
        there is no separately stored start time to read instead). */
    private fun startTimeFromFileName(name: String): String? {
        val m = Regex("""(\d{8})-(\d{6})""").find(name) ?: return null
        val hhmmss = m.groupValues[2]
        return "${hhmmss.substring(0, 2)}:${hhmmss.substring(2, 4)}"
    }

    /**
     * What, if anything, keeps the device plate from even trying: a denied
     * permission, Bluetooth off, or GPS off. These used to be their own
     * tvProblem block (see renderDevicePlate()'s comment); now they take
     * over the plate directly, since they are exactly the thing standing
     * between the rider and a connection. Null means nothing is blocking.
     */
    private fun devicePlateBlocker(): String? {
        // missingPermissions(), not the sticky permissionsDenied flag: that
        // flag means "was denied once" (onRetryPressed() needs that to pick
        // settings vs. the system dialog) and never clears once the user
        // grants it from Settings, which left this returning "Permission
        // needed" forever -- caught by Opus review, 2026-11-07.
        if (missingPermissions().isNotEmpty()) return "Permission needed"
        val adapter = service?.bluetoothAdapter()
        if (adapter != null && !adapter.isEnabled) return "Bluetooth is off"
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "Location is off"
        return null
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
