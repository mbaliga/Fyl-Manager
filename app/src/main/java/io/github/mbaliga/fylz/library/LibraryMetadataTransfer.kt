package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Portable, secret-free export for favourites, tags and saved searches. */
class LibraryMetadataTransfer(context: Context) {
    private val store = LibraryStore(context.applicationContext)

    fun export(): String {
        val favorites = store.favorites()
        val searches = store.savedSearches()
        val taggedUris = favorites.map(FavoriteLocation::uri).distinct()
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMillis", System.currentTimeMillis())
            put("favorites", JSONArray().apply {
                favorites.forEach { favorite ->
                    put(JSONObject().put("uri", favorite.uri.toString()).put("name", favorite.name))
                }
            })
            put("savedSearches", JSONArray().apply {
                searches.forEach { search ->
                    put(JSONObject().put("id", search.id).put("name", search.name).put("query", search.query))
                }
            })
            put("tags", JSONArray().apply {
                taggedUris.forEach { uri ->
                    val tags = store.tags(uri)
                    if (tags.isNotEmpty()) {
                        put(JSONObject().put("uri", uri.toString()).put("values", JSONArray(tags.toList())))
                    }
                }
            })
        }.toString(2)
    }

    fun import(value: String): LibraryImportResult {
        require(value.length <= MAX_IMPORT_CHARS) { "Library metadata file exceeds the import limit." }
        val root = JSONObject(value)
        require(root.optInt("schemaVersion", -1) == 1) { "Unsupported library metadata version." }
        var favorites = 0
        var searches = 0
        var tagged = 0

        root.optJSONArray("favorites")?.let { array ->
            require(array.length() <= MAX_ITEMS)
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri"))
                val name = item.getString("name").trim().take(MAX_NAME_CHARS)
                if (name.isNotBlank() && store.favorites().none { it.uri == uri }) {
                    store.toggleFavorite(uri, name)
                    favorites += 1
                }
            }
        }
        root.optJSONArray("savedSearches")?.let { array ->
            require(array.length() <= MAX_ITEMS)
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val search = SavedSearch(
                    id = item.getString("id").take(MAX_ID_CHARS),
                    name = item.getString("name").trim().take(MAX_NAME_CHARS),
                    query = item.getString("query").take(MAX_QUERY_CHARS),
                )
                if (search.id.isNotBlank() && search.name.isNotBlank()) {
                    store.saveSearch(search)
                    searches += 1
                }
            }
        }
        root.optJSONArray("tags")?.let { array ->
            require(array.length() <= MAX_ITEMS)
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri"))
                val values = item.optJSONArray("values") ?: JSONArray()
                val tags = buildList {
                    repeat(minOf(values.length(), MAX_TAGS_PER_FILE)) { tagIndex -> add(values.getString(tagIndex)) }
                }
                store.setTags(uri, tags)
                if (tags.isNotEmpty()) tagged += 1
            }
        }
        return LibraryImportResult(favorites, searches, tagged)
    }

    data class LibraryImportResult(
        val favoritesImported: Int,
        val searchesImported: Int,
        val taggedFilesImported: Int,
    )

    companion object {
        private const val MAX_IMPORT_CHARS = 8 * 1024 * 1024
        private const val MAX_ITEMS = 100_000
        private const val MAX_TAGS_PER_FILE = 20
        private const val MAX_NAME_CHARS = 200
        private const val MAX_QUERY_CHARS = 4_096
        private const val MAX_ID_CHARS = 200
    }
}
