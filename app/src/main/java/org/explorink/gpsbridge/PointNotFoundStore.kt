package org.explorink.gpsbridge

import android.content.SharedPreferences

/**
 * Persists a point shard's 404 streak across sync sessions.
 *
 * The whole reason this exists rather than an in-memory set:
 * `docs/point-layer-lifecycle.md` decision 3 requires two consecutive 404s in
 * two *separate* sync sessions before `gone` fires, precisely so a transient
 * CDN hiccup (a partial deploy, an rsync mid-flight) cannot delete a shard
 * the rider will want back the next time they are near it. A tracker that
 * forgot the first miss when the app process died would silently relax that
 * to "one 404, any time" -- the exact failure the rule exists to prevent.
 *
 * One boolean per shard that has missed once, keyed `"$col/$row"`. A shard
 * that never repeats a miss never grows an entry it has to prune: the second
 * miss (returns true) and a later success (recordFound) both remove the key.
 */
class PointNotFoundStore(private val prefs: SharedPreferences) : PointSync.NotFoundTracker {

    companion object {
        private const val KEY_PREFIX = "point_404_"
    }

    private fun key(col: Long, row: Long) = "$KEY_PREFIX$col/$row"

    override fun recordNotFound(col: Long, row: Long): Boolean {
        val k = key(col, row)
        if (!prefs.getBoolean(k, false)) {
            prefs.edit().putBoolean(k, true).apply()
            return false
        }
        // Second consecutive miss -- the streak is spent either way, whether
        // the caller sends `gone` now or the shard reappears later.
        prefs.edit().remove(k).apply()
        return true
    }

    override fun recordFound(col: Long, row: Long) {
        prefs.edit().remove(key(col, row)).apply()
    }
}
