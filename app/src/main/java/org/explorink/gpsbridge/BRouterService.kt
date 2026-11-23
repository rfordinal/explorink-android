package org.explorink.gpsbridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Routes through a BRouter HTTP server.
 *
 * API read off the project's own documentation
 * (`abrensch/brouter`, `docs/developers/http_server.md`) rather than a
 * tutorial: a `GET` to `/brouter` with `lonlats`, `profile`, `alternativeidx`
 * and `format`, where
 *
 *     lonlats=8.723037,50.000491|8.712737,50.002899&profile=trekking&alternativeidx=0&format=geojson
 *
 * **`lonlats` is longitude first.** Everything else in this app is latitude
 * first ([RouteLink.Stop], [RouteWriter.project], the position packet), so this
 * is the one place the order flips, and a swap here puts a Slovak route in
 * Somalia rather than failing.
 *
 * Profiles are the file names in the project's `misc/profiles2`, read from the
 * repository: `car-vario`, `trekking`, `hiking-mountain` and the rest. There is
 * no `car-fast` and no plain `hiking`, whatever a search suggests.
 *
 * **The public endpoint's terms are `[open]`.** Neither `brouter.de` nor the
 * project documentation states a usage policy, a rate limit or a rule about
 * third-party apps, checked 2026-10-27. So this default is for a prototype, and
 * our own instance is what ships (`T-325` in the xteink repo): a public router
 * also learns the rider's whole trip on every share.
 */
class BRouterService(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val callbackThread: (() -> Unit) -> Unit = MainThread::post,
) : RouteService {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "brouter") }

    override fun route(stops: List<RouteLink.Stop>, mode: RouteLink.Mode, done: (RouteService.Result) -> Unit) {
        if (stops.size < 2) {
            done(RouteService.Result.Failed("a route needs at least two stops"))
            return
        }
        val url = buildUrl(baseUrl, stops, mode)
        io.execute {
            val result = fetch(url)
            callbackThread { done(result) }
        }
    }

    override fun describe(): String = baseUrl

    private fun fetch(url: String): RouteService.Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code != 200) {
                // BRouter answers a routing failure with a plain-text message
                // and a non-200, so the body is the reason worth showing.
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }?.trim()
                Log.w(TAG, "HTTP $code from $baseUrl: $body")
                return RouteService.Result.Failed(body?.take(200) ?: "router answered HTTP $code")
            }
            parseGeoJson(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            Log.w(TAG, "routing through $baseUrl", t)
            RouteService.Result.Failed(t.message ?: "could not reach the router")
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "BRouterService"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Named so the operator of a shared service can see who is calling. */
        private const val USER_AGENT = "ExplorInk-GPS/1.0 (+https://explorink.com)"

        const val DEFAULT_BASE_URL = "https://brouter.de/brouter"

        /**
         * Profile per travel mode, from the link's own `!3e<n>`.
         *
         * Transit has no router here and is deliberately not refused: the rider
         * shared a route and wants the shape of it. It routes as a car and the
         * confirm screen says which profile was used, so the rider can see the
         * mismatch rather than discover it on the trail.
         */
        fun profileFor(mode: RouteLink.Mode): String = when (mode) {
            RouteLink.Mode.BICYCLE -> "trekking"
            RouteLink.Mode.WALK -> "hiking-mountain"
            RouteLink.Mode.CAR, RouteLink.Mode.TRANSIT, RouteLink.Mode.UNKNOWN -> "car-vario"
        }

        /** Pure, so the one place latitude and longitude swap is unit tested. */
        fun buildUrl(baseUrl: String, stops: List<RouteLink.Stop>, mode: RouteLink.Mode): String {
            val lonlats = stops.joinToString("|") { "${trim(it.lon)},${trim(it.lat)}" }
            return "$baseUrl?lonlats=$lonlats&profile=${profileFor(mode)}" +
                "&alternativeidx=0&format=geojson"
        }

        /**
         * Six decimals is 0.11 m at the equator, well under the whole-metre
         * rounding `.tir` does anyway, and it keeps the URL short enough that a
         * ten-stop route stays inside any proxy's line limit.
         */
        private fun trim(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)

        /**
         * GeoJSON out, `[(lat, lon), ...]` back.
         *
         * Parsed with this app's own [Json] and not `org.json`: the platform
         * class is a stub in unit tests that answers defaults instead of
         * throwing, so a parser built on it is unverifiable off-device
         * ([Json]'s own header has the measurement).
         *
         * GeoJSON coordinates are `[lon, lat]`, the same flip as the request.
         */
        fun parseGeoJson(text: String): RouteService.Result {
            val root = runCatching { Json.parse(text) }.getOrElse {
                return RouteService.Result.Failed("the router's answer was not JSON")
            }
            val features = ((root as? Map<*, *>)?.get("features") as? List<*>)
                ?: return RouteService.Result.Failed("the router's answer carried no features")
            val geometry = (features.firstOrNull() as? Map<*, *>)?.get("geometry") as? Map<*, *>
                ?: return RouteService.Result.Failed("the router's answer carried no geometry")
            val coordinates = geometry["coordinates"] as? List<*>
                ?: return RouteService.Result.Failed("the router's answer carried no coordinates")

            val out = ArrayList<Pair<Double, Double>>(coordinates.size)
            for (point in coordinates) {
                val pair = point as? List<*> ?: continue
                val lon = (pair.getOrNull(0) as? Number)?.toDouble() ?: continue
                val lat = (pair.getOrNull(1) as? Number)?.toDouble() ?: continue
                out.add(lat to lon)
            }
            // Two points is a straight line, which is exactly what this feature
            // must never draw. A router that answers that has not routed.
            if (out.size < 2) return RouteService.Result.Failed("the router returned no line")
            return RouteService.Result.Ok(out)
        }
    }
}
