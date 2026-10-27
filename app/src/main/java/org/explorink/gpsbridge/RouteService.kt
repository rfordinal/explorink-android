package org.explorink.gpsbridge

/**
 * Turns the rider's stops into a line on the ground.
 *
 * A shared Google Maps link carries **waypoints and no geometry** -- no
 * polyline exists in it at any point (`docs/route-share-plan.md`, "The hard
 * part is Google, not us"). So the line the device draws is this router's, not
 * Google's, and it will differ from what the rider saw on the Google screen.
 * That is a property of the feature; the app says it once, plainly, rather
 * than pretending otherwise.
 *
 * Behind an interface because the implementation is going to be replaced: a
 * public endpoint is fine while this is being built and is not fine shipped --
 * five stops plus a timestamp is where somebody rides, handed to a third party
 * on every share (`T-325`).
 */
interface RouteService {

    sealed class Result {
        /** The routed line, `[(lat, lon), ...]`, dense, start to finish. */
        class Ok(val latLon: List<Pair<Double, Double>>) : Result()

        /**
         * No line. **Never a straight one instead**: a straight line between
         * two towns, drawn on a map somebody is finding their way by, is a lie
         * the device has no way to caveat.
         */
        class Failed(val reason: String) : Result()
    }

    /**
     * All the stops in one request, in order.
     *
     * Never leg by leg and concatenated: the joins get duplicated points and a
     * U-turn wherever the router enters a stop from the wrong side.
     *
     * [done] is called exactly once, on the thread that called this.
     */
    fun route(stops: List<RouteLink.Stop>, mode: RouteLink.Mode, done: (Result) -> Unit)

    /** For the log line and the rider-facing notice. */
    fun describe(): String
}
