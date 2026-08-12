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
     * Fixed header length: magic, version, z, x, y, origin_x, origin_y,
     * build_epoch, osm_epoch, header_crc32, layer_count
     * (`mapbuilder/tilegen/tile_reader.py`, `_HEADER_FMT`).
     */
    private const val HEADER_LEN = 36

    private const val LAYER_COUNT_AT = 35

    /** id u8, offset u32, length u32, crc32 u32, index_offset u32, index_length u32. */
    private const val DIR_ENTRY_LEN = 21

    private const val DIR_CRC_AT = 9

    /** Layer ids in the order [contentId] hashes them: water..landuse, 1..6. */
    private val LAYER_IDS = intArrayOf(1, 2, 3, 4, 5, 6)

    /**
     * The tile's content identity: crc32 over the six per-layer crc32s, little
     * endian, in layer id order. A layer that is not present counts as 0, which
     * is also crc32 of nothing, so absent and empty are the same by
     * construction.
     *
     * The third implementation of one number -- `mapbuilder/tilegen/tiles.py`
     * `content_id_from_layer_crcs()` and `MapTileReader::contentId()` are the
     * other two, and all three must agree bit for bit or the freshness check
     * reports every tile as stale forever. [TileHeaderTest] pins it to the same
     * fixed vectors `mapbuilder/tilegen/test_tile_index.py` uses.
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
        if (bytes.size < HEADER_LEN) return null
        if (formatVersion(bytes) == null) return null
        val layerCount = bytes[LAYER_COUNT_AT].toInt() and 0xff
        if (bytes.size < HEADER_LEN + layerCount * DIR_ENTRY_LEN) return null

        val crcs = HashMap<Int, Long>(layerCount)
        for (i in 0 until layerCount) {
            val at = HEADER_LEN + i * DIR_ENTRY_LEN
            crcs[bytes[at].toInt() and 0xff] = u32(bytes, at + DIR_CRC_AT)
        }

        val packed = ByteArray(LAYER_IDS.size * 4)
        for ((i, id) in LAYER_IDS.withIndex()) {
            val crc = crcs[id] ?: 0L
            for (b in 0 until 4) packed[i * 4 + b] = ((crc shr (8 * b)) and 0xff).toByte()
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
