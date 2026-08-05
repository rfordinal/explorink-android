package org.trailink.gpsbridge

import android.util.Log
import java.io.File

/**
 * Where a tile's bytes come from when the device asks for it.
 *
 * **This interface is the seam the real tile CDN slots into.** The plan
 * (`docs/missing-tiles-fetch-plan.md`) names a public tile CDN as the real
 * source; it does not exist yet and building it is a separate, larger job. So
 * today the only implementation reads a directory on the phone laid out exactly
 * like the CDN's `out_dir` would be. When the CDN is real, an HTTP GET
 * implementation replaces [FileTileSource] behind this one call shape --
 * `(z, col, row) -> bytes or miss` -- and nothing else in the app changes.
 *
 * Keep the shape. A source that needs a different call shape (a batch fetch, a
 * suspend function, a callback) is a source that makes every caller change with
 * it.
 */
interface TileSource {
    /** The tile's bytes, or null if this source does not have it. */
    fun read(z: Int, col: Long, row: Long): ByteArray?

    /** For the UI and the log: what this source is, in a few words. */
    fun describe(): String
}

/**
 * Reads tiles out of a directory on the phone -- the stand-in CDN.
 *
 * Layout under [root] is the same one `mapbuilder` writes and the device reads:
 *
 *     <root>/base/<z>/<col>/<row>.tib
 *
 * Populating it (adb push of a `mapbuilder out_dir`, a file manager, whatever
 * is convenient) is a manual test-setup step, not app functionality.
 */
class FileTileSource(private val root: File) : TileSource {

    companion object {
        private const val TAG = "FileTileSource"

        /**
         * A tile larger than this is not pushed. The device refuses a begin over
         * 8 MB outright (`docs/ble-map-transfer-protocol.md`), and this channel
         * is the small/urgent path anyway -- a whole region goes on the card over
         * WiFi/SD. Reading a huge file into memory to be refused is worse than
         * not reading it.
         */
        const val MAX_TILE_BYTES = TransferFrames.MAX_FILE_BYTES
    }

    override fun read(z: Int, col: Long, row: Long): ByteArray? {
        val file = File(root, TransferFrames.tileRelPath(z, col, row))
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0 || length > MAX_TILE_BYTES) {
            Log.w(TAG, "skipping ${file.path}: $length bytes")
            return null
        }
        return try {
            file.readBytes()
        } catch (t: Throwable) {
            // A miss, not a crash: the fetch tells the device `skip` and moves on.
            Log.w(TAG, "read failed ${file.path}", t)
            null
        }
    }

    override fun describe(): String = "local ${root.path}"
}
