package org.explorink.gpsbridge

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Reads the stops out of a shared Google Maps directions link.
 *
 * Measured shape, 2026-10-27, from a share made in the Google Maps Android app
 * (`docs/route-share-plan.md`, "What the capture found" -- numbers and names
 * redacted there because this repository is public):
 *
 *     /maps/dir/<lat>,<lon>/Villa+Name/Other+Place/data=
 *       !4m19!4m18!1m1!4e1
 *       !1m5!1m4!1s0x476c....:0x....!8m2!3d<lat>!4d<lon>
 *       !1m5!1m4!1s0x476c....:0x....!8m2!3d<lat>!4d<lon>
 *       !2m2!2b1!11b1!3e0
 *
 * Three facts that decide how this is written:
 *
 * - **A named stop carries its coordinates** in the `data=` blob, in the order
 *   of the path segments. So the common case needs no geocoder -- a name with
 *   no group behind it is the exception, and it is reported rather than guessed
 *   at.
 * - **The origin is not a stop.** It sits in the path as `lat,lon` with
 *   `!1m1!4e1` where its group would be. Reading that as a "my location"
 *   marker is a guess; nothing here depends on it, because the coordinates are
 *   in the path already.
 * - **`!3e<n>` is the travel mode**, which names the router profile instead of
 *   the app defaulting to one.
 *
 * So the parse **walks the path segments in order** and a named segment
 * consumes the next unused group. Aligning by position is the only rule that
 * survives a route mixing typed coordinates with picked places. Aligning by
 * name cannot work at all: the blob carries no names.
 *
 * None of this is documented by Google and all of it will change without
 * notice. A link that cannot be read whole is [Unreadable], never a route
 * built from the half that parsed -- the device draws what it is given and has
 * no way to caveat a line that is wrong.
 *
 * Pure: no Android, no network. Resolving a short link first is
 * [MapsShortLink]'s job.
 */
object RouteLink {

    /** One point of the route, with the label the link called it by. */
    data class Stop(val lat: Double, val lon: Double, val label: String?)

    /** What `!3e<n>` said. Decides the routing profile, not how this parses. */
    enum class Mode { CAR, BICYCLE, WALK, TRANSIT, UNKNOWN }

    sealed class Result {
        /**
         * [stops] in the rider's own order, first is the start and last is the
         * destination. [unresolved] names the stops that carried no
         * coordinates, in order -- they need a geocoder before this is a route.
         */
        class Route(
            val stops: List<Stop>,
            val mode: Mode,
            val unresolved: List<String>,
        ) : Result()

        /** A link that is a place, a search, or anything else with no route in it. */
        class NotARoute(val reason: String) : Result()

        /** A directions link this parser could not read whole. */
        class Unreadable(val reason: String) : Result()
    }

    private val MAPS_HOSTS = setOf("www.google.com", "google.com", "maps.google.com")

    /** `!3d<lat>!4d<lon>`, the pair each picked place carries in the blob. */
    private val BLOB_PAIR = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")

    /** `!3e<n>`, the travel mode, at the tail of the blob. */
    private val BLOB_MODE = Regex("""!3e(\d)""")

    /** A path segment that is already a coordinate: `48.1574598,17.0477014`. */
    private val COORD_SEGMENT = Regex("""(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")

    fun parse(url: String): Result {
        val host = runCatching { java.net.URL(url).host?.lowercase() }.getOrNull()
            ?: return Result.Unreadable("not a URL")
        if (host !in MAPS_HOSTS) return Result.NotARoute("not a Google Maps link")

        val path = url.substringAfter(host, "").substringBefore('?').substringBefore('#')
        if (!path.contains("/maps/dir")) {
            // A place share is the other thing a rider sends, and it is one
            // point. Saying which of the two they did is the part they can act
            // on; a two-point .tir drawn as a straight line across a field is
            // not.
            return Result.NotARoute("that is a place, not a route")
        }

        apiWaypoints(url)?.let { return it }

        val segments = path.substringAfter("/maps/dir").trim('/').split('/')
            .filter { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("data=") && it != "maps" }
        if (segments.isEmpty()) return Result.Unreadable("a dir link with no stops in its path")

        val blob = path.substringAfter("data=", "")
        val pairs = BLOB_PAIR.findAll(blob)
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            .toMutableList()
        val mode = modeOf(blob)

        val stops = ArrayList<Stop>(segments.size)
        val unresolved = ArrayList<String>()
        for (segment in segments) {
            val coord = COORD_SEGMENT.matchEntire(segment)
            if (coord != null) {
                val lat = coord.groupValues[1].toDouble()
                val lon = coord.groupValues[2].toDouble()
                if (!inRange(lat, lon)) return Result.Unreadable("a stop outside the world: $segment")
                stops.add(Stop(lat, lon, null))
                continue
            }
            val label = decode(segment)
            val pair = pairs.removeFirstOrNull()
            if (pair == null) {
                // Reported, not guessed at. The caller geocodes it or asks.
                unresolved.add(label)
                continue
            }
            if (!inRange(pair.first, pair.second)) {
                return Result.Unreadable("a stop outside the world: $label")
            }
            stops.add(Stop(pair.first, pair.second, label))
        }

        if (stops.size + unresolved.size < 2) {
            return Result.NotARoute("one point is a place, not a route")
        }
        return Result.Route(stops, mode, unresolved)
    }

    /**
     * The documented `?api=1&origin=&destination=&waypoints=` form.
     *
     * Not what a phone share produces -- it is what another app builds or a
     * person types. Tried first anyway, because it is the only shape Google
     * actually documents, and a link carrying it means it.
     */
    private fun apiWaypoints(url: String): Result? {
        val query = url.substringAfter('?', "").ifEmpty { return null }
        val params = query.split('&').mapNotNull {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to v
        }.toMap()
        if (params["api"] != "1") return null

        val ordered = buildList {
            params["origin"]?.let { add(it) }
            params["waypoints"]?.split("%7C", "|")?.filter { it.isNotEmpty() }?.forEach { add(it) }
            params["destination"]?.let { add(it) }
        }
        if (ordered.size < 2) return null

        val stops = ArrayList<Stop>()
        val unresolved = ArrayList<String>()
        for (raw in ordered) {
            val text = decode(raw)
            val coord = COORD_SEGMENT.matchEntire(text.replace(" ", ""))
            if (coord == null) {
                // No blob to fall back on in this form: a name here really does
                // need a geocoder.
                unresolved.add(text)
                continue
            }
            val lat = coord.groupValues[1].toDouble()
            val lon = coord.groupValues[2].toDouble()
            if (!inRange(lat, lon)) return Result.Unreadable("a stop outside the world: $text")
            stops.add(Stop(lat, lon, null))
        }
        return Result.Route(stops, modeOfTravelMode(params["travelmode"]), unresolved)
    }

    private fun modeOf(blob: String): Mode = when (BLOB_MODE.find(blob)?.groupValues?.get(1)) {
        "0" -> Mode.CAR
        "1" -> Mode.BICYCLE
        "2" -> Mode.WALK
        "3" -> Mode.TRANSIT
        else -> Mode.UNKNOWN
    }

    private fun modeOfTravelMode(value: String?): Mode = when (value?.lowercase()) {
        "driving" -> Mode.CAR
        "bicycling" -> Mode.BICYCLE
        "walking" -> Mode.WALK
        "transit" -> Mode.TRANSIT
        else -> Mode.UNKNOWN
    }

    private fun inRange(lat: Double, lon: Double): Boolean =
        lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0

    private fun decode(segment: String): String =
        runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) }
            .getOrDefault(segment.replace('+', ' '))
}
