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
 *  4. **Degrees, minutes, seconds**: `48°09'05.4"N 17°07'47.1"E`. Google Maps
 *     shows a place's coordinates in this form, so it is what a rider copies
 *     most often. The hemisphere letters carry the sign and the order, so a
 *     `W`-then-`N` paste lands the right way round.
 *  5. A bare pair: `48.4372, 17.0186`, comma, semicolon or space separated.
 *
 * **DMS is tried before the bare pair, and a DMS-shaped text that cannot be read
 * is refused rather than falling through to it.** Measured on the phone
 * 2026-08-19: `48 09 05.4N 17 07 47.1E` (the same coordinate with the degree and
 * minute symbols stripped, which is what a share sheet or a keyboard can leave
 * behind) matched the bare pair on its first two numbers and offered to save
 * `48.0000000, 9.0000000` -- Germany, 700 km from the place asked for. Nothing in
 * the parser noticed; only the confirmation dialog showing the number back stood
 * between that and a pin on the card.
 *
 * What it refuses, with a reason rather than a guess:
 *
 *  - **A short link** (`maps.app.goo.gl`, `goo.gl/maps`). The coordinates are not
 *    in the text; only a redirect would produce them, and this app makes no
 *    network request except to the tile CDN (`android/README.md`). Guessing here
 *    would mean pinning the wrong place silently.
 *  - **A DMS-shaped text it cannot read whole** -- a hemisphere letter or a degree
 *    symbol with no readable degrees/minutes behind it.
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

    private val SHORT_LINK = Regex("""(maps\.app\.goo\.gl|goo\.gl/maps)""", RegexOption.IGNORE_CASE)

    /**
     * One DMS component: degrees, minutes, optional seconds, optional hemisphere.
     *
     * Every separator is optional because the symbols are what a copy, a share
     * sheet or a keyboard drops first -- `48°09'05.4"N` and `48 09 05.4 N` are the
     * same paste, and the second one is the dangerous one (see the class comment).
     *
     * Optional includes the whitespace, so on its own this would read `4809` as
     * 48 degrees 9 minutes. It is never applied on its own: [DMS_SHAPED] gates it,
     * and that needs a symbol, a hemisphere letter or three numbers in a row.
     */
    private const val DMS_ONE =
        """(\d{1,3})\s*[°d]?\s*(\d{1,2})\s*['′´m]?\s*(?:(\d{1,2}(?:[.,]\d+)?)\s*["″”s]?)?\s*([NSEWnsew])?"""

    private val DMS_PAIR = Regex("""$DMS_ONE\s*[,;]?\s*$DMS_ONE""")

    /**
     * Text that looks like DMS at all: a degree/minute symbol after a digit, a
     * hemisphere letter after a number, or three whitespace-separated numbers in a
     * row (`48 09 05.4`). Any of those and the bare pair is **not** consulted.
     */
    private val DMS_SHAPED = Regex(
        """[0-9]\s*[°'"´’′″”]|[0-9]\s*[NSEWnsew]\b|\d+\s+\d+\s+\d+"""
    )

    fun parse(text: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Failure("Nothing pasted.")

        if (SHORT_LINK.containsMatchIn(t)) {
            return Result.Failure(
                "That is a short Google Maps link. Open it, then copy the coordinates " +
                    "or the full link."
            )
        }

        // A URL's own parameters are unambiguous, so they are read before any
        // shape test: `!3d`, `q=` and `@` all carry decimal degrees by definition.
        val urlMatch = PLACE_3D_4D.find(t) ?: PARAM.find(t) ?: AT_CENTRE.find(t)

        // DMS before the bare pair, and DMS-shaped text never reaches the bare
        // pair at all -- `48 09 05.4N 17 07 47.1E` reads as `48, 9` there, which is
        // a different country and no warning (measured on the phone 2026-08-19).
        val match = if (urlMatch != null) {
            urlMatch
        } else if (DMS_SHAPED.containsMatchIn(t)) {
            return parseDms(t)
        } else {
            // `geo:48.43,17.01` has no parameter name in front of the pair, so it
            // lands here deliberately, rather than a pattern that says the same
            // thing again.
            BARE_PAIR.find(t) ?: return Result.Failure("No coordinates in that text.")
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

    /**
     * `48°09'05.4"N 17°07'47.1"E`, and the same thing with the symbols stripped.
     *
     * The hemisphere letters, when present, decide both the sign and which half is
     * the latitude -- some sources write longitude first, and a silent swap puts
     * the pin in the sea. With no letters the first component is the latitude,
     * which is the convention every other format here follows.
     */
    private fun parseDms(text: String): Result {
        val m = DMS_PAIR.find(text)
            ?: return Result.Failure(
                "That looks like degrees, minutes and seconds, but it could not be " +
                    "read. Try 48°09'05.4\"N 17°07'47.1\"E, or decimal degrees."
            )
        val first = component(m, 1) ?: return dmsUnreadable()
        val second = component(m, 5) ?: return dmsUnreadable()

        val firstIsLat = when {
            first.hemisphere in "NS" -> true
            first.hemisphere in "EW" -> false
            second.hemisphere in "NS" -> false
            second.hemisphere in "EW" -> true
            // No letters at all: latitude first, like every other format here.
            else -> true
        }
        val lat = if (firstIsLat) first else second
        val lon = if (firstIsLat) second else first

        val latE7 = lat.toE7()
        val lonE7 = lon.toE7()
        if (!PinList.isValidLatE7(latE7)) return Result.Failure("Latitude is out of range.")
        if (!PinList.isValidLonE7(lonE7)) return Result.Failure("Longitude is out of range.")
        return Result.Parsed(latE7, lonE7)
    }

    private fun dmsUnreadable(): Result = Result.Failure(
        "That looks like degrees, minutes and seconds, but it could not be read. " +
            "Try 48°09'05.4\"N 17°07'47.1\"E, or decimal degrees."
    )

    private class Dms(val deg: Long, val min: Long, val secMilli: Long, val hemisphere: String) {
        /**
         * Integer only, and rounded, not truncated: a second of arc is ~31 m and
         * the tenths in `05.4"` are ~3 m, so dropping the remainder of the
         * division would move the pin by more than the value the rider typed.
         */
        fun toE7(): Int {
            // 1 degree = 1e7 in E7. minutes/60 and milli-seconds/3600000, all
            // scaled to E7 in one expression so there is one rounding, not three.
            val tenMillionths =
                deg * 10_000_000L + (min * 10_000_000L + 30L) / 60L +
                    (secMilli * 10_000_000L + 1_800_000L) / 3_600_000L
            val signed = if (hemisphere == "S" || hemisphere == "W") -tenMillionths else tenMillionths
            return signed.toInt()
        }
    }

    /** Reads one DMS component out of [m], starting at group [at]. */
    private fun component(m: MatchResult, at: Int): Dms? {
        val deg = m.groupValues[at].toLongOrNull() ?: return null
        val min = m.groupValues[at + 1].toLongOrNull() ?: return null
        // Seconds may be absent (`48°09'N`) and may be written with a comma in a
        // Slovak locale -- both are the same number to the device.
        val secText = m.groupValues[at + 2].replace(',', '.')
        val secMilli = if (secText.isEmpty()) {
            0L
        } else {
            val asMilli = (secText.toDoubleOrNull() ?: return null) * 1000.0
            Math.round(asMilli)
        }
        if (min >= 60 || secMilli >= 60_000) return null
        return Dms(deg, min, secMilli, m.groupValues[at + 3].uppercase())
    }
}
