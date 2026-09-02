package org.explorink.gpsbridge

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
    /**
     * Hands back the tile's bytes, or null if this source does not have it.
     *
     * [expectedContentId] is what the freshness index says this tile's content
     * should be, when a check has established it ([ExpectedContentIds]), and
     * null otherwise. It is not a filter -- it is a **cache key and a receipt**.
     * The CDN caches a tile path for seven days with no purge mechanism, so a
     * rebuilt tile lives at the same URL as the copy it replaces; fetching it
     * without saying which version is wanted gets the old one back and the
     * device asks again forever.
     */
    fun read(
        z: Int,
        col: Long,
        row: Long,
        formatVersion: Int?,
        expectedContentId: Long?,
        done: (ByteArray?) -> Unit,
    )

    /**
     * Tells the source a tile is wanted, without downloading it.
     *
     * **This is the only way ground nobody has ridden ever gets built.** The tile
     * host builds from one signal and one only: a 404 in its own access log
     * (`docs/tile-autobuild.md`). Reading the freshness index does not produce
     * one -- an index read is a byte range on a file that exists -- so a queue
     * that only ever re-reads the index waits forever for a build nobody asked
     * for. Found 2026-09-02, with a pre-trip queue of 33 squares that would have
     * sat unbuilt indefinitely while the screen said the server builds them on
     * ask.
     *
     * One request per tile per round, never in a loop: the ranking is by hit
     * count over 24 h and the server takes the top ten per pass, so repetition
     * buys priority the rider did not ask for and spends our own server.
     *
     * Default no-op, because a source that is not the CDN has no such channel.
     */
    fun prime(z: Int, col: Long, row: Long, formatVersion: Int?, done: (exists: Boolean) -> Unit) {
        done(false)
    }

    /** For the UI and the log: what this source is, in a few words. */
    fun describe(): String

    /** Releases any threads. Called when the service stops. */
    fun close() {}
}

/**
 * Fetches tiles from the public tile CDN.
 *
 * `https://tiles.explorink.com/v<format>/base/<z>/<col>/<row>.tib` --
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

        const val DEFAULT_BASE_URL = "https://tiles.explorink.com"

        /**
         * Used only when the device did not say which format it reads -- an
         * older firmware build, before `NEED_TILES` carried `fmt`. Pushing the
         * wrong version wastes a transfer, so this is a guess of last resort
         * rather than a default anybody should rely on.
         *
         * **Keep it on the version the firmware actually reads**
         * (`MapTileReader::kFormatVersion`, 4 since the v4 freeze). It used to
         * say 2 on the reasoning that a stale guess is harmless because the
         * device always states its own version -- and that was wrong twice over.
         * Measured against the live CDN 2026-09-02: `/v2/` is an abandoned tree,
         * its index answers 404 for every block and its `mapset.json` lists zero
         * areas. And the pre-trip planner runs **before** any device has spoken,
         * by design (a rider plans at home with the device off), so this guess is
         * that feature's normal path rather than its fallback. On a real phone it
         * reported "0 of 26 squares available" for ground where all 26 exist.
         */
        const val DEFAULT_FORMAT_VERSION = 4

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

    /** Distinct cache-buster per retry. Never reused, which is the whole job. */
    private val busts = java.util.concurrent.atomic.AtomicLong(0)

    override fun read(
        z: Int,
        col: Long,
        row: Long,
        formatVersion: Int?,
        expectedContentId: Long?,
        done: (ByteArray?) -> Unit,
    ) {
        io.execute {
            val bytes = readVerified(z, col, row, formatVersion, expectedContentId)
            MainThread.post { done(bytes) }
        }
    }

    /**
     * Fetches the tile and checks it is the version that was asked for.
     *
     * The `?crc=` query makes each content version its own cache key, so this
     * should always agree first time. It is checked anyway, because the failure
     * it guards against is silent: an edge that serves the wrong body leaves the
     * device with a tile it thinks is current, and the whole freshness check
     * then reports it stale again on the next pass -- a loop with a rider's
     * battery behind it.
     *
     * One retry with a cache-buster, then give up. Giving up is honest: the
     * fetch answers `skip`, the device counts it as failed and stops waiting.
     */
    private fun readVerified(
        z: Int,
        col: Long,
        row: Long,
        formatVersion: Int?,
        expectedContentId: Long?,
    ): ByteArray? {
        val first = readBlocking(z, col, row, formatVersion, expectedContentId)
        if (expectedContentId == null || first == null) return first
        if (TileHeader.contentId(first) == expectedContentId) return first

        val bust = busts.incrementAndGet()
        Log.w(
            TAG,
            "z$z $col/$row came back as content ${TileHeader.contentId(first)?.toString(16)}, " +
                "expected ${expectedContentId.toString(16)} -- retrying past the cache",
        )
        val again = readBlocking(z, col, row, formatVersion, expectedContentId, bust)
        if (again != null && TileHeader.contentId(again) == expectedContentId) return again
        Log.w(TAG, "z$z $col/$row still not the expected version; not pushing it")
        return null
    }

    fun readBlocking(
        z: Int,
        col: Long,
        row: Long,
        formatVersion: Int?,
        expectedContentId: Long? = null,
        bust: Long? = null,
    ): ByteArray? {
        val version = formatVersion ?: defaultFormatVersion
        val query = buildString {
            if (expectedContentId != null) append("?crc=%08x".format(expectedContentId))
            if (bust != null) append(if (isEmpty()) "?cb=$bust" else "&cb=$bust")
        }
        val url = "$baseUrl/v$version/${TransferFrames.tileRelPath(z, col, row)}$query"
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

    /**
     * `HEAD`, not `GET`: the host's log line carries the status and the URI and
     * says nothing about the method (`docs/tile-autobuild.md`, the log format),
     * so a HEAD 404 ranks exactly like a GET one and costs neither side a body.
     *
     * The answer is acted on. A 404 is the whole point of the call. A **200 means
     * the index is behind the tiles it describes**, which is a real state rather
     * than a curiosity: measured 2026-09-02, a cell built at 13:26 was serving
     * its squares at 13:29 while the index still read absent. The caller marks
     * those ready rather than leaving the rider watching "building" over ground
     * that is already there. A failure means no network, and changes nothing.
     */
    override fun prime(z: Int, col: Long, row: Long, formatVersion: Int?, done: (exists: Boolean) -> Unit) {
        io.execute {
            val version = formatVersion ?: defaultFormatVersion
            val url = "$baseUrl/v$version/${TransferFrames.tileRelPath(z, col, row)}"
            var conn: HttpURLConnection? = null
            var exists = false
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                val code = conn.responseCode
                exists = code == 200
                Log.i(TAG, "asked the server for z$z $col/$row -> HTTP $code")
            } catch (t: Throwable) {
                Log.w(TAG, "could not ask for $url: ${t.javaClass.simpleName}")
            } finally {
                conn?.disconnect()
            }
            MainThread.post { done(exists) }
        }
    }

    override fun describe(): String = "CDN $baseUrl"

    override fun close() {
        io.shutdown()
    }
}
