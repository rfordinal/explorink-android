package org.explorink.gpsbridge.wallet

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest

/**
 * A localhost stand-in for the device's HTTP file endpoints, backed by a real temp
 * directory.
 *
 * It exists to reproduce the **quirks**, not the happy path: `/upload` refusing to
 * overwrite, `/upload` refusing to create a directory, `/mkdir` answering 400 for
 * an existing folder, `/rename` answering 409 for an occupied target, and
 * `/api/hash` being the only thing that says what the card actually holds. Those
 * are the behaviours that would corrupt a sync written against an idealised
 * device, so they are the behaviours a test has to be able to hit on purpose.
 *
 * Sources for every rule, all in the firmware's own web server:
 * `/mkdir` `CrossPointWebServer.cpp:837-895`, `/upload`
 * `CrossPointWebServer.cpp:681-835`, `/api/hash` `:524-573`, `/rename` `:828-975`,
 * `/delete` `:1003-1120`.
 *
 * Raw `ServerSocket` and a five-line HTTP parser rather than a library: the app has
 * two dependencies and this is a test fixture. Every response closes the
 * connection, which `HttpURLConnection` handles.
 *
 * There is a second copy of these rules in `tools/wallet_wifi_double.py`, for
 * driving the app on an emulator, where a JVM fixture cannot reach. Duplicated on
 * purpose and stated as a drift risk in `docs/android-wallet.md`.
 */
class WifiDeviceDouble(
    /** Stands in for `/trailink` on the card. */
    val cardRoot: File = Files.createTempDirectory("card").toFile().also {
        File(it, "trailink").mkdirs()
    },
) {

    private val server = ServerSocket(0)
    private var thread: Thread? = null

    @Volatile
    var running = true
        private set

    val port: Int get() = server.localPort
    val host: String get() = "127.0.0.1"

    /** Every request line served, for asserting the ORDER of stage-verify-swap. */
    val requests = java.util.Collections.synchronizedList(ArrayList<String>())

    /** Set to fail the next N uploads with this reply, to test a retry. */
    @Volatile
    var failUploads = 0

    /** Corrupt what lands, so `/api/hash` disagrees with what was sent. */
    @Volatile
    var corruptNextUpload = false

    /** Make `/api/hash` unavailable, i.e. an older firmware with no integrity check. */
    @Volatile
    var hashEndpointMissing = false

    fun start(): WifiDeviceDouble {
        thread = Thread({
            while (running) {
                try {
                    server.accept().use { serve(it) }
                } catch (t: Throwable) {
                    if (running) throw t
                }
            }
        }, "wifi-double").apply { isDaemon = true; start() }
        return this
    }

    fun stop() {
        running = false
        server.close()
        thread?.join(2000)
    }

    fun file(absPath: String): File = File(cardRoot, absPath.trimStart('/'))

    // --- the wire -----------------------------------------------------------

    private fun serve(sock: Socket) {
        val input = sock.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val request = lines.firstOrNull() ?: return
        requests.add(request)
        val method = request.substringBefore(' ')
        val target = request.substringAfter(' ').substringBefore(' ')
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val at = l.indexOf(':')
            if (at > 0) headers[l.substring(0, at).lowercase()] = l.substring(at + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) readExactly(input, length) else ByteArray(0)

        val path = target.substringBefore('?')
        val query = parseForm(target.substringAfter('?', ""))
        val out = sock.getOutputStream()

        when {
            method == "GET" && path == "/api/status" ->
                reply(out, 200, "{\"free\":123456}", "application/json")

            method == "GET" && path == "/api/hash" -> handleHash(out, query["path"])

            method == "POST" && path == "/mkdir" -> {
                val form = parseForm(String(body, Charsets.UTF_8))
                handleMkdir(out, form["path"], form["name"])
            }

            method == "POST" && path == "/upload" ->
                handleUpload(out, query["path"], headers["content-type"], body)

            method == "POST" && path == "/delete" -> {
                val form = parseForm(String(body, Charsets.UTF_8))
                // The target is in the body, not the query, so the request line alone
                // cannot say WHICH file a delete was for -- and "was the live file
                // deleted before its replacement was verified" is the one question
                // the log has to be able to answer.
                requests.add("POST /delete ${form["path"]}")
                handleDelete(out, form["path"])
            }

            method == "POST" && path == "/rename" -> {
                val form = parseForm(String(body, Charsets.UTF_8))
                handleRename(out, form["path"], form["name"])
            }

            else -> reply(out, 404, "Not found")
        }
    }

    private fun handleHash(out: OutputStream, path: String?) {
        if (hashEndpointMissing) {
            reply(out, 404, "Not found")
            return
        }
        if (path.isNullOrEmpty()) {
            reply(out, 400, "Missing path")
            return
        }
        val f = file(path)
        if (!f.exists()) {
            reply(out, 404, "Item not found")
            return
        }
        if (f.isDirectory) {
            reply(out, 400, "Path is a directory")
            return
        }
        val bytes = f.readBytes()
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        reply(out, 200, "{\"size\":${bytes.size},\"sha256\":\"$hex\"}", "application/json")
    }

    private fun handleMkdir(out: OutputStream, parent: String?, name: String?) {
        if (name.isNullOrEmpty()) {
            reply(out, 400, "Missing folder name")
            return
        }
        val dir = File(file(parent ?: "/"), name)
        // The real device answers 400 for an existing folder, which is
        // indistinguishable from a real failure without a second round trip. The
        // transport therefore creates blindly and lets the upload be the verdict.
        if (dir.exists()) {
            reply(out, 400, "Folder already exists")
            return
        }
        if (!file(parent ?: "/").isDirectory) {
            reply(out, 500, "Failed to create folder")
            return
        }
        reply(out, if (dir.mkdir()) 200 else 500,
            if (dir.isDirectory) "Folder created: $name" else "Failed to create folder")
    }

    private fun handleUpload(out: OutputStream, dir: String?, contentType: String?,
                             body: ByteArray) {
        if (failUploads > 0) {
            failUploads--
            reply(out, 400, "Unknown error during upload")
            return
        }
        val boundary = contentType?.substringAfter("boundary=", "")?.takeIf { it.isNotEmpty() }
        if (boundary == null) {
            reply(out, 400, "Unknown error during upload")
            return
        }
        val part = multipart(body, boundary)
        if (part == null) {
            reply(out, 400, "Unknown error during upload")
            return
        }
        val (name, bytes) = part
        val target = File(file(dir ?: "/"), name)
        // `/upload` does NOT create missing directories. The BLE receiver does; that
        // asymmetry is the whole reason the transport has a mkdirs step.
        if (!target.parentFile.isDirectory) {
            reply(out, 400, "Failed to create file on SD card")
            return
        }
        // ...and it refuses to overwrite, which is why the sync stages under a temp
        // name and swaps.
        if (target.exists()) {
            reply(out, 400, "File already exists: $name")
            return
        }
        val written = if (corruptNextUpload) {
            corruptNextUpload = false
            bytes.copyOf().also { if (it.isNotEmpty()) it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        } else {
            bytes
        }
        target.writeBytes(written)
        reply(out, 200, "File uploaded successfully: $name")
    }

    private fun handleDelete(out: OutputStream, path: String?) {
        if (path.isNullOrEmpty()) {
            reply(out, 400, "No paths provided")
            return
        }
        val f = file(path)
        if (!f.exists()) {
            reply(out, 400, "Failed to delete some items")
            return
        }
        reply(out, if (f.delete()) 200 else 400, "Deleted")
    }

    private fun handleRename(out: OutputStream, path: String?, name: String?) {
        if (path.isNullOrEmpty() || name.isNullOrEmpty()) {
            reply(out, 400, "Missing parameters")
            return
        }
        if (name.startsWith(".")) {
            reply(out, 403, "Cannot rename to protected name")
            return
        }
        val f = file(path)
        if (!f.exists()) {
            reply(out, 404, "Item not found")
            return
        }
        val target = File(f.parentFile, name)
        if (target.exists()) {
            reply(out, 409, "Target already exists")
            return
        }
        reply(out, if (f.renameTo(target)) 200 else 500, "Renamed")
    }

    // --- plumbing -----------------------------------------------------------

    /** filename and bytes of the first file part. */
    private fun multipart(body: ByteArray, boundary: String): Pair<String, ByteArray>? {
        val text = String(body, Charsets.ISO_8859_1)
        val headEnd = text.indexOf("\r\n\r\n")
        if (headEnd < 0) return null
        val partHead = text.substring(0, headEnd)
        val name = Regex("filename=\"([^\"]*)\"").find(partHead)?.groupValues?.get(1)
            ?: return null
        val start = headEnd + 4
        val end = text.indexOf("\r\n--$boundary", start)
        if (end < 0) return null
        return Pair(name, body.copyOfRange(start, end))
    }

    private fun parseForm(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in s.split('&')) {
            if (pair.isEmpty()) continue
            val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            out[k] = v
        }
        return out
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> 4
                else -> 0
            }
            if (state == 4) break
        }
        val s = buf.toString("ISO-8859-1")
        return s.removeSuffix("\r\n\r\n")
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val got = input.read(out, at, n - at)
            if (got <= 0) break
            at += got
        }
        return out
    }

    private fun reply(out: OutputStream, code: Int, body: String,
                      type: String = "text/plain") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        else -> "Error"
    }
}
