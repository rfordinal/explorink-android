package org.explorink.gpsbridge

/**
 * The pin types the device offers, mirrored from the firmware's catalogue
 * (`firmware/explorink/src/activities/map/PinCatalog.h`, `kPinCatalog`).
 *
 * **The key is the contract, not the order.** The device's log stores the stable
 * text key and never an index into that table, so a row added or moved in the
 * firmware cannot change what an old record means -- and this table only has to
 * agree with it on the keys (`firmware/explorink/docs/pins.md`, "The catalogue
 * is soft, the log is hard").
 *
 * The order here is only the order the save spinner offers, and it is the
 * firmware's order so the two screens read the same way.
 *
 * A key this build does not know is **not an error**: the device may run a newer
 * firmware with more types, and it lists them like any other. [labelFor] hands
 * back the raw key for those, which is exactly what the device's own Pins list
 * does with a foreign key.
 */
object PinKinds {

    class Kind(val key: String, val label: String)

    val ALL: List<Kind> = listOf(
        Kind("base", "Base"),
        Kind("parking", "Parking"),
        Kind("dest", "Destination"),
        Kind("meet", "Meet"),
        Kind("camp", "Camp"),
        Kind("favorite", "Favorite"),
        Kind("c1", "#1"),
        Kind("c2", "#2"),
        Kind("c3", "#3"),
        Kind("c4", "#4"),
        Kind("c5", "#5"),
    )

    /** The catalogue label, or the raw key when this build does not know it. */
    fun labelFor(key: String): String = ALL.firstOrNull { it.key == key }?.label ?: key

    /**
     * True when [key] is one this build's catalogue names.
     *
     * Only used to decide whether the app may *offer* a type to save. It is
     * never used to filter a listing: the device is authoritative, and a pin it
     * reports is a pin the rider has, known key or not.
     */
    fun isKnown(key: String): Boolean = ALL.any { it.key == key }
}
