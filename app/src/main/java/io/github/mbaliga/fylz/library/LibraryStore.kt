package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteLocation(val uri: Uri, val name: String)
data class SavedSearch(val id: String, val name: String, val query: String)

/** Local-only library metadata. File bytes and user content are never stored here. */
class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun favorites(): List<FavoriteLocation> = runCatching {
        val array = JSONArray(preferences.getString(FAVORITES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(FavoriteLocation(Uri.parse(item.getString("uri")), item.getString("name")))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun toggleFavorite(uri: Uri, name: String): Boolean {
        val current = favorites().toMutableList()
        val index = current.indexOfFirst { it.uri == uri }
        val added = index < 0
        if (added) current += FavoriteLocation(uri, name) else current.removeAt(index)
        val array = JSONArray()
        current.forEach { array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)) }
        preferences.edit().putString(FAVORITES, array.toString()).commit()
        return added
    }

    @Synchronized
    fun tags(uri: Uri): Set<String> = preferences
        .getStringSet(TAG_PREFIX + uri.toString(), emptySet())
        .orEmpty()
        .toSortedSet(String.CASE_INSENSITIVE_ORDER)

    @Synchronized
    fun setTags(uri: Uri, tags: Collection<String>) {
        val normalized = tags.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { it.take(40) }
            .distinctBy(String::lowercase)
            .take(20)
            .toSet()
        preferences.edit().putStringSet(TAG_PREFIX + uri.toString(), normalized).commit()
    }

    @Synchronized
    fun savedSearches(): List<SavedSearch> = runCatching {
        val array = JSONArray(preferences.getString(SEARCHES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(SavedSearch(item.getString("id"), item.getString("name"), item.getString("query")))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveSearch(search: SavedSearch) {
        val current = savedSearches().filterNot { it.id == search.id } + search
        val array = JSONArray()
        current.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("query", it.query))
        }
        preferences.edit().putString(SEARCHES, array.toString()).commit()
    }

    @Synchronized
    fun removeSearch(id: String) {
        val current = savedSearches().filterNot { it.id == id }
        val array = JSONArray()
        current.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("query", it.query))
        }
        preferences.edit().putString(SEARCHES, array.toString()).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_library"
        const val FAVORITES = "favorites"
        const val SEARCHES = "saved_searches"
        const val TAG_PREFIX = "tags:"
    }
}
