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

    @Test
    fun `allTags counts each tagged item once per tag`() {
        val store = store()
        store.setTags(uri("a.txt"), listOf("work", "receipts"))
        store.setTags(uri("b.txt"), listOf("work"))
        store.setTags(uri("c.txt"), listOf("personal"))

        assertEquals(mapOf("work" to 2, "receipts" to 1, "personal" to 1), store.allTags())
    }

    @Test
    fun `allTags folds different casings of the same tag into one count`() {
        val store = store()
        store.setTags(uri("a.txt"), listOf("Work"))
        store.setTags(uri("b.txt"), listOf("work"))

        val tags = store.allTags()

        assertEquals(1, tags.size)
        assertEquals(2, tags.getValue("Work"))
    }

    @Test
    fun `allTags is empty when nothing is tagged`() {
        assertEquals(emptyMap<String, Int>(), store().allTags())
    }

    @Test
    fun `itemsWithTag finds every item carrying the tag, case-insensitively`() {
        val store = store()
        val a = uri("a.txt")
        val b = uri("b.txt")
        store.setTags(a, listOf("Work"))
        store.setTags(b, listOf("personal"))

        assertEquals(listOf(a), store.itemsWithTag("work"))
        assertEquals(emptyList<Uri>(), store.itemsWithTag("nonexistent"))
    }

    @Test
    fun `pruneOrphanedTags drops only the requested records and reports how many`() {
        val store = store()
        val kept = uri("kept.txt")
        val removedUri = uri("removed.txt")
        store.setTags(kept, listOf("work"))
        store.setTags(removedUri, listOf("work"))

        val pruned = store.pruneOrphanedTags(listOf(removedUri, uri("never-tagged.txt")))

        assertEquals(1, pruned)
        assertEquals(setOf("work"), store.tags(kept))
        assertEquals(emptySet<String>(), store.tags(removedUri))
    }

    @Test
    fun `pruneOrphanedTags on an empty collection is a no-op`() {
        val store = store()
        val tagged = uri("tagged.txt")
        store.setTags(tagged, listOf("work"))

        assertEquals(0, store.pruneOrphanedTags(emptyList()))
        assertEquals(setOf("work"), store.tags(tagged))
    }

    // -- unionOfTags / applyTagDelta: the multi-select tag dialog fix W applies in FylzV1App --

    @Test
    fun `unionOfTags combines every item's tags and folds case-insensitively`() {
        val union = unionOfTags(listOf(setOf("Work", "red"), setOf("work", "blue")))
        assertEquals(setOf("Work", "red", "blue"), union)
        assertEquals(3, union.size)
    }

    @Test
    fun `applyTagDelta with no edit at all is a no-op for every item`() {
        val before = unionOfTags(listOf(setOf("work", "red"), setOf("work", "blue")))
        // The user opened the dialog and hit Save without touching the field.
        val after = before

        assertEquals(setOf("work", "red"), applyTagDelta(setOf("work", "red"), before, after))
        assertEquals(setOf("work", "blue"), applyTagDelta(setOf("work", "blue"), before, after))
    }

    @Test
    fun `applyTagDelta adds a new tag to every item without touching tags unique to another item`() {
        val before = unionOfTags(listOf(setOf("work", "red"), setOf("work", "blue")))
        val after = before + "urgent"

        assertEquals(setOf("work", "red", "urgent"), applyTagDelta(setOf("work", "red"), before, after))
        assertEquals(setOf("work", "blue", "urgent"), applyTagDelta(setOf("work", "blue"), before, after))
    }

    @Test
    fun `applyTagDelta removes a tag from every item that had it, leaving tags unique to others alone`() {
        val before = unionOfTags(listOf(setOf("work", "red"), setOf("work", "blue")))
        val after = before - "work"

        assertEquals(setOf("red"), applyTagDelta(setOf("work", "red"), before, after))
        assertEquals(setOf("blue"), applyTagDelta(setOf("work", "blue"), before, after))
    }

    @Test
    fun `applyTagDelta never reproduces the overwrite bug -- a second item keeps tags the first never had`() {
        // This is the exact shape of the bug: item A has "red", item B has "blue"; the dialog
        // used to seed from A alone and write A's list onto B, destroying "blue".
        val aTags = setOf("work", "red")
        val bTags = setOf("work", "blue")
        val before = unionOfTags(listOf(aTags, bTags))
        val after = before // dialog saved unchanged

        val bAfter = applyTagDelta(bTags, before, after)
        assertTrue("blue" in bAfter)
        assertTrue("work" in bAfter)
    }
}
