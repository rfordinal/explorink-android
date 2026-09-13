package org.explorink.gpsbridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fetches point shards from the public tile CDN.
 *
 * `https://tiles.explorink.com/v<tileFormat>/points/10/<col>/<row>.tip` --
 * under the **same** `/v<N>/` tree as `.tib` tiles, versioned by the *tile*
 * format and not by the point-file format (`MapPointReader::kFormatVersion`,
 * a separate axis still at 1 -- `docs/point-layer-lifecycle.md` decision 1
 * and its closing note, "no `.tip` version bump"). [tileFormatVersion] is
 * therefore the same number [CdnTileSource] uses to pick its own tree, read
 * from the same place ([BridgeService.deviceTileFormat]) -- **not**
 * `NEED_POINTS`'s `fmt`, which names a different format entirely.
 *
 * Static files, no API, same as [CdnTileSource]: a miss is a 404 and that is
 * the whole protocol. No cache-busting retry here -- unlike tiles, a point
 * shard carries no `expectedContentId` yet (decision 1's `.pidx` freshness
 * index is not built), so there is nothing to verify a fetch against.
 */
class CdnPointSource(
    private val baseUrl: String = CdnTileSource.DEFAULT_BASE_URL,
    private val tileFormatVersion: () -> Int?,
) : PointSync.PointSource {

    companion object {
        private const val TAG = "CdnPointSource"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /** A shard bigger than this is not pushed -- same reasoning as [CdnTileSource.MAX_TILE_BYTES]. */
        const val MAX_SHARD_BYTES = TransferFrames.MAX_FILE_BYTES
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "point-cdn") }

    override fun read(col: Long, row: Long, done: (PointSync.ReadResult) -> Unit) {
        io.execute {
            val result = readBlocking(col, row)
            MainThread.post { done(result) }
        }
    }

    private fun readBlocking(col: Long, row: Long): PointSync.ReadResult {
        val version = tileFormatVersion() ?: CdnTileSource.DEFAULT_FORMAT_VERSION
        val path = TransferFrames.pointShardRelPath(PointSync.SHARD_ZOOM, col, row)
        val url = "$baseUrl/v$version/$path"
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
                    if (bytes.size > MAX_SHARD_BYTES) {
                        Log.w(TAG, "$url is ${bytes.size} bytes, over the transfer cap")
                        PointSync.ReadResult.Failed
                    } else {
                        PointSync.ReadResult.Bytes(bytes)
                    }
                }
                // A miss. The CDN only holds areas somebody has built, and only
                // publishes points for them weekly (tile-autobuild.md, "the
                // points pass") -- this is ordinary, not an error.
                404 -> PointSync.ReadResult.NotFound
                else -> {
                    Log.w(TAG, "$url -> HTTP $code")
                    PointSync.ReadResult.Failed
                }
            }
        } catch (t: Throwable) {
            // No network, DNS down, TLS refused: never `gone` for this -- see
            // ReadResult.Failed's own doc.
            Log.w(TAG, "fetch failed $url: ${t.javaClass.simpleName}")
            PointSync.ReadResult.Failed
        } finally {
            conn?.disconnect()
        }
    }

    fun close() {
        io.shutdown()
    }
}
