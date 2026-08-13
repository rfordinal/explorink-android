package org.explorink.gpsbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shouldPostProgress` is the throttle for `onTileProgress` -> `notifyObserver()`
 * propagation (see `BridgeService.onTileProgress`, `PROGRESS_POST_THROTTLE_MS`).
 * The chunk callback itself always runs and keeps `tileProgress` current; this
 * function only decides whether that chunk's state also reaches the
 * notification/observer render. Three cases: too soon (withheld), the window
 * has elapsed (posted), and a terminal state (always posted, regardless of
 * timing).
 */
class BridgeProgressThrottleTest {

    @Test
    fun withinWindowIsWithheld() {
        val now = 1_000L
        val last = now - (BridgeService.PROGRESS_POST_THROTTLE_MS - 1)
        assertFalse(shouldPostProgress(now, last, terminal = false))
    }

    @Test
    fun atOrPastWindowIsPosted() {
        val now = 1_000L
        val last = now - BridgeService.PROGRESS_POST_THROTTLE_MS
        assertTrue(shouldPostProgress(now, last, terminal = false))
    }

    @Test
    fun terminalAlwaysPostsEvenMidWindow() {
        val now = 1_000L
        val last = now - 1 // as recent as a post can be
        assertTrue(shouldPostProgress(now, last, terminal = true))
    }
}
