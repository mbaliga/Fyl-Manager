package io.github.mbaliga.fylz.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A folder the user opened, with enough to open it again: the tree it lives in and its own URI. */
data class RecentFolder(
    val treeUri: String,
    val folderUri: String,
    val name: String,
)

/** The list rules, pure: newest first, one entry per folder, capped. */
object RecentFolders {
    const val MAX = 8

    fun record(current: List<RecentFolder>, opened: RecentFolder, max: Int = MAX): List<RecentFolder> {
        if (opened.name.isBlank()) return current
        return (listOf(opened) + current.filterNot { it.folderUri == opened.folderUri }).take(max)
    }
}

/**
 * Persists the launcher's "Recent" row. App-private and tiny; it holds locations only, never file
 * contents, and is cleared with the app's data. Plain SharedPreferences: this is a convenience
 * list, and losing it costs nothing.
 */
class RecentFoldersStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("fylz_recent_folders", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<RecentFolder> = runCatching {
        val array = JSONArray(preferences.getString(KEY, "[]").orEmpty())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RecentFolder(
                treeUri = item.optString("tree").ifBlank { return@mapNotNull null },
                folderUri = item.optString("folder").ifBlank { return@mapNotNull null },
                name = item.optString("name").ifBlank { return@mapNotNull null },
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun record(opened: RecentFolder): List<RecentFolder> {
        val updated = RecentFolders.record(list(), opened)
        val array = JSONArray()
        updated.forEach {
            array.put(JSONObject().put("tree", it.treeUri).put("folder", it.folderUri).put("name", it.name.take(200)))
        }
        preferences.edit().putString(KEY, array.toString()).apply()
        return updated
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "recent"
    }
}
