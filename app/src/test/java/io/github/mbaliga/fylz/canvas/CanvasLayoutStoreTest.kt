package io.github.mbaliga.fylz.canvas

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [CanvasLayoutStore] is [io.github.mbaliga.fylz.staging.ShelfStore]-modelled; these tests pin
 * the same contract ShelfStoreTest pins for the Shelf -- round-trip, idempotent upsert, the
 * corrupted-current-falls-back-to-backup recovery -- plus the two bounds only this store needs:
 * per-location tile eviction and cross-location eviction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasLayoutStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = CanvasLayoutStore(context)
    private fun uri(tail: String) = Uri.parse("content://fylz.test/tree/root/document/$tail")
    private fun location(tail: String) = Uri.parse("content://fylz.test/tree/root/document/loc-$tail")

    @Test
    fun `placements survive a fresh store instance over the same preferences`() {
        val loc = location("home")
        store().place(loc, uri("a.txt"), TilePlacement(0.4f, 0.5f, 2))

        assertEquals(mapOf(uri("a.txt") to TilePlacement(0.4f, 0.5f, 2)), store().placements(loc))
    }

    @Test
    fun `a location with no placements yet reports an empty map, not a crash`() {
        assertTrue(store().placements(location("untouched")).isEmpty())
    }

    @Test
    fun `placing the same uri again updates it in place rather than duplicating`() {
        val store = store()
        val loc = location("home")
        store.place(loc, uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))

        store.place(loc, uri("a.txt"), TilePlacement(0.5f, 0.5f, 3))

        val placements = store.placements(loc)
        assertEquals(1, placements.size)
        assertEquals(TilePlacement(0.5f, 0.5f, 3), placements[uri("a.txt")])
    }

    @Test
    fun `two locations keep independent placements`() {
        val store = store()
        val home = location("home")
        val other = location("other")
        store.place(home, uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))
        store.place(other, uri("b.txt"), TilePlacement(0.9f, 0.9f, 0))

        assertEquals(setOf(uri("a.txt")), store.placements(home).keys)
        assertEquals(setOf(uri("b.txt")), store.placements(other).keys)
    }

    @Test
    fun `place clamps x and y into 0 to 1 regardless of what is handed in`() {
        val store = store()
        val loc = location("home")

        store.place(loc, uri("a.txt"), TilePlacement(-3f, 8f, 0))

        val placed = store.placements(loc).getValue(uri("a.txt"))
        assertEquals(0f, placed.x, 0.0001f)
        assertEquals(1f, placed.y, 0.0001f)
    }

    @Test
    fun `placing past the per-location cap evicts the earliest-placed tile first`() {
        val store = store()
        val loc = location("home")
        // MAX_TILES mirrors ShelfStore's own cap idiom: 48, oldest-written dropped first.
        (1..49).forEach { store.place(loc, uri("tile$it.txt"), TilePlacement(0.1f, 0.1f, it)) }

        val placements = store.placements(loc)
        assertEquals(48, placements.size)
        assertFalse(uri("tile1.txt") in placements)
        assertTrue(uri("tile49.txt") in placements)
    }

    @Test
    fun `placing a 65th distinct location evicts the least-recently-written location entirely`() {
        val store = store()
        // MAX_LOCATIONS is 64; the 65th place() call is what tips eviction.
        (1..65).forEach { n -> store.place(location("l$n"), uri("tile.txt"), TilePlacement(0.1f, 0.1f, 0)) }

        assertTrue(store.placements(location("l1")).isEmpty())
        assertTrue(store.placements(location("l65")).isNotEmpty())
    }

    @Test
    fun `writing back to an existing location keeps it out of eviction`() {
        val store = store()
        (1..64).forEach { n -> store.place(location("l$n"), uri("tile.txt"), TilePlacement(0.1f, 0.1f, 0)) }
        // Re-touch l1 so it is no longer the least-recently-written when l65 arrives.
        store.place(location("l1"), uri("second.txt"), TilePlacement(0.2f, 0.2f, 1))

        store.place(location("l65"), uri("tile.txt"), TilePlacement(0.1f, 0.1f, 0))

        assertTrue(store.placements(location("l1")).isNotEmpty())
        assertTrue(store.placements(location("l2")).isEmpty())
    }

    @Test
    fun `place silently drops a uri past the length cap instead of persisting it`() {
        val store = store()
        val loc = location("home")

        store.place(loc, uri("x".repeat(9_000)), TilePlacement(0.1f, 0.1f, 0))

        assertTrue(store.placements(loc).isEmpty())
    }

    @Test
    fun `prune keeps only placements whose uri is still present`() {
        val store = store()
        val loc = location("home")
        store.place(loc, uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))
        store.place(loc, uri("b.txt"), TilePlacement(0.2f, 0.2f, 1))

        store.prune(loc, setOf(uri("a.txt")))

        assertEquals(setOf(uri("a.txt")), store.placements(loc).keys)
    }

    @Test
    fun `prune with everything still present changes nothing`() {
        val store = store()
        val loc = location("home")
        store.place(loc, uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))

        store.prune(loc, setOf(uri("a.txt")))

        assertEquals(1, store.placements(loc).size)
    }

    @Test
    fun `prune on a location with no placements is a no-op`() {
        val store = store()
        store.prune(location("untouched"), setOf(uri("a.txt")))
        assertTrue(store.placements(location("untouched")).isEmpty())
    }

    @Test
    fun `migrateUri rewrites a tile's own uri wherever it is placed`() {
        val store = store()
        val loc = location("home")
        store.place(loc, uri("before.txt"), TilePlacement(0.2f, 0.3f, 1))

        val changed = store.migrateUri(uri("before.txt"), uri("after.txt"))

        assertTrue(changed)
        val placements = store.placements(loc)
        assertFalse(uri("before.txt") in placements)
        assertEquals(TilePlacement(0.2f, 0.3f, 1), placements[uri("after.txt")])
    }

    @Test
    fun `migrateUri renames the location key itself when the location moved`() {
        val store = store()
        val oldLoc = location("old")
        val newLoc = location("new")
        store.place(oldLoc, uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))

        val changed = store.migrateUri(oldLoc, newLoc)

        assertTrue(changed)
        assertTrue(store.placements(oldLoc).isEmpty())
        assertEquals(TilePlacement(0.1f, 0.1f, 0), store.placements(newLoc)[uri("a.txt")])
    }

    @Test
    fun `migrateUri on an untracked uri is a no-op`() {
        val store = store()
        store.place(location("home"), uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))

        assertFalse(store.migrateUri(uri("untracked.txt"), uri("also-untracked.txt")))
    }

    @Test
    fun `migrateUri onto the same uri is a no-op`() {
        val store = store()
        assertFalse(store.migrateUri(uri("a.txt"), uri("a.txt")))
    }

    @Test
    fun `clear forgets a location's layout entirely`() {
        val store = store()
        val loc = location("home")
        store.place(loc, uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))

        store.clear(loc)

        assertTrue(store.placements(loc).isEmpty())
    }

    @Test
    fun `clear on an already-empty location is a no-op`() {
        val store = store()
        store.clear(location("untouched"))
        assertTrue(store.placements(location("untouched")).isEmpty())
    }

    @Test
    fun `a corrupted current layout falls back to the last known good backup`() {
        val store = store()
        val loc = location("home")
        store.place(loc, uri("a.txt"), TilePlacement(0.1f, 0.1f, 0))
        // This second write is what promotes the first write's payload into the backup slot.
        store.place(loc, uri("b.txt"), TilePlacement(0.2f, 0.2f, 1))

        context.getSharedPreferences("fylz_canvas", Context.MODE_PRIVATE)
            .edit()
            .putString("layout:$loc", "{not json[")
            .commit()

        assertEquals(setOf(uri("a.txt")), store.placements(loc).keys)
    }

    @Test
    fun `an unrecognized extra field in a stored tile does not break decoding`() {
        val loc = location("home")
        val raw = """
            [{"schemaVersion":1,"uri":"${uri("a.txt")}","x":0.25,"y":0.5,"z":2,"futureField":"wat"}]
        """.trimIndent()
        context.getSharedPreferences("fylz_canvas", Context.MODE_PRIVATE)
            .edit()
            .putString("layout:$loc", raw)
            .commit()

        val placements = store().placements(loc)

        assertEquals(TilePlacement(0.25f, 0.5f, 2), placements[uri("a.txt")])
    }
}
