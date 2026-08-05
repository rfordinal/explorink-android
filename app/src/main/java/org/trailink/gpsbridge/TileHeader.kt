package org.trailink.gpsbridge

/**
 * Just enough of the `.tib` header to know whether a tile is worth pushing.
 *
 * Layout (`mapbuilder/tiles.py`, `docs/map-data-spec.md` "Tile file format",
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
}
