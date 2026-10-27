package org.explorink.gpsbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Geocoder
import android.app.Activity
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The rider shares a route from Google Maps and it ends up on the card.
 *
 * This is also **the app's one share entry** for plain text: it classifies what
 * arrived ([ShareRouting]) and forwards a place to the pins window or an area to
 * the map-area window, so the system share sheet lists ExplorInk once rather
 * than three times.
 *
 * The chain, and every step of it can fail out loud rather than approximately:
 *
 *  1. resolve a short link ([MapsShortLink]) -- a share carries one of those
 *     and nothing else
 *  2. read the stops out of the `dir` link ([RouteLink])
 *  3. geocode any stop that carried no coordinates, which a phone share does not
 *     produce but another app's link can
 *  4. **show the stops and let the rider confirm them** -- the only check that
 *     exists, see the layout's comment
 *  5. route them ([RouteService]), build the `.tir` ([RouteWriter]), queue it
 *     ([RouteOutbox]) and push it when the device is there
 *
 * No straight-line fallback at any point. If the router fails the app says so:
 * a straight line between two towns, drawn on a map somebody is navigating by,
 * is a lie the device cannot caveat.
 */
class ShareRouteActivity : Activity() {

    private lateinit var tvState: TextView
    private lateinit var tvProblem: TextView
    private lateinit var tvCounts: TextView
    private lateinit var tvStops: TextView
    private lateinit var tvNote: TextView
    private lateinit var btnSend: Button
    private lateinit var btnCancel: Button

    private var service: BridgeService? = null
    private var bound = false

    /** Stops as the rider will see them, once everything resolved. */
    private var stops: List<RouteLink.Stop> = emptyList()
    private var mode: RouteLink.Mode = RouteLink.Mode.UNKNOWN
    private var busy = false

    private val router: RouteService by lazy { BRouterService() }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "share-route") }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as? BridgeService.LocalBinder)?.service
            bound = true
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_route)
        tvState = findViewById(R.id.tvRouteState)
        tvProblem = findViewById(R.id.tvRouteProblem)
        tvCounts = findViewById(R.id.tvRouteCounts)
        tvStops = findViewById(R.id.tvRouteStops)
        tvNote = findViewById(R.id.tvRouteNote)
        btnSend = findViewById(R.id.btnRouteSend)
        btnCancel = findViewById(R.id.btnRouteCancel)

        btnSend.setOnClickListener { onSendPressed() }
        btnCancel.setOnClickListener { finish() }

        // The service is started so a queued route has something to push it, and
        // bound so this screen can hand the bytes over. Starting it is not a
        // side effect of the share: this app's whole job is that link.
        startService(Intent(this, BridgeService::class.java))
        bindService(Intent(this, BridgeService::class.java), connection, Context.BIND_AUTO_CREATE)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            ?: intent?.dataString?.trim()
        if (text.isNullOrEmpty()) {
            problem("nothing was shared")
            return
        }

        when (ShareRouting.classify(text)) {
            ShareRouting.Target.ROUTE -> readRoute(text)
            ShareRouting.Target.RESOLVE_FIRST -> resolveThenClassify(text)
            // One point, two possible destinations. The rider is asked, because
            // only they know which they meant.
            ShareRouting.Target.PIN -> askPinOrArea(text)
            ShareRouting.Target.UNKNOWN -> problem("could not read what was shared")
        }
    }

    private fun forward(target: Class<*>, text: String) {
        startActivity(
            Intent(this, target).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
        )
        finish()
    }

    /**
     * A short link is the only thing a Google Maps share hands over, and what it
     * expands to decides everything: a `dir` link is a route, a `place` link is
     * one point and not a route at all.
     */
    private fun resolveThenClassify(shortLink: String) {
        state("reading the link")
        MapsShortLink.resolve(shortLink) { expanded, error ->
            if (expanded == null) {
                problem(error ?: "could not open that link. Is the phone online?")
                return@resolve
            }
            when (ShareRouting.classify(expanded)) {
                ShareRouting.Target.ROUTE -> readRoute(expanded)
                ShareRouting.Target.PIN -> askPinOrArea(expanded)
                else -> problem("that link is a place, not a route")
            }
        }
    }

    /**
     * A shared place is either a pin or an area to cache, and nothing in the
     * text says which -- the rider's own intent is the only thing that does.
     *
     * That choice used to live in the share sheet, as two ExplorInk entries.
     * It is the same choice, asked where there is room to name what each one
     * does, and asked only for the shape that is actually ambiguous: a
     * directions link never reaches here.
     */
    private fun askPinOrArea(text: String) {
        state("a place, not a route")
        tvCounts.text = "ExplorInk can save this as a pin on the device, or cache the map " +
            "around it so the ground is on the card before you ride."
        tvStops.text = text.take(200)
        tvNote.text = ""
        btnSend.text = "Save as a pin"
        btnSend.isEnabled = true
        btnSend.setOnClickListener { forward(PinsActivity::class.java, text) }
        btnCancel.text = "Cache the map here"
        btnCancel.setOnClickListener { forward(TileQueueActivity::class.java, text) }
    }

    private fun readRoute(url: String) {
        when (val parsed = RouteLink.parse(url)) {
            is RouteLink.Result.NotARoute -> problem(parsed.reason)
            is RouteLink.Result.Unreadable -> problem("could not read this link: ${parsed.reason}")
            is RouteLink.Result.Route -> {
                mode = parsed.mode
                if (parsed.unresolved.isEmpty()) {
                    stops = parsed.stops
                    render()
                } else {
                    geocode(parsed)
                }
            }
        }
    }

    /**
     * Looks up the stops that carried no coordinates.
     *
     * A phone share does not produce these -- every picked place rides with its
     * own `!3d`/`!4d` pair -- so this is the path for another app's link or a
     * hand-typed one. `Geocoder` is optional on a device, and when it is absent
     * or answers nothing the rider is told which stop could not be found rather
     * than having the route built without it.
     */
    private fun geocode(parsed: RouteLink.Result.Route) {
        state("looking up ${parsed.unresolved.size} place(s)")
        if (!Geocoder.isPresent()) {
            problem("this phone cannot look up place names. Share a link with coordinates in it.")
            return
        }
        val geocoder = Geocoder(this, Locale.getDefault())
        io.execute {
            val found = ArrayList<RouteLink.Stop>()
            var missing: String? = null
            for (name in parsed.unresolved) {
                @Suppress("DEPRECATION")
                val hit = runCatching { geocoder.getFromLocationName(name, 1) }.getOrNull()?.firstOrNull()
                if (hit == null) {
                    missing = name
                    break
                }
                found.add(RouteLink.Stop(hit.latitude, hit.longitude, name))
            }
            MainThread.post {
                if (missing != null) {
                    problem("could not find \"$missing\". Share a link with coordinates in it.")
                    return@post
                }
                // Appended rather than interleaved: the parser reports the
                // unresolved names in order but not where they sat, and a stop
                // put back in the wrong place bends the route silently. Shown to
                // the rider for exactly that reason.
                stops = parsed.stops + found
                render()
            }
        }
    }

    private fun onSendPressed() {
        if (busy || stops.size < 2) return
        val bridge = service ?: run {
            problem("the bridge is not running")
            return
        }
        busy = true
        state("routing ${stops.size} stops")
        render()
        router.route(stops, mode) { result ->
            when (result) {
                is RouteService.Result.Failed -> {
                    busy = false
                    problem("could not work out a route: ${result.reason}")
                    render()
                }

                is RouteService.Result.Ok -> queue(bridge, result.latLon)
            }
        }
    }

    private fun queue(bridge: BridgeService, line: List<Pair<Double, Double>>) {
        val name = routeName()
        val merc = RouteWriter.routeFromLatLon(line, SIMPLIFY_METRES)
        val bytes = runCatching {
            RouteWriter.pack(merc, name, System.currentTimeMillis() / 1000L)
        }.getOrElse {
            busy = false
            problem("could not build the route file: ${it.message}")
            render()
            return
        }

        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().time)
        val fileName = RouteOutbox.fileNameFor(name, today, suffix = bridge.routesWaiting())
        val refusal = bridge.routeQueue(fileName, bytes)
        busy = false
        if (refusal != null) {
            problem(refusal)
            render()
            return
        }
        // Queued, not sent: the device advertises only while its map or sync
        // screen is open, so "it will go when the device is there" is the honest
        // sentence and the usual one.
        state("queued: ${merc.size} points, ${bytes.size / 1024} kB")
        tvNote.text = "It goes to the device the next time the map or the sync screen is open. " +
            "The route is listed under Trips."
        btnSend.isEnabled = false
    }

    /**
     * The route's own name, which is what the device's picker draws.
     *
     * Built from the first and last stop that have a **name**, not the first
     * and last stop. A phone share starts from "my location", which is a bare
     * coordinate with no label, so taking stops.first() blindly named a
     * six-stop route "Trnava" -- measured on the first real share, 2026-10-27.
     */
    private fun routeName(): String {
        val named = stops.mapNotNull { it.label }
        val first = named.firstOrNull()
        val last = named.lastOrNull()
        return when {
            first != null && last != null && first != last -> "$first - $last"
            last != null -> last
            else -> SimpleDateFormat("d.M.yyyy", Locale.US).format(Calendar.getInstance().time)
        }
    }

    private fun state(text: String) {
        tvState.text = text
        tvProblem.visibility = android.view.View.GONE
    }

    private fun problem(text: String) {
        Log.w(TAG, text)
        tvProblem.text = text
        tvProblem.visibility = android.view.View.VISIBLE
        btnSend.isEnabled = false
    }

    private fun render() {
        if (stops.isEmpty()) return
        tvState.text = if (busy) tvState.text.toString() else "shared route"
        // Counted out loud, in the rider's own units: a stop the parser lost is
        // invisible in a list they did not count against what they built.
        tvCounts.text = "${stops.size} stops, mode ${mode.name.lowercase()}" +
            ", router profile ${BRouterService.profileFor(mode)}"
        tvStops.text = stops.mapIndexed { i, s ->
            val label = s.label ?: "(coordinate)"
            String.format(Locale.US, "%d. %-24s %.5f, %.5f", i + 1, label.take(24), s.lat, s.lon)
        }.joinToString("\n")
        tvNote.text = "Google shares the stops, not the line. The route drawn on the device is " +
            "worked out by ${router.describe()} and will differ from what you saw in Google Maps."
        btnSend.isEnabled = !busy && stops.size >= 2
    }

    companion object {
        private const val TAG = "ShareRouteActivity"

        /**
         * Douglas-Peucker tolerance. 5 m is what the laptop route builder uses
         * (`mapbuilder/build_route.py`), and the real cap on points is panel
         * redraw time rather than file size -- unmeasured, `T-131`.
         */
        private const val SIMPLIFY_METRES = 5.0
    }
}
