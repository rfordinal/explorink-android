package org.explorink.gpsbridge

/**
 * Turns pasted text into a coordinate the device can be told to pin.
 *
 * This is the whole reason the phone is in the pins feature at all. The device
 * has no keyboard and no way to name a place: the rider picks a type and the
 * position is whatever the last fix said
 * (`firmware/explorink/docs/pins-plan.md`, "Philosophy"). So the one thing the
 * phone adds is a place the rider is **not** standing at -- a campsite found on
 * a map at home, a meeting point somebody sent in a chat.
 *
 * What it accepts, in the order it tries:
 *
 *  1. A Google Maps `!3d<lat>!4d<lon>` pair. Preferred over the `@` centre in the
 *     same URL: `@` is where the camera was, `!3d/!4d` is the place that was
 *     actually looked up, and on a place link the two differ by however far the
 *     view was dragged.
 *  2. A `q=`, `ll=`, `query=`, `daddr=` or `destination=` parameter carrying
 *     `lat,lon` -- what a share sheet and a `geo:` URI produce.
 *  3. An `@lat,lon` centre.
 *  4. A bare pair: `48.4372, 17.0186`, comma, semicolon or space separated.
 *
 * What it refuses, with a reason rather than a guess:
 *
 *  - **A short link** (`maps.app.goo.gl`, `goo.gl/maps`). The coordinates are not
 *    in the text; only a redirect would produce them, and this app makes no
 *    network request except to the tile CDN (`android/README.md`). Guessing here
 *    would mean pinning the wrong place silently.
 *  - **Degrees-minutes-seconds** (`48°26'13"N`). Not parsed yet; it is a real
 *    format riders meet, and pretending to read the first number out of it would
 *    pin a point 26 minutes of arc away.
 *  - Anything out of range, and anything with no pair in it at all.
 *
 * Pure: no Android, no network, host-tested.
 */
object PinCoordinates {

    sealed class Result {
        class Parsed(val latE7: Int, val lonE7: Int) : Result()

        /** Short, and written to be shown to the rider as it is. */
        class Failure(val reason: String) : Result()
    }

    private const val NUM = """[-+]?\d{1,3}(?:\.\d+)?"""

    private val PLACE_3D_4D = Regex("""!3d($NUM)!4d($NUM)""")
    private val PARAM = Regex(
        """(?:^|[?&])(?:q|ll|query|daddr|destination)=($NUM)\s*,\s*($NUM)""",
        RegexOption.IGNORE_CASE,
    )
    private val AT_CENTRE = Regex("""@($NUM)\s*,\s*($NUM)""")
    private val BARE_PAIR = Regex("""($NUM)\s*[,;\s]\s*($NUM)""")

    private val DMS_HINT = Regex("""[0-9]\s*[°'"´’”]""")
    private val SHORT_LINK = Regex("""(maps\.app\.goo\.gl|goo\.gl/maps)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Failure("Nothing pasted.")

        if (SHORT_LINK.containsMatchIn(t)) {
            return Result.Failure(
                "That is a short Google Maps link. Open it, then copy the coordinates " +
                    "or the full link."
            )
        }

        // `geo:48.43,17.01` has no parameter name in front of the pair, so it
        // falls through to the bare pair below -- deliberately, rather than a
        // fifth pattern that says the same thing.
        val match = PLACE_3D_4D.find(t)
            ?: PARAM.find(t)
            ?: AT_CENTRE.find(t)
            ?: BARE_PAIR.find(t)

        if (match == null) {
            if (DMS_HINT.containsMatchIn(t)) {
                return Result.Failure(
                    "Degrees, minutes and seconds are not read yet. Paste decimal " +
                        "degrees, like 48.4372, 17.0186."
                )
            }
            return Result.Failure("No coordinates in that text.")
        }

        val latE7 = PinList.parseDegreesE7(match.groupValues[1].removePrefix("+"))
        val lonE7 = PinList.parseDegreesE7(match.groupValues[2].removePrefix("+"))
        if (latE7 == null || lonE7 == null) return Result.Failure("Could not read those numbers.")
        // Range, both ends, before anything is sent: the device refuses out of
        // range with `ERR out_of_range`, and a rider who pasted a house number by
        // accident deserves to hear which half was wrong, not a wire error code.
        if (!PinList.isValidLatE7(latE7)) return Result.Failure("Latitude is out of range.")
        if (!PinList.isValidLonE7(lonE7)) return Result.Failure("Longitude is out of range.")
        return Result.Parsed(latE7, lonE7)
    }
}
