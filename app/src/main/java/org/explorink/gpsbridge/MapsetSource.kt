package org.explorink.gpsbridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * What ground the CDN has ever built, from its own `mapset.json`.
 *
 * **This is the whole of "not yet" against "never".** A tile fetch cannot tell
 * the two apart -- a 404 is a 404 -- and neither can an empty index slot on its
 * own. Only the published area list can say whether a build has ever run over
 * that square: built and still empty means sea or empty OSM and the tile will
 * never exist; not built means it will, minutes from now, with nobody doing
 * anything ([TilePlan.BuiltGround], `docs/tile-autobuild.md`).
 *
 * Same three-way failure vocabulary as [IndexSource], for the same reason:
 * **an unreachable CDN must never look like a verdict.** Feed an
 * [Result.Unreachable] into [TilePlan.BuiltGround] as an empty list and a whole
 * city of tiles gets marked [TilePlan.State.ABSENT] on the strength of one
 * flight-mode toggle, and the rider never gets them.
 *
 * Asynchronous for the same reason [TileSource] and [IndexSource] are: BLE lives
 * on the service's main thread and Android throws `NetworkOnMainThreadException`
 * for an HTTP call there. The callback comes back on the main thread, exactly
 * once.
 */
interface MapsetSource {

    sealed class Result {
        /**
         * The published area list. May be empty only when the CDN really said so.
         */
        class Areas(val areas: List<TilePlan.BuiltArea>) : Result()

        /**
         * There is no `mapset.json` under this format version at all. A real
         * verdict, and a different one from [Areas] with an empty list only in
         * what it means to a log reader: nothing anywhere is built, so nothing
         * can ever be [TilePlan.State.ABSENT].
         *
         * Kept distinct because the likely cause is not "the world is unbuilt"
         * but a format version this CDN does not publish, and a caller that
         * silently retried a whole city forever would never surface that.
         */
        object NothingPublished : Result()

        /** No network, a server error, a body that would not parse. Nothing is known. */
        class Unreachable(val why: String) : Result()
    }

    /**
     * Reads the area list for the device's `.tib` [formatVersion].
     *
     * The list is per format version -- `/v4/mapset.json` describes the `/v4/`
     * tree only -- so passing the device's own number through is what keeps a
     * v3 device from being told about v4 ground.
     *
     * [done] fires exactly once, on the caller's thread, and **may fire before
     * this call returns** when the answer is already cached. That is the same
     * shape the fake sources in the tests have, so it is the shape every caller
     * here is already written against -- but a caller that reads state after
     * calling this has to expect the callback to have run first.
     */
    fun read(formatVersion: Int?, done: (Result) -> Unit)

    fun close() {}
}

/**
 * Parsing `mapset.json`, with no network in the way.
 *
 * The whole file, as measured against the live CDN on 2026-09-02:
 *
 * ```
 * { "format_version": 4,
 *   "index_format_version": 1,
 *   "lods": { "overview": {"zoom": 11}, ... },
 *   "builds": [ { "name": "auto-z11-1035-764",
 *                 "bbox": {"south":..,"west":..,"north":..,"east":..},
 *                 "build_epoch": 1788100555, "osm_epoch": 1788100433,
 *                 "rules_hash": 2633242815, "tiles": 21, "points": 1933 },
 *               ... ] }
 * ```
 *
 * 21 262 bytes for 60 builds -- about 354 B each, and linear in coverage. Small
 * enough to read **once per round**, and far too big to read per tile: a 40 km
 * Barcelona box is 202 tiles, which would be 4.3 MB of the rider's data to
 * answer a question one fetch already answered.
 *
 * Only `name` and `bbox` are read. The rest is real and deliberately ignored:
 * [TilePlan.BuiltArea] answers one question, and a field nobody reads is a
 * field that goes stale without anyone noticing.
 *
 * Pure, so that shape is pinned by a fixture test rather than by whatever the
 * CDN happened to answer the day somebody looked.
 */
object Mapset {

    /** Relative to the `/v<N>/` root the device's tiles come from. */
    const val PATH = "mapset.json"

    /**
     * Areas out of one `mapset.json` document.
     *
     * Throws [IllegalArgumentException] on anything that is not this shape --
     * the caller turns that into [MapsetSource.Result.Unreachable], never into
     * an empty list, because a body that will not parse is not a statement that
     * nothing is built.
     *
     * A **single** malformed build entry is skipped rather than failing the
     * whole file. The direction matters: dropping one entry can only make
     * covered ground look uncovered, which costs retries, while refusing the
     * file entirely would leave the queue with no ground list at all.
     */
    fun parse(text: String): List<TilePlan.BuiltArea> {
        val root = Json.asMap(Json.parse(text))
        val builds = Json.asList(root["builds"] ?: throw IllegalArgumentException("no builds"))
        val out = ArrayList<TilePlan.BuiltArea>(builds.size)
        for (b in builds) {
            val area = runCatching { area(Json.asMap(b)) }.getOrNull() ?: continue
            out.add(area)
        }
        return out
    }

    /** The document's own `format_version`, or null when it does not state one. */
    fun formatVersionOf(text: String): Int? = runCatching {
        Json.asInt(Json.asMap(Json.parse(text))["format_version"])
    }.getOrNull()

    private fun area(o: Map<String, Any?>): TilePlan.BuiltArea {
        val bbox = Json.asMap(o["bbox"])
        return TilePlan.BuiltArea(
            name = Json.asString(o["name"]),
            south = Json.asDouble(bbox["south"]),
            west = Json.asDouble(bbox["west"]),
            north = Json.asDouble(bbox["north"]),
            east = Json.asDouble(bbox["east"]),
        )
    }
}

/**
 * The public tile CDN's `mapset.json`, over plain HTTP.
 *
 * **Cached, and the lifetime is not a guess.** The file sits behind Varnish with
 * a `max-age` of 300 s, so a second fetch inside five minutes cannot see a change
 * that has happened and can only spend the rider's data. One retry round asks
 * once; the tiles of that round all read the same answer.
 *
 * A failure is never cached. A CDN that was unreachable a minute ago says
 * nothing about the ground, and pinning that for five minutes would turn one
 * dropped connection into five minutes of a queue that cannot tell sea from a
 * city.
 */
class CdnMapsetSource(
    private val baseUrl: String = CdnTileSource.DEFAULT_BASE_URL,
    private val defaultFormatVersion: Int = CdnTileSource.DEFAULT_FORMAT_VERSION,
) : MapsetSource {

    companion object {
        private const val TAG = "CdnMapsetSource"

        /**
         * Varnish's own `max-age` on this file, measured 2026-09-02. Asking
         * sooner cannot see a change even when there is one.
         */
        const val CACHE_MS = 300_000L

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        /**
         * Refuse a body larger than this rather than reading it into RAM.
         *
         * 21 kB today for 60 areas and linear in coverage, so this is roughly a
         * thousandfold headroom -- big enough never to bite a real file, small
         * enough that a misrouted request answering with a tile (up to 8 MB)
         * does not become an allocation on a phone.
         */
        const val MAX_BYTES = 16 * 1024 * 1024
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tile-mapset") }

    private class Cached(val areas: List<TilePlan.BuiltArea>, val atMs: Long)

    /** format version -> the last good answer for it. */
    private val cache = HashMap<Int, Cached>()

    /** Monotonic milliseconds, injected so the cache window is testable. */
    var nowMs: () -> Long = { System.nanoTime() / 1_000_000L }

    override fun read(formatVersion: Int?, done: (MapsetSource.Result) -> Unit) {
        val version = formatVersion ?: defaultFormatVersion
        val hit = cache[version]
        if (hit != null && nowMs() - hit.atMs < CACHE_MS) {
            // Straight back on the caller's thread. MainThread.post already
            // runs inline when it is the main looper, and this path never left.
            MainThread.post { done(MapsetSource.Result.Areas(hit.areas)) }
            return
        }
        io.execute {
            val result = readBlocking(version)
            // The cache is touched on the caller's thread only, never on this
            // worker: it is a plain HashMap, and the contract every source in
            // this app keeps is single-threaded delivery, not a lock.
            MainThread.post {
                if (result is MapsetSource.Result.Areas) {
                    cache[version] = Cached(result.areas, nowMs())
                }
                done(result)
            }
        }
    }

    private fun readBlocking(version: Int): MapsetSource.Result {
        val url = "$baseUrl/v$version/${Mapset.PATH}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            when (val code = conn.responseCode) {
                200 -> {
                    val length = conn.contentLength
                    if (length > MAX_BYTES) {
                        Log.w(TAG, "$url is $length bytes, refusing to read it")
                        return MapsetSource.Result.Unreachable("mapset too large")
                    }
                    val body = conn.inputStream.use { it.readBytes() }
                    if (body.size > MAX_BYTES) {
                        return MapsetSource.Result.Unreachable("mapset too large")
                    }
                    parse(url, version, body.toString(Charsets.UTF_8))
                }
                404 -> MapsetSource.Result.NothingPublished
                else -> {
                    Log.w(TAG, "$url -> HTTP $code")
                    MapsetSource.Result.Unreachable("HTTP $code")
                }
            }
        } catch (t: Throwable) {
            // No network, DNS down, TLS refused. All of it means "I could not
            // ask", which must stay different from "nothing is built here" all
            // the way to the queue.
            Log.w(TAG, "mapset read failed $url: ${t.javaClass.simpleName}")
            MapsetSource.Result.Unreachable(t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Parses a body, and checks the document's own `format_version` against the
     * tree it came out of.
     *
     * The same insistence [CdnIndexSource] has about `index_format_version`, and
     * cheap for the same reason: the two numbers can only disagree through a
     * misconfigured deploy, and believing a mismatched list would draw area
     * boxes for one `.tib` version over another version's tiles.
     */
    private fun parse(url: String, version: Int, text: String): MapsetSource.Result {
        val stated = Mapset.formatVersionOf(text)
        if (stated != null && stated != version) {
            Log.w(TAG, "$url states format_version $stated, this tree is v$version")
            return MapsetSource.Result.Unreachable("mapset is for another format")
        }
        return try {
            MapsetSource.Result.Areas(Mapset.parse(text))
        } catch (t: Throwable) {
            Log.w(TAG, "$url did not parse: ${t.javaClass.simpleName}")
            MapsetSource.Result.Unreachable("mapset did not parse")
        }
    }

    override fun close() {
        io.shutdown()
    }
}
