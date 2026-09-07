package io.github.mbaliga.fylz.settings

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.ui.landing.HomeMode
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `theme style defaults to NEO`() {
        assertEquals(ThemeStyle.NEO, store().themeStyle())
    }

    @Test
    fun `theme style round-trips through the store`() {
        val store = store()
        store.setThemeStyle(ThemeStyle.CLI)
        assertEquals(ThemeStyle.CLI, store().themeStyle())
    }

    @Test
    fun `theme style falls back to NEO on a corrupted value`() {
        val store = store()
        context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
            .edit().putString("theme_style", "NOT_A_THEME_STYLE").commit()

        assertEquals(ThemeStyle.NEO, store.themeStyle())
    }

    @Test
    fun `a legacy stored GLASS value migrates to FYLZ rather than falling back to NEO`() {
        val store = store()
        context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
            .edit().putString("theme_style", "GLASS").commit()

        assertEquals(ThemeStyle.FYLZ, store.themeStyle())
    }

    @Test
    fun `reading a legacy GLASS value rewrites the preference in place`() {
        val store = store()
        val preferences = context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
        preferences.edit().putString("theme_style", "GLASS").commit()

        store.themeStyle()

        assertEquals("FYLZ", preferences.getString("theme_style", null))
    }

    @Test
    fun `a fresh store instance reads the rewritten FYLZ value directly, no second migration needed`() {
        val store = store()
        context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
            .edit().putString("theme_style", "GLASS").commit()
        store.themeStyle()

        assertEquals(ThemeStyle.FYLZ, store().themeStyle())
    }

    @Test
    fun `density defaults to COMFORTABLE`() {
        assertEquals(DensityMode.COMFORTABLE, store().density())
    }

    @Test
    fun `density round-trips through the store`() {
        val store = store()
        store.setDensity(DensityMode.COMPACT)
        assertEquals(DensityMode.COMPACT, store().density())
    }

    @Test
    fun `density falls back to COMFORTABLE on a corrupted value`() {
        val store = store()
        context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
            .edit().putString("density", "NOT_A_DENSITY").commit()

        assertEquals(DensityMode.COMFORTABLE, store.density())
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

    @Test
    fun `landing splash defaults to true`() {
        assertTrue(store().landingSplash())
    }

    @Test
    fun `landing splash round-trips through the store`() {
        val store = store()
        store.setLandingSplash(false)
        assertFalse(store().landingSplash())
    }

    @Test
    fun `home mode defaults to DESKTOP`() {
        assertEquals(HomeMode.DESKTOP, store().homeMode())
    }

    @Test
    fun `home mode round-trips through the store`() {
        val store = store()
        store.setHomeMode(HomeMode.CANVAS)
        assertEquals(HomeMode.CANVAS, store().homeMode())
    }

    @Test
    fun `home mode falls back to DESKTOP on a corrupted value`() {
        val store = store()
        context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
            .edit().putString("landing_view", "NOT_A_HOME_MODE").commit()

        assertEquals(HomeMode.DESKTOP, store.homeMode())
    }

    @Test
    fun `a stored legacy OVERVIEW home mode resolves to DESKTOP and is rewritten in place`() {
        val store = store()
        context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE)
            .edit().putString("landing_view", "OVERVIEW").commit()

        assertEquals(HomeMode.DESKTOP, store.homeMode())
        // The rewrite happened: a fresh store instance reads DESKTOP as a plain valueOf, not a
        // second migration.
        assertEquals(
            "DESKTOP",
            context.getSharedPreferences("fylz_app_settings", Context.MODE_PRIVATE).getString("landing_view", null),
        )
        assertEquals(HomeMode.DESKTOP, store().homeMode())
    }

    @Test
    fun `desktop snap defaults to true`() {
        assertTrue(store().desktopSnap())
    }

    @Test
    fun `desktop snap round-trips through the store`() {
        val store = store()
        store.setDesktopSnap(false)
        assertFalse(store().desktopSnap())
    }

    @Test
    fun `desktop labels defaults to true`() {
        assertTrue(store().desktopLabels())
    }

    @Test
    fun `desktop labels round-trips through the store`() {
        val store = store()
        store.setDesktopLabels(false)
        assertFalse(store().desktopLabels())
    }

    @Test
    fun `landing subject is null until one is picked`() {
        assertNull(store().landingSubject())
    }

    @Test
    fun `landing subject round-trips through the store`() {
        val store = store()
        store.setLandingSubject("content://tree/primary", "content://tree/primary/document/primary%3APhotos")

        assertEquals(
            "content://tree/primary" to "content://tree/primary/document/primary%3APhotos",
            store().landingSubject(),
        )
    }

    @Test
    fun `setting landing subject to null clears it`() {
        val store = store()
        store.setLandingSubject("content://tree/primary", "content://tree/primary/document/primary%3APhotos")

        store.setLandingSubject(null, null)

        assertNull(store().landingSubject())
    }

    @Test
    fun `migrating the landing subject rewrites the folder on a hit`() {
        val store = store()
        val old = Uri.parse("content://tree/primary/document/primary%3APhotos")
        val new = Uri.parse("content://tree/primary/document/primary%3ACamera")
        store.setLandingSubject("content://tree/primary", old.toString())

        val migrated = store.migrateLandingSubject(old, new)

        assertTrue(migrated)
        assertEquals("content://tree/primary" to new.toString(), store().landingSubject())
    }

    @Test
    fun `migrating the landing subject is a no-op on a miss`() {
        val store = store()
        val current = Uri.parse("content://tree/primary/document/primary%3APhotos")
        val unrelated = Uri.parse("content://tree/primary/document/primary%3AOther")
        val new = Uri.parse("content://tree/primary/document/primary%3ACamera")
        store.setLandingSubject("content://tree/primary", current.toString())

        val migrated = store.migrateLandingSubject(unrelated, new)

        assertFalse(migrated)
        assertEquals("content://tree/primary" to current.toString(), store().landingSubject())
    }
}
