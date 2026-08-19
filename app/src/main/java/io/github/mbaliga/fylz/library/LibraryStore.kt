package io.github.mbaliga.fylz.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import java.util.TreeSet
import java.util.UUID

data class FavoriteLocation(val uri: Uri, val name: String)
data class SavedSearch(val id: String, val name: String, val query: String)

/**
 * Every tag across [perItemTags], folded case-insensitively the same way [LibraryStore.tags]
 * folds one item's own set. What a *multi-item* tag dialog should seed its editable field with --
 * every tag any selected item carries -- rather than only the first selected item's own tags,
 * which is today's bug (`FylzV1App.kt`'s `TagDialog` call site seeds from
 * `selectedEntries.firstOrNull()`).
 */
fun unionOfTags(perItemTags: Collection<Set<String>>): Set<String> =
    caseInsensitiveTagSet().apply { perItemTags.forEach(::addAll) }

/**
 * What one item's own tags become after a multi-item edit of [unionOfTags]'s seeded field:
 * [existing] plus whatever the user added to that union ([after] minus [before]), minus whatever
 * they removed from it ([before] minus [after]). A tag [existing] already carried, that the
 * union-edit never touched -- present in both [before] and [after], or in neither -- survives
 * untouched.
 *
 * This is the fix for the other half of today's bug: `TagDialog`'s `onConfirm` calls
 * `library.setTags(it.uri, tags)` for every selected entry with the *same* freshly-typed list,
 * silently overwriting every entry but the first with tags it never had and erasing whichever of
 * its own tags weren't also the first entry's. The correct edit is per-item and additive/
 * subtractive against each item's *own* existing tags, computed here as:
 * ```
 * val before = unionOfTags(selectedEntries.map { library.tags(it.uri) })  // seeds the dialog
 * // ...user edits the field, producing `after`...
 * selectedEntries.forEach { entry ->
 *     library.setTags(entry.uri, applyTagDelta(library.tags(entry.uri), before, after))
 * }
 * ```
 * All three sets are compared case-insensitively, matching [LibraryStore.tags]'s own ordering, so
 * retyping a tag in a different case is a genuine remove-and-add rather than silently either "no
 * change" or two unrelated tags.
 */
fun applyTagDelta(existing: Set<String>, before: Set<String>, after: Set<String>): Set<String> {
    val beforeSet = caseInsensitiveTagSet(before)
    val afterSet = caseInsensitiveTagSet(after)
    val added = caseInsensitiveTagSet(afterSet).apply { removeAll(beforeSet) }
    val removed = caseInsensitiveTagSet(beforeSet).apply { removeAll(afterSet) }
    return caseInsensitiveTagSet(existing).apply { addAll(added); removeAll(removed) }
}

private fun caseInsensitiveTagSet(source: Collection<String> = emptyList()): TreeSet<String> =
    TreeSet<String>(String.CASE_INSENSITIVE_ORDER).apply { addAll(source) }

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

    /**
     * Every tag in use across the whole library, with how many items carry it -- the same
     * `preferences.all` prefix scan [exportJson] already runs, generalised into something a
     * browsing UI can read instead of only an export sink.
     *
     * Folded case-insensitively, same as [tags]'s own ordering and how `FylzSearch`'s `TagFacet`
     * matches a `tag:` query: two items tagged "Work" and "work" are one tag two items carry, not
     * two the user has to notice are the same thing and reconcile by hand. [TreeMap]'s
     * comparator-equal keys keep whichever spelling was inserted first rather than being replaced
     * by the next one seen, so sorting keys before scanning (same as [exportJson]) makes the
     * display spelling deterministic run to run.
     *
     * `preferences.all` copies the entire SharedPreferences map on every call -- cheap enough for
     * the settings export this scan was lifted from, not cheap enough to call from inside
     * composition on every recomposition. Callers must cache the result themselves (`remember`
     * against whatever version counter they bump on write, the `tagsVersion` idiom) rather than
     * call this fresh every time a composable using it recomposes.
     */
    @Synchronized
    fun allTags(): Map<String, Int> {
        val counts = TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        preferences.all.keys.asSequence()
            .filter { it.startsWith(TAG_PREFIX) }
            .sorted()
            .forEach { key ->
                preferences.getStringSet(key, emptySet()).orEmpty().forEach { tag ->
                    val trimmed = tag.trim()
                    if (trimmed.isNotEmpty()) counts[trimmed] = (counts[trimmed] ?: 0) + 1
                }
            }
        return counts
    }

    /**
     * Every item carrying [tag], case-insensitively -- exactly how `TagFacet` matches a `tag:`
     * query, so a tag browser's own filter and the search box's `tag:<name>` never disagree about
     * which items qualify. Same whole-map scan, and the same caching obligation, as [allTags].
     */
    @Synchronized
    fun itemsWithTag(tag: String): List<Uri> {
        val needle = tag.trim()
        if (needle.isEmpty()) return emptyList()
        return preferences.all.keys.asSequence()
            .filter { it.startsWith(TAG_PREFIX) }
            .filter { key ->
                preferences.getStringSet(key, emptySet()).orEmpty().any { it.equals(needle, ignoreCase = true) }
            }
            .map { Uri.parse(it.removePrefix(TAG_PREFIX)) }
            .sortedBy { it.toString() }
            .toList()
    }

    /**
     * Drops the `tags:` record for each of [removed] -- for a caller that just deleted those
     * items and knows, with certainty, that their tags no longer describe anything real. Nothing
     * in the app calls this yet; deleting an item today leaves its tags behind forever, and this
     * is the hook a deletion path wires up to stop that.
     *
     * Deliberately not [CanvasLayoutStore][io.github.mbaliga.fylz.canvas.CanvasLayoutStore]'s
     * `prune(scope, present)` shape, even though the two stores otherwise share an author: that
     * one is safe to express as "keep only what's `present`" because its records are already
     * scoped to one location's own children. Tags have no such scope -- one URI's `tags:` key can
     * be the only trace of a file the user browsed to once, months ago, from anywhere on the
     * device -- so a caller can only ever safely name what it just, itself, deleted; there is no
     * "present" superset it could assemble instead without silently erasing tags on everything it
     * didn't happen to be looking at.
     *
     * @return how many records were actually present and dropped, for a caller that wants to
     *   report it.
     */
    @Synchronized
    fun pruneOrphanedTags(removed: Collection<Uri>): Int {
        if (removed.isEmpty()) return 0
        val editor = preferences.edit()
        var pruned = 0
        removed.forEach { uri ->
            val key = TAG_PREFIX + uri.toString()
            if (preferences.contains(key)) {
                editor.remove(key)
                pruned += 1
            }
        }
        if (pruned > 0) check(editor.commit()) { "Unable to prune orphaned tags." }
        return pruned
    }

    /** Preserves favorites and tags after a provider returns a new URI for rename or move. */
    @Synchronized
    fun migrateUri(oldUri: Uri, newUri: Uri): Boolean {
        if (oldUri == newUri) return false
        var changed = false

        val current = favorites()
        val favoriteIndex = current.indexOfFirst { it.uri == oldUri }
        if (favoriteIndex >= 0) {
            val migrated = current.toMutableList()
            migrated[favoriteIndex] = migrated[favoriteIndex].copy(uri = newUri)
            preferences.edit().putString(FAVORITES, encodeFavorites(migrated).toString()).commit()
            changed = true
        }

        val oldTagsKey = TAG_PREFIX + oldUri.toString()
        val oldTags = preferences.getStringSet(oldTagsKey, null)
        if (oldTags != null) {
            val newTagsKey = TAG_PREFIX + newUri.toString()
            val merged = oldTags + preferences.getStringSet(newTagsKey, emptySet()).orEmpty()
            preferences.edit()
                .remove(oldTagsKey)
                .putStringSet(newTagsKey, merged)
                .commit()
            changed = true
        }

        return changed
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
