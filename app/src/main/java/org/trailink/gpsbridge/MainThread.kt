package org.trailink.gpsbridge

import android.os.Handler
import android.os.Looper

/**
 * Hands work back to the main thread.
 *
 * [TileFetcher] is single-threaded by contract and [BleLink] owns all its state
 * on the main looper, so anything that had to leave that thread -- a file read,
 * an HTTP GET -- has to come back to it before touching either. One place to do
 * that, so no worker is left guessing.
 *
 * A plain object rather than an injected dependency because the alternative is
 * threading a Handler through every TileSource for the sake of a line of test
 * setup; the tests use their own fake sources and never reach this.
 */
object MainThread {
    private val handler = Handler(Looper.getMainLooper())

    fun post(action: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            action()
        } else {
            handler.post(action)
        }
    }
}
