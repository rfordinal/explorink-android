package org.explorink.gpsbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The map-area screen: pick a place, see exactly what it costs, and let the
 * phone push that ground to the device across as many connections as it takes.
 *
 * Both existing paths to the card are reactive -- the device only ever asks for
 * a square it has already failed to draw -- so nothing fills ground the rider
 * has never been on (`docs/send-tiles-plan.md`). This is the one place that
 * does, and it is the only screen in the app whose work outlives the window: the
 * queue is on disk and the service drains it, so closing this changes nothing
 * about what is being sent.
 *
 * A third window, against the app's one-window rule ([MainActivity]), for the
 * same reason [PinsActivity] is a second: a coordinate field, a plan step and a
 * per-zone list do not fit in the status page without pushing the link state off
 * the top of it. It owns no bridge state -- everything comes from
 * [BridgeService.outboxSnapshot] over the same binder.
 *
 * **Redraws are throttled from the first line, deliberately.** The wallet's sync
 * screen paid for this already (`android` commit `e6d62bd` on `feat/wallet`):
 * its `render()` reloaded and repainted on every `onChanged()`, and progress
 * fires once per acknowledged chunk, so the screen spent ~900 ms between chunks
 * and **the wire fell from 7.5 kB/s to 253 B/s**. The identical trap is here --
 * `TileFetcher.Listener.onTileProgress` fires per chunk and this screen is one
 * of its observers -- so a repaint is capped at [REPAINT_MS] and the rows are
 * rebuilt only when their own text has actually changed.
 */
class TileQueueActivity : Activity(), BridgeService.Observer {

    companion object {
        private const val TAG = "TileQueueActivity"

        /**
         * Floor between repaints.
         *
         * The same 250 ms [BridgeService.PROGRESS_POST_THROTTLE_MS] uses one
         * level down, and it has to be here as well: that one bounds how often
         * the service *notifies*, this one bounds how much work a notification
         * costs. Fast enough that a byte counter still looks live, slow enough
         * that it cannot eat the ~30 ms budget the main looper needs to issue
         * the next chunk at a 15 ms connection interval.
         */
        private const val REPAINT_MS = 250L

        /** Keeps the ETA and the "x s ago" moving when nothing else changes. */
        private const val UI_TICK_MS = 1000L
    }

    private lateinit var tvState: TextView
    private lateinit var tvProblem: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPlan: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var etArea: EditText
    private lateinit var rgSize: RadioGroup
    private lateinit var llZones: LinearLayout
    private lateinit var btnUseMyPosition: Button
    private lateinit var btnPickOnMap: Button
    private lateinit var btnPlan: Button
    private lateinit var btnAddZone: Button
    private lateinit var btnContinue: Button
    private lateinit var btnPause: Button
    private lateinit var btnClear: Button

    private val main = Handler(Looper.getMainLooper())
    private val dayFmt = SimpleDateFormat("d MMM", Locale.US)

    private var service: BridgeService? = null
    private var bound = false

    /** The plan the rider is looking at, and the ask it describes. Null until asked for. */
    private var plan: TileOutboxController.Plan? = null
    private var plannedLatE7 = 0
    private var plannedLonE7 = 0
    private var plannedSideKm = 0
    private var planning = false

    /** Row text as it was last painted, keyed by zone. See the class doc's throttle. */
    private val rowText = HashMap<String, String>()

    private var lastRepaintMs = 0L
    private var repaintPosted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as? BridgeService.LocalBinder)?.service ?: return
            service?.setObserver(this@TileQueueActivity)
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
        setContentView(R.layout.activity_tile_queue)
        actionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowHomeEnabled(true)
            setDisplayUseLogoEnabled(true)
            setLogo(R.drawable.ic_logo)
        }

        tvState = findViewById(R.id.tvQueueState)
        tvProblem = findViewById(R.id.tvQueueProblem)
        tvStatus = findViewById(R.id.tvQueueStatus)
        tvPlan = findViewById(R.id.tvPlan)
        tvSummary = findViewById(R.id.tvSummary)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvEmpty = findViewById(R.id.tvQueueEmpty)
        etArea = findViewById(R.id.etArea)
        rgSize = findViewById(R.id.rgSize)
        llZones = findViewById(R.id.llZones)
        btnUseMyPosition = findViewById(R.id.btnUseMyPosition)
        btnPickOnMap = findViewById(R.id.btnPickOnMap)
        btnPlan = findViewById(R.id.btnPlan)
        btnAddZone = findViewById(R.id.btnAddZone)
        btnContinue = findViewById(R.id.btnQueueContinue)
        btnPause = findViewById(R.id.btnQueuePause)
        btnClear = findViewById(R.id.btnQueueClear)

        btnUseMyPosition.setOnClickListener { onUseMyPosition() }
        btnPickOnMap.setOnClickListener { onPickOnMap() }
        btnPlan.setOnClickListener { onPlanPressed() }
        btnAddZone.setOnClickListener { onAddPressed() }
        btnContinue.setOnClickListener { onContinuePressed() }
        btnPause.setOnClickListener { service?.outboxPause() }
        btnClear.setOnClickListener { onClearPressed() }
        findViewById<Button>(R.id.btnQueueClose).setOnClickListener { finish() }

        // A plan is about one point and one side. Changing either invalidates
        // it, and an Add button still offering the old numbers is how a rider
        // queues a box they were never shown.
        rgSize.setOnCheckedChangeListener { _, _ -> clearPlan() }

        intent?.let { prefillFromIntent(it) }
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { prefillFromIntent(it) }
    }

    /**
     * Fills the coordinate field from shared text. Never queues anything.
     *
     * A share is one tap away from a mis-tap, and this one commits the rider to
     * tens of megabytes and half an hour of radio, so it stops at the field --
     * the plan step is what turns it into an ask.
     */
    private fun prefillFromIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        etArea.setText(text.trim())
        clearPlan()
    }

    override fun onStart() {
        super.onStart()
        if (BridgeService.isRunning) bindBridge()
        main.removeCallbacks(uiTick)
        main.postDelayed(uiTick, UI_TICK_MS)
        render()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(uiTick)
        // A plan is a handful of HTTP reads and the rider walked away from it.
        service?.outboxCancelPlan()
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
     * Binds without creating, the same as [PinsActivity]: a service created by a
     * bind alone never went through `onStartCommand`, so it would sit there not
     * foreground and with no link.
     */
    private fun bindBridge() {
        if (bound) return
        bound = true
        if (!bindService(Intent(this, BridgeService::class.java), connection, 0)) {
            Log.w(TAG, "bindService returned false")
        }
    }

    /**
     * The service changed something. Repainted at most every [REPAINT_MS], with
     * the last change always painted -- see the class doc.
     */
    override fun onBridgeChanged() {
        main.post {
            val now = System.currentTimeMillis()
            val since = now - lastRepaintMs
            if (since >= REPAINT_MS) {
                render()
                return@post
            }
            // Trailing edge, so the final state of a batch is never the one
            // dropped by the throttle.
            if (repaintPosted) return@post
            repaintPosted = true
            main.postDelayed({
                repaintPosted = false
                render()
            }, REPAINT_MS - since)
        }
    }

    // --- the ask --------------------------------------------------------

    private fun sideKm(): Int = when (rgSize.checkedRadioButtonId) {
        R.id.rbSize10 -> 10
        R.id.rbSize40 -> 40
        else -> 20
    }

    private fun onUseMyPosition() {
        // The phone's own last fix, not the device's: the device's is this
        // phone's last *sent* packet and is up to one send interval behind, and
        // "an area around here" means here, now.
        val fix = service?.snapshot()?.lastFix
        if (fix == null) {
            toast("No position yet. The bridge needs a fix first.")
            return
        }
        etArea.setText(String.format(Locale.US, "%.6f, %.6f", fix.latitude, fix.longitude))
        clearPlan()
    }

    /**
     * Opens a maps app so the rider can find the place by looking.
     *
     * **One way, and that is the platform's doing, not a shortcut.** Android has
     * no place picker any more, and Google Maps returns nothing: measured on a
     * Galaxy S24 Ultra with Maps 26.35, 2026-09-02, `ACTION_PICK` on a `geo:`
     * URI has **zero** handlers on the phone. So the trip back is the share
     * sheet, which this activity already accepts
     * ([prefillFromIntent]), and the hint under the field says so -- a button
     * that drops the rider in another app with no way back is worse than no
     * button.
     *
     * The alternative is a map view inside the app, which means Play Services,
     * an API key in the APK, billing, and this app talking to Google for the
     * first time. That trade is written down in `docs/send-tiles-plan.md`,
     * "Picking the area", and it was refused for choosing a centre point.
     *
     * Opens where the rider already is, in this order: whatever is in the field
     * if it parses, then the phone's own last fix, then nothing. `geo:0,0` with
     * no query would land them in the Atlantic and cost them the pan.
     */
    private fun onPickOnMap() {
        val typed = PinCoordinates.parse(etArea.text.toString())
        val lat: Double?
        val lon: Double?
        if (typed is PinCoordinates.Result.Parsed) {
            lat = typed.latE7 / 1e7
            lon = typed.lonE7 / 1e7
        } else {
            val fix = service?.snapshot()?.lastFix
            lat = fix?.latitude
            lon = fix?.longitude
        }
        // z=11 is about the box this screen sends, so what the rider sees framed
        // is roughly what they would be asking for.
        val uri = if (lat != null && lon != null) {
            Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?z=11", lat, lon))
        } else {
            Uri.parse("geo:0,0?q=")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
        // Caught rather than pre-checked. `resolveActivity()` is subject to
        // Android 11's package visibility and answers null for anything the
        // manifest does not declare -- which is how this button spent its first
        // run silently refusing itself on a phone with Google Maps installed
        // (2026-09-02). The manifest declares the geo: intent now, and this
        // still catches rather than trusts, because the failure mode of getting
        // it wrong is a dead button.
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "no maps app for $uri", t)
            toast("No maps app on this phone. Paste a link or coordinates instead.")
            return
        }
        toast("Hold the spot, then Share it back to ExplorInk tiles.")
    }

    private fun clearPlan() {
        plan = null
        tvPlan.visibility = View.GONE
        btnAddZone.visibility = View.GONE
    }

    private fun onPlanPressed() {
        val s = service
        if (s == null) {
            toast("The bridge is not running. Open the main screen first.")
            return
        }
        when (val parsed = PinCoordinates.parse(etArea.text.toString())) {
            is PinCoordinates.Result.Failure -> {
                tvProblem.visibility = View.VISIBLE
                tvProblem.text = parsed.reason
            }

            is PinCoordinates.Result.Parsed -> {
                plannedLatE7 = parsed.latE7
                plannedLonE7 = parsed.lonE7
                plannedSideKm = sideKm()
                planning = true
                clearPlan()
                tvPlan.visibility = View.VISIBLE
                tvPlan.text = "asking the map server what is there..."
                s.outboxPlan(
                    plannedLatE7 / 1e7,
                    plannedLonE7 / 1e7,
                    plannedSideKm.toDouble(),
                ) { result ->
                    planning = false
                    plan = result
                    render()
                }
            }
        }
    }

    private fun onAddPressed() {
        val p = plan ?: return
        val s = service ?: return
        val label = "${PinList.formatE7(plannedLatE7)}, ${PinList.formatE7(plannedLonE7)}" +
            "  ${plannedSideKm} km"
        AlertDialog.Builder(this)
            .setTitle("Send this area?")
            .setMessage(planText(p) + "\n\nThe phone sends it whenever the device is on its Sync map tiles screen. It can take several connections.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                s.outboxQueueZone(plannedLatE7, plannedLonE7, plannedSideKm, label)
                clearPlan()
                render()
            }
            .show()
    }

    private fun onContinuePressed() {
        val s = service
        if (s == null) {
            toast("The bridge is not running. Open the main screen first.")
            return
        }
        s.outboxStartDraining()?.let { toast(it) }
    }

    private fun onClearPressed() {
        val s = service ?: return
        val n = s.outboxClearFinished()
        toast(if (n == 0) "Nothing finished yet." else "Cleared $n.")
        render()
    }

    private fun onZonePressed(row: BridgeService.ZoneRow) {
        AlertDialog.Builder(this)
            .setTitle(row.zone.label)
            // True and worth saying: what is already on the card stays there.
            // A receipt is a fact about the device, not about the ask that
            // produced it (`docs/tile-outbox-format.md`).
            .setMessage("Drop this area from the queue? Squares already on the device stay on it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Drop") { _, _ ->
                service?.outboxDropZone(row.zone.zoneId)
                render()
            }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- render ---------------------------------------------------------

    private fun render() {
        lastRepaintMs = System.currentTimeMillis()
        val snap = service?.outboxSnapshot()
        if (snap == null) {
            tvState.text = "map areas"
            tvProblem.visibility = View.VISIBLE
            tvProblem.text = "The bridge is not running. Open the main screen first."
            llZones.removeAllViews()
            rowText.clear()
            return
        }

        tvState.text = when (snap.phase) {
            TileOutboxController.Phase.IDLE ->
                if (snap.totals.queued > 0) "map areas: ${snap.totals.queued} to send" else "map areas"
            TileOutboxController.Phase.ASKING_INFO -> "map areas: asking the device"
            TileOutboxController.Phase.READING_MAPSET -> "map areas: reading the map server"
            TileOutboxController.Phase.SCANNING -> "map areas: checking the map server"
            TileOutboxController.Phase.ANNOUNCING -> "map areas: telling the device"
            TileOutboxController.Phase.PUSHING -> "map areas: sending"
        }

        val problems = ArrayList<String>()
        snap.queueLost?.let { problems.add("$it. Anything queued before is gone; queue it again.") }
        if (!snap.connected) {
            problems.add("Not connected to the device. The queue is kept and sends itself when the device is back.")
        } else if (snap.deviceScreen != null && snap.deviceScreen != DeviceInfo.Screen.SYNC) {
            problems.add(
                when (snap.deviceScreen) {
                    DeviceInfo.Screen.MAP ->
                        "The device is not on its Sync map tiles screen. A long transfer only runs there: open Sync map tiles on the device."
                    DeviceInfo.Screen.UNSTATED ->
                        "This firmware does not say which screen it is on, so the phone cannot tell whether a batch is safe to start. Update the device."
                    else -> "The device is on a screen this app does not know."
                }
            )
        } else if (snap.blocker != null) {
            problems.add(snap.blocker)
        }
        tvProblem.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        if (problems.isNotEmpty()) tvProblem.text = problems.joinToString("\n")

        tvStatus.visibility = if (snap.status == null) View.GONE else View.VISIBLE
        snap.status?.let { tvStatus.text = it }

        renderPlan()
        renderSummary(snap)
        renderZones(snap)

        btnPause.isEnabled = !snap.paused
        btnContinue.isEnabled = snap.paused || snap.phase == TileOutboxController.Phase.IDLE
        btnPlan.isEnabled = !planning
    }

    private fun renderPlan() {
        val p = plan
        if (p == null) {
            if (!planning) {
                tvPlan.visibility = View.GONE
                btnAddZone.visibility = View.GONE
            }
            return
        }
        tvPlan.visibility = View.VISIBLE
        tvPlan.text = planText(p)
        // Offered even when the plan is short: what is missing today is mostly
        // ground the server has not built yet, and the queue is built to wait
        // for exactly that.
        btnAddZone.visibility = if (p.summary.tiles > 0) View.VISIBLE else View.GONE
    }

    /**
     * The plan in exact numbers.
     *
     * Every figure here is arithmetic off the index, not an average: the index
     * states each tile's `sizeBytes` and it was verified byte for byte against
     * the served HTTP body (`docs/send-tiles-plan.md`). The ETA is the one
     * number that is not exact, and it says which rate it used rather than
     * pretending otherwise.
     */
    private fun planText(p: TileOutboxController.Plan): String {
        val s = p.summary
        val rate = service?.outboxSnapshot()?.bytesPerSecond
        val eta = s.etaSeconds(rate ?: TilePlan.START_BYTES_PER_SECOND)
        val out = StringBuilder()
        out.append("${s.present} of ${s.tiles} squares available\n")
        out.append("${TileFormat.bytes(s.bytes.toInt())} to send")
        if (eta != null) {
            out.append(", about ").append(TileFormat.duration(eta.toInt()))
            // Which rate produced the number, because they are far apart: a
            // measured one from this run, or the 2026-08-14 starting figure.
            out.append(if (rate != null) " at the measured rate" else " at 9 kB/s")
        }
        out.append('\n')
        if (s.waitingBuild > 0) {
            out.append("${s.waitingBuild} not built yet -- the map server builds them on ask, usually minutes\n")
        }
        if (s.absent > 0) out.append("${s.absent} have no map data (sea, or empty)\n")
        if (s.unknown > 0) out.append("${s.unknown} could not be checked\n")
        p.problem?.let { out.append(it).append('\n') }
        // Which tree the answer is about. Silent before 2026-09-02, and that
        // silence is what made a wrong version look like an empty world.
        out.append("map format v${p.formatVersion}\n")
        return out.toString().trimEnd()
    }

    private fun renderSummary(snap: BridgeService.OutboxSnapshot) {
        val t = snap.totals
        if (t.tiles == 0) {
            tvSummary.text = "nothing queued"
            tvCurrent.visibility = View.GONE
            return
        }
        val parts = ArrayList<String>()
        parts.add("${t.tiles} queued")
        parts.add("${t.sent} sent")
        // "building", not "unavailable". A square the server has not made yet is
        // the ordinary case and it resolves on its own: the phone's 404 is what
        // puts it in the server's queue, and the area is usually built minutes
        // later (`docs/tile-autobuild.md`). Calling that unavailable reads as a
        // refusal and it is not one.
        if (t.waitingBuild > 0) parts.add("${t.waitingBuild} building")
        // The one honest never, and it is rare -- 1 of 26 squares over Barcelona.
        if (t.noData > 0) parts.add("${t.noData} with no map data")
        if (t.stuck > 0) parts.add("${t.stuck} gave up")
        val remaining = (t.remainingBytes - t.inFlightBytes).coerceAtLeast(0L)
        if (remaining > 0) parts.add("${TileFormat.bytes(remaining.toInt())} left")
        snap.etaSeconds?.let { parts.add("~${TileFormat.duration(it.toInt())}") }
        tvSummary.text = parts.joinToString(", ")

        val current = snap.current
        if (current == null) {
            tvCurrent.visibility = View.GONE
        } else {
            tvCurrent.visibility = View.VISIBLE
            // The square's own name and its own bytes. On this link one city
            // tile is 30 s to 130 s on its own, so a batch counter alone cannot
            // tell a slow transfer from a dead one.
            tvCurrent.text = "z${current.tile.z} ${current.tile.col}/${current.tile.row}   " +
                "${TileFormat.bytes(current.sentBytes.toInt())} / " +
                TileFormat.bytes(current.totalBytes.toInt())
        }
    }

    private fun renderZones(snap: BridgeService.OutboxSnapshot) {
        if (snap.zones.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            llZones.removeAllViews()
            rowText.clear()
            return
        }
        tvEmpty.visibility = View.GONE

        // Rebuilt only when the set of zones changed. Inflating a row per
        // repaint is the other half of the cost the throttle exists to avoid,
        // and this screen repaints while the wire is busy.
        val ids = snap.zones.map { it.zone.zoneId }
        if (llZones.childCount != ids.size ||
            (0 until llZones.childCount).any { llZones.getChildAt(it).tag != ids[it] }
        ) {
            llZones.removeAllViews()
            rowText.clear()
            val inflater = LayoutInflater.from(this)
            for (row in snap.zones) {
                val v = inflater.inflate(R.layout.tile_zone_row, llZones, false)
                v.tag = row.zone.zoneId
                v.setOnClickListener { onZonePressed(row) }
                llZones.addView(v)
            }
        }

        for ((i, row) in snap.zones.withIndex()) {
            val v = llZones.getChildAt(i) ?: continue
            val t = row.totals
            val detail = buildString {
                append(t.sent).append('/').append(t.tiles).append(" sent")
                if (t.waitingBuild > 0) append("   ").append(t.waitingBuild).append(" building")
                if (t.noData > 0) append("   ").append(t.noData).append(" no data")
                if (t.stuck > 0) append("   ").append(t.stuck).append(" gave up")
                val left = (t.remainingBytes - t.inFlightBytes).coerceAtLeast(0L)
                if (left > 0) append("   ").append(TileFormat.bytes(left.toInt())).append(" left")
            }
            // Compared before it is set: setText invalidates and relayouts even
            // when the string is identical, and this runs while chunks are
            // moving.
            if (rowText[row.zone.zoneId] != detail) {
                rowText[row.zone.zoneId] = detail
                v.findViewById<TextView>(R.id.tvZoneLabel).text =
                    "${row.zone.label}   ${dayFmt.format(Date(row.zone.createdAtMs))}"
                v.findViewById<TextView>(R.id.tvZoneDetail).text = detail
                v.findViewById<ProgressBar>(R.id.pbZone).progress = (t.fraction * 1000).toInt()
            }
        }
    }
}
