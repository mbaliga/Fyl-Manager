package io.github.mbaliga.fylz.settings

import android.content.Context
import io.github.mbaliga.fylz.model.ThemeMode

/**
 * App-wide display preferences: theme mode and whether dotfiles show up in listings.
 *
 * Kept separate from [io.github.mbaliga.fylz.library.LibraryStore] on purpose — these values are
 * read once per composition root ([io.github.mbaliga.fylz.ui.FylzV1App]) rather than per file or
 * per folder, and folding them into the per-item metadata store would tie an unrelated schema
 * version to settings that have none.
 */
class AppPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun themeMode(): ThemeMode {
        val raw = preferences.getString(THEME_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    @Synchronized
    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(THEME_MODE, mode.name).commit()
    }

    /** Hidden = `name.startsWith(".")`. Off by default so `.thumbnails`-style clutter stays out. */
    @Synchronized
    fun showHidden(): Boolean = preferences.getBoolean(SHOW_HIDDEN, false)

    @Synchronized
    fun setShowHidden(value: Boolean) {
        preferences.edit().putBoolean(SHOW_HIDDEN, value).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_app_settings"
        const val THEME_MODE = "theme_mode"
        const val SHOW_HIDDEN = "show_hidden"
    }
}
