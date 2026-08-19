package org.explorink.gpsbridge.wallet

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executor

/**
 * Wi-Fi Fast Sync (phase P7's phone half), over the device's own HTTP file
 * endpoints. Nothing new was needed on the device.
 *
 * Measured: **199-236 kB/s** end to end including the card write
 * (`docs/wallet-plan.md` 7d), against BLE's 8-9 kB/s. A whole encrypted A4 page
 * is ~4.3 s here and ~2 minutes there, so this is where bulk belongs -- and it is
 * why an encrypted tree shipping no RLE sidecars stopped mattering.
 *
 * ## The endpoints, as they really behave
 *
 * All three quirks were learned the hard way and every one of them would corrupt
 * a sync that assumed the two transports behave alike:
 *
 *  - `POST /mkdir` takes **form fields** `path` (parent) and `name`, not a path in
 *    the query string. An existing folder answers **400 "Folder already exists"**
 *    (`CrossPointWebServer.cpp:872-875`), so that is a success for us.
 *  - `POST /upload?path=<dir>` is multipart, and it **does not create missing
 *    directories** -- a post into an absent shard fails with
 *    `400 Failed to create file on SD card`. BLE's receiver creates them
 *    (`MapTransferReceiver.cpp:292`); this one does not.
 *  - `POST /upload` **refuses to overwrite**: `400 File already exists`
 *    (`CrossPointWebServer.cpp:735-739`).
 *  - `GET /api/hash?path=…` answers `{"size":N,"sha256":"…"}`, streamed off the
 *    card in 1 kB bites (`CrossPointWebServer.cpp:524-573`). **This is the only
 *    integrity check the Wi-Fi path has.** A 200 from `/upload` means "written",
 *    not "correct".
 *  - `POST /rename` takes `path` (the file) and `name` (a bare new name) and
 *    answers **409 "Target already exists"** rather than overwriting
 *    (`CrossPointWebServer.cpp:948-952`).
 *
 * ## Stage, verify, swap -- and why it is not negotiable
 *
 *     upload  <name>.part      the live file is untouched
 *     hash    <name>.part      the device says what it holds
 *     delete  <name>           only now, and only if the hash matched
 *     rename  <name>.part -> <name>
 *
 * The other order was tried on the laptop: delete first, upload second. A crash
 * between the two **wiped a card's manifest and wrote nothing back** -- brief
 * section 31's failure exactly ("delete old / start writing new / connection dies
 * / nothing works"). A temp file is always safe to delete because it is never the
 * live file; the live file is deleted only after its replacement has been verified
 * on the card.
 *
 * ## Portability
 *
 * `java.net.HttpURLConnection` is JVM, not Android, and nothing here touches an
 * Android type -- the class sits behind [WalletTransport] like any other pipe. An
 * iOS port rewrites this file against `URLSession` and keeps the engine, the queue
 * and the stage-verify-swap order untouched. Two iOS costs are real and are
 * written down in `docs/android-wallet.md` ("iOS notes"): joining the device's
 * hotspot needs `NEHotspotConfiguration` and a user prompt, and reaching a LAN
 * address at all needs the Local Network permission.
 */
class WalletWifiTransport(
    /** The device's address. 192.168.69.20 in station mode; the AP has its own. */
    var host: String,
    var port: Int = 80,
    /** Where the blocking HTTP work runs. Direct in tests, a worker thread in the app. */
    private val executor: Executor = Executor { it.run() },
    /** Where callbacks land. `MainThread.post` in the app, direct in tests. */
    private val poster: (Runnable) -> Unit = { it.run() },
    /** Card root the wallet tree lives under. `/trailink`, per the naming rules. */
    private val cardRoot: String = "/trailink",
) : WalletTransport {

    override val name: String get() = "wifi"
    override val label: String get() = "Wi-Fi"

    /** Lower bound of the measured range, so an estimate never flatters itself. */
    override val bytesPerSecond: Int get() = 199_000

    /** `/upload` is a whole-file multipart POST. No ranges, so no mid-file resume. */
    override val resumesAcrossSessions: Boolean get() = false

    /** Shards this run has already tried to create. One blind mkdir per shard, not per asset. */
    private val madeDirs = HashSet<String>()

    @Volatile
    private var cancelled = false

    /** Last `/api/status` round trip, ms, or -1. Set by [probe]. */
    var lastProbeMs: Long = -1
        private set

    var lastProbeDetail: String = "not probed"
        private set

    /**
     * Is the device there? A `GET /api/status`, which answered in ~59 ms on
     * hardware. Blocking -- call it off the UI thread.
     */
    fun probe(): Boolean {
        val start = System.currentTimeMillis()
        val r = get("/api/status")
        lastProbeMs = System.currentTimeMillis() - start
        lastProbeDetail = if (r.code == 200) "HTTP 200 in ${lastProbeMs} ms"
        else "HTTP ${r.code} ${r.text.take(60)}"
        return r.code == 200
    }

    /**
     * Optimistic: a reachable host is only known by asking, and asking here would
     * put a blocking round trip on the engine's thread. The screen probes, the
     * engine's first `/mkdir` or `/upload` is the real answer, and a dead host
     * comes back as a non-retryable failure that stops the run.
     */
    override fun isReady(): Boolean = host.isNotEmpty()

    override fun cancel() {
        cancelled = true
    }

    override fun send(job: SendJob, cb: SendCallback) {
        cancelled = false
        executor.execute {
            val outcome = try {
                stageVerifySwap(job)
            } catch (t: Throwable) {
                Outcome("wifi: ${t.javaClass.simpleName}: ${t.message}", retryable = true)
            }
            poster(Runnable {
                when {
                    outcome.confirmed != null -> {
                        cb.onProgress(job.bytes.size)
                        cb.onConfirmed(outcome.confirmed)
                    }
                    else -> cb.onFailed(outcome.reason ?: "wifi failed", outcome.retryable)
                }
            })
        }
    }

    private class Outcome(
        val reason: String? = null,
        val retryable: Boolean = true,
        val confirmed: String? = null,
    )

    private fun stageVerifySwap(job: SendJob): Outcome {
        val abs = "$cardRoot/${job.relPath}"
        val dir = abs.substringBeforeLast('/')
        val fileName = abs.substringAfterLast('/')
        val tempName = "$fileName.part"
        val tempAbs = "$dir/$tempName"

        // 1. The shard. `/upload` will not create it, so we do -- blind, because an
        //    existing folder answers 400 and that is indistinguishable from a real
        //    failure without a second round trip. Cached per shard.
        mkdirs(dir)
        if (cancelled) return Outcome("cancelled", retryable = true)

        // 2. Stage. Optimistic: on a card that does not already hold a leftover temp
        //    -- the normal case -- this is one request. `/upload` refuses to
        //    overwrite, so a leftover temp from a killed run comes back as
        //    "File already exists", and only THEN is the temp deleted and the upload
        //    retried. Deleting a temp is always safe: it is never the live file.
        //    Measured on the localhost double: doing the delete unconditionally cost
        //    one wasted round trip per asset, 80 of them on an 80-asset wallet, all
        //    of them 400s.
        var up = upload(dir, tempName, job.bytes)
        if (up.code == 400 && up.text.contains("already exists")) {
            post("/delete", mapOf("path" to tempAbs))
            up = upload(dir, tempName, job.bytes)
        }
        if (up.code != 200) {
            val fatal = up.code < 0
            return Outcome("upload ${up.code}: ${up.text.take(80)}", retryable = !fatal)
        }
        if (cancelled) return Outcome("cancelled", retryable = true)

        // 4. Verify, off the card. THE integrity check -- the 200 above was not one.
        val hash = hashOf(tempAbs)
        if (hash == null) {
            return Outcome("no hash for $tempName", retryable = true)
        }
        if (hash.size != job.bytes.size || !hash.sha256.equals(job.sha256, ignoreCase = true)) {
            // The card holds something else. Take the temp away; the live file was
            // never touched, so nothing is lost by failing here.
            post("/delete", mapOf("path" to tempAbs))
            return Outcome(
                "hash mismatch: card ${hash.size} B ${hash.sha256.take(12)}, " +
                    "sent ${job.bytes.size} B ${job.sha256.take(12)}",
                retryable = true)
        }

        // 5. Swap. Optimistic again: `/rename` answers 409 when the target exists, so
        //    the live file is deleted only when there really is one -- and only after
        //    its verified replacement is on the card. That ordering is the whole rule:
        //    a host script that deleted first and uploaded second wiped a card's
        //    manifest and wrote nothing back (brief section 31).
        //
        //    A crash in the window between the delete and the second rename leaves the
        //    VERIFIED temp on the card and no live file. The next run re-stages and
        //    swaps, so the loss is one asset's transfer, never the data.
        var ren = post("/rename", mapOf("path" to tempAbs, "name" to fileName))
        if (ren.code == 409) {
            post("/delete", mapOf("path" to abs))
            ren = post("/rename", mapOf("path" to tempAbs, "name" to fileName))
        }
        if (ren.code != 200) {
            return Outcome("rename ${ren.code}: ${ren.text.take(80)}", retryable = true)
        }

        // The confirmation covers the BYTES (hashed on the card at the temp path)
        // and the NAME (a 200 from rename, which is a FAT directory entry and cannot
        // alter content). Re-hashing after the rename would double the transfer time
        // to re-prove bytes nothing touched.
        return Outcome(confirmed = "api/hash ${hash.size} B sha256 ${hash.sha256.take(16)}")
    }

    /**
     * Create every component under the card root, parent first.
     *
     * The card root itself is never created -- `/trailink` is the firmware's own
     * directory and always there. Each component is remembered, so a wallet with 20
     * shards issues one `/mkdir` for `wallet` and one per shard, not 20 pairs.
     */
    private fun mkdirs(dir: String) {
        if (!dir.startsWith("$cardRoot/")) return
        var parent = cardRoot
        for (p in dir.removePrefix("$cardRoot/").split('/')) {
            val here = "$parent/$p"
            if (madeDirs.add(here)) post("/mkdir", mapOf("path" to parent, "name" to p))
            parent = here
        }
    }

    // --- HTTP ---------------------------------------------------------------

    class Hash(val size: Int, val sha256: String)

    /** `{"size":N,"sha256":"…"}`, or null when the file is not there. */
    fun hashOf(absPath: String): Hash? {
        val r = get("/api/hash?path=" + enc(absPath))
        if (r.code != 200) return null
        val size = Regex("\"size\"\\s*:\\s*(\\d+)").find(r.text)?.groupValues?.get(1)?.toIntOrNull()
        val sha = Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]+)\"").find(r.text)?.groupValues?.get(1)
        if (size == null || sha == null) return null
        return Hash(size, sha)
    }

    class Reply(val code: Int, val text: String)

    /**
     * `http://<host>:<port>`, with `host` allowed to carry its own port.
     *
     * The port in the string is what makes the localhost double reachable without an
     * app change: `--es host 127.0.0.1:8099` after an `adb reverse` points the real
     * transport at `tools/wallet_wifi_double.py`. On the device itself the port is
     * always 80 and nobody types one.
     */
    private fun base(): String =
        if (host.contains(':')) "http://$host" else "http://$host:$port"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(path: String): Reply = request("GET", path, null, null)

    fun post(path: String, form: Map<String, String>): Reply {
        val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            .toByteArray(Charsets.UTF_8)
        return request("POST", path, "application/x-www-form-urlencoded", body)
    }

    /** Multipart, because that is what `/upload` parses. Field name is not checked. */
    fun upload(dir: String, fileName: String, bytes: ByteArray): Reply {
        val boundary = "----ExplorInkWallet%08x".format(bytes.size xor fileName.hashCode())
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = ByteArray(head.size + bytes.size + tail.size)
        head.copyInto(body, 0)
        bytes.copyInto(body, head.size)
        tail.copyInto(body, head.size + bytes.size)
        return request("POST", "/upload?path=" + enc(dir),
            "multipart/form-data; boundary=$boundary", body)
    }

    private fun request(method: String, path: String, contentType: String?, body: ByteArray?): Reply {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(base() + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                // Generous, and deliberately so: an upload's response comes only
                // after the card write finishes, and a 512 kB asset at 199 kB/s is
                // already 2.6 s before FAT allocation gets slow.
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                if (body != null) {
                    doOutput = true
                    contentType?.let { setRequestProperty("Content-Type", it) }
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = readAll(if (code in 200..299) conn.inputStream else conn.errorStream)
            return Reply(code, text)
        } catch (t: Throwable) {
            // -1 means "the host is not answering", which the caller treats as fatal:
            // marching through the rest of the queue against a dead hotspot would
            // fail every asset and bury the real reason.
            return Reply(-1, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun readAll(s: InputStream?): String {
        if (s == null) return ""
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = s.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        s.close()
        return out.toString("UTF-8")
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 30_000

        /** The device's own address in station mode, measured 2026-08-18. */
        const val DEFAULT_HOST = "192.168.69.20"
    }
}
