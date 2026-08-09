package org.trailink.gpsbridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Reads slices of the CDN's freshness index.
 *
 * Asynchronous for the same reason [TileSource] is: BLE lives on the service's
 * main thread and Android throws `NetworkOnMainThreadException` for an HTTP call
 * there. The callback comes back on the main thread, exactly once.
 *
 * Kept separate from [TileSource] on purpose. A tile fetch is a whole object and
 * a miss is ordinary; an index read is a byte range and a miss means "nobody has
 * published anything about this ground". The two want different failure
 * vocabularies, and folding them together would cost the distinction that makes
 * "I do not know" possible.
 */
interface IndexSource {

    /**
     * Why an index read did not produce bytes. The split is the whole point:
     * **an unreachable CDN must never look like a verdict.**
     */
    sealed class Result {
        /** The requested range, exactly [Span.length] bytes long. */
        class Bytes(val data: ByteArray) : Result()

        /** No index block covers this ground. Nothing is published here. */
        object NotPublished : Result()

        /** No network, a server error, a truncated read. Nothing is known. */
        class Unreachable(val why: String) : Result()
    }

    /**
     * Reads bytes `first..last` inclusive of the block at [relPath], under the
     * device's `.tib` [formatVersion] path prefix.
     */
    fun readRange(relPath: String, first: Int, last: Int, formatVersion: Int?, done: (Result) -> Unit)

    fun close() {}
}

/**
 * The public tile CDN, over HTTP byte ranges.
 *
 * `Range: bytes=<first>-<last>` against a static file, answered `206` from the
 * edge -- verified against this host on 2026-08-06, cache MISS and HIT alike
 * (`docs/tile-cdn-plan.md`). No API and no server-side index is what makes the
 * whole design affordable to host.
 *
 * Two things this class insists on, both cheap and both the difference between
 * a check and a guess:
 *
 * - **The block's `index_format_version` is read and matched** before any slot
 *   is believed. A future fourth LOD changes the plane layout, so parsing an
 *   unknown version would read a neighbouring tile's slot and report it with a
 *   straight face. The header is 8 bytes, read once per block and cached for the
 *   life of the app.
 * - **A short or over-long body is [Result.Unreachable]**, not truncated data.
 *   A proxy that ignores `Range` and returns the whole 86 KB block would
 *   otherwise be parsed at the wrong offsets.
 */
class CdnIndexSource(
    private val baseUrl: String = CdnTileSource.DEFAULT_BASE_URL,
    private val defaultFormatVersion: Int = CdnTileSource.DEFAULT_FORMAT_VERSION,
) : IndexSource {

    companion object {
        private const val TAG = "CdnIndexSource"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tile-index") }

    /** relPath -> whether its `index_format_version` is one this app can read. */
    private val versionChecked = HashMap<String, Boolean>()

    override fun readRange(
        relPath: String,
        first: Int,
        last: Int,
        formatVersion: Int?,
        done: (IndexSource.Result) -> Unit,
    ) {
        io.execute {
            val result = readBlocking(relPath, first, last, formatVersion)
            MainThread.post { done(result) }
        }
    }

    private fun readBlocking(
        relPath: String,
        first: Int,
        last: Int,
        formatVersion: Int?,
    ): IndexSource.Result {
        val version = formatVersion ?: defaultFormatVersion
        val url = "$baseUrl/v$version/$relPath"

        when (versionChecked[url]) {
            true -> Unit
            false -> return IndexSource.Result.Unreachable("index layout not understood")
            null -> {
                val header = get(url, 0, TileIndex.HEADER_BYTES - 1)
                if (header !is IndexSource.Result.Bytes) {
                    // Nothing is cached from a failure: a block that is missing
                    // today may be published tomorrow, and one outage must not
                    // pin a permanent verdict on this ground.
                    return header
                }
                val got = TileIndex.formatVersion(header.data)
                val ok = got == TileIndex.INDEX_FORMAT_VERSION.toLong()
                versionChecked[url] = ok
                if (!ok) {
                    Log.w(
                        TAG,
                        "$url: index_format_version $got, this app reads ${TileIndex.INDEX_FORMAT_VERSION}",
                    )
                    return IndexSource.Result.Unreachable("index layout not understood")
                }
            }
        }

        return get(url, first, last)
    }

    private fun get(url: String, first: Int, last: Int): IndexSource.Result {
        val want = last - first + 1
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Range", "bytes=$first-$last")
                // Ranges and transfer encodings do not mix well through proxies,
                // and the payload is already incompressible crc32 values.
                setRequestProperty("Accept-Encoding", "identity")
            }
            when (val code = conn.responseCode) {
                206 -> {
                    val body = conn.inputStream.use { it.readBytes() }
                    if (body.size == want) {
                        IndexSource.Result.Bytes(body)
                    } else {
                        Log.w(TAG, "$url wanted $want bytes, got ${body.size}")
                        IndexSource.Result.Unreachable("range ignored")
                    }
                }
                // 200 to a Range request means the server sent the whole object.
                // Slicing it here would work, and would also hide a
                // misconfiguration that makes every check download 86 KB.
                200 -> {
                    Log.w(TAG, "$url answered 200 to a Range request -- ranges are not being served")
                    IndexSource.Result.Unreachable("no range support")
                }
                404 -> IndexSource.Result.NotPublished
                else -> {
                    Log.w(TAG, "$url -> HTTP $code")
                    IndexSource.Result.Unreachable("HTTP $code")
                }
            }
        } catch (t: Throwable) {
            // No network, DNS down, TLS refused. All of it means "I do not
            // know", which is a different answer from "your tile is stale" and
            // must stay different all the way to the device.
            Log.w(TAG, "index read failed $url: ${t.javaClass.simpleName}")
            IndexSource.Result.Unreachable(t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    override fun close() {
        io.shutdown()
    }
}
