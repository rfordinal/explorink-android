package org.explorink.gpsbridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Turns a shared maps short link into text [PinCoordinates] can read.
 *
 * **Why this exists, and why it is the one exception.** The rider's own path
 * through this feature ends here: tap the map button, find the place in Google
 * Maps, drop a pin, Share back to ExplorInk. What the share sheet hands over is
 * `https://maps.app.goo.gl/<id>` and nothing else -- no coordinates in the text
 * at all. `PinCoordinates` refuses it rather than guessing, which is right, and
 * that refusal used to be where the whole flow stopped. Measured on a Galaxy S24
 * Ultra 2026-09-02: the field filled with the short link and the screen had
 * nothing more to say.
 *
 * **One request, one header, no page.** The link answers `302` with a `Location`
 * that already carries the pair:
 *
 *     302 -> https://www.google.com/maps/place/49.936764,17.902762/data=...!3d49.9367636!4d17.9027618...
 *
 * So this reads the header and stops. `instanceFollowRedirects` is off on
 * purpose: the redirect target is a Google Maps page and there is no reason to
 * fetch it. `!3d`/`!4d` is what [PinCoordinates] prefers anyway -- it is the
 * place that was looked up, where `@` is only where the camera sat.
 *
 * **What it costs, stated plainly.** The app now talks to one host besides the
 * tile CDN, and `AndroidManifest.xml` and `android/README.md` say so. It happens
 * only when the rider has just shared a link, never in the background and never
 * on a schedule. The privacy cost is close to nothing: the rider created that
 * link inside Google Maps on this same phone seconds earlier, so resolving it
 * tells Google nothing it did not already watch happen.
 *
 * Asynchronous for the same reason [TileSource] is: Android throws
 * `NetworkOnMainThreadException` for an HTTP call on the main thread, and the
 * callback comes back on the main thread exactly once.
 */
object MapsShortLink {

    private const val TAG = "MapsShortLink"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * Only the two shorteners Google Maps actually produces, matched on the host
     * rather than anywhere in the string. A rule that fired on any URL would send
     * this at whatever the rider pasted, which is a request they did not ask for.
     */
    private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co")

    /** True when [text] is a bare maps short link and nothing else. */
    fun isShortLink(text: String): Boolean {
        val t = text.trim()
        if (t.contains(' ') || t.contains('\n')) return false
        val host = runCatching { URL(t).host?.lowercase() }.getOrNull() ?: return false
        if (host !in SHORT_HOSTS) return false
        // `goo.gl` and `g.co` shorten more than maps, so those need the path to
        // say so; `maps.app.goo.gl` is maps by construction.
        if (host == "maps.app.goo.gl") return true
        val path = runCatching { URL(t).path ?: "" }.getOrNull() ?: ""
        return path.startsWith("/maps") || path.startsWith("/kgs")
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "maps-shortlink") }

    /**
     * Hands back the expanded URL, or null with a short reason the rider can read.
     *
     * Null is never "there are no coordinates" -- that verdict belongs to
     * [PinCoordinates] once it has the expanded text. Null here means the
     * expansion itself did not happen.
     */
    fun resolve(text: String, done: (String?, String?) -> Unit) {
        io.execute {
            val r = resolveBlocking(text.trim())
            MainThread.post { done(r.first, r.second) }
        }
    }

    fun resolveBlocking(text: String): Pair<String?, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(text).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            when (val code = conn.responseCode) {
                in 300..399 -> {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) {
                        null to "that link went nowhere"
                    } else {
                        loc to null
                    }
                }
                // A short link that resolves in one hop with no redirect has
                // nothing for us; say so rather than handing back a page.
                else -> {
                    Log.w(TAG, "short link answered HTTP $code")
                    null to "that link could not be opened (HTTP $code)"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolve failed: ${t.javaClass.simpleName}")
            null to "no network to open that link"
        } finally {
            conn?.disconnect()
        }
    }
}
