package org.explorink.gpsbridge

import java.util.zip.CRC32

/**
 * The sender side of the X4's map file transfer wire format.
 *
 * Contract: `docs/ble-map-transfer-protocol.md` in the xteink repo. Firmware:
 * `MapTransferReceiver.{h,cpp}`. Reference sender, already proven against real
 * hardware: `tools/blepush.py` -- this is a port of it, not a re-derivation.
 *
 * One BLE write is one frame. First byte is the opcode, every multi-byte field
 * after it is little endian:
 *
 *     0x01 begin   u32 totalLen | u32 crc32 | u8 pathLen | pathLen path bytes
 *     0x02 chunk   u32 offset   | payload
 *     0x03 abort   (no body)
 *
 * Pure functions only: no BLE, no Android, no I/O. That is what lets the whole
 * format be unit tested on the JVM.
 */
object TransferFrames {

    const val OP_BEGIN: Byte = 0x01
    const val OP_CHUNK: Byte = 0x02
    const val OP_ABORT: Byte = 0x03

    /** ATT's own header, which every write pays before our frame starts. */
    const val ATT_HEADER_BYTES = 3

    /** Opcode plus the u32 offset a chunk frame carries. */
    const val CHUNK_HEADER_BYTES = 5

    /** The device's cap on the relative path (`MapTransferReceiver::kMaxRelPathBytes`). */
    const val MAX_REL_PATH_BYTES = 80

    /** The device refuses a begin outside this range. */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    /**
     * Payload bytes that fit in one chunk on a link with this ATT MTU.
     *
     * Not an optimisation. On the default 23-byte MTU this is 15 bytes per
     * write, which measured out at 0.2 KB/s against the X4 -- a 4 KB tile took
     * 25 seconds. Requesting a larger MTU is what makes a real tile transfer
     * bearable, so the negotiated value has to reach this function rather than
     * being assumed.
     */
    fun maxChunkPayload(mtu: Int): Int {
        val usable = mtu - ATT_HEADER_BYTES - CHUNK_HEADER_BYTES
        // One byte is the protocol's own minimum payload; a smaller MTU than
        // that cannot happen (23 is the floor) but a bogus reported value can.
        return if (usable < 1) 1 else usable
    }

    /**
     * `base/<z>/<col>/<row>.tib` -- relative to `/trailink` on the card, the
     * layout `MapTileSource::buildPath()` reads and the CDN's `out_dir` mirrors
     * (`mapbuilder/tilegen/tiles.py`).
     */
    fun tileRelPath(z: Int, col: Long, row: Long): String = "base/$z/$col/$row.tib"

    /**
     * The device's own path rules, checked here so a doomed transfer is never
     * started: no leading or trailing slash, no `..` component, no empty
     * component, printable ASCII only, and inside the length cap.
     *
     * Duplicating the device's guard is deliberate. It stays the authority --
     * this only avoids spending a round trip to be told no.
     */
    fun isSafeRelPath(path: String): Boolean {
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_REL_PATH_BYTES) return false
        if (path.startsWith("/") || path.endsWith("/")) return false
        if (bytes.any { it < 0x20 || it > 0x7e }) return false
        if (path.contains('\\')) return false
        return path.split('/').none { it.isEmpty() || it == ".." }
    }

    /** zlib CRC32 -- what the device's `esp_rom_crc32_le(0, ...)` computes. */
    fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    fun beginFrame(relPath: String, totalLen: Int, crc32: Long): ByteArray {
        val path = relPath.toByteArray(Charsets.UTF_8)
        require(path.size in 1..MAX_REL_PATH_BYTES) { "path length ${path.size}" }
        require(totalLen in 1..MAX_FILE_BYTES) { "total length $totalLen" }
        val out = ByteArray(1 + 4 + 4 + 1 + path.size)
        out[0] = OP_BEGIN
        putU32(out, 1, totalLen.toLong())
        putU32(out, 5, crc32)
        out[9] = path.size.toByte()
        path.copyInto(out, 10)
        return out
    }

    fun chunkFrame(offset: Int, payload: ByteArray, payloadLen: Int = payload.size): ByteArray {
        require(payloadLen in 1..payload.size) { "payload length $payloadLen" }
        val out = ByteArray(CHUNK_HEADER_BYTES + payloadLen)
        out[0] = OP_CHUNK
        putU32(out, 1, offset.toLong())
        payload.copyInto(out, CHUNK_HEADER_BYTES, 0, payloadLen)
        return out
    }

    fun abortFrame(): ByteArray = byteArrayOf(OP_ABORT)

    private fun putU32(out: ByteArray, at: Int, value: Long) {
        out[at] = (value and 0xff).toByte()
        out[at + 1] = ((value shr 8) and 0xff).toByte()
        out[at + 2] = ((value shr 16) and 0xff).toByte()
        out[at + 3] = ((value shr 24) and 0xff).toByte()
    }

    /** One status line from `...0005`, as the device spells them. */
    sealed class Status {
        /** `RDY <totalLen>` -- begin accepted, send chunks. */
        data class Ready(val totalLen: Int) : Status()

        /** `OK <bytes> <crc32hex>` -- landed, CRC verified, renamed into place. */
        data class Ok(val bytes: Int, val crc32: Long) : Status()

        /** `ERR <reason>` -- terminal for that transfer; the device already cleaned up. */
        data class Err(val reason: String) : Status()

        /** Anything else. Logged, never acted on -- guessing at an unknown verdict is worse. */
        data class Unknown(val line: String) : Status()
    }

    fun parseStatus(line: String): Status {
        val t = line.trim()
        return when {
            t.startsWith("RDY ") -> t.removePrefix("RDY ").trim().toIntOrNull()
                ?.let { Status.Ready(it) } ?: Status.Unknown(t)

            t.startsWith("OK ") -> {
                val parts = t.removePrefix("OK ").trim().split(' ')
                val bytes = parts.getOrNull(0)?.toIntOrNull()
                val crc = parts.getOrNull(1)?.toLongOrNull(16)
                if (bytes != null && crc != null) Status.Ok(bytes, crc) else Status.Unknown(t)
            }

            t.startsWith("ERR ") -> Status.Err(t.removePrefix("ERR ").trim())
            else -> Status.Unknown(t)
        }
    }
}
