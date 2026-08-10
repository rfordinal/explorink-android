package org.explorink.gpsbridge

/**
 * The CDN's tile freshness index, as read over HTTP byte ranges.
 *
 * A hand port of `mapbuilder/tile_index.py`. Cite that file, not this one, when
 * the layout is in question -- it is the spec, `docs/tile-index-spec.md` is the
 * reasoning, and this is the second implementation that has to agree with it.
 * [TileIndexTest] pins the offsets against the numbers the spec states.
 *
 * One `.idx` file per z7 block, a dense array of 16-byte slots covering every
 * z11/z12/z13 tile position in it. A slot is **addressed, never searched**: its
 * offset is arithmetic on `(z, col, row)`, so a client reads 16 bytes of an
 * 86 KB object and needs no API, no database and nothing server-side. That is
 * the whole reason this can live on a static CDN.
 *
 * The device never reads this. The phone does, on its behalf -- see
 * [FreshnessChecker].
 */
object TileIndex {

    const val BLOCK_Z = 7
    const val SLOT_BYTES = 16

    /** `index_format_version: u32`, `reserved: u32`. */
    const val HEADER_BYTES = 8

    const val INDEX_FORMAT_VERSION = 1

    /**
     * The indexed LOD zooms, in plane order (`docs/map-data-spec.md`, "Levels of
     * detail"). A fourth LOD changes the plane layout, which is what
     * [INDEX_FORMAT_VERSION] is for -- so an unknown version must not be parsed
     * hopefully, it must be refused.
     */
    val LOD_ZOOMS = intArrayOf(11, 12, 13)

    private const val FLAG_PRESENT = 1

    const val COVERAGE_OUTSIDE = 0
    const val COVERAGE_PARTIAL = 1
    const val COVERAGE_FULL = 2

    /** Tiles per side of a z7 block at zoom [z]: z11 -> 16, z12 -> 32, z13 -> 64. */
    fun side(z: Int): Int = 1 shl (z - BLOCK_Z)

    /** Slot-count offset of [z]'s plane inside a block, or -1 for an unindexed zoom. */
    fun planeBaseSlots(z: Int): Int {
        var base = 0
        for (zoom in LOD_ZOOMS) {
            if (zoom == z) return base
            base += side(zoom) * side(zoom)
        }
        return -1
    }

    /** 256 + 1024 + 4096. */
    val BLOCK_SLOTS: Int = LOD_ZOOMS.sumOf { side(it) * side(it) }

    val BLOCK_BYTES: Int = HEADER_BYTES + BLOCK_SLOTS * SLOT_BYTES

    fun blockCol(z: Int, col: Long): Long = col shr (z - BLOCK_Z)

    fun blockRow(z: Int, row: Long): Long = row shr (z - BLOCK_Z)

    /** Byte offset of `(z, col, row)`'s slot in its block, or -1 for an unindexed zoom. */
    fun slotOffset(z: Int, col: Long, row: Long): Int {
        if (z < BLOCK_Z) return -1
        val plane = planeBaseSlots(z)
        if (plane < 0) return -1
        val shift = z - BLOCK_Z
        val localCol = col - (blockCol(z, col) shl shift)
        val localRow = row - (blockRow(z, row) shl shift)
        val side = side(z).toLong()
        return HEADER_BYTES + (plane + (localRow * side + localCol).toInt()) * SLOT_BYTES
    }

    /** Path of a block's index file, relative to the version root. */
    fun blockRelPath(blockCol: Long, blockRow: Long): String =
        "base/index/$BLOCK_Z/$blockCol/$blockRow.idx"

    /** `index_format_version` from a block's first 8 bytes, or null if too short. */
    fun formatVersion(header: ByteArray): Long? {
        if (header.size < 4) return null
        return u32(header, 0)
    }

    /**
     * One tile position as the index describes it.
     *
     * [contentId] is the only field the freshness check compares -- see
     * `mapbuilder/tiles.py`, `content_id_from_layer_crcs()`, for why every
     * timestamp candidate failed. [buildEpoch] is for saying "this tile last
     * changed in March", never for deciding anything.
     */
    data class Slot(
        val present: Boolean,
        val coverage: Int,
        val contentId: Long,
        val buildEpoch: Long,
        val sizeBytes: Long,
    ) {
        companion object {
            /**
             * All-zero: no tile, never built. A block is written zero-filled and
             * only real tiles set anything, so an untouched slot reads correctly
             * by construction.
             */
            val ABSENT = Slot(false, COVERAGE_OUTSIDE, 0, 0, 0)
        }
    }

    /** Reads the 16-byte slot at [at] in [buf]. Null if [buf] is too short. */
    fun parseSlot(buf: ByteArray, at: Int): Slot? {
        if (at < 0 || at + SLOT_BYTES > buf.size) return null
        val flags = buf[at].toInt() and 0xff
        return Slot(
            present = (flags and FLAG_PRESENT) != 0,
            coverage = (flags shr 1) and 0b11,
            contentId = u32(buf, at + 2),
            buildEpoch = u32(buf, at + 6),
            sizeBytes = u32(buf, at + 10),
        )
    }

    private fun u32(buf: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[at + i].toLong() and 0xff) shl (8 * i))
        return v
    }

    /**
     * One contiguous byte range covering every tile in [tiles], with the tiles
     * it covers.
     *
     * **This is why the check fits in the reply timeout.** A viewport is up to
     * 32 tiles; asking for 32 slots one at a time is 32 sequential HTTPS round
     * trips, which does not finish inside [TileFetcher.REPLY_TIMEOUT_MS] on
     * mobile data. The slots of one zoom plane are laid out row-major, so the
     * whole viewport sits between one lowest and one highest offset: a single
     * `Range` request of a few kB.
     *
     * Grouped by block **and by zoom**, not by block alone. A block's three
     * planes are 4 KB, 16 KB and 64 KB apart, so one span across two zooms would
     * drag tens of kB of slots nobody asked about over the rider's data.
     */
    data class Span(
        val blockCol: Long,
        val blockRow: Long,
        val z: Int,
        /** First byte of the range, inclusive. */
        val first: Int,
        /** Last byte of the range, inclusive. */
        val last: Int,
        val tiles: List<HeldTile>,
    ) {
        val length: Int get() = last - first + 1

        fun relPath(): String = blockRelPath(blockCol, blockRow)

        /** Offset of [tile]'s slot within the bytes this span returns. */
        fun offsetWithin(tile: HeldTile): Int = slotOffset(tile.z, tile.col, tile.row) - first
    }

    /**
     * Groups [tiles] into one span per (block, zoom).
     *
     * Tiles at a zoom with no plane are dropped: an index built to this layout
     * has nothing to say about them, and inventing an offset would read a
     * neighbouring tile's slot and call it an answer.
     */
    fun planSpans(tiles: List<HeldTile>): List<Span> {
        val groups = LinkedHashMap<Triple<Long, Long, Int>, MutableList<HeldTile>>()
        for (t in tiles) {
            if (slotOffset(t.z, t.col, t.row) < 0) continue
            val key = Triple(blockCol(t.z, t.col), blockRow(t.z, t.row), t.z)
            groups.getOrPut(key) { mutableListOf() }.add(t)
        }
        return groups.map { (key, members) ->
            val offsets = members.map { slotOffset(it.z, it.col, it.row) }
            Span(
                blockCol = key.first,
                blockRow = key.second,
                z = key.third,
                first = offsets.min(),
                last = offsets.max() + SLOT_BYTES - 1,
                tiles = members,
            )
        }
    }
}

/**
 * A tile the device says it holds, and the content id it holds it at.
 *
 * The device computes [contentId] for free -- `MapTileReader` already keeps
 * every layer's crc32 in RAM after `open()`, so this costs no seek and no read
 * on a device where both are expensive.
 */
data class HeldTile(
    val z: Int,
    val col: Long,
    val row: Long,
    val contentId: Long,
)
