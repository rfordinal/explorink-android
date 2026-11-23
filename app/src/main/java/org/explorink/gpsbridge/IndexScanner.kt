package org.explorink.gpsbridge

import android.util.Log

/**
 * Reads the CDN's index for a whole list of tiles, and says what each one is.
 *
 * This is the half of the pre-trip ask that costs almost nothing. A 40 km box
 * around Barcelona is **202 tiles and 19.4 MB**, but deciding which of them
 * exist, how big each one is and which are sea is a handful of byte-range reads
 * of a few kB each ([TileIndex.planSpans]) -- never a tile fetch. That is what
 * lets the screen state the exact byte count before the rider commits to half an
 * hour of radio.
 *
 * **Three answers, and they are not interchangeable** ([TilePlan.Reading]):
 *
 *  - a slot came back -> [TilePlan.Reading.Found], and its `sizeBytes` is exact,
 *    verified against the served HTTP body for three Barcelona tiles 2026-09-02;
 *  - the block's `.idx` answers 404 -> [TilePlan.Reading.NoBlock] for every tile
 *    in that span. Real, reachable ground that nobody has built
 *    (`v4/base/index/7/60/44.idx` today);
 *  - the read failed -> [TilePlan.Reading.Unreachable]. **Never a verdict.** A
 *    tile must not become [TilePlan.State.ABSENT] because a phone lost signal.
 *
 * **Sequenced, one span at a time**, not fanned out. A city box is a handful of
 * spans, the existing index reader never runs two reads at once
 * ([FreshnessChecker]), and firing them together would put several HTTPS
 * handshakes on a phone radio to save a second on a job whose next step takes
 * twenty minutes.
 *
 * Single-threaded by contract, like [TileFetcher] and [FreshnessChecker]: every
 * callback arrives on the caller's one thread, and [start] and [cancel] are
 * called from it.
 *
 * **No timeout of its own, unlike [FreshnessChecker].** That one owes the device
 * a reply inside a deadline; this one owes nobody anything. A hung read is
 * already bounded by [CdnIndexSource]'s connect and read timeouts, and the rider
 * can back out of the plan screen at any point, which is [cancel].
 *
 * No Android in here except the log tag, so a whole scan runs on the laptop.
 */
class IndexScanner(private val index: IndexSource) {

    companion object {
        private const val TAG = "IndexScanner"
    }

    /** One tile and what the index said about it. */
    class Read(val tile: TileRef, val reading: TilePlan.Reading)

    /**
     * How a scan ended.
     *
     * [unindexed] is not an error the rider caused: [TileBox] only ever produces
     * the three zooms the index has planes for ([TileIndex.LOD_ZOOMS]), so a
     * non-empty list means a caller asked about a zoom this index layout cannot
     * describe. There is no honest [TilePlan.Reading] for that -- `NoBlock`
     * would say "wait, it is coming" and `Unreachable` would say "ask again",
     * and both retry forever -- so it is reported here instead, and the caller
     * marks those items terminally failed.
     */
    class Summary(
        val total: Int,
        val read: Int,
        /** Tiles whose span could not be reached. Nothing is known about them. */
        val unreachable: Int,
        val unindexed: List<TileRef>,
        val cancelled: Boolean,
    ) {
        /** True only when every tile asked about got a real answer. */
        val complete: Boolean
            get() = !cancelled && unreachable == 0 && unindexed.isEmpty()
    }

    interface Listener {
        /**
         * One span answered. Delivered as it goes, not held to the end, so a
         * scan the rider cancels halfway still leaves the queue everything it
         * learned -- a city box is a dozen round trips on mobile data.
         */
        fun onTilesRead(reads: List<Read>) {}

        /** [read] of [total] tiles have an answer. For the progress line. */
        fun onScanProgress(read: Int, total: Int) {}

        /**
         * The scan ended, exactly once, whether it ran out of spans or was
         * cancelled.
         *
         * A scan replaced by a second [start] reports nothing: the caller that
         * replaced it is the caller that would have been told, and it already
         * knows. Only a scan the caller is still waiting on gets an ending.
         */
        fun onScanFinished(summary: Summary)
    }

    private var listener: Listener? = null
    private val pending = ArrayDeque<TileIndex.Span>()
    private var formatVersion: Int? = null

    private var total = 0
    private var read = 0
    private var unreachable = 0
    private var unindexed: List<TileRef> = emptyList()

    /**
     * Which scan this is, bumped at every start and every end.
     *
     * The same guard [FreshnessChecker] carries, for the same reason: a restart
     * or a cancel can happen while an index read is outstanding, and that read's
     * callback has no idea. A `running` flag alone cannot tell this scan's late
     * answer from the next scan's live one once both have reached the reading
     * state.
     */
    private var scanGen = 0

    var running: Boolean = false
        private set

    /**
     * Starts a scan of [tiles], restarting whatever was running.
     *
     * Duplicate tiles are read once: two zones round the same city share almost
     * every square, and the index does not answer differently the second time.
     *
     * [formatVersion] is the `.tib` version the **device** reads. The index
     * lives under the same `/v<N>/` prefix as the tiles it describes, so a
     * device on an older format has to be answered from its own tree or every
     * slot would look wrong at once.
     *
     * An empty list finishes immediately rather than leaving a scan that never
     * ends: the caller still gets its one [Listener.onScanFinished].
     */
    fun start(tiles: List<TileRef>, formatVersion: Int?, listener: Listener) {
        scanGen++
        this.listener = listener
        this.formatVersion = formatVersion

        val seen = HashSet<String>()
        val unique = tiles.filter { seen.add(it.key) }
        val spans = TileIndex.planSpans(unique.map { it.asIndexProbe() })

        // planSpans drops a zoom with no plane. Recovering which tiles those
        // were here, rather than trusting the caller, is what keeps them from
        // silently never being answered at all.
        val indexed = HashSet<String>()
        for (s in spans) for (t in s.tiles) indexed.add("${t.z}/${t.col}/${t.row}")
        unindexed = unique.filter { it.key !in indexed }

        pending.clear()
        pending.addAll(spans)
        total = unique.size
        read = 0
        unreachable = 0
        running = true

        readNextSpan()
    }

    /**
     * The rider backed out. Ends the scan now and reports what it had.
     *
     * An outstanding read's answer is dropped rather than waited for: it belongs
     * to a scan that no longer exists, and the tiles it would have answered are
     * simply still unknown, which is the state they started in.
     */
    fun cancel() {
        if (!running) return
        finish(cancelled = true)
    }

    // --- reading -------------------------------------------------------------

    private fun readNextSpan() {
        val span = pending.removeFirstOrNull()
        if (span == null) {
            finish(cancelled = false)
            return
        }
        // Captured before the read, same reason as in FreshnessChecker: a
        // restart bumps scanGen and puts the *new* scan into the same reading
        // state, which a plain flag cannot tell apart from this one.
        val gen = scanGen
        index.readRange(span.relPath(), span.first, span.last, formatVersion) { result ->
            if (gen != scanGen) {
                Log.i(TAG, "dropping a late index read; the scan ended or moved on")
                return@readRange
            }
            onSpan(span, result)
        }
    }

    private fun onSpan(span: TileIndex.Span, result: IndexSource.Result) {
        val reads = ArrayList<Read>(span.tiles.size)
        when (result) {
            is IndexSource.Result.Bytes -> {
                for (t in span.tiles) {
                    val tile = TileRef(t.z, t.col, t.row)
                    val slot = TileIndex.parseSlot(result.data, span.offsetWithin(t))
                    // A slot that will not parse means the body was not the
                    // range asked for. That is the source lying about its own
                    // length, and the honest reading is "I do not know" -- never
                    // a neighbouring tile's bytes read at the wrong offset.
                    reads.add(
                        Read(
                            tile,
                            if (slot != null) TilePlan.Reading.Found(slot)
                            else TilePlan.Reading.Unreachable,
                        )
                    )
                    if (slot == null) unreachable++
                }
            }
            // No index block covers this ground at all. Every tile of the span
            // is real, reachable ground that nobody has built yet.
            is IndexSource.Result.NotPublished -> {
                for (t in span.tiles) {
                    reads.add(Read(TileRef(t.z, t.col, t.row), TilePlan.Reading.NoBlock))
                }
            }
            is IndexSource.Result.Unreachable -> {
                Log.w(TAG, "${span.relPath()} unreachable: ${result.why}")
                for (t in span.tiles) {
                    reads.add(Read(TileRef(t.z, t.col, t.row), TilePlan.Reading.Unreachable))
                    unreachable++
                }
            }
        }

        read += reads.size

        // The listener may cancel, or start a different scan, from inside
        // either callback -- the plan screen's back button lands here. So the
        // generation is re-checked after every hand-off rather than at the end:
        // otherwise a cancel inside onTilesRead would still be followed by a
        // progress line and one more span read, both belonging to a scan that
        // has already reported it ended.
        val gen = scanGen
        listener?.onTilesRead(reads)
        if (gen != scanGen) return
        listener?.onScanProgress(read, total)
        if (gen != scanGen) return

        readNextSpan()
    }

    private fun finish(cancelled: Boolean) {
        val l = listener
        val summary = Summary(total, read, unreachable, unindexed, cancelled)
        scanGen++
        running = false
        listener = null
        pending.clear()
        l?.onScanFinished(summary)
    }
}
