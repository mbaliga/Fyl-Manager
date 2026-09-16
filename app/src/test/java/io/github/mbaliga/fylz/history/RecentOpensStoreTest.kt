package io.github.mbaliga.fylz.history

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
 * [RecentOpensStore] is [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled; these tests
 * pin the same contract that store's own test pins -- round trip, the corrupted-current-falls-
 * back-to-backup recovery, a malformed record not sinking the whole list -- plus the two things
 * unique to this store: newest-first ordering with a hard [RecentOpensStore] cap, and
 * [RecentOpensStore.record]'s dedupe-and-move-to-front behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecentOpensStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = RecentOpensStore(context)
    private fun uri(tail: String) = Uri.parse("content://fylz.test/tree/root/document/$tail")

    // ── record / items ────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh store reports an empty list, not a crash`() {
        assertTrue(store().items().isEmpty())
    }

    @Test
    fun `record survives a fresh store instance over the same preferences`() {
        store().record(uri("a"), "a.txt", "TEXT", 1_000L)

        val items = store().items()

        assertEquals(1, items.size)
        assertEquals(RecentOpen(uri("a"), "a.txt", "TEXT", 1_000L), items.single())
    }

    @Test
    fun `newer records land at the front`() {
        val store = store()
        store.record(uri("a"), "a.txt", "TEXT", 1_000L)
        store.record(uri("b"), "b.txt", "TEXT", 2_000L)
        store.record(uri("c"), "c.txt", "TEXT", 3_000L)

        assertEquals(listOf(uri("c"), uri("b"), uri("a")), store.items().map { it.uri })
    }

    // ── dedupe ────────────────────────────────────────────────────────────────────────

    @Test
    fun `re-recording an existing uri moves it to the front and updates its timestamp`() {
        val store = store()
        store.record(uri("a"), "a.txt", "TEXT", 1_000L)
        store.record(uri("b"), "b.txt", "TEXT", 2_000L)

        store.record(uri("a"), "a-renamed.txt", "TEXT", 3_000L)

        val items = store.items()
        assertEquals(2, items.size)
        assertEquals(uri("a"), items.first().uri)
        assertEquals("a-renamed.txt", items.first().displayName)
        assertEquals(3_000L, items.first().openedAtMillis)
    }

    // ── cap ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `recording past the cap drops the oldest entry`() {
        val store = store()
        (1..31).forEach { n -> store.record(uri("$n"), "$n.txt", "TEXT", n.toLong()) }

        val items = store.items()

        assertEquals(30, items.size)
        assertFalse(uri("1") in items.map { it.uri })
        assertTrue(uri("31") in items.map { it.uri })
        // Newest first, still.
        assertEquals(uri("31"), items.first().uri)
    }

    // ── migrateUri ────────────────────────────────────────────────────────────────────

    @Test
    fun `migrateUri rewrites a matching entry's uri`() {
        val store = store()
        store.record(uri("before"), "f.txt", "TEXT", 1_000L)

        val changed = store.migrateUri(uri("before"), uri("after"))

        assertTrue(changed)
        assertEquals(uri("after"), store.items().single().uri)
    }

    @Test
    fun `migrateUri on an untracked uri is a no-op`() {
        val store = store()
        store.record(uri("a"), "a.txt", "TEXT", 1_000L)

        assertFalse(store.migrateUri(uri("untracked"), uri("also-untracked")))
        assertEquals(uri("a"), store.items().single().uri)
    }

    @Test
    fun `migrateUri onto the same uri is a no-op`() {
        assertFalse(store().migrateUri(uri("a"), uri("a")))
    }

    // ── clear ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties the list`() {
        val store = store()
        store.record(uri("a"), "a.txt", "TEXT", 1_000L)

        store.clear()

        assertTrue(store.items().isEmpty())
    }

    // ── corruption recovery ───────────────────────────────────────────────────────────

    @Test
    fun `a corrupted current list falls back to the last known good backup`() {
        val store = store()
        store.record(uri("a"), "a.txt", "TEXT", 1_000L)
        // This second write is what promotes the first write's payload into the backup slot.
        store.record(uri("b"), "b.txt", "TEXT", 2_000L)

        context.getSharedPreferences("fylz_recent_opens", Context.MODE_PRIVATE)
            .edit()
            .putString("items", "{not json[")
            .commit()

        val items = store().items()
        assertEquals(listOf(uri("a")), items.map { it.uri })
    }

    @Test
    fun `a malformed record is skipped without sinking the rest of the list`() {
        val raw = """
            [
              {"schemaVersion":1,"uri":"${uri("good")}","displayName":"good.txt",
               "kindName":"TEXT","openedAtMillis":1000},
              {"schemaVersion":1,"uri":"${uri("bad")}"},
              {"totally":"unrelated shape"}
            ]
        """.trimIndent()
        context.getSharedPreferences("fylz_recent_opens", Context.MODE_PRIVATE)
            .edit()
            .putString("items", raw)
            .commit()

        val items = store().items()

        assertEquals(1, items.size)
        assertEquals(uri("good"), items.single().uri)
    }

    @Test
    fun `an empty preferences value decodes as an empty list, not a crash`() {
        context.getSharedPreferences("fylz_recent_opens", Context.MODE_PRIVATE)
            .edit()
            .putString("items", "")
            .commit()

        assertTrue(store().items().isEmpty())
    }
}
