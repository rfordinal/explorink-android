package org.explorink.gpsbridge.wallet

/**
 * The `.rle` BLE sidecar. Port of `rle_encode*` / `build_sidecar` in
 * `tools/walletgen.py`, contract in `docs/wallet-format.md` section 7.
 *
 * The card holds raw assets; only the BLE wire is compressed, because a page is
 * ~1 MB and BLE runs at the measured 8-9 kB/s. Bands are independent, so an
 * interrupted transfer resumes on a band boundary instead of starting again.
 *
 * File layout:
 *
 *     file = assetHeader(32, verbatim copy of the .dat header) || EWRL block
 *     EWRL = "EWRL" | u8 version | u8 bandRows | u16 bandCount | u32 rawLen
 *            | u32 bandCompressedLen[bandCount] | bands
 *
 * The header prefix is what lets the device rebuild a complete `.dat` from the
 * sidecar alone, without waiting for the manifest.
 */
object Rle {

    val MAGIC = "EWRL".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1

    /**
     * Physical rows per band, every panel. Not arbitrary: it is the grey plane
     * band size the firmware already uses (`lib/GfxRenderer/GrayscaleFrame.h:101-102`),
     * so the two stay aligned. A short last band is legal and X3 needs one.
     */
    const val BAND_ROWS = 80

    /** magic + version + bandRows + bandCount + rawLen. */
    const val HEADER_FIXED_LEN = 12

    /**
     * PackBits-style byte RLE, deterministic.
     *
     *     op < 0x80   copy the next (op + 1) literal bytes            1..128
     *     op >= 0x80  repeat the next single byte (op - 0x80 + 2) times 2..129
     *
     * A run of 2 is emitted as literals -- same cost, and it keeps the literal
     * stretch going. Only runs of 3+ become repeat ops, so the worst case adds
     * one op byte per 128 literal bytes and no more.
     */
    fun encodeBand(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size / 2 + 16)
        val n = data.size
        var i = 0
        var lit = 0

        fun flush(end: Int) {
            while (lit < end) {
                val chunk = minOf(128, end - lit)
                out.write(chunk - 1)
                out.write(data, lit, chunk)
                lit += chunk
            }
        }

        while (i < n) {
            var run = 1
            val b = data[i]
            while (i + run < n && data[i + run] == b && run < 129) run++
            if (run >= 3) {
                flush(i)
                out.write(0x80 + run - 2)
                out.write(b.toInt())
                i += run
                lit = i
            } else {
                i++
                if (i - lit == 128) flush(i)
            }
        }
        flush(n)
        return out.toByteArray()
    }

    fun decodeBand(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val op = data[i].toInt() and 0xff
            i++
            if (op < 0x80) {
                val count = op + 1
                if (i + count > data.size) throw IllegalArgumentException("truncated literal run")
                out.write(data, i, count)
                i += count
            } else {
                val count = op - 0x80 + 2
                if (i >= data.size) throw IllegalArgumentException("truncated repeat run")
                val b = data[i].toInt()
                for (k in 0 until count) out.write(b)
                i++
            }
        }
        return out.toByteArray()
    }

    /**
     * Wrap a raw payload into an EWRL block. A band is [bandRows] *native* rows
     * of THIS asset, so its size follows the asset's own stride: one screen is
     * 80 * 100 = 8,000 B on X4, and a page image bands by its own, larger stride.
     */
    fun encode(raw: ByteArray, rowBytes: Int, bandRows: Int = BAND_ROWS): ByteArray {
        val bandBytes = bandRows * rowBytes
        val bands = ArrayList<ByteArray>()
        var off = 0
        while (off < raw.size) {
            val end = minOf(off + bandBytes, raw.size)
            bands.add(encodeBand(raw.copyOfRange(off, end)))
            off = end
        }
        if (bands.isEmpty()) bands.add(encodeBand(ByteArray(0)))

        val head = ByteArray(HEADER_FIXED_LEN + 4 * bands.size)
        System.arraycopy(MAGIC, 0, head, 0, 4)
        head[4] = VERSION.toByte()
        head[5] = bandRows.toByte()
        head[6] = (bands.size and 0xff).toByte()
        head[7] = ((bands.size ushr 8) and 0xff).toByte()
        putU32(head, 8, raw.size)
        for ((i, b) in bands.withIndex()) putU32(head, HEADER_FIXED_LEN + 4 * i, b.size)

        var total = head.size
        for (b in bands) total += b.size
        val out = ByteArray(total)
        System.arraycopy(head, 0, out, 0, head.size)
        var at = head.size
        for (b in bands) {
            System.arraycopy(b, 0, out, at, b.size)
            at += b.size
        }
        return out
    }

    /** Decode an EWRL block. Needs no panel: the band table carries the sizes. */
    fun decode(blob: ByteArray): ByteArray {
        for (i in 0 until 4) {
            if (blob[i] != MAGIC[i]) throw IllegalArgumentException("bad rle magic")
        }
        val version = blob[4].toInt() and 0xff
        if (version != VERSION) throw IllegalArgumentException("unsupported rle version $version")
        val bandCount = (blob[6].toInt() and 0xff) or ((blob[7].toInt() and 0xff) shl 8)
        val rawLen = u32(blob, 8)
        var off = HEADER_FIXED_LEN
        val lens = IntArray(bandCount)
        for (i in 0 until bandCount) {
            lens[i] = u32(blob, off)
            off += 4
        }
        val out = java.io.ByteArrayOutputStream(rawLen)
        for (len in lens) {
            out.write(decodeBand(blob.copyOfRange(off, off + len)))
            off += len
        }
        val result = out.toByteArray()
        if (result.size != rawLen) {
            throw IllegalArgumentException("rle rawLen mismatch: header $rawLen, decoded ${result.size}")
        }
        return result
    }

    /** Full `.rle` file: the asset's 32-byte header verbatim, then the EWRL block. */
    fun buildSidecar(assetHeader: ByteArray, payload: ByteArray, rowBytes: Int): ByteArray {
        if (assetHeader.size != WalletFormat.ASSET_HEADER_LEN) {
            throw IllegalArgumentException("asset header must be 32 bytes")
        }
        val block = encode(payload, rowBytes)
        val out = ByteArray(assetHeader.size + block.size)
        System.arraycopy(assetHeader, 0, out, 0, assetHeader.size)
        System.arraycopy(block, 0, out, assetHeader.size, block.size)
        return out
    }

    private fun putU32(buf: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) buf[at + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun u32(buf: ByteArray, at: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((buf[at + i].toInt() and 0xff) shl (8 * i))
        return v
    }
}
