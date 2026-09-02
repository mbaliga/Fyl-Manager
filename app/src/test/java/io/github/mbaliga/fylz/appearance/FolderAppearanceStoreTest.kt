package io.github.mbaliga.fylz.appearance

import android.content.Context
import android.net.Uri
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
 * [FolderAppearanceStore] is [io.github.mbaliga.fylz.canvas.CanvasLayoutStore]-modelled; these
 * tests pin the same contract that store's own tests pin -- round-trip, idempotent upsert, the
 * corrupted-current-falls-back-to-backup recovery, the uri-length guard, migrateUri -- plus the
 * per-folder MRU eviction only this store needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderAppearanceStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = FolderAppearanceStore(context)
    private fun folder(tail: String) = Uri.parse("content://fylz.test/tree/root/document/$tail")

    @Test
    fun `an untouched folder reports no appearance`() {
        assertNull(store().get(folder("untouched")))
    }

    @Test
    fun `an appearance survives a fresh store instance over the same preferences`() {
        val appearance = FolderAppearance(iconKey = "zip", colorSlug = "teal", stickers = listOf("star"))
        store().set(folder("photos"), appearance)

        assertEquals(appearance, store().get(folder("photos")))
    }

    /**
     * The finish axis arrived after the store did, on a schema bump. Both directions matter: a
     * record written with a finish has to come back with it, and a record written before finishes
     * existed -- no `finishSlug` key at all -- has to come back as "no finish" rather than as a
     * decode failure that loses the folder's colour and stickers with it.
     */
    @Test
    fun `a chosen finish survives the round trip`() {
        val appearance = FolderAppearance(colorSlug = "amber", finishSlug = FolderFinish.LEATHER.slug)
        store().set(folder("ledgers"), appearance)

        assertEquals(appearance, store().get(folder("ledgers")))
    }

    @Test
    fun `a record predating finishes still decodes, with no finish`() {
        val legacy = FolderAppearance(iconKey = "pdf", colorSlug = "teal", stickers = listOf("pin"))
        store().set(folder("legacy"), legacy)

        val read = store().get(folder("legacy"))
        assertEquals(legacy, read)
        assertNull(read?.finishSlug)
    }

    @Test
    fun `setting the same folder again replaces rather than merges`() {
        val store = store()
        val target = folder("photos")
        store.set(target, FolderAppearance(iconKey = "zip", colorSlug = "teal"))

        store.set(target, FolderAppearance(colorSlug = "rose"))

        assertEquals(FolderAppearance(colorSlug = "rose"), store.get(target))
    }

    @Test
    fun `two folders keep independent appearances`() {
        val store = store()
        store.set(folder("a"), FolderAppearance(colorSlug = "blue"))
        store.set(folder("b"), FolderAppearance(colorSlug = "red"))

        assertEquals("blue", store.get(folder("a"))?.colorSlug)
        assertEquals("red", store.get(folder("b"))?.colorSlug)
    }

    @Test
    fun `stickers past the cap are trimmed on write`() {
        val store = store()
        val target = folder("a")
        val tooMany = (1..MAX_FOLDER_STICKERS + 3).map { "star" }

        store.set(target, FolderAppearance(stickers = tooMany))

        assertEquals(MAX_FOLDER_STICKERS, store.get(target)?.stickers?.size)
    }

    @Test
    fun `set silently drops a folder past the uri length cap`() {
        val store = store()
        val target = Uri.parse("content://fylz.test/tree/root/document/" + "x".repeat(9_000))

        store.set(target, FolderAppearance(colorSlug = "blue"))

        assertNull(store.get(target))
    }

    @Test
    fun `clear forgets a folder's appearance entirely`() {
        val store = store()
        val target = folder("a")
        store.set(target, FolderAppearance(colorSlug = "blue"))

        store.clear(target)

        assertNull(store.get(target))
    }

    @Test
    fun `clear on an already-bare folder is a no-op`() {
        val store = store()
        store.clear(folder("untouched"))
        assertNull(store.get(folder("untouched")))
    }

    @Test
    fun `migrateUri carries a folder's appearance to its new uri`() {
        val store = store()
        store.set(folder("before"), FolderAppearance(iconKey = "zip", colorSlug = "amber"))

        val changed = store.migrateUri(folder("before"), folder("after"))

        assertTrue(changed)
        assertNull(store.get(folder("before")))
        assertEquals(FolderAppearance(iconKey = "zip", colorSlug = "amber"), store.get(folder("after")))
    }

    @Test
    fun `migrateUri on a folder with no appearance is a no-op`() {
        val store = store()
        assertFalse(store.migrateUri(folder("untouched"), folder("also-untouched")))
    }

    @Test
    fun `migrateUri onto the same uri is a no-op`() {
        val store = store()
        assertFalse(store.migrateUri(folder("a"), folder("a")))
    }

    @Test
    fun `customising a 257th distinct folder evicts the least-recently-written one entirely`() {
        val store = store()
        // MAX_FOLDERS is 256; the 257th set() call is what tips eviction.
        (1..257).forEach { n -> store.set(folder("f$n"), FolderAppearance(colorSlug = "blue")) }

        assertNull(store.get(folder("f1")))
        assertEquals("blue", store.get(folder("f257"))?.colorSlug)
    }

    @Test
    fun `writing back to an existing folder keeps it out of eviction`() {
        val store = store()
        (1..256).forEach { n -> store.set(folder("f$n"), FolderAppearance(colorSlug = "blue")) }
        // Re-touch f1 so it is no longer the least-recently-written when f257 arrives.
        store.set(folder("f1"), FolderAppearance(colorSlug = "rose"))

        store.set(folder("f257"), FolderAppearance(colorSlug = "blue"))

        assertEquals("rose", store.get(folder("f1"))?.colorSlug)
        assertNull(store.get(folder("f2")))
    }

    @Test
    fun `a corrupted current record falls back to the last known good backup`() {
        val store = store()
        val target = folder("a")
        store.set(target, FolderAppearance(colorSlug = "blue"))
        // This second write is what promotes the first write's payload into the backup slot.
        store.set(target, FolderAppearance(colorSlug = "rose"))

        context.getSharedPreferences("fylz_folder_appearance", Context.MODE_PRIVATE)
            .edit()
            .putString("appearance:$target", "{not json[")
            .commit()

        assertEquals("blue", store.get(target)?.colorSlug)
    }

    @Test
    fun `an unrecognized extra field in a stored record does not break decoding`() {
        val target = folder("a")
        val raw = """{"schemaVersion":1,"iconKey":"zip","futureField":"wat"}"""
        context.getSharedPreferences("fylz_folder_appearance", Context.MODE_PRIVATE)
            .edit()
            .putString("appearance:$target", raw)
            .commit()

        assertEquals(FolderAppearance(iconKey = "zip"), store().get(target))
    }
}
