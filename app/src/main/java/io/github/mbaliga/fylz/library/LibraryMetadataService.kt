package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.model.FileEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class LibraryImportMode { MERGE, REPLACE }

data class SmartCollectionRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val query: String = "",
    val extensions: Set<String> = emptySet(),
    val mimePrefixes: Set<String> = emptySet(),
    val tagsAny: Set<String> = emptySet(),
    val tagsAll: Set<String> = emptySet(),
    val minimumBytes: Long? = null,
    val maximumBytes: Long? = null,
    val modifiedAfterMillis: Long? = null,
    val modifiedBeforeMillis: Long? = null,
    val includeDirectories: Boolean = false,
) {
    init {
        require(name.isNotBlank())
        require(name.length <= 120)
        require(query.length <= 500)
        require(extensions.size <= 100)
        require(mimePrefixes.size <= 100)
        require(tagsAny.size <= 50)
        require(tagsAll.size <= 50)
        require(minimumBytes == null || minimumBytes >= 0L)
        require(maximumBytes == null || maximumBytes >= 0L)
        require(minimumBytes == null || maximumBytes == null || minimumBytes <= maximumBytes)
    }
}

data class LibraryImportResult(
    val favorites: Int,
    val taggedFiles: Int,
    val savedSearches: Int,
    val smartCollections: Int,
    val warnings: List<String>,
)

/** Versioned, content-free portability and deterministic collection policy. */
class LibraryMetadataService(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val library = LibraryStore(context)

    @Synchronized
    fun smartCollections(): List<SmartCollectionRule> = runCatching {
        val array = JSONArray(preferences.getString(SMART_COLLECTIONS, "[]"))
        List(array.length()) { decodeRule(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun putSmartCollection(rule: SmartCollectionRule) {
        val current = smartCollections().filterNot { it.id == rule.id } + rule
        require(current.size <= MAX_SMART_COLLECTIONS) { "Too many smart collections." }
        writeRules(current.sortedBy { it.name.lowercase() })
    }

    @Synchronized
    fun removeSmartCollection(id: String) {
        writeRules(smartCollections().filterNot { it.id == id })
    }

    fun matches(rule: SmartCollectionRule, entry: FileEntry, tags: Set<String> = library.tags(entry.uri)): Boolean {
        if (entry.isDirectory && !rule.includeDirectories) return false
        val query = rule.query.trim()
        if (query.isNotEmpty() && !entry.name.contains(query, ignoreCase = true)) return false
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        if (rule.extensions.isNotEmpty() && extension !in rule.extensions.normalized()) return false
        if (rule.mimePrefixes.isNotEmpty() && rule.mimePrefixes.none { entry.mimeType.startsWith(it, ignoreCase = true) }) return false
        val normalizedTags = tags.normalized()
        if (rule.tagsAny.isNotEmpty() && rule.tagsAny.normalized().none(normalizedTags::contains)) return false
        if (rule.tagsAll.isNotEmpty() && !normalizedTags.containsAll(rule.tagsAll.normalized())) return false
        entry.sizeBytes?.let { size ->
            if (rule.minimumBytes != null && size < rule.minimumBytes) return false
            if (rule.maximumBytes != null && size > rule.maximumBytes) return false
        } ?: if (rule.minimumBytes != null || rule.maximumBytes != null) return false
        entry.lastModifiedMillis?.let { modified ->
            if (rule.modifiedAfterMillis != null && modified < rule.modifiedAfterMillis) return false
            if (rule.modifiedBeforeMillis != null && modified > rule.modifiedBeforeMillis) return false
        } ?: if (rule.modifiedAfterMillis != null || rule.modifiedBeforeMillis != null) return false
        return true
    }

    @Synchronized
    fun exportJson(): String {
        val tags = JSONObject()
        preferences.all.entries.asSequence()
            .filter { it.key.startsWith(TAG_PREFIX) }
            .take(MAX_TAGGED_FILES)
            .forEach { (key, raw) ->
                @Suppress("UNCHECKED_CAST")
                val values = (raw as? Set<String>).orEmpty().take(MAX_TAGS_PER_FILE)
                tags.put(key.removePrefix(TAG_PREFIX), JSONArray(values))
            }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("product", "Fylz")
            put("exportedAtMillis", System.currentTimeMillis())
            put("favorites", JSONArray().apply {
                library.favorites().take(MAX_FAVORITES).forEach { favorite ->
                    put(JSONObject().put("uri", favorite.uri.toString()).put("name", favorite.name.take(255)))
                }
            })
            put("tags", tags)
            put("savedSearches", JSONArray().apply {
                library.savedSearches().take(MAX_SEARCHES).forEach { search ->
                    put(JSONObject().put("id", search.id).put("name", search.name.take(120)).put("query", search.query.take(500)))
                }
            })
            put("smartCollections", JSONArray().apply {
                smartCollections().take(MAX_SMART_COLLECTIONS).forEach { put(encodeRule(it)) }
            })
        }.toString(2)
    }

    @Synchronized
    fun importJson(value: String, mode: LibraryImportMode): LibraryImportResult {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "Library metadata file is too large." }
        val root = JSONObject(value)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported library metadata version." }
        val warnings = mutableListOf<String>()
        val favorites = decodeFavorites(root.optJSONArray("favorites"), warnings)
        val tags = decodeTags(root.optJSONObject("tags"), warnings)
        val searches = decodeSearches(root.optJSONArray("savedSearches"), warnings)
        val rules = decodeRules(root.optJSONArray("smartCollections"), warnings)

        val editor = preferences.edit()
        if (mode == LibraryImportMode.REPLACE) {
            preferences.all.keys.filter { it.startsWith(TAG_PREFIX) }.forEach(editor::remove)
            editor.remove(FAVORITES).remove(SEARCHES).remove(SMART_COLLECTIONS)
        }

        val mergedFavorites = if (mode == LibraryImportMode.MERGE) {
            (library.favorites() + favorites).distinctBy { it.uri.toString() }.take(MAX_FAVORITES)
        } else favorites
        editor.putString(FAVORITES, JSONArray().apply {
            mergedFavorites.forEach { put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)) }
        }.toString())

        tags.forEach { (uri, values) ->
            val key = TAG_PREFIX + uri
            val merged = if (mode == LibraryImportMode.MERGE) {
                preferences.getStringSet(key, emptySet()).orEmpty() + values
            } else values
            editor.putStringSet(key, merged.map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase).take(MAX_TAGS_PER_FILE).toSet())
        }

        val mergedSearches = if (mode == LibraryImportMode.MERGE) {
            (library.savedSearches() + searches).associateBy(SavedSearch::id).values.take(MAX_SEARCHES)
        } else searches
        editor.putString(SEARCHES, JSONArray().apply {
            mergedSearches.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("query", it.query)) }
        }.toString())

        val mergedRules = if (mode == LibraryImportMode.MERGE) {
            (smartCollections() + rules).associateBy(SmartCollectionRule::id).values.take(MAX_SMART_COLLECTIONS)
        } else rules
        editor.putString(SMART_COLLECTIONS, JSONArray().apply { mergedRules.forEach { put(encodeRule(it)) } }.toString())
        check(editor.commit()) { "Unable to commit imported library metadata." }
        return LibraryImportResult(mergedFavorites.size, tags.size, mergedSearches.size, mergedRules.size, warnings)
    }

    private fun decodeFavorites(array: JSONArray?, warnings: MutableList<String>): List<FavoriteLocation> = buildList {
        if (array == null) return@buildList
        repeat(minOf(array.length(), MAX_FAVORITES)) { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val uri = Uri.parse(item.getString("uri"))
                val name = item.getString("name").trim().take(255)
                require(uri.scheme != null && name.isNotEmpty())
                add(FavoriteLocation(uri, name))
            }.onFailure { warnings += "Skipped invalid favorite at position ${index + 1}." }
        }
    }

    private fun decodeTags(root: JSONObject?, warnings: MutableList<String>): Map<String, Set<String>> = buildMap {
        if (root == null) return@buildMap
        root.keys().asSequence().take(MAX_TAGGED_FILES).forEach { uri ->
            runCatching {
                require(Uri.parse(uri).scheme != null)
                val values = root.getJSONArray(uri)
                put(uri, buildSet {
                    repeat(minOf(values.length(), MAX_TAGS_PER_FILE)) { add(values.getString(it).trim().take(40)) }
                }.filter(String::isNotBlank).toSet())
            }.onFailure { warnings += "Skipped tags for an invalid URI." }
        }
    }

    private fun decodeSearches(array: JSONArray?, warnings: MutableList<String>): List<SavedSearch> = buildList {
        if (array == null) return@buildList
        repeat(minOf(array.length(), MAX_SEARCHES)) { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = item.getString("name").trim().take(120)
                val query = item.getString("query").take(500)
                require(name.isNotBlank())
                add(SavedSearch(id, name, query))
            }.onFailure { warnings += "Skipped invalid saved search at position ${index + 1}." }
        }
    }

    private fun decodeRules(array: JSONArray?, warnings: MutableList<String>): List<SmartCollectionRule> = buildList {
        if (array == null) return@buildList
        repeat(minOf(array.length(), MAX_SMART_COLLECTIONS)) { index ->
            runCatching { add(decodeRule(array.getJSONObject(index))) }
                .onFailure { warnings += "Skipped invalid smart collection at position ${index + 1}." }
        }
    }

    private fun encodeRule(value: SmartCollectionRule) = JSONObject().apply {
        put("id", value.id)
        put("name", value.name)
        put("query", value.query)
        put("extensions", JSONArray(value.extensions.toList()))
        put("mimePrefixes", JSONArray(value.mimePrefixes.toList()))
        put("tagsAny", JSONArray(value.tagsAny.toList()))
        put("tagsAll", JSONArray(value.tagsAll.toList()))
        put("minimumBytes", value.minimumBytes ?: JSONObject.NULL)
        put("maximumBytes", value.maximumBytes ?: JSONObject.NULL)
        put("modifiedAfterMillis", value.modifiedAfterMillis ?: JSONObject.NULL)
        put("modifiedBeforeMillis", value.modifiedBeforeMillis ?: JSONObject.NULL)
        put("includeDirectories", value.includeDirectories)
    }

    private fun decodeRule(value: JSONObject) = SmartCollectionRule(
        id = value.optString("id").ifBlank { UUID.randomUUID().toString() },
        name = value.getString("name").trim(),
        query = value.optString("query").take(500),
        extensions = value.optJSONArray("extensions").stringSet(100),
        mimePrefixes = value.optJSONArray("mimePrefixes").stringSet(100),
        tagsAny = value.optJSONArray("tagsAny").stringSet(50),
        tagsAll = value.optJSONArray("tagsAll").stringSet(50),
        minimumBytes = value.optLongOrNull("minimumBytes"),
        maximumBytes = value.optLongOrNull("maximumBytes"),
        modifiedAfterMillis = value.optLongOrNull("modifiedAfterMillis"),
        modifiedBeforeMillis = value.optLongOrNull("modifiedBeforeMillis"),
        includeDirectories = value.optBoolean("includeDirectories", false),
    )

    private fun writeRules(values: List<SmartCollectionRule>) {
        val array = JSONArray()
        values.forEach { array.put(encodeRule(it)) }
        check(preferences.edit().putString(SMART_COLLECTIONS, array.toString()).commit())
    }

    private fun JSONArray?.stringSet(max: Int): Set<String> = buildSet {
        if (this@stringSet == null) return@buildSet
        repeat(minOf(length(), max)) { add(getString(it).trim().lowercase().take(120)) }
    }.filter(String::isNotBlank).toSet()

    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun Collection<String>.normalized() = map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()

    private companion object {
        const val SCHEMA_VERSION = 1
        const val PREFERENCES_NAME = "fylz_library"
        const val FAVORITES = "favorites"
        const val SEARCHES = "saved_searches"
        const val SMART_COLLECTIONS = "smart_collections"
        const val TAG_PREFIX = "tags:"
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val MAX_FAVORITES = 2_000
        const val MAX_TAGGED_FILES = 100_000
        const val MAX_TAGS_PER_FILE = 20
        const val MAX_SEARCHES = 500
        const val MAX_SMART_COLLECTIONS = 500
    }
}
