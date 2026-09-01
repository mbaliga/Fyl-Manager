package io.github.mbaliga.fylz.settings

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.model.ShakeAction
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.components.QuickAction
import io.github.mbaliga.fylz.ui.landing.HomeMode
import io.github.mbaliga.fylz.ui.theme.ThemeStyle

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
     * Which of the five owner-named looks the app draws in — icon pack, folder material, and for
     * three of the five, a fixed palette and monospace type on top. [ThemeStyle.NEO] because it
     * is today's app: dynamic wallpaper colour, solid folders, unchanged for anyone who never
     * opens this screen.
     */
    @Synchronized
    fun themeStyle(): ThemeStyle {
        val raw = preferences.getString(THEME_STYLE, null) ?: return ThemeStyle.NEO
        if (raw == LEGACY_GLASS_STYLE) {
            // GLASS was renamed to FYLZ after ship; a bare valueOf on the stored name would miss,
            // fall through to getOrDefault, and silently downgrade every existing Glass user to
            // Neo with no error. Resolve it explicitly and rewrite the stored value in place, so
            // every read after this first one is a plain valueOf again, not a second migration.
            preferences.edit().putString(THEME_STYLE, ThemeStyle.FYLZ.name).commit()
            return ThemeStyle.FYLZ
        }
        return runCatching { ThemeStyle.valueOf(raw) }.getOrDefault(ThemeStyle.NEO)
    }

    @Synchronized
    fun setThemeStyle(style: ThemeStyle) {
        preferences.edit().putString(THEME_STYLE, style.name).commit()
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

    /** Which [ViewMode] the browser last rendered, restored on the next launch. */
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
     * The S/M/L icon-size axis, independent of [ViewMode]. [DensityMode.COMFORTABLE] is today's
     * fixed sizing, so nobody who never opens this screen sees anything change.
     */
    @Synchronized
    fun density(): DensityMode {
        val raw = preferences.getString(DENSITY, null) ?: return DensityMode.COMFORTABLE
        return runCatching { DensityMode.valueOf(raw) }.getOrDefault(DensityMode.COMFORTABLE)
    }

    @Synchronized
    fun setDensity(mode: DensityMode) {
        preferences.edit().putString(DENSITY, mode.name).commit()
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

    /** What a shake does; the detector is shared, only this dispatch is a preference. */
    @Synchronized
    fun shakeAction(): ShakeAction {
        val raw = preferences.getString(SHAKE_ACTION, null) ?: return ShakeAction.REFRESH
        return runCatching { ShakeAction.valueOf(raw) }.getOrDefault(ShakeAction.REFRESH)
    }

    @Synchronized
    fun setShakeAction(action: ShakeAction) {
        preferences.edit().putString(SHAKE_ACTION, action.name).commit()
    }

    /**
     * Deliberate actions: destructive confirmations require a slide instead of a tap, per the
     * owner's mechanical-actuation-force rationale (docs/product/deliberate-ux.md). Off by
     * default — deliberation is a choice, not a toll.
     */
    @Synchronized
    fun deliberateActions(): Boolean = preferences.getBoolean(DELIBERATE_ACTIONS, false)

    @Synchronized
    fun setDeliberateActions(value: Boolean) {
        preferences.edit().putBoolean(DELIBERATE_ACTIONS, value).commit()
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

    /** Whether the landing hero shows on cold start. On by default -- it is meant to be seen. */
    @Synchronized
    fun landingSplash(): Boolean = preferences.getBoolean(LANDING_SPLASH, true)

    @Synchronized
    fun setLandingSplash(value: Boolean) {
        preferences.edit().putBoolean(LANDING_SPLASH, value).commit()
    }

    /**
     * Which surface the landing home renders once past the hero. [HomeMode.DESKTOP] by default --
     * an absent key means a fresh install, which lands on the desktop rather than the plain
     * locations list.
     */
    @Synchronized
    fun homeMode(): HomeMode {
        val raw = preferences.getString(LANDING_VIEW, null) ?: return HomeMode.DESKTOP
        if (raw == LEGACY_OVERVIEW_MODE) {
            // HomeMode.OVERVIEW was renamed to DESKTOP; a bare valueOf on the stored name would
            // miss, fall through to getOrDefault, and silently drop every existing Overview user
            // back to whatever the default resolves to instead of the desktop they had. Resolve
            // it explicitly and rewrite the stored value in place, the same one-time migration
            // themeStyle() runs for GLASS -> FYLZ.
            preferences.edit().putString(LANDING_VIEW, HomeMode.DESKTOP.name).commit()
            return HomeMode.DESKTOP
        }
        return runCatching { HomeMode.valueOf(raw) }.getOrDefault(HomeMode.DESKTOP)
    }

    @Synchronized
    fun setHomeMode(mode: HomeMode) {
        preferences.edit().putString(LANDING_VIEW, mode.name).commit()
    }

    /** Whether desktop icons snap to the grid while dragging. On by default. */
    @Synchronized
    fun desktopSnap(): Boolean = preferences.getBoolean(DESKTOP_SNAP, true)

    @Synchronized
    fun setDesktopSnap(value: Boolean) {
        preferences.edit().putBoolean(DESKTOP_SNAP, value).commit()
    }

    /** Whether desktop shortcut tiles show their name label beneath the thumbnail. On by default. */
    @Synchronized
    fun desktopLabels(): Boolean = preferences.getBoolean(DESKTOP_LABELS, true)

    @Synchronized
    fun setDesktopLabels(value: Boolean) {
        preferences.edit().putBoolean(DESKTOP_LABELS, value).commit()
    }

    /**
     * The folder LIST/BENTO/CANVAS list, as its raw tree and folder URI strings -- parsing and
     * grant validation happen at the boot call site, not here, so a store read never itself
     * throws on a URI that no longer resolves. Null when either half is missing.
     */
    @Synchronized
    fun landingSubject(): Pair<String, String>? {
        val tree = preferences.getString(LANDING_SUBJECT_TREE, null) ?: return null
        val folder = preferences.getString(LANDING_SUBJECT_FOLDER, null) ?: return null
        return tree to folder
    }

    /** Pass null for both to clear the subject and fall back to [HomeMode.LOCATIONS]. */
    @Synchronized
    fun setLandingSubject(treeUri: String?, folderUri: String?) {
        val editor = preferences.edit()
        if (treeUri == null || folderUri == null) {
            editor.remove(LANDING_SUBJECT_TREE).remove(LANDING_SUBJECT_FOLDER)
        } else {
            editor.putString(LANDING_SUBJECT_TREE, treeUri).putString(LANDING_SUBJECT_FOLDER, folderUri)
        }
        editor.commit()
    }

    /**
     * Rewrites the stored subject's folder URI when a move or rename carries it away from under
     * the pref -- the same relocation seam [io.github.mbaliga.fylz.library.LibraryStore] and
     * [io.github.mbaliga.fylz.history.FileHistoryStore] already answer to. Only the folder half
     * is ever rewritten; the tree grant itself does not change from an in-app move. Returns
     * whether the stored subject matched (and was rewritten), so the caller can tell a hit from
     * a miss without a redundant read of its own.
     */
    @Synchronized
    fun migrateLandingSubject(oldUri: Uri, newUri: Uri): Boolean {
        val folder = preferences.getString(LANDING_SUBJECT_FOLDER, null) ?: return false
        if (folder != oldUri.toString()) return false
        preferences.edit().putString(LANDING_SUBJECT_FOLDER, newUri.toString()).commit()
        return true
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_app_settings"
        const val THEME_MODE = "theme_mode"
        const val THEME_STYLE = "theme_style"
        const val LEGACY_GLASS_STYLE = "GLASS"
        const val SHOW_HIDDEN = "show_hidden"
        const val ICON_STYLE = "icon_style"
        const val VIEW_MODE = "view_mode"
        const val DENSITY = "density"
        const val SHOW_EXTENSIONS = "show_extensions"
        const val AUTO_ANIMATE = "auto_animate"
        const val RECENT_SEARCHES = "recent_searches"
        const val SHAKE_ACTION = "shake_action"
        const val DELIBERATE_ACTIONS = "deliberate_actions"
        const val QUICK_ACTIONS = "quick_actions"
        const val PREVIEW_WIDTH = "preview_width_fraction"
        const val PREVIEW_HEIGHT = "preview_height_fraction"
        const val DEFAULT_PREVIEW_WIDTH = 0.86f
        const val DEFAULT_PREVIEW_HEIGHT = 0.62f
        const val MAX_RECENT_SEARCHES = 8
        const val LANDING_SPLASH = "landing_splash"
        const val LANDING_VIEW = "landing_view"
        const val LEGACY_OVERVIEW_MODE = "OVERVIEW"
        const val LANDING_SUBJECT_TREE = "landing_subject_tree"
        const val LANDING_SUBJECT_FOLDER = "landing_subject_folder"
        const val DESKTOP_SNAP = "desktop_snap"
        const val DESKTOP_LABELS = "desktop_labels"
    }
}
