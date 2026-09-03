package org.explorink.gpsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing `mapset.json`, against the shape the live CDN actually serves.
 *
 * The fixture below is trimmed from `https://tiles.explorink.com/v4/mapset.json`
 * as fetched on 2026-09-02 (21 262 bytes, 60 builds, every one of them named
 * `auto-z11-<col>-<row>`), plus one hand-named area that does not exist yet
 * (T-310). The hand-named one is the case worth a test: the z11 name is a fast
 * path, not the rule, and an area published on purpose must not be read as
 * unbuilt ground.
 */
class MapsetTest {

    /**
     * Two real Barcelona areas and one invented hand-published one. The
     * `auto-z11-1036-765` cell is the sea cell southeast of the city -- the one
     * that makes three z13 squares [TilePlan.State.ABSENT] rather than waiting
     * for a build that already happened.
     */
    private val fixture = """
        {
          "builds": [
            {
              "bbox": {"east": 2.109361068099572, "north": 41.50856651618735,
                       "south": 41.37682391159234, "west": 1.9336146659144289},
              "build_epoch": 1788100555,
              "name": "auto-z11-1035-764",
              "osm_epoch": 1788100433,
              "points": 1933,
              "rules_hash": 2633242815,
              "tiles": 21
            },
            {
              "bbox": {"east": 2.28515625, "north": 41.37682391159234,
                       "south": 41.244772343082076, "west": 2.109375},
              "build_epoch": 1788100621,
              "name": "auto-z11-1036-765",
              "osm_epoch": 1788100433,
              "points": 12,
              "rules_hash": 3403328319,
              "tiles": 21
            },
            {
              "bbox": {"east": 17.3, "north": 48.3, "south": 48.0, "west": 17.0},
              "build_epoch": 1788100999,
              "name": "bratislava-handmade",
              "osm_epoch": 1788100433,
              "points": 500,
              "rules_hash": 3403328319,
              "tiles": 40
            }
          ],
          "format_version": 4,
          "index_format_version": 1,
          "lods": {"detail": {"zoom": 13}, "overview": {"zoom": 11}, "regional": {"zoom": 12}}
        }
    """.trimIndent()

    @Test
    fun `every published area is read, whatever it is called`() {
        val areas = Mapset.parse(fixture)
        assertEquals(3, areas.size)
        assertEquals(
            listOf("auto-z11-1035-764", "auto-z11-1036-765", "bratislava-handmade"),
            areas.map { it.name },
        )
    }

    @Test
    fun `a bbox is read as south, west, north, east and not by position`() {
        // The live file writes its keys alphabetically -- east, north, south,
        // west -- which is the opposite order to the constructor's. Reading by
        // name is the whole reason a mis-sorted file cannot silently become a
        // box in the sea.
        val a = Mapset.parse(fixture).first()
        assertEquals(41.37682391159234, a.south, 1e-12)
        assertEquals(1.9336146659144289, a.west, 1e-12)
        assertEquals(41.50856651618735, a.north, 1e-12)
        assertEquals(2.109361068099572, a.east, 1e-12)
    }

    @Test
    fun `the file states its own format version`() {
        assertEquals(4, Mapset.formatVersionOf(fixture))
        assertNull(Mapset.formatVersionOf("""{"builds": []}"""))
    }

    @Test
    fun `a hand-named area is covered by its bbox, not by the z11 name`() {
        val ground = TilePlan.BuiltGround(Mapset.parse(fixture))
        // z13 tile inside the invented Bratislava box.
        val inside = TileBox.centreTile(48.15, 17.15, 13)
        assertTrue(ground.covers(inside))
    }

    @Test
    fun `an auto area covers its own z11 cell and nothing else`() {
        val ground = TilePlan.BuiltGround(Mapset.parse(fixture))
        assertTrue(ground.covers(TileRef(11, 1035, 764)))
        assertTrue(ground.covers(TileRef(13, 1035 * 4, 764 * 4)))
        // The z11 cell next door was never built.
        assertFalse(ground.covers(TileRef(11, 1034, 764)))
    }

    @Test
    fun `one malformed entry is dropped, the rest of the file survives`() {
        // Dropping one entry can only make covered ground look uncovered, which
        // costs retries. Refusing the file would leave the queue with no ground
        // list at all, and then nothing could ever be called sea.
        val broken = """
            {"format_version": 4, "builds": [
              {"name": "auto-z11-1035-764",
               "bbox": {"south": 1.0, "west": 2.0, "north": 3.0, "east": 4.0}},
              {"name": "no-bbox-here"},
              {"bbox": {"south": 1.0, "west": 2.0, "north": 3.0, "east": 4.0}}
            ]}
        """.trimIndent()
        assertEquals(listOf("auto-z11-1035-764"), Mapset.parse(broken).map { it.name })
    }

    @Test
    fun `a file with no builds key is refused, not read as nothing published`() {
        // "I could not read the list" and "the list is empty" are opposite
        // verdicts: the second one makes every empty slot in the world ABSENT.
        var threw = false
        try {
            Mapset.parse("""{"format_version": 4}""")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a truncated body is refused`() {
        var threw = false
        try {
            Mapset.parse(fixture.substring(0, fixture.length / 2))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `an empty build list is a real answer and makes nothing absent`() {
        val areas = Mapset.parse("""{"format_version": 4, "builds": []}""")
        assertEquals(0, areas.size)
        val ground = TilePlan.BuiltGround(areas)
        assertFalse(ground.covers(TileRef(13, 4144, 3059)))
        // An empty slot on unbuilt ground is a wait, never a give-up.
        assertEquals(
            TilePlan.State.WAITING_BUILD,
            TilePlan.classify(TilePlan.Reading.Found(TileIndex.Slot.ABSENT), false),
        )
    }
}
