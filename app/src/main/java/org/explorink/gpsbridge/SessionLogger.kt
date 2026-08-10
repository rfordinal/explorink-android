package org.explorink.gpsbridge

import android.location.Location
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * One JSON Lines file per app session, in the app's own external files
 * directory so it shows up over MTP / in the Files app with no extra
 * permission.
 *
 * Follows `docs/replay-concept.md`:
 *
 *  - first line is a header with a format version
 *  - two streams in the one session file, tagged by `type`: `fix` for every
 *    raw Location the phone produced, `packet` for every 21 bytes actually
 *    written to the characteristic (successes and failures both). `event`
 *    lines carry connect / disconnect / scan so a gap in the recording is
 *    explained rather than mysterious.
 *  - append-only, flushed AND fsynced after every line, so an app killed
 *    mid-ride leaves a usable partial file instead of a truncated one
 *  - every timestamped line carries UTC millis plus the tz offset, the same
 *    clock convention as the wire packet
 *
 * All writes go through one single-thread executor: file order is stable and
 * the main thread never blocks on I/O.
 */
class SessionLogger(private val dir: File, private val appVersion: String) {

    companion object {
        private const val TAG = "SessionLogger"

        /**
         * Bump when the meaning of any field changes.
         *
         * 2: the packet stream stopped being a fixed 5 s cadence. `packet` lines
         *    carry `reason` / `moved_m` / `since_last_ms`, and the header carries
         *    the send policy's bounds. A reader that assumes v1's fixed interval
         *    would mis-read a v2 file's gaps as signal loss.
         */
        const val FORMAT_VERSION = 2

        const val PACKET_ENCODING = "hex"
    }

    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    var file: File? = null
        private set

    private var out: FileOutputStream? = null
    private var writer: OutputStreamWriter? = null

    /**
     * Set before the executor shuts down. A late BLE callback (a disconnect
     * delivered after the activity is gone) must be dropped, not turned into a
     * RejectedExecutionException on the main thread.
     */
    @Volatile
    private var closed = false

    @Volatile
    var linesWritten: Long = 0L
        private set

    fun open() {
        submit {
            try {
                if (!dir.exists()) dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val f = File(dir, "explorink-gps-$stamp.jsonl")
                val fos = FileOutputStream(f, true)
                out = fos
                writer = OutputStreamWriter(fos, Charsets.UTF_8)
                file = f

                val h = JSONObject()
                h.put("type", "header")
                h.put("format", FORMAT_VERSION)
                h.put("app", "org.explorink.gpsbridge")
                h.put("app_version", appVersion)
                h.put("packet_encoding", PACKET_ENCODING)
                h.put("packet_bytes", PositionPacket.SIZE)
                h.put("target_device_name", BleLink.DEVICE_NAME)
                h.put("service_uuid", BleLink.SERVICE_UUID.toString())
                h.put("position_characteristic_uuid", BleLink.POSITION_CHAR_UUID.toString())
                h.put("streams", "fix|packet|event")
                // The send policy that produced this file's packet stream. A
                // replay that re-derives packets from the raw fixes needs to
                // know which rules it is reproducing or replacing.
                h.put("send_min_interval_ms", SendPolicy.MIN_INTERVAL_MS)
                h.put("send_keepalive_interval_ms", SendPolicy.KEEPALIVE_INTERVAL_MS)
                h.put("send_move_threshold_m", SendPolicy.MOVE_THRESHOLD_M)
                h.put("send_heading_min_move_m", SendPolicy.HEADING_MIN_MOVE_M)
                // The address actually connected to is not known yet at header
                // time; it lands on the first `event` line with kind=connected.
                writeLineLocked(h)
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
            }
        }
    }

    fun logFix(loc: Location) {
        val now = System.currentTimeMillis()
        submit {
            val o = JSONObject()
            o.put("type", "fix")
            stamp(o, now)
            o.put("provider", loc.provider ?: "unknown")
            o.put("lat", loc.latitude)
            o.put("lon", loc.longitude)
            if (loc.hasAltitude()) o.put("alt_m", loc.altitude)
            if (loc.hasBearing()) o.put("bearing_deg", loc.bearing.toDouble())
            if (loc.hasSpeed()) o.put("speed_mps", loc.speed.toDouble())
            if (loc.hasAccuracy()) o.put("accuracy_m", loc.accuracy.toDouble())
            if (loc.hasVerticalAccuracy()) {
                o.put("vertical_accuracy_m", loc.verticalAccuracyMeters.toDouble())
            }
            if (loc.hasBearingAccuracy()) {
                o.put("bearing_accuracy_deg", loc.bearingAccuracyDegrees.toDouble())
            }
            if (loc.hasSpeedAccuracy()) {
                o.put("speed_accuracy_mps", loc.speedAccuracyMetersPerSecond.toDouble())
            }
            o.put("is_mock", loc.isMock)
            // The fix's own clock, as reported by the provider.
            o.put("fix_time_utc_ms", loc.time)
            o.put("elapsed_realtime_nanos", loc.elapsedRealtimeNanos)
            writeLineLocked(o)
        }
    }

    fun logPacket(
        bytes: ByteArray,
        ok: Boolean,
        seq: Int,
        heading: Int,
        latDeg: Double,
        lonDeg: Double,
        accuracyM: Double,
        speedKmh: Double,
        /** Metres above sea level, null when the fix carried no altitude. */
        altitudeM: Double? = null,
        error: String?,
        /** Why the send policy fired: first / moved / heading / keepalive. */
        reason: String? = null,
        /** Metres from the previously sent position, null on the first packet. */
        movedM: Double? = null,
        /** Millis since the previous send, -1 on the first packet. */
        sinceLastMs: Long = -1L,
    ) {
        val now = System.currentTimeMillis()
        val hex = PositionPacket.toHex(bytes)
        submit {
            val o = JSONObject()
            o.put("type", "packet")
            stamp(o, now)
            o.put("ok", ok)
            o.put("bytes", hex)
            o.put("len", bytes.size)
            o.put("seq", seq)
            o.put("heading", heading)
            // The decoded values as they went out, so a reader does not have to
            // parse the hex to eyeball the file.
            o.put("lat", latDeg)
            o.put("lon", lonDeg)
            o.put("accuracy_m", accuracyM)
            o.put("speed_kmh", speedKmh)
            if (altitudeM != null) o.put("alt_m", altitudeM)
            // The send policy's own reasoning, so a replay can tell a packet
            // that was earned by movement from an hourly keep-alive, and can
            // re-derive a different cadence from the raw fixes around it.
            if (reason != null) o.put("reason", reason)
            if (movedM != null) o.put("moved_m", movedM)
            if (sinceLastMs >= 0) o.put("since_last_ms", sinceLastMs)
            if (error != null) o.put("error", error)
            writeLineLocked(o)
        }
    }

    fun logEvent(kind: String, message: String?, extras: Map<String, Any?>? = null) {
        val now = System.currentTimeMillis()
        submit {
            val o = JSONObject()
            o.put("type", "event")
            stamp(o, now)
            o.put("kind", kind)
            if (message != null) o.put("message", message)
            extras?.forEach { (k, v) -> if (v != null) o.put(k, v) }
            writeLineLocked(o)
        }
    }

    fun close() {
        if (closed) return
        // The flag goes up first so nothing new is queued behind this, then the
        // final task drains whatever was already queued.
        closed = true
        try {
            io.execute {
                try {
                    writer?.flush()
                    writer?.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "close failed", t)
                }
                writer = null
                out = null
            }
        } catch (t: RejectedExecutionException) {
            Log.w(TAG, "close after shutdown", t)
        }
        io.shutdown()
    }

    /** Queues one file operation, dropping it if the session is already closed. */
    private fun submit(block: () -> Unit) {
        if (closed) return
        try {
            io.execute(block)
        } catch (t: RejectedExecutionException) {
            Log.w(TAG, "write after shutdown", t)
        }
    }

    private fun stamp(o: JSONObject, utcMs: Long) {
        o.put("t_utc_ms", utcMs)
        o.put("t_utc_s", utcMs / 1000L)
        o.put("tz_offset_min", TimeZone.getDefault().getOffset(utcMs) / 60000)
    }

    /** Runs only on the single-thread executor, so no extra locking needed. */
    private fun writeLineLocked(o: JSONObject) {
        val w = writer ?: return
        try {
            w.write(o.toString())
            w.write("\n")
            w.flush()
            // flush() only pushes into the OS; sync() is what survives a kill.
            out?.fd?.sync()
            linesWritten++
        } catch (t: Throwable) {
            Log.e(TAG, "write failed", t)
        }
    }
}
