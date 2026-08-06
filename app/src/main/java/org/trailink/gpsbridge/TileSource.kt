package org.trailink.gpsbridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Where a tile's bytes come from when the device asks for it.
 *
 * **Asynchronous, and it has to be.** The fetch runs on the service's main
 * thread, because that is where BLE lives, and Android throws
 * `NetworkOnMainThreadException` for an HTTP call there -- so a source that
 * reaches the network cannot be a plain function returning bytes. The callback
 * is invoked on the main thread, exactly once, so the state machine keeps its
 * single-threaded contract ([TileFetcher]).
 *
 * `formatVersion` is what the **device** asked for (`NEED_TILES ... fmt N`), not
 * something the app decides. The CDN publishes under `/v<N>/`, one path per
 * `.tib` format version, so passing the device's number straight through makes
 * a format mismatch impossible by construction rather than by a check after the
 * fact.
 *
 * **One implementation, and it is the CDN.** There used to be a directory on the
 * phone as well -- a stand-in from before the CDN existed, then briefly a cache.
 * Both are gone: a tile belongs on the X4 or on the CDN, the phone is the pipe
 * between them, and a second source is only somewhere for the two to disagree.
 * The interface stays because it is what keeps the network out of the state
 * machine and out of its tests.
 */
interface TileSource {
    /** Hands back the tile's bytes, or null if this source does not have it. */
    fun read(z: Int, col: Long, row: Long, formatVersion: Int?, done: (ByteArray?) -> Unit)

    /** For the UI and the log: what this source is, in a few words. */
    fun describe(): String

    /** Releases any threads. Called when the service stops. */
    fun close() {}
}

/**
 * Fetches tiles from the public tile CDN.
 *
 * `https://tiles.trailink-app.com/v<format>/base/<z>/<col>/<row>.tib` --
 * versioned by `.tib` `FORMAT_VERSION`, one path per version, so a device on v2
 * and a device on v3 read different trees and neither goes silently stale
 * (`docs/tile-cdn-plan.md`, "Versioning: path prefix").
 *
 * Static files, no API: a miss is a 404 and that is the whole protocol.
 */
class CdnTileSource(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultFormatVersion: Int = DEFAULT_FORMAT_VERSION,
) : TileSource {

    companion object {
        private const val TAG = "CdnTileSource"

        const val DEFAULT_BASE_URL = "https://tiles.trailink-app.com"

        /**
         * Used only when the device did not say which format it reads -- an
         * older firmware build, before `NEED_TILES` carried `fmt`. Pushing the
         * wrong version wastes a transfer, so this is a guess of last resort
         * rather than a default anybody should rely on.
         */
        const val DEFAULT_FORMAT_VERSION = 2

        /**
         * A tile larger than this is not pushed. The device refuses a begin over
         * 8 MB outright (`docs/ble-map-transfer-protocol.md`), so downloading
         * one only to be refused is work for nothing.
         */
        const val MAX_TILE_BYTES = TransferFrames.MAX_FILE_BYTES

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tile-cdn") }

    override fun read(z: Int, col: Long, row: Long, formatVersion: Int?, done: (ByteArray?) -> Unit) {
        io.execute {
            val bytes = readBlocking(z, col, row, formatVersion)
            MainThread.post { done(bytes) }
        }
    }

    fun readBlocking(z: Int, col: Long, row: Long, formatVersion: Int?): ByteArray? {
        val version = formatVersion ?: defaultFormatVersion
        val url = "$baseUrl/v$version/${TransferFrames.tileRelPath(z, col, row)}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept-Encoding", "identity")
            }
            when (val code = conn.responseCode) {
                200 -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > MAX_TILE_BYTES) {
                        Log.w(TAG, "$url is ${bytes.size} bytes, over the transfer cap")
                        null
                    } else {
                        bytes
                    }
                }
                // A miss. The CDN only holds areas somebody has built, so this
                // is ordinary, not an error -- the fetch answers `skip nosource`.
                404 -> null
                else -> {
                    Log.w(TAG, "$url -> HTTP $code")
                    null
                }
            }
        } catch (t: Throwable) {
            // No network, DNS down, TLS refused: all a miss from here. The rider
            // gets `skip`, not a crash mid-ride.
            Log.w(TAG, "fetch failed $url: ${t.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    override fun describe(): String = "CDN $baseUrl"

    override fun close() {
        io.shutdown()
    }
}
