package org.explorink.gpsbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The pins screen: what the device has saved, and the one thing only the phone
 * can do -- put a pin somewhere the rider is not standing.
 *
 * The device holds every pin and every history record on its own card and is
 * authoritative (`firmware/explorink/docs/pins-plan.md`, decision 7). This screen
 * therefore stores nothing: it shows the last `pin list` reply and asks for a new
 * one after every change. A row here is always something the device said it has.
 *
 * **Pins only answer while the device is on its map screen.** That activity is the
 * only one that wires a pin store to the console, so the tile sync screen answers
 * `pins=unavailable` -- shown as its own message, never as an empty list.
 *
 * A second window, against the app's one-window rule ([MainActivity]): a list with
 * per-row actions, a coordinate field and a history pager do not fit in the status
 * page without pushing the link state, the fix and the transfer bars off the top of
 * it. Everything it needs comes from [BridgeService] over the same binder as the
 * first window; it owns no bridge state of its own.
 */
class PinsActivity : Activity(), BridgeService.Observer {

    companion object {
        private const val TAG = "PinsActivity"

        /** Redraw this often, so the distances follow the phone's own movement. */
        private const val UI_TICK_MS = 1000L
    }

    private lateinit var tvState: TextView
    private lateinit var tvProblem: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPhoneFix: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvHistory: TextView
    private lateinit var spKind: Spinner
    private lateinit var llPins: LinearLayout
    private lateinit var etCoords: EditText
    private lateinit var btnSaveHere: Button
    private lateinit var btnSaveAt: Button
    private lateinit var btnHistory: Button
    private lateinit var btnHistoryOlder: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClose: Button

    private val main = Handler(Looper.getMainLooper())

    /** UTC, and labelled as such on screen: the device's own records are UTC. */
    private val utcFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var service: BridgeService? = null
    private var bound = false

    /** The spinner's rows, in catalogue order. Rebuilt only when the labels change. */
    private var spinnerLabels: List<String> = emptyList()

    /** Asked for a listing once per visit, and only once: [render] runs every second. */
    private var refreshAsked = false

    /** True while a history page has been asked for or shown, so it survives a redraw. */
    private var historyShown = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as? BridgeService.LocalBinder)?.service ?: return
            service?.setObserver(this@PinsActivity)
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
        setContentView(R.layout.activity_pins)

        tvState = findViewById(R.id.tvPinsState)
        tvProblem = findViewById(R.id.tvPinsProblem)
        tvStatus = findViewById(R.id.tvPinsStatus)
        tvPhoneFix = findViewById(R.id.tvPhoneFix)
        tvEmpty = findViewById(R.id.tvPinsEmpty)
        tvHistory = findViewById(R.id.tvHistory)
        spKind = findViewById(R.id.spKind)
        llPins = findViewById(R.id.llPins)
        etCoords = findViewById(R.id.etCoords)
        btnSaveHere = findViewById(R.id.btnSaveHere)
        btnSaveAt = findViewById(R.id.btnSaveAt)
        btnHistory = findViewById(R.id.btnHistory)
        btnHistoryOlder = findViewById(R.id.btnHistoryOlder)
        btnRefresh = findViewById(R.id.btnPinsRefresh)
        btnClose = findViewById(R.id.btnPinsClose)

        btnSaveHere.setOnClickListener { onSaveHere() }
        btnSaveAt.setOnClickListener { onSaveAtCoordinates() }
        btnHistory.setOnClickListener { onHistoryPressed(0) }
        btnHistoryOlder.setOnClickListener {
            onHistoryPressed(service?.pinsSnapshot()?.historyNext ?: 0)
        }
        btnRefresh.setOnClickListener { ask { it.pinsRefresh() } }
        btnClose.setOnClickListener { finish() }

        // A shared position lands here: "Share to ExplorInk GPS" from a maps app
        // sends text, and the coordinate field is exactly what it is for.
        intent?.let { prefillFromIntent(it) }
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { prefillFromIntent(it) }
    }

    /**
     * Fills the coordinate field from shared text, without saving anything.
     *
     * Never a silent save: a share is one tap away from a mis-tap, and a pin the
     * rider did not choose the *type* of is not a pin they can find again.
     */
    private fun prefillFromIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        etCoords.setText(text.trim())
    }

    override fun onStart() {
        super.onStart()
        if (BridgeService.isRunning) bindBridge()
        refreshAsked = false
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
    }

    private val uiTick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, UI_TICK_MS)
        }
    }

    /**
     * Binds without creating. The bridge is started by the first window, and a
     * service created by a bind alone never went through `onStartCommand`, so it
     * would sit there not foreground and with no link -- the same trap
     * [MainActivity] avoids.
     */
    private fun bindBridge() {
        if (bound) return
        bound = true
        if (!bindService(Intent(this, BridgeService::class.java), connection, 0)) {
            Log.w(TAG, "bindService returned false")
        }
    }

    override fun onBridgeChanged() {
        main.post { render() }
    }

    // --- actions --------------------------------------------------------

    /** Runs a bridge call and shows whatever reason it gives for not running. */
    private fun ask(call: (BridgeService) -> String?) {
        val s = service
        if (s == null) {
            toast("The bridge is not running. Open the main screen first.")
            return
        }
        call(s)?.let { toast(it) }
    }

    private fun selectedKey(): String? {
        val at = spKind.selectedItemPosition
        return PinKinds.ALL.getOrNull(at)?.key
    }

    private fun savedKeys(): Set<String> =
        service?.pinsSnapshot()?.pins?.map { it.key }?.toSet() ?: emptySet()

    private fun onSaveHere() {
        val key = selectedKey() ?: return
        val label = PinKinds.labelFor(key)
        // Replace is always confirmed, exactly as on the device: the old position
        // stays in the history either way, but a pin the rider still needs is not
        // something to overwrite on one tap.
        confirmIfSaved(key, "Replace $label with the phone position?") {
            ask { it.pinsSaveHere(key) }
        }
    }

    private fun onSaveAtCoordinates() {
        val key = selectedKey() ?: return
        val label = PinKinds.labelFor(key)
        when (val parsed = PinCoordinates.parse(etCoords.text.toString())) {
            is PinCoordinates.Result.Failure -> {
                tvProblem.visibility = View.VISIBLE
                tvProblem.text = parsed.reason
            }

            is PinCoordinates.Result.Parsed -> {
                val where = "${PinList.formatE7(parsed.latE7)}, ${PinList.formatE7(parsed.lonE7)}"
                // Always confirmed, saved slot or not: the coordinate came out of
                // pasted text, and showing it back is the only chance the rider gets
                // to catch a link that pointed somewhere else.
                AlertDialog.Builder(this)
                    .setTitle("Save $label")
                    .setMessage("$where\n\nThis is sent to the device now.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(if (key in savedKeys()) "Replace" else "Save") { _, _ ->
                        ask { it.pinsSaveAt(key, parsed.latE7, parsed.lonE7) }
                    }
                    .show()
            }
        }
    }

    private fun confirmIfSaved(key: String, question: String, action: () -> Unit) {
        if (key !in savedKeys()) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(question)
            // Cancel first, so the destructive choice is never the one under a
            // thumb resting on the dialog -- the device orders its own the same way.
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace") { _, _ -> action() }
            .show()
    }

    private fun onRowPressed(pin: DevicePin) {
        val label = pin.label
        val actions = listOf(
            "Replace with the phone position",
            "Copy the coordinates",
            "Delete $label",
        )
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> confirmIfSaved(pin.key, "Replace $label with the phone position?") {
                        ask { it.pinsSaveHere(pin.key) }
                    }

                    1 -> copyCoordinates(pin)
                    2 -> confirmDelete(pin)
                }
            }
            .show()
    }

    private fun confirmDelete(pin: DevicePin) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${pin.label}?")
            // True, and worth saying: the device appends a `del` record and erases
            // nothing, so a wrong delete is recoverable from the history.
            .setMessage("The device keeps the record in its history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> ask { it.pinsDelete(pin.key) } }
            .show()
    }

    private fun copyCoordinates(pin: DevicePin) {
        val text = "${PinList.formatE7(pin.latE7)}, ${PinList.formatE7(pin.lonE7)}"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast("No clipboard")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText(pin.label, text))
        toast("Copied $text")
    }

    private fun onHistoryPressed(offset: Int) {
        historyShown = true
        ask { it.pinsHistory(offset) }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- render ---------------------------------------------------------

    private fun render() {
        val snap = service?.pinsSnapshot()
        if (snap == null) {
            tvState.text = "pins"
            tvProblem.visibility = View.VISIBLE
            tvProblem.text = "The bridge is not running. Open the main screen first."
            renderSpinner(emptySet())
            llPins.removeAllViews()
            return
        }

        // One listing per visit, asked for as soon as there is a link to ask on.
        if (!refreshAsked && snap.connected && !snap.busy) {
            refreshAsked = true
            service?.pinsRefresh()
        }

        val pins = snap.pins
        tvState.text = when {
            !snap.connected -> "pins: no device"
            snap.busy -> "pins: asking the device..."
            pins == null -> "pins"
            else -> "pins: ${pins.size} on the device"
        }

        val problems = ArrayList<String>()
        if (!snap.connected) {
            problems.add("Not connected to the device. Pins are read and written over the link.")
        } else if (snap.unavailable) {
            problems.add(
                "The device is not on its map screen. Pins live there: open the map on " +
                    "the device, then press Refresh."
            )
        }
        if (snap.connected && snap.tilesBusy) {
            problems.add("Map squares are transferring. Pin commands wait for that to finish.")
        }
        tvProblem.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        if (problems.isNotEmpty()) tvProblem.text = problems.joinToString("\n")

        tvStatus.visibility = if (snap.status == null) View.GONE else View.VISIBLE
        snap.status?.let { tvStatus.text = it }

        val fix = snap.phoneFix
        tvPhoneFix.text = if (fix == null) {
            "phone position: none yet"
        } else {
            val ageS = (System.currentTimeMillis() - fix.time) / 1000
            "phone position: " +
                String.format(Locale.US, "%.7f, %.7f", fix.latitude, fix.longitude) +
                "  (${ageS} s old)"
        }
        // Off rather than failing at the tap: without a fix there is nothing to
        // save, and the device refuses the same way for the same reason.
        btnSaveHere.isEnabled = snap.connected && fix != null && !snap.busy
        btnSaveAt.isEnabled = snap.connected && !snap.busy

        renderSpinner(pins?.map { it.key }?.toSet() ?: emptySet())
        renderRows(pins, fix)
        renderHistory(snap)
    }

    /**
     * The save spinner: every catalogue type, with the saved ones marked.
     *
     * Marked rather than hidden or reordered: a rider replacing a pin picks the
     * same row they created it from, and the mark is what turns the next tap into
     * a confirmation instead of a surprise.
     */
    private fun renderSpinner(saved: Set<String>) {
        val labels = PinKinds.ALL.map { if (it.key in saved) "${it.label}  (saved)" else it.label }
        if (labels == spinnerLabels) return
        // Rebuilt only when a label actually changed: this runs every second, and
        // resetting the adapter under an open spinner loses the rider's choice.
        val at = spKind.selectedItemPosition.coerceAtLeast(0)
        spinnerLabels = labels
        spKind.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (at < labels.size) spKind.setSelection(at)
    }

    private fun renderRows(pins: List<DevicePin>?, fix: android.location.Location?) {
        llPins.removeAllViews()
        when {
            pins == null -> {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Not read yet."
                return
            }

            pins.isEmpty() -> {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "No pins saved on the device."
                return
            }

            else -> tvEmpty.visibility = View.GONE
        }

        val inflater = LayoutInflater.from(this)
        for (pin in pins) {
            val row = inflater.inflate(R.layout.pin_row, llPins, false)
            row.findViewById<TextView>(R.id.tvRowLabel).text = pin.label
            row.findViewById<TextView>(R.id.tvRowDetail).text = buildString {
                append(PinList.formatE7(pin.latE7)).append(", ").append(PinList.formatE7(pin.lonE7))
                append("   id ").append(pin.id)
                // 0 is the device saying it had no clock when the pin was saved,
                // which is a real state on a device with no RTC -- not a missing
                // value to paper over with a fabricated time.
                append("\nsaved ")
                append(if (pin.utc > 0) "${utcFmt.format(Date(pin.utc * 1000))} UTC" else "time unknown")
                if (!PinKinds.isKnown(pin.key)) {
                    // A key from a newer firmware. It is shown, and it stays
                    // deletable: an app that hid it would hide a pin the rider can
                    // see on the panel.
                    append("   (unknown type on this app)")
                }
            }
            row.findViewById<TextView>(R.id.tvRowDistance).text =
                if (fix == null) {
                    // `-` is "nothing to measure from", and it has to be distinct
                    // from a number: `0 m` is a real answer and means the rider is
                    // standing on the pin -- seen on the phone 2026-08-19, where the
                    // Destination pin sat at the phone's own position and read
                    // `0 m` correctly. The device rounds the same way.
                    "-"
                } else {
                    PinGeo.formatDistance(
                        PinGeo.distanceM(
                            (fix.latitude * 1e7).roundToInt(),
                            (fix.longitude * 1e7).roundToInt(),
                            pin.latE7,
                            pin.lonE7,
                        )
                    )
                }
            row.setOnClickListener { onRowPressed(pin) }
            llPins.addView(row)
        }
    }

    private fun renderHistory(snap: BridgeService.PinsSnapshot) {
        if (!historyShown || snap.history.isEmpty()) {
            tvHistory.visibility = View.GONE
            btnHistoryOlder.visibility = View.GONE
            return
        }
        tvHistory.visibility = View.VISIBLE
        val head = buildString {
            append("history ")
            append(snap.historyOffset + 1)
            append("-")
            append(snap.historyOffset + snap.history.size)
            snap.historyTotal?.let { append(" of ").append(it) }
            append(", newest first")
        }
        // Newest first is the device's order and it is kept: the reason to open a
        // history at all is "I just deleted the wrong camp", and that record is the
        // first line.
        tvHistory.text = (listOf(head) + snap.history.map { record ->
            buildString {
                append(record.seq).append("  ")
                append(record.op).append(' ')
                append(record.label)
                if (record.latE7 != null && record.lonE7 != null) {
                    append("  ").append(PinList.formatE7(record.latE7))
                    append(", ").append(PinList.formatE7(record.lonE7))
                }
                if (record.utc > 0) append("  ").append(utcFmt.format(Date(record.utc * 1000)))
            }
        }).joinToString("\n")

        btnHistoryOlder.visibility = if (snap.historyNext != null) View.VISIBLE else View.GONE
    }
}
