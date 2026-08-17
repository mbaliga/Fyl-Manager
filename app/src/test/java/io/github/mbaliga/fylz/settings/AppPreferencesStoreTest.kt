package io.github.mbaliga.fylz.settings

import android.content.Context
import io.github.mbaliga.fylz.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPreferencesStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private fun store() = AppPreferencesStore(context)

    @Test
    fun `view mode defaults to LIST`() {
        assertEquals(ViewMode.LIST, store().viewMode())
    }

    @Test
    fun `view mode round-trips through the store`() {
        val store = store()
        store.setViewMode(ViewMode.DETAILS)
        assertEquals(ViewMode.DETAILS, store().viewMode())
    }

    @Test
    fun `view mode falls back to LIST on a corrupted value`() {
        val store = store()
        context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
            .edit().putString("view_mode", "NOT_A_VIEW_MODE").commit()

        assertEquals(ViewMode.LIST, store.viewMode())
    }

    @Test
    fun `show extensions defaults to true`() {
        assertTrue(store().showExtensions())
    }

    @Test
    fun `show extensions round-trips through the store`() {
        val store = store()
        store.setShowExtensions(false)
        assertFalse(store().showExtensions())
    }

    @Test
    fun `auto animate defaults to true`() {
        assertTrue(store().autoAnimate())
    }

    @Test
    fun `auto animate round-trips through the store`() {
        val store = store()
        store.setAutoAnimate(false)
        assertFalse(store().autoAnimate())
    }

    @Test
    fun `recent searches is empty until something is searched`() {
        assertEquals(emptyList<String>(), store().recentSearches())
    }

    @Test
    fun `recent searches orders most recent first`() {
        val store = store()
        store.addRecentSearch("photos")
        store.addRecentSearch("large videos")

        assertEquals(listOf("large videos", "photos"), store.recentSearches())
    }

    @Test
    fun `adding an existing search case-insensitively moves it to the front instead of duplicating`() {
        val store = store()
        store.addRecentSearch("Photos")
        store.addRecentSearch("large videos")
        store.addRecentSearch("photos")

        assertEquals(listOf("photos", "large videos"), store.recentSearches())
    }

    @Test
    fun `blank searches are not recorded`() {
        val store = store()
        store.addRecentSearch("   ")

        assertEquals(emptyList<String>(), store.recentSearches())
    }

    @Test
    fun `recent searches are trimmed before being stored`() {
        val store = store()
        store.addRecentSearch("  photos  ")

        assertEquals(listOf("photos"), store.recentSearches())
    }

    @Test
    fun `recent searches are capped at eight, dropping the oldest`() {
        val store = store()
        (1..9).forEach { store.addRecentSearch("search $it") }

        val recents = store.recentSearches()
        assertEquals(8, recents.size)
        assertEquals("search 9", recents.first())
        assertTrue("search 1" !in recents)
    }

    @Test
    fun `clearing recent searches empties the list`() {
        val store = store()
        store.addRecentSearch("photos")

        store.clearRecentSearches()

        assertEquals(emptyList<String>(), store.recentSearches())
    }
}
