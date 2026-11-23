package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a zone costs, and the one distinction the whole feature rests on.
 *
 * The fixtures are the live CDN, read 2026-09-02: `v4/base/index/7/64/47.idx`
 * against the 20 km Barcelona box is **55 present, 3 absent on built ground, 13
 * on ground nobody has built**, and the three exact sizes are the ones compared
 * byte for byte against the served HTTP bodies.
 */
class TilePlanTest {

    private val barcelonaLat = 41.3874
    private val barcelonaLon = 2.1686

    /** The four z11 cells `v4/mapset.json` lists for metropolitan Barcelona. */
    private val barcelonaAreas = listOf(
        area("auto-z11-1035-764"),
        area("auto-z11-1035-765"),
        area("auto-z11-1036-764"),
        area("auto-z11-1036-765"),
    )

    private fun area(name: String) = TilePlan.BuiltArea(name, 0.0, 0.0, 0.0, 0.0)

    private fun present(size: Long, contentId: Long = 0x1234L) =
        TilePlan.Reading.Found(TileIndex.Slot(true, TileIndex.COVERAGE_FULL, contentId, 1_786_201_179L, size))

    private val emptySlot = TilePlan.Reading.Found(TileIndex.Slot.ABSENT)

    // --- not yet against never ---------------------------------------------

    @Test
    fun `an empty slot on built ground is absent and an empty slot on unbuilt ground is not`() {
        val built = TilePlan.BuiltGround(barcelonaAreas)
        // Sea southeast of the city. Its z11 cell was built, so this square is
        // as finished as it will ever be -- 404 today and 404 for ever.
        assertTrue(built.covers(TileRef(13, 4146, 3061)))
        assertEquals(TilePlan.State.ABSENT, TilePlan.classify(emptySlot, true))

        // One column further east: no area covers it, so the same 404 means the
        // server has not got round to it.
        assertFalse(built.covers(TileRef(13, 4148, 3061)))
        assertEquals(TilePlan.State.WAITING_BUILD, TilePlan.classify(emptySlot, false))
    }

    @Test
    fun `an unreachable cdn is not a verdict`() {
        assertEquals(TilePlan.State.UNKNOWN, TilePlan.classify(TilePlan.Reading.Unreachable, true))
        assertEquals(TilePlan.State.UNKNOWN, TilePlan.classify(TilePlan.Reading.Unreachable, false))
    }

    @Test
    fun `no index block at all means nothing is built here yet`() {
        // `v4/base/index/7/60/44.idx` answers 404, which CdnIndexSource maps to
        // NotPublished. Both 404s are real; only the one inside a block that
        // exists can ever be absent.
        assertEquals(TilePlan.State.WAITING_BUILD, TilePlan.classify(TilePlan.Reading.NoBlock, false))
        // Even when an area claims the ground: the block is written by the
        // build, so a claim with no block behind it is an unfinished build.
        assertEquals(TilePlan.State.WAITING_BUILD, TilePlan.classify(TilePlan.Reading.NoBlock, true))
    }

    @Test
    fun `an area is found from the tile's own z11 ancestor`() {
        val built = TilePlan.BuiltGround(barcelonaAreas)
        assertTrue(built.covers(TileRef(11, 1036, 765)))
        assertTrue(built.covers(TileRef(12, 2072, 1530)))
        assertTrue(built.covers(TileRef(13, 4147, 3062)))
        assertFalse(built.covers(TileRef(13, 4148, 3062)))
        assertFalse(built.covers(TileRef(11, 1037, 764)))
    }

    @Test
    fun `an area with a hand written name is tested by its bbox instead`() {
        // T-310 publishes areas on purpose, and those will not be named
        // auto-z11-<col>-<row>. Making the name the only path would call that
        // ground unbuilt for ever.
        val hand = TilePlan.BuiltArea("pyrenees-weekend", south = 41.0, west = 2.0, north = 42.0, east = 3.0)
        val built = TilePlan.BuiltGround(listOf(hand))
        assertTrue(built.covers(TileRef(13, 4144, 3059)))
        // Wholly inside, not merely touching: a tile hanging over the edge of a
        // built area is left waiting rather than declared sea for ever.
        assertFalse(built.covers(TileRef(11, 1032, 764)))
    }

    @Test
    fun `nothing published means nothing is covered`() {
        assertFalse(TilePlan.BuiltGround().covers(TileRef(13, 4144, 3059)))
    }

    // --- the whole Barcelona box -------------------------------------------

    @Test
    fun `the twenty kilometre barcelona box splits the way the cdn splits it`() {
        val built = TilePlan.BuiltGround(barcelonaAreas)
        // The three squares that are genuinely not there, each covered by
        // auto-z11-1036-765 and each a 404 today.
        val sea = setOf(
            TileRef(13, 4146, 3061).key,
            TileRef(13, 4147, 3061).key,
            TileRef(13, 4147, 3062).key,
        )
        val entries = TileBox.tilesFor(barcelonaLat, barcelonaLon, 20.0).map { tile ->
            val covered = built.covers(tile)
            val reading = if (!covered || tile.key in sea) emptySlot else present(258_000L)
            TilePlan.Entry.of(tile, reading, covered)
        }

        val s = TilePlan.summarize(entries)
        assertEquals(71, s.tiles)
        assertEquals(55, s.present)
        assertEquals(3, s.absent)
        assertEquals(13, s.waitingBuild)
        assertEquals(0, s.unknown)
    }

    // --- bytes and time ------------------------------------------------------

    @Test
    fun `the byte total is the index's own sizes added up`() {
        // The three tiles whose slot sizeBytes matched the served body exactly.
        val entries = listOf(
            TilePlan.Entry.of(TileRef(13, 4144, 3059), present(966_878L), true),
            TilePlan.Entry.of(TileRef(12, 2072, 1529), present(1_008_547L), true),
            TilePlan.Entry.of(TileRef(11, 1036, 764), present(809_034L), true),
        )
        val s = TilePlan.summarize(entries)
        assertEquals(3, s.present)
        assertEquals(2_784_459L, s.bytes)
    }

    @Test
    fun `a tile that is not there costs nothing`() {
        val entries = listOf(
            TilePlan.Entry.of(TileRef(13, 4144, 3059), present(966_878L), true),
            TilePlan.Entry.of(TileRef(13, 4146, 3061), emptySlot, true),
            TilePlan.Entry.of(TileRef(13, 4148, 3061), emptySlot, false),
            TilePlan.Entry.of(TileRef(13, 4148, 3062), TilePlan.Reading.Unreachable, false),
        )
        val s = TilePlan.summarize(entries)
        assertEquals(966_878L, s.bytes)
        assertEquals(1, s.absent)
        assertEquals(1, s.waitingBuild)
        assertEquals(1, s.unknown)
        // An empty slot carries a stale size in no case: it is zeroed on read.
        assertEquals(0L, entries[1].sizeBytes)
    }

    @Test
    fun `a tile in two zones is counted once`() {
        val one = TilePlan.Entry.of(TileRef(13, 4144, 3059), present(966_878L), true)
        val s = TilePlan.summarize(listOf(one, one.copy()))
        assertEquals(1, s.tiles)
        assertEquals(966_878L, s.bytes)
    }

    @Test
    fun `the eta is the measured rate applied to the exact bytes`() {
        // The measurement itself: 81 774 B in 9.08 s, 2026-08-14.
        assertEquals(9L, TilePlan.etaSeconds(81_774L, TilePlan.START_BYTES_PER_SECOND))
        // 16.4 MB of city at that rate is the half hour the plan doc quotes.
        assertEquals(1822L, TilePlan.etaSeconds(16_400_000L, TilePlan.START_BYTES_PER_SECOND))
        // A faster link, same arithmetic. The caller feeds the last batch's rate.
        assertEquals(1025L, TilePlan.etaSeconds(16_400_000L, 16_000.0))
    }

    @Test
    fun `no rate means no estimate, not a default one`() {
        assertNull(TilePlan.etaSeconds(16_400_000L, 0.0))
        assertNull(TilePlan.etaSeconds(16_400_000L, -1.0))
        assertNull(TilePlan.etaSeconds(16_400_000L, Double.NaN))
        assertEquals(0L, TilePlan.etaSeconds(0L, TilePlan.START_BYTES_PER_SECOND))
    }

    @Test
    fun `a summary estimates from its own bytes`() {
        val entries = listOf(TilePlan.Entry.of(TileRef(13, 4144, 3059), present(90_000L), true))
        assertEquals(10L, TilePlan.summarize(entries).etaSeconds(9000.0))
    }
}
