package io.github.mbaliga.fylz.staging

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.storage.toItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Shelf is the one part of Build 7 whose loss the user would notice immediately -- a
 * persistent tray that reboots the app without reopening is, by definition, broken. These tests
 * pin the codec and the mutation contract Workstream E's `ShelfSheet` is built against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = ShelfStore(context)
    private fun uri(tail: String) = Uri.parse("content://fylz.test/tree/root/document/$tail")

    private fun item(tail: String, addedAt: Long = 1_000L) = ShelfItem(
        ref = uri(tail).toItemRef(),
        displayName = tail,
        kind = EntryKind.TEXT,
        isDirectory = false,
        sizeBytes = 42L,
        modifiedAtMillis = 500L,
        addedAtMillis = addedAt,
        sourceCrumb = "Downloads",
    )

    @Test
    fun `items survive a fresh store instance over the same preferences`() {
        val first = item("a.txt")
        store().add(listOf(first))

        assertEquals(listOf(first), store().items())
    }

    @Test
    fun `adding is idempotent per ref and reports only the genuinely new count`() {
        val store = store()
        val a = item("a.txt")
        val b = item("b.txt")
        store.add(listOf(a))

        val added = store.add(listOf(a, b))

        assertEquals(1, added)
        assertEquals(listOf(a, b), store.items())
    }

    @Test
    fun `adding an already-shelved ref again is a no-op`() {
        val store = store()
        val a = item("a.txt")
        store.add(listOf(a))

        val added = store.add(listOf(a))

        assertEquals(0, added)
        assertEquals(1, store.size())
    }

    @Test
    fun `remove drops one member and leaves the rest in order`() {
        val store = store()
        val a = item("a.txt")
        val b = item("b.txt")
        store.add(listOf(a, b))

        store.remove(a.ref)

        assertEquals(listOf(b), store.items())
    }

    @Test
    fun `removeAll drops every requested member and leaves the rest in order`() {
        val store = store()
        val a = item("a.txt")
        val b = item("b.txt")
        val c = item("c.txt")
        store.add(listOf(a, b, c))

        store.removeAll(listOf(a.ref, c.ref))

        assertEquals(listOf(b), store.items())
    }

    @Test
    fun `removeAll with an empty ref set is a no-op`() {
        val store = store()
        val a = item("a.txt")
        store.add(listOf(a))

        store.removeAll(emptyList())

        assertEquals(listOf(a), store.items())
    }

    @Test
    fun `clear empties the shelf`() {
        val store = store()
        store.add(listOf(item("a.txt"), item("b.txt")))

        store.clear()

        assertTrue(store.items().isEmpty())
        assertEquals(0, store.size())
    }

    @Test
    fun `adding past the cap drops the oldest members first`() {
        val store = store()
        // MAX_ITEMS mirrors OperationJournal's own cap idiom: 500, oldest dropped.
        val items = (1..505).map { item("item-$it.txt", addedAt = it.toLong()) }

        store.add(items)

        val survivors = store.items()
        assertEquals(500, survivors.size)
        assertEquals(items.takeLast(500), survivors)
        assertFalse(items.first() in survivors)
    }

    @Test
    fun `a corrupted current manifest falls back to the last known good backup`() {
        val store = store()
        val a = item("a.txt")
        val b = item("b.txt")
        store.add(listOf(a))
        // This second write is what promotes the first write's payload into the backup slot.
        store.add(listOf(b))

        context.getSharedPreferences("fylz_shelf", Context.MODE_PRIVATE)
            .edit()
            .putString("shelf_items", "{not json[")
            .commit()

        assertEquals(listOf(a), store.items())
    }

    @Test
    fun `an unrecognized extra field in a stored item does not break decoding`() {
        val raw = """
            [{"schemaVersion":1,"ref":{"providerId":"fylz.test","locationId":"root","opaqueItemId":"root/a.txt"},
            "displayName":"a.txt","kind":"TEXT","isDirectory":false,"sizeBytes":10,"modifiedAtMillis":5,
            "addedAtMillis":1,"sourceCrumb":"Downloads","futureField":"wat"}]
        """.trimIndent()
        context.getSharedPreferences("fylz_shelf", Context.MODE_PRIVATE)
            .edit()
            .putString("shelf_items", raw)
            .commit()

        val items = store().items()

        assertEquals(1, items.size)
        assertEquals("a.txt", items.single().displayName)
    }

    @Test
    fun `migrateRef rewrites the matching member and leaves others untouched`() {
        val store = store()
        val moved = item("before.txt")
        val other = item("other.txt")
        store.add(listOf(moved, other))
        val newUri = uri("after.txt")

        val changed = store.migrateRef(uri("before.txt"), newUri)

        assertTrue(changed)
        val refs = store.items().map { it.ref }
        assertTrue(newUri.toItemRef() in refs)
        assertFalse(moved.ref in refs)
        assertTrue(other.ref in refs)
        // Only the ref moves; the add-time metadata is untouched.
        assertEquals(moved.copy(ref = newUri.toItemRef()), store.items().first { it.ref == newUri.toItemRef() })
    }

    @Test
    fun `migrateRef on an untracked uri is a no-op`() {
        val store = store()
        store.add(listOf(item("a.txt")))

        assertFalse(store.migrateRef(uri("untracked.txt"), uri("also-untracked.txt")))
        assertEquals(1, store.size())
    }

    @Test
    fun `migrateRef onto the same uri is a no-op`() {
        val store = store()
        val a = item("a.txt")
        store.add(listOf(a))

        assertFalse(store.migrateRef(uri("a.txt"), uri("a.txt")))
        assertEquals(listOf(a), store.items())
    }

    @Test
    fun `replaceAll swaps the whole manifest`() {
        val store = store()
        store.add(listOf(item("a.txt")))
        val refreshed = listOf(item("b.txt"), item("c.txt"))

        store.replaceAll(refreshed)

        assertEquals(refreshed, store.items())
    }

    @Test
    fun `refreshMetadata updates only the members the update map has, by ref`() {
        val store = store()
        val a = item("a.txt")
        val b = item("b.txt")
        store.add(listOf(a, b))
        val refreshedA = a.copy(sizeBytes = 999L, modifiedAtMillis = 1_234L)

        store.refreshMetadata(mapOf(a.ref to refreshedA))

        assertEquals(listOf(refreshedA, b), store.items())
    }

    @Test
    fun `refreshMetadata never reintroduces a ref a concurrent removal already dropped`() {
        // Pins the probe-vs-concurrent-mutation fix: refreshMetadata reads the store fresh under
        // its own lock, so a remove that lands between when a caller built its update map and
        // when it calls refreshMetadata is never undone.
        val store = store()
        val a = item("a.txt")
        val b = item("b.txt")
        store.add(listOf(a, b))
        val staleUpdates = mapOf(a.ref to a.copy(sizeBytes = 111L), b.ref to b.copy(sizeBytes = 222L))

        store.remove(a.ref)
        store.refreshMetadata(staleUpdates)

        assertEquals(listOf(b.copy(sizeBytes = 222L)), store.items())
    }

    @Test
    fun `refreshMetadata with an empty update map is a no-op`() {
        val store = store()
        val a = item("a.txt")
        store.add(listOf(a))

        store.refreshMetadata(emptyMap())

        assertEquals(listOf(a), store.items())
    }

    @Test
    fun `an empty preferences file decodes to an empty shelf, not a crash`() {
        assertTrue(store().items().isEmpty())
        assertNull(context.getSharedPreferences("fylz_shelf", Context.MODE_PRIVATE).getString("shelf_backup", null))
    }
}
