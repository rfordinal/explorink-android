package org.explorink.gpsbridge

/**
 * The device's own way of stating a transfer, ported exactly.
 *
 * Why it lives here and not inline in the UI: the rider watches both screens at
 * once, and two screens describing one transfer differently is worse than one of
 * them being terse. The app used to compute kB as 1024 bytes while the device
 * uses 1000, and to time each square separately while the device times the whole
 * fetch -- so every number differed a little and nothing was wrong enough to
 * point at.
 *
 * Reference, and what to keep this file matching:
 * `firmware/explorink/src/activities/map/TileSyncActivity.cpp`,
 * `formatBytes` / `formatDuration` / `formatSummary`. No Android types, so it is
 * unit-tested against the numbers that file produces.
 */
object TileFormat {

    /**
     * Decimal kB/MB, not KiB -- the device's `formatBytes`. A rider compares this
     * against a phone's storage screen, which is decimal everywhere.
     */
    fun bytes(b: Int): String = when {
        b < 1000 -> "$b B"
        b < 1_000_000 -> "${(b + 500) / 1000} kB"
        else -> {
            // One decimal past a megabyte: "1 MB" for anything from 1.0 to 1.9
            // would hide most of a fetch's progress.
            val tenths = (b + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} MB"
        }
    }

    /** The device's `formatDuration`. */
    fun duration(seconds: Int): String = when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }

    /**
     * Bytes per second over the whole fetch, from **completed** squares only, as
     * the device computes it. The square in flight is deliberately excluded: its
     * bytes are on the card but its own verdict is not in yet, and counting them
     * makes the rate jump every chunk.
     *
     * Null until a square has landed and a second has passed -- before that there
     * is no honest number, and the device shows none either.
     */
    fun ratePerSec(completedBytes: Int, elapsedMs: Long, completedSquares: Int): Int? {
        if (completedSquares == 0 || elapsedMs < 1000) return null
        val elapsedS = (elapsedMs / 1000).coerceAtLeast(1)
        return (completedBytes / elapsedS).toInt()
    }

    /** `7.4 kB/s`, one decimal, decimal thousands -- the device's rate format. */
    fun rate(bytesPerSec: Int): String = "${bytesPerSec / 1000}.${(bytesPerSec % 1000) / 100} kB/s"

    /**
     * Seconds left: time per settled square times the squares not settled yet.
     * A skip settles a square too -- a tile the supplier lacks really does
     * shorten the run, even though it must not fill the bar.
     *
     * Null when nothing has settled or nothing is left. Squares vary a lot in
     * size (6 kB to 75 kB in one real fetch), so this firms up as the run goes,
     * which is what an ETA is.
     */
    fun etaSeconds(elapsedMs: Long, completedSquares: Int, skippedSquares: Int, totalSquares: Int): Int? {
        val settled = completedSquares + skippedSquares
        if (settled == 0) return null
        val remaining = (totalSquares - settled).coerceAtLeast(0)
        if (remaining == 0) return null
        val elapsedS = (elapsedMs / 1000).coerceAtLeast(1)
        return (elapsedS * remaining / settled).toInt()
    }

    /**
     * The whole summary line, in the device's order: settled of asked, squares the
     * supplier could not provide stated apart, bytes moved, rate, time left.
     *
     * [movedBytes] includes the square in flight, as the device's `moved` does.
     * [completedBytes] excludes it, because the rate is computed from it and the
     * device computes the rate the same way -- the two numbers are different on
     * purpose and swapping them is exactly the drift this file exists to stop.
     */
    fun summary(
        completedSquares: Int,
        skippedSquares: Int,
        totalSquares: Int,
        movedBytes: Int,
        completedBytes: Int,
        elapsedMs: Long,
    ): String {
        val parts = StringBuilder("$completedSquares / $totalSquares")
        if (skippedSquares > 0) parts.append("   $skippedSquares not available")
        parts.append("   ").append(bytes(movedBytes))
        ratePerSec(completedBytes, elapsedMs, completedSquares)?.let { parts.append("   ").append(rate(it)) }
        etaSeconds(elapsedMs, completedSquares, skippedSquares, totalSquares)
            ?.let { parts.append("   ").append(duration(it)).append(" left") }
        return parts.toString()
    }
}
