package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FavoriteLocation(val uri: Uri, val name: String)
data class SavedSearch(val id: String, val name: String, val query: String)

enum class SmartRuleField {
    NAME,
    EXTENSION,
    MIME_TYPE,
    SIZE_BYTES,
    MODIFIED_MILLIS,
    TAG,
}

enum class SmartRuleOperator {
    CONTAINS,
    EQUALS,
    STARTS_WITH,
    ENDS_WITH,
    GREATER_THAN,
    LESS_THAN,
}

data class SmartRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val field: SmartRuleField,
    val operator: SmartRuleOperator,
    val value: String,
    val enabled: Boolean = true,
)

data class LibraryImportResult(
    val favorites: Int,
    val taggedItems: Int,
    val savedSearches: Int,
    val smartRules: Int,
)

/** Local-only library metadata. File bytes and user content are never stored here. */
class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun favorites(): List<FavoriteLocation> = decodeFavorites(preferences.getString(FAVORITES, "[]").orEmpty())

    @Synchronized
    fun toggleFavorite(uri: Uri, name: String): Boolean {
        val current = favorites().toMutableList()
        val index = current.indexOfFirst { it.uri == uri }
        val added = index < 0
        if (added) current += FavoriteLocation(uri, name.take(200)) else current.removeAt(index)
        preferences.edit().putString(FAVORITES, encodeFavorites(current).toString()).commit()
        return added
    }

    @Synchronized
    fun tags(uri: Uri): Set<String> = preferences
        .getStringSet(TAG_PREFIX + uri.toString(), emptySet())
        .orEmpty()
        .toSortedSet(String.CASE_INSENSITIVE_ORDER)

    @Synchronized
    fun setTags(uri: Uri, tags: Collection<String>) {
        preferences.edit().putStringSet(TAG_PREFIX + uri.toString(), normalizeTags(tags)).commit()
    }

    @Synchronized
    fun savedSearches(): List<SavedSearch> = decodeSearches(preferences.getString(SEARCHES, "[]").orEmpty())

    @Synchronized
    fun saveSearch(search: SavedSearch) {
        require(search.name.isNotBlank())
        val sanitized = search.copy(name = search.name.trim().take(100), query = search.query.take(1_000))
        val current = savedSearches().filterNot { it.id == search.id } + sanitized
        preferences.edit().putString(SEARCHES, encodeSearches(current).toString()).commit()
    }

    @Synchronized
    fun removeSearch(id: String) {
        preferences.edit().putString(SEARCHES, encodeSearches(savedSearches().filterNot { it.id == id }).toString()).commit()
    }

    @Synchronized
    fun smartRules(): List<SmartRule> = runCatching {
        val array = JSONArray(preferences.getString(SMART_RULES, "[]"))
        buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    SmartRule(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        field = SmartRuleField.valueOf(item.getString("field")),
                        operator = SmartRuleOperator.valueOf(item.getString("operator")),
                        value = item.getString("value"),
                        enabled = item.optBoolean("enabled", true),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveSmartRule(rule: SmartRule) {
        require(rule.name.isNotBlank())
        require(rule.value.isNotBlank())
        val sanitized = rule.copy(name = rule.name.trim().take(100), value = rule.value.trim().take(500))
        val current = smartRules().filterNot { it.id == rule.id } + sanitized
        preferences.edit().putString(SMART_RULES, encodeRules(current).toString()).commit()
    }

    @Synchronized
    fun removeSmartRule(id: String) {
        preferences.edit().putString(SMART_RULES, encodeRules(smartRules().filterNot { it.id == id }).toString()).commit()
    }

    /** Portable metadata only. Persisted SAF grants and secrets are deliberately excluded. */
    @Synchronized
    fun exportJson(): String {
        val tagged = JSONObject()
        preferences.all.keys.asSequence()
            .filter { it.startsWith(TAG_PREFIX) }
            .sorted()
            .forEach { key ->
                val uri = key.removePrefix(TAG_PREFIX)
                tagged.put(uri, JSONArray(preferences.getStringSet(key, emptySet()).orEmpty().sorted()))
            }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAtMillis", System.currentTimeMillis())
            put("favorites", encodeFavorites(favorites()))
            put("tags", tagged)
            put("savedSearches", encodeSearches(savedSearches()))
            put("smartRules", encodeRules(smartRules()))
        }.toString(2)
    }

    @Synchronized
    fun importJson(json: String, replace: Boolean = false): LibraryImportResult {
        require(json.length <= MAX_IMPORT_CHARS) { "Library metadata import exceeds the safety limit." }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported library metadata schema." }
        val importedFavorites = decodeFavorites(root.optJSONArray("favorites")?.toString().orEmpty())
        val importedSearches = decodeSearches(root.optJSONArray("savedSearches")?.toString().orEmpty())
        val importedRules = decodeRules(root.optJSONArray("smartRules") ?: JSONArray())
        val importedTags = root.optJSONObject("tags") ?: JSONObject()
        require(importedFavorites.size <= MAX_FAVORITES)
        require(importedSearches.size <= MAX_SEARCHES)
        require(importedRules.size <= MAX_RULES)
        require(importedTags.length() <= MAX_TAGGED_ITEMS)

        val editor = preferences.edit()
        if (replace) {
            preferences.all.keys.filter { it.startsWith(TAG_PREFIX) }.forEach(editor::remove)
        }
        val favorites = if (replace) importedFavorites else (favorites() + importedFavorites)
            .distinctBy { it.uri.toString() }.take(MAX_FAVORITES)
        val searches = if (replace) importedSearches else (savedSearches() + importedSearches)
            .distinctBy(SavedSearch::id).take(MAX_SEARCHES)
        val rules = if (replace) importedRules else (smartRules() + importedRules)
            .distinctBy(SmartRule::id).take(MAX_RULES)
        editor.putString(FAVORITES, encodeFavorites(favorites).toString())
        editor.putString(SEARCHES, encodeSearches(searches).toString())
        editor.putString(SMART_RULES, encodeRules(rules).toString())

        var taggedItems = 0
        importedTags.keys().forEach { uri ->
            val incoming = importedTags.optJSONArray(uri)?.toStringList().orEmpty()
            val merged = if (replace) incoming else tags(Uri.parse(uri)) + incoming
            editor.putStringSet(TAG_PREFIX + uri, normalizeTags(merged))
            taggedItems += 1
        }
        check(editor.commit()) { "Unable to commit imported library metadata." }
        return LibraryImportResult(importedFavorites.size, taggedItems, importedSearches.size, importedRules.size)
    }

    private fun decodeFavorites(raw: String): List<FavoriteLocation> = runCatching {
        val array = if (raw.isBlank()) JSONArray() else JSONArray(raw)
        buildList {
            repeat(minOf(array.length(), MAX_FAVORITES)) { index ->
                val item = array.getJSONObject(index)
                val uri = item.getString("uri").take(MAX_URI_CHARS)
                val name = item.getString("name").take(200)
                if (uri.isNotBlank()) add(FavoriteLocation(Uri.parse(uri), name))
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeSearches(raw: String): List<SavedSearch> = runCatching {
        val array = if (raw.isBlank()) JSONArray() else JSONArray(raw)
        buildList {
            repeat(minOf(array.length(), MAX_SEARCHES)) { index ->
                val item = array.getJSONObject(index)
                add(
                    SavedSearch(
                        item.getString("id").take(100),
                        item.getString("name").take(100),
                        item.getString("query").take(1_000),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeRules(array: JSONArray): List<SmartRule> = buildList {
        repeat(minOf(array.length(), MAX_RULES)) { index ->
            val item = array.getJSONObject(index)
            add(
                SmartRule(
                    id = item.getString("id").take(100),
                    name = item.getString("name").take(100),
                    field = SmartRuleField.valueOf(item.getString("field")),
                    operator = SmartRuleOperator.valueOf(item.getString("operator")),
                    value = item.getString("value").take(500),
                    enabled = item.optBoolean("enabled", true),
                ),
            )
        }
    }

    private fun encodeFavorites(values: List<FavoriteLocation>) = JSONArray().apply {
        values.take(MAX_FAVORITES).forEach { put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)) }
    }

    private fun encodeSearches(values: List<SavedSearch>) = JSONArray().apply {
        values.take(MAX_SEARCHES).forEach {
            put(JSONObject().put("id", it.id).put("name", it.name).put("query", it.query))
        }
    }

    private fun encodeRules(values: List<SmartRule>) = JSONArray().apply {
        values.take(MAX_RULES).forEach {
            put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("field", it.field.name)
                    .put("operator", it.operator.name)
                    .put("value", it.value)
                    .put("enabled", it.enabled),
            )
        }
    }

    private fun normalizeTags(tags: Collection<String>): Set<String> = tags.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(40) }
        .distinctBy(String::lowercase)
        .take(20)
        .toSet()

    private fun JSONArray.toStringList(): List<String> = buildList {
        repeat(minOf(length(), 20)) { index -> add(getString(index).take(40)) }
    }

    private companion object {
        const val PREFERENCES_NAME = "fylz_library"
        const val FAVORITES = "favorites"
        const val SEARCHES = "saved_searches"
        const val SMART_RULES = "smart_rules"
        const val TAG_PREFIX = "tags:"
        const val SCHEMA_VERSION = 1
        const val MAX_IMPORT_CHARS = 8 * 1024 * 1024
        const val MAX_URI_CHARS = 8_192
        const val MAX_FAVORITES = 5_000
        const val MAX_SEARCHES = 1_000
        const val MAX_RULES = 1_000
        const val MAX_TAGGED_ITEMS = 100_000
    }
}
