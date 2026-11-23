package org.explorink.gpsbridge

import java.util.zip.CRC32

/**
 * Just enough of the `.tib` header to know whether a tile is worth pushing.
 *
 * Layout (`mapbuilder/tilegen/tiles.py`, `docs/map-data-spec.md` "Tile file format",
 * firmware `MapTileReader::parseHeader()`), little endian throughout:
 *
 *     0..3   magic  "TIB1"
 *     4..5   version  u16
 *     6      z        u8
 *     7..10  x        u32
 *     11..14 y        u32
 *     ...
 *
 * The fixed part is 36 bytes at version 3 and 37 at version 4, which added
 * `coord_shift`. [contentId] reads the layer directory that follows it, so it
 * has to know which.
 *
 * The app reads no further. It is not a tile renderer -- it only has to answer
 * "will the device accept this file", and the device's answer turns entirely on
 * magic and version: `MapTileReader` checks version for exact equality and
 * refuses anything else outright, because a version-1 file's layer directory
 * entries are 9 bytes where version 2's are 13, so parsing an older file would
 * produce plausible garbage instead of a clean refusal.
 *
 * Checking z/x/y against the requested tile would be a second, independent
 * safeguard -- a tile self-locates from its header origin, so a file filed under
 * the wrong path renders off-screen rather than failing. Not done here: it needs
 * the col/row convention to be pinned down on both sides first, and getting it
 * subtly wrong would reject good tiles.
 */
object TileHeader {

    val MAGIC = byteArrayOf('T'.code.toByte(), 'I'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    /** Bytes needed before version can be read. */
    const val MIN_BYTES = 6

    /** The `.tib` format version of [bytes], or null if it is not a tile at all. */
    fun formatVersion(bytes: ByteArray): Int? {
        if (bytes.size < MIN_BYTES) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        val lo = bytes[4].toInt() and 0xff
        val hi = bytes[5].toInt() and 0xff
        return lo or (hi shl 8)
    }

    /**
     * True when this file is a tile the device will accept.
     *
     * [wanted] null means the device never said which version it reads (an older
     * firmware build). Then the magic is all there is to go on: pushing is the
     * only way to find out, and refusing everything would break the fetch
     * against exactly the builds that have no way to tell us.
     */
    fun isAcceptable(bytes: ByteArray, wanted: Int?): Boolean {
        val version = formatVersion(bytes) ?: return false
        return wanted == null || version == wanted
    }

    // --- content id ---------------------------------------------------------

    /**
     * Fixed header length, which is **per format version**: magic, version, z,
     * x, y, origin_x, origin_y, build_epoch, osm_epoch, header_crc32 and
     * layer_count is 36 bytes, and version 4 inserts `coord_shift` (u8) before
     * header_crc32 for 37 (`mapbuilder/tilegen/tile_reader.py`, `_HEADER_FMT`).
     *
     * **This side read 36 for every version until 2026-11-05**, so against a
     * version-4 tile it took the layer count from the wrong byte -- 65 on a
     * real one, where the truth was 8 -- and then walked a directory of
     * nonsense. Null for a version whose layout is not known here: a content id
     * computed from garbage is worse than no answer, because the caller cannot
     * tell the two apart.
     */
    private fun headerLenFor(version: Int): Int? = when (version) {
        3 -> 36
        4 -> 37
        else -> null
    }

    /** id u8, offset u32, length u32, crc32 u32, index_offset u32, index_length u32. */
    private const val DIR_ENTRY_LEN = 21

    private const val DIR_CRC_AT = 9

    /**
     * How many slots [contentId] folds. Fixed, and deliberately NOT the number
     * of layers the format has: mirrors `CONTENT_ID_SLOTS` in
     * `mapbuilder/tilegen/tiles.py` and `kContentIdSlots` in the firmware's
     * `MapTileReader`. If this tracked the layer list, adding a layer would
     * change the identity of every tile whose bytes had not moved.
     *
     * **It was 6 until 2026-11-05, and that is one of the three numbers it
     * could not be.** The writer has folded 15 since the slot count was fixed,
     * so this side disagreed with it for every tile in existence, empty ones
     * included. Measured on 13/4483/2842: the index and the firmware both say
     * `953e7604`, folding six slots says `918bd22f`.
     */
    private const val CONTENT_ID_SLOTS = 15

    /**
     * The tile's content identity: crc32 over [CONTENT_ID_SLOTS] per-layer
     * crc32s, little endian, in layer id order 1..15. A layer that is not
     * present counts as 0, which is also crc32 of nothing, so absent and empty
     * are the same by construction.
     *
     * The third implementation of one number -- `mapbuilder/tilegen/tiles.py`
     * `content_id_from_layer_crcs()` and `MapTileReader::contentId()` are the
     * other two, and all three must agree bit for bit or the freshness check
     * reports every tile as stale forever. [TileHeaderTest] pins it to the same
     * fixed vectors `mapbuilder/tilegen/test_tile_index.py` uses -- and until
     * 2026-11-05 it pinned different ones, which is why nothing caught the
     * disagreement.
     *
     * The app computes it for one reason: to check that a tile the CDN just
     * handed over really is the version the index promised. The edge caches for
     * seven days with no purge, so "the download succeeded" is not the same as
     * "the download was the new tile".
     *
     * Null when [bytes] is not a readable tile of the expected format. Only the
     * header and the layer directory are read -- 162 bytes of geometry-free
     * bookkeeping, no matter how big the tile is.
     */
    fun contentId(bytes: ByteArray): Long? {
        val version = formatVersion(bytes) ?: return null
        val headerLen = headerLenFor(version) ?: return null
        if (bytes.size < headerLen) return null
        val layerCount = bytes[headerLen - 1].toInt() and 0xff
        if (bytes.size < headerLen + layerCount * DIR_ENTRY_LEN) return null

        val crcs = HashMap<Int, Long>(layerCount)
        for (i in 0 until layerCount) {
            val at = headerLen + i * DIR_ENTRY_LEN
            crcs[bytes[at].toInt() and 0xff] = u32(bytes, at + DIR_CRC_AT)
        }

        val packed = ByteArray(CONTENT_ID_SLOTS * 4)
        for (slot in 0 until CONTENT_ID_SLOTS) {
            val crc = crcs[slot + 1] ?: 0L
            for (b in 0 until 4) packed[slot * 4 + b] = ((crc shr (8 * b)) and 0xff).toByte()
        }
        val out = CRC32()
        out.update(packed)
        return out.value
    }

    private fun u32(buf: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[at + i].toLong() and 0xff) shl (8 * i))
        return v
    }
}
