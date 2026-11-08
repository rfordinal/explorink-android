package org.explorink.gpsbridge

/**
 * Which glyph a pin kind is drawn with, mirrored from the firmware's own
 * table (`firmware/explorink/src/components/icons/pins.icons.txt`, baked by
 * `PinIcons.h` into the balloon the device draws on the map). Kept apart
 * from [PinKinds] the same way the firmware keeps `PinCatalog.h` (what a
 * kind is) apart from `PinIcons.h` (what it looks like): the catalogue is
 * the log's contract, the glyph is not.
 *
 * `parking` and `c1`-`c5` have no Lucide source on the device either -- the
 * firmware bakes a "P" or a digit from a font instead, because a parking
 * glyph and `circle-parking` both read as mush at the panel's size
 * (`pins.icons.txt`, 2026-08-17). This mirrors that: [glyphTextFor] hands
 * back the same character for those keys, [iconFor] returns null.
 *
 * A key this build does not know gets neither (both functions return null
 * for it) -- the two call sites (PinsActivity.renderRows, PinKindAdapter)
 * show a literal "?" in the glyph slot for that case rather than leaving it
 * empty, the same way [PinKinds.isKnown] lets the row still show with its
 * raw key for a label (per [PinKinds.labelFor]) instead of hiding the row.
 */
object PinIcons {

    fun iconFor(key: String): Int? = when (key) {
        "base" -> R.drawable.ic_house
        "dest" -> R.drawable.ic_pin_dest
        "meet" -> R.drawable.ic_pin_meet
        "camp" -> R.drawable.ic_pin_camp
        "favorite" -> R.drawable.ic_pin_favorite
        else -> null
    }

    fun glyphTextFor(key: String): String? = when (key) {
        "parking" -> "P"
        "c1" -> "1"
        "c2" -> "2"
        "c3" -> "3"
        "c4" -> "4"
        "c5" -> "5"
        else -> null
    }
}
