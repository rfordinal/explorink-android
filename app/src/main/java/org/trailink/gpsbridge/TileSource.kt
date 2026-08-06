package org.trailink.gpsbridge

import android.util.Log
import java.io.File
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
 * Reads tiles out of a directory on the phone.
 *
 * Two jobs: a place to drop a `mapbuilder out_dir` by hand for testing, and the
 * on-disk cache [CdnTileSource]'s results land in via [ChainTileSource].
 *
 * Layout under [root] is the same one `mapbuilder` writes and the device reads:
 *
 *     <root>/base/<z>/<col>/<row>.tib
 */
class FileTileSource(private val root: File) : TileSource {

    companion object {
        private const val TAG = "FileTileSource"

        /**
         * A tile larger than this is not pushed. The device refuses a begin over
         * 8 MB outright (`docs/ble-map-transfer-protocol.md`), and this channel
         * is the small/urgent path anyway.
         */
        const val MAX_TILE_BYTES = TransferFrames.MAX_FILE_BYTES
    }

    // One thread: reads are serialised anyway (the fetcher asks for one tile at
    // a time), and a pool would only add threads that sit idle mid-ride.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tile-file") }

    override fun read(z: Int, col: Long, row: Long, formatVersion: Int?, done: (ByteArray?) -> Unit) {
        io.execute {
            val bytes = readBlocking(z, col, row)
            MainThread.post { done(bytes) }
        }
    }

    /** Blocking read, for use from a worker -- [ChainTileSource] calls this too. */
    fun readBlocking(z: Int, col: Long, row: Long): ByteArray? {
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

    /** Stores a tile fetched elsewhere, so the next ask does not pay for it twice. */
    fun write(z: Int, col: Long, row: Long, bytes: ByteArray): Boolean {
        val file = File(root, TransferFrames.tileRelPath(z, col, row))
        return try {
            file.parentFile?.mkdirs()
            // Written beside the target and renamed, so an interrupted write
            // never leaves a half tile that reads as whole -- the same rule the
            // device applies to its own arrivals (MapTransferReceiver).
            val part = File(file.path + ".part")
            part.writeBytes(bytes)
            if (file.exists()) file.delete()
            part.renameTo(file)
        } catch (t: Throwable) {
            Log.w(TAG, "cache write failed ${file.path}", t)
            false
        }
    }

    override fun describe(): String = "local ${root.path}"

    override fun close() {
        io.shutdown()
    }
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
                    if (bytes.size > FileTileSource.MAX_TILE_BYTES) {
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

/**
 * Local first, then the CDN, and what the CDN gives back is kept.
 *
 * Local first because it costs no data and no latency, and because a tile
 * already on the phone is by definition one the CDN served before. The cache
 * write is what makes a retried sync -- link dropped halfway, rider tries again
 * -- free the second time.
 *
 * The cache is deliberately the same directory a `mapbuilder out_dir` gets
 * pushed into by hand. One layout, one place to look, no second concept.
 */
class ChainTileSource(
    private val local: FileTileSource,
    private val cdn: CdnTileSource,
) : TileSource {

    companion object {
        private const val TAG = "ChainTileSource"
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tile-chain") }

    override fun read(z: Int, col: Long, row: Long, formatVersion: Int?, done: (ByteArray?) -> Unit) {
        io.execute {
            var bytes = local.readBlocking(z, col, row)
            if (bytes == null) {
                bytes = cdn.readBlocking(z, col, row, formatVersion)
                if (bytes != null) {
                    // Cached before it is handed on: the transfer that follows
                    // takes tens of seconds, and a link that dies during it must
                    // not cost the download again.
                    val cached = local.write(z, col, row, bytes)
                    Log.i(TAG, "cdn hit $z/$col/$row (${bytes.size} B)" + if (cached) ", cached" else "")
                }
            }
            val result = bytes
            MainThread.post { done(result) }
        }
    }

    override fun describe(): String = "${local.describe()} then ${cdn.describe()}"

    override fun close() {
        io.shutdown()
        local.close()
        cdn.close()
    }
}
