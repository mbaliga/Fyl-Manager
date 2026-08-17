package io.github.mbaliga.fylz.settings

import android.content.Context
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.components.QuickAction

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

    /**
     * Which treatment the file-type icons are drawn in. [IconStyle.DEFAULT] because it is the
     * quietest of the four; the pack does not ship Default artwork for every icon, and
     * [io.github.mbaliga.fylz.ui.components.FileTypeIcons] absorbs that.
     */
    @Synchronized
    fun iconStyle(): IconStyle {
        val raw = preferences.getString(ICON_STYLE, null) ?: return IconStyle.DEFAULT
        return runCatching { IconStyle.valueOf(raw) }.getOrDefault(IconStyle.DEFAULT)
    }

    @Synchronized
    fun setIconStyle(style: IconStyle) {
        preferences.edit().putString(ICON_STYLE, style.name).commit()
    }

    /** Which of LIST/GRID/DETAILS the browser last rendered, restored on the next launch. */
    @Synchronized
    fun viewMode(): ViewMode {
        val raw = preferences.getString(VIEW_MODE, null) ?: return ViewMode.LIST
        return runCatching { ViewMode.valueOf(raw) }.getOrDefault(ViewMode.LIST)
    }

    @Synchronized
    fun setViewMode(mode: ViewMode) {
        preferences.edit().putString(VIEW_MODE, mode.name).commit()
    }

    /**
     * Whether display names carry their extension (`report.pdf`) or hide it (`report`). On by
     * default — this is a file manager, not Finder with the Apple defaults. Folders are never
     * affected regardless of this flag; that exemption lives with the call sites, not here.
     */
    @Synchronized
    fun showExtensions(): Boolean = preferences.getBoolean(SHOW_EXTENSIONS, true)

    @Synchronized
    fun setShowExtensions(value: Boolean) {
        preferences.edit().putBoolean(SHOW_EXTENSIONS, value).commit()
    }

    /**
     * Whether video thumbnails cycle frames and previews autoplay, both muted. On by default;
     * off restores the static, no-motion behaviour for anyone who finds the motion distracting.
     */
    @Synchronized
    fun autoAnimate(): Boolean = preferences.getBoolean(AUTO_ANIMATE, true)

    @Synchronized
    fun setAutoAnimate(value: Boolean) {
        preferences.edit().putBoolean(AUTO_ANIMATE, value).commit()
    }

    /**
     * Recent search text, most-recent first, newline-joined. Capped at [MAX_RECENT_SEARCHES]
     * and blank lines dropped, so a corrupted or hand-edited value can't hand back an unbounded
     * or empty-entry list.
     */
    @Synchronized
    fun recentSearches(): List<String> {
        val raw = preferences.getString(RECENT_SEARCHES, null) ?: return emptyList()
        return raw.split("\n").filter(String::isNotBlank).take(MAX_RECENT_SEARCHES)
    }

    /**
     * Records a search as the most recent, moving it to the front if it was already present.
     * The dedupe is case-insensitive ("Photos" and "photos" are the same recent search) but the
     * newly-typed casing wins, since that's the text the user will recognise in the list.
     */
    @Synchronized
    fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val deduped = listOf(trimmed) + recentSearches().filterNot { it.equals(trimmed, ignoreCase = true) }
        preferences.edit()
            .putString(RECENT_SEARCHES, deduped.take(MAX_RECENT_SEARCHES).joinToString("\n"))
            .commit()
    }

    @Synchronized
    fun clearRecentSearches() {
        preferences.edit().remove(RECENT_SEARCHES).commit()
    }

    /** The preview card's pinned actions, in rail order. */
    @Synchronized
    fun quickActions(): List<QuickAction> {
        val raw = preferences.getString(QUICK_ACTIONS, null) ?: return QuickAction.DEFAULT_RAIL
        return QuickAction.railFrom(raw.split(",").filter { it.isNotBlank() })
    }

    @Synchronized
    fun setQuickActions(actions: List<QuickAction>) {
        preferences.edit().putString(QUICK_ACTIONS, actions.joinToString(",") { it.id }).commit()
    }

    /**
     * The preview card's size as a fraction of the viewport, remembered from the last drag.
     *
     * Stored as fractions rather than dp so a size dragged out on a phone still means the same
     * thing on a tablet, and so a rotation does not leave the card wider than the screen.
     */
    @Synchronized
    fun previewScale(): Pair<Float, Float> = Pair(
        preferences.getFloat(PREVIEW_WIDTH, DEFAULT_PREVIEW_WIDTH).coerceIn(0.4f, 1f),
        preferences.getFloat(PREVIEW_HEIGHT, DEFAULT_PREVIEW_HEIGHT).coerceIn(0.3f, 0.95f),
    )

    @Synchronized
    fun setPreviewScale(width: Float, height: Float) {
        preferences.edit()
            .putFloat(PREVIEW_WIDTH, width.coerceIn(0.4f, 1f))
            .putFloat(PREVIEW_HEIGHT, height.coerceIn(0.3f, 0.95f))
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_app_settings"
        const val THEME_MODE = "theme_mode"
        const val SHOW_HIDDEN = "show_hidden"
        const val ICON_STYLE = "icon_style"
        const val VIEW_MODE = "view_mode"
        const val SHOW_EXTENSIONS = "show_extensions"
        const val AUTO_ANIMATE = "auto_animate"
        const val RECENT_SEARCHES = "recent_searches"
        const val QUICK_ACTIONS = "quick_actions"
        const val PREVIEW_WIDTH = "preview_width_fraction"
        const val PREVIEW_HEIGHT = "preview_height_fraction"
        const val DEFAULT_PREVIEW_WIDTH = 0.86f
        const val DEFAULT_PREVIEW_HEIGHT = 0.62f
        const val MAX_RECENT_SEARCHES = 8
    }
}
