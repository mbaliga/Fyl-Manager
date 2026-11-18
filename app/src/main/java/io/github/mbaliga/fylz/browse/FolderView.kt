package io.github.mbaliga.fylz.browse

import android.content.Context
import io.github.mbaliga.fylz.model.ViewMode

/** How one folder is shown: list or grid, and its order. */
data class FolderView(val viewMode: ViewMode = ViewMode.LIST, val sort: SortSpec = SortSpec.Default)

/**
 * The text form of a [FolderView], for storage. Pure so the round trip is tested; anything
 * unreadable (an older build's value, a renamed enum) decodes to null and the caller falls back
 * to the default instead of crashing at startup.
 */
object FolderViewCodec {

    fun encode(view: FolderView): String =
        listOf(view.viewMode.name, view.sort.field.name, view.sort.direction.name, if (view.sort.foldersFirst) "1" else "0")
            .joinToString("|")

    fun decode(text: String?): FolderView? {
        val parts = text?.split('|') ?: return null
        if (parts.size != 4) return null
        val mode = ViewMode.entries.firstOrNull { it.name == parts[0] } ?: return null
        val field = SortField.entries.firstOrNull { it.name == parts[1] } ?: return null
        val direction = SortDirection.entries.firstOrNull { it.name == parts[2] } ?: return null
        val foldersFirst = when (parts[3]) { "1" -> true; "0" -> false; else -> return null }
        return FolderView(mode, SortSpec(field, direction, foldersFirst))
    }
}

/**
 * Which view applies to a folder.
 *
 * With per-folder memory on, a folder uses what was last chosen *for it*, and a folder never
 * visited uses the global view. With it off, every folder uses the global view and changes
 * update the global view. Pure: the store only supplies the saved values.
 */
object FolderViewPolicy {

    fun resolve(perFolder: Boolean, saved: FolderView?, global: FolderView): FolderView =
        if (perFolder) saved ?: global else global
}

/**
 * Browser preferences kept across launches: the global view, each folder's own view, and the two
 * behaviour switches in Settings. Plain SharedPreferences: small, synchronous, nothing sensitive.
 */
class BrowserPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("fylz_browser", Context.MODE_PRIVATE)

    /** Tap opens the quick preview instead of the file (long press then previews and selects). */
    var tapToPreview: Boolean
        get() = prefs.getBoolean(KEY_TAP_TO_PREVIEW, false)
        set(value) { prefs.edit().putBoolean(KEY_TAP_TO_PREVIEW, value).apply() }

    /** Remember list/grid and sort for each folder; off means one view for every folder. */
    var perFolderView: Boolean
        get() = prefs.getBoolean(KEY_PER_FOLDER, true)
        set(value) { prefs.edit().putBoolean(KEY_PER_FOLDER, value).apply() }

    fun global(): FolderView = FolderViewCodec.decode(prefs.getString(KEY_GLOBAL, null)) ?: FolderView()

    /** The view to show for [folderKey] under the current per-folder setting. */
    fun viewFor(folderKey: String): FolderView {
        val saved = FolderViewCodec.decode(prefs.getString(FOLDER_PREFIX + folderKey, null))
        return FolderViewPolicy.resolve(perFolderView, saved, global())
    }

    /** Remembers [view] for [folderKey], or globally when per-folder memory is off. */
    fun save(folderKey: String?, view: FolderView) {
        val editor = prefs.edit()
        if (perFolderView && folderKey != null) {
            editor.putString(FOLDER_PREFIX + folderKey, FolderViewCodec.encode(view))
        } else {
            editor.putString(KEY_GLOBAL, FolderViewCodec.encode(view))
        }
        editor.apply()
    }

    private companion object {
        const val KEY_TAP_TO_PREVIEW = "tap_to_preview"
        const val KEY_PER_FOLDER = "per_folder_view"
        const val KEY_GLOBAL = "global_view"
        const val FOLDER_PREFIX = "folder:"
    }
}
