package org.explorink.gpsbridge

/**
 * Decides which of this app's three share destinations a piece of shared text
 * belongs to.
 *
 * **Why one receiver and not three filters.** `ACTION_SEND` / `text/plain` was
 * claimed twice already -- the pins window and the map-area window -- and a
 * third entry makes the system share sheet a list the rider has to read every
 * time. One entry classifies instead, shows what it chose, and lets them change
 * it.
 *
 * It classifies **text**, so a short link is a verdict of its own: the
 * coordinates are not in it at all and only a redirect produces them
 * ([MapsShortLink]). The caller resolves it and asks again.
 *
 * Pure: no Android, no network. The destination is a decision, not an action.
 */
object ShareRouting {

    enum class Target {
        /** A directions link: several ordered points. [RouteLink] reads it. */
        ROUTE,

        /** A place, a coordinate pair, DMS: one point. [PinCoordinates] reads it. */
        PIN,

        /** A short link: resolve it, then ask again. */
        RESOLVE_FIRST,

        /** Nothing this app can read. Say so rather than open a screen. */
        UNKNOWN,
    }

    fun classify(sharedText: String?): Target {
        val text = sharedText?.trim().orEmpty()
        if (text.isEmpty()) return Target.UNKNOWN

        if (MapsShortLink.isShortLink(text)) return Target.RESOLVE_FIRST

        // A directions link is the only shape that carries more than one point,
        // and `/maps/dir` is what names it in both the share form and the
        // documented `api=1` form.
        if (text.contains("/maps/dir")) return Target.ROUTE

        // Everything the pin parser already reads, and it is the authority on
        // that: asking it is cheaper than keeping a second list of shapes here
        // that drifts from the first.
        if (PinCoordinates.parse(text) is PinCoordinates.Result.Parsed) return Target.PIN

        // A place link carries a feature id and no coordinates, so the parser
        // above refuses it -- but it is still a place the rider meant to pin,
        // and the pins screen is where its refusal is explained.
        if (text.contains("/maps/place") || text.contains("google.com/maps")) return Target.PIN

        return Target.UNKNOWN
    }
}
