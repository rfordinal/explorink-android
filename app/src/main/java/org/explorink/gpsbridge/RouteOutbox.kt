package org.explorink.gpsbridge

import android.util.Log
import java.io.File

/**
 * Routes built but not yet on the device, kept on disk until they land.
 *
 * **The device is usually absent when a route is shared, and that is the normal
 * case rather than an error.** It advertises only while the map or the sync
 * screen is open (`docs/ble-map-transfer-protocol.md`, "Device availability"),
 * so a rider sharing a route from the sofa has nowhere to send it yet. Holding
 * the bytes and pushing them at the next connection is what makes the feature
 * usable at all; without it, the first realistic attempt fails.
 *
 * On disk, not in memory, for the same reason the tile outbox is persisted: the
 * service is killed between a share and the next ride, and a queue the rider has
 * to rebuild is a queue in name only.
 *
 * One file per route, named by the path it is destined for under
 * `/trailink/trips/`. The name is decided once, when the route is built, and is
 * never reused -- overwriting a route the map has open stops it drawing with no
 * error and no banner (`firmware/explorink/docs/route-layer.md`, "Do not
 * overwrite a route the map has open").
 */
class RouteOutbox(private val dir: File) {

    /** One queued route: the bytes and the file name they go to. */
    data class Entry(val fileName: String, val bytes: ByteArray) {
        /** Relative to `/trailink`, which is what the transfer channel takes. */
        val relPath: String get() = "$TRIPS_DIR/$fileName"

        // Data class with a ByteArray: equals/hashCode would compare the array
        // by identity, and nothing here does either. Written out so that is a
        // decision rather than an oversight.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun add(fileName: String, bytes: ByteArray): Boolean {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create $dir")
            return false
        }
        return try {
            // Write then rename, so a kill mid-write cannot leave a half route
            // in the queue to be pushed as if it were whole. Same rule the
            // device applies to an arriving file.
            val tmp = File(dir, "$fileName.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(dir, fileName))) {
                tmp.delete()
                return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "queueing $fileName", t)
            false
        }
    }

    /** Oldest first: a rider who shared two routes gets them in that order. */
    fun next(): Entry? {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: return null
        val oldest = files.minByOrNull { it.lastModified() } ?: return null
        return try {
            Entry(oldest.name, oldest.readBytes())
        } catch (t: Throwable) {
            Log.w(TAG, "reading ${oldest.name}", t)
            null
        }
    }

    fun count(): Int = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }?.size ?: 0

    /** Called when the device has confirmed the file, never before. */
    fun remove(fileName: String) {
        File(dir, fileName).delete()
    }

    companion object {
        private const val TAG = "RouteOutbox"

        /** What the device's picker lists (`MapRouteStore.h`, `kTripsDir`). */
        const val TRIPS_DIR = "trips"
        const val EXTENSION = ".tir"

        /**
         * A file name for a route, from its own name plus the date.
         *
         * ASCII only and lower case: the device's picker reads the file name
         * off a FAT card and draws the route's *header* name, not this
         * (`MapRouteStore.h`). A diacritic here buys nothing and costs a path
         * the firmware may not open.
         *
         * [suffix] distinguishes two routes shared the same day. Never reuse a
         * name: see the class comment.
         */
        fun fileNameFor(routeName: String, dateYmd: String, suffix: Int = 0): String {
            val slug = routeName.lowercase()
                .map { c ->
                    when {
                        c in 'a'..'z' || c in '0'..'9' -> c
                        c == 'á' || c == 'ä' || c == 'à' -> 'a'
                        c == 'é' || c == 'ě' -> 'e'
                        c == 'í' -> 'i'
                        c == 'ó' || c == 'ô' || c == 'ö' -> 'o'
                        c == 'ú' || c == 'ů' || c == 'ü' -> 'u'
                        c == 'ý' -> 'y'
                        c == 'č' -> 'c'
                        c == 'ď' -> 'd'
                        c == 'ľ' || c == 'ĺ' -> 'l'
                        c == 'ň' -> 'n'
                        c == 'ř' -> 'r'
                        c == 'š' -> 's'
                        c == 'ť' -> 't'
                        c == 'ž' -> 'z'
                        else -> '-'
                    }
                }
                .joinToString("")
                .trim('-')
                .replace(Regex("-{2,}"), "-")
                .take(MAX_SLUG_CHARS)
                .trim('-')
                .ifEmpty { "route" }
            val tail = if (suffix > 0) "-$suffix" else ""
            return "$slug-$dateYmd$tail$EXTENSION"
        }

        /**
         * Keeps the whole path inside the device's cap: `trips/` plus the name
         * plus `.part` during the transfer, against
         * `MapTransferReceiver::kMaxRelPathBytes` (80) and
         * `MapRouteStore::kMaxFileNameBytes` (48, nul included). 24 leaves room
         * for the date, a suffix and both.
         */
        private const val MAX_SLUG_CHARS = 24
    }
}
