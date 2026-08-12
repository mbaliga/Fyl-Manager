package io.github.mbaliga.fylz.library

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
 * WP-1.1: `migrateUri` is what keeps a favorite or a tag pointed at the right item after a
 * rename or move hands back a new URI -- without it, the old identity-keyed metadata is
 * silently orphaned under a URI nothing resolves to any more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = LibraryStore(context)
    private fun uri(tail: String) = Uri.parse("content://fylz.test/tree/root/document/$tail")

    @Test
    fun `migrating a favorite carries its name to the new uri`() {
        val store = store()
        val oldUri = uri("before.txt")
        val newUri = uri("after.txt")
        store.toggleFavorite(oldUri, "Before")

        val changed = store.migrateUri(oldUri, newUri)

        assertTrue(changed)
        assertEquals(listOf(FavoriteLocation(newUri, "Before")), store.favorites())
    }

    @Test
    fun `migrating tags moves them off the old uri onto the new one`() {
        val store = store()
        val oldUri = uri("before.txt")
        val newUri = uri("after.txt")
        store.setTags(oldUri, listOf("work", "receipts"))

        store.migrateUri(oldUri, newUri)

        assertEquals(emptySet<String>(), store.tags(oldUri))
        assertEquals(setOf("receipts", "work"), store.tags(newUri))
    }

    @Test
    fun `migrating tags onto a uri that already has tags merges rather than overwrites`() {
        val store = store()
        val oldUri = uri("before.txt")
        val newUri = uri("after.txt")
        store.setTags(oldUri, listOf("work"))
        store.setTags(newUri, listOf("personal"))

        store.migrateUri(oldUri, newUri)

        assertEquals(setOf("personal", "work"), store.tags(newUri))
    }

    @Test
    fun `migrating an untracked uri is a no-op`() {
        val store = store()
        assertFalse(store.migrateUri(uri("untracked.txt"), uri("also-untracked.txt")))
    }

    @Test
    fun `migrating a uri onto itself is a no-op`() {
        val store = store()
        val tracked = uri("same.txt")
        store.toggleFavorite(tracked, "Same")

        assertFalse(store.migrateUri(tracked, tracked))
        assertEquals(listOf(FavoriteLocation(tracked, "Same")), store.favorites())
    }
}
