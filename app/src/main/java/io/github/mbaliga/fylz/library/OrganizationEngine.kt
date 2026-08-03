package io.github.mbaliga.fylz.library

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class IndexedFileRecord(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val extension: String,
    val parentUri: String?,
    val tags: Set<String> = emptySet(),
    val sha256: String? = null,
    val indexedAtMillis: Long = System.currentTimeMillis(),
)

enum class RuleField {
    NAME,
    EXTENSION,
    MIME,
    SIZE,
    MODIFIED,
    TAG,
    PARENT,
}

enum class RuleOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    MATCHES_REGEX,
    GREATER_THAN,
    GREATER_OR_EQUAL,
    LESS_THAN,
    LESS_OR_EQUAL,
    EXISTS,
    NOT_EXISTS,
}

data class FileRulePredicate(
    val field: RuleField,
    val operator: RuleOperator,
    val value: String? = null,
    val caseSensitive: Boolean = false,
)

enum class RuleCombination { ALL, ANY }

data class SmartCollection(
    val id: String,
    val name: String,
    val predicates: List<FileRulePredicate>,
    val combination: RuleCombination = RuleCombination.ALL,
    val sortField: RuleField = RuleField.NAME,
    val descending: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(predicates.isNotEmpty())
        require(predicates.size <= 64)
    }
}

data class PortableLibraryMetadata(
    val schemaVersion: Int = 1,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val tagsByUri: Map<String, Set<String>>,
    val favourites: Set<String>,
    val collections: List<SmartCollection>,
)

object OrganizationEngine {
    fun matches(record: IndexedFileRecord, collection: SmartCollection): Boolean {
        val results = collection.predicates.map { matches(record, it) }
        return when (collection.combination) {
            RuleCombination.ALL -> results.all(Boolean::booleanValue)
            RuleCombination.ANY -> results.any(Boolean::booleanValue)
        }
    }

    fun evaluate(records: Iterable<IndexedFileRecord>, collection: SmartCollection): List<IndexedFileRecord> {
        val matched = records.filter { matches(it, collection) }
        val comparator = comparator(collection.sortField)
        return if (collection.descending) matched.sortedWith(comparator.reversed()) else matched.sortedWith(comparator)
    }

    fun matches(record: IndexedFileRecord, predicate: FileRulePredicate): Boolean {
        val textual = when (predicate.field) {
            RuleField.NAME -> listOf(record.displayName)
            RuleField.EXTENSION -> listOf(record.extension)
            RuleField.MIME -> listOf(record.mimeType)
            RuleField.TAG -> record.tags.toList()
            RuleField.PARENT -> listOfNotNull(record.parentUri)
            RuleField.SIZE, RuleField.MODIFIED -> emptyList()
        }
        return when (predicate.field) {
            RuleField.SIZE -> compareNumber(record.sizeBytes, predicate)
            RuleField.MODIFIED -> compareNumber(record.modifiedAtMillis, predicate)
            else -> compareText(textual, predicate)
        }
    }

    fun encodePortable(value: PortableLibraryMetadata): String = JSONObject().apply {
        put("schemaVersion", value.schemaVersion)
        put("exportedAtMillis", value.exportedAtMillis)
        put("tagsByUri", JSONObject().apply {
            value.tagsByUri.toSortedMap().forEach { (uri, tags) ->
                put(uri, JSONArray(tags.map(::normalizeTag).filter(String::isNotBlank).distinct().sorted()))
            }
        })
        put("favourites", JSONArray(value.favourites.sorted()))
        put("collections", JSONArray().apply {
            value.collections.sortedBy(SmartCollection::name).forEach { collection ->
                put(JSONObject().apply {
                    put("id", collection.id)
                    put("name", collection.name)
                    put("combination", collection.combination.name)
                    put("sortField", collection.sortField.name)
                    put("descending", collection.descending)
                    put("predicates", JSONArray().apply {
                        collection.predicates.forEach { predicate ->
                            put(JSONObject().apply {
                                put("field", predicate.field.name)
                                put("operator", predicate.operator.name)
                                put("value", predicate.value ?: JSONObject.NULL)
                                put("caseSensitive", predicate.caseSensitive)
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    fun decodePortable(serialized: String): PortableLibraryMetadata {
        require(serialized.length <= MAX_IMPORT_CHARS) { "Library metadata import exceeds the safety limit." }
        val root = JSONObject(serialized)
        require(root.getInt("schemaVersion") == 1) { "Unsupported library metadata version." }
        val tagsObject = root.optJSONObject("tagsByUri") ?: JSONObject()
        require(tagsObject.length() <= MAX_TAGGED_URIS) { "Too many tagged files in metadata import." }
        val tags = tagsObject.keys().asSequence().associateWith { uri ->
            require(uri.length <= MAX_URI_CHARS)
            val values = tagsObject.getJSONArray(uri)
            require(values.length() <= MAX_TAGS_PER_FILE)
            buildSet {
                repeat(values.length()) { index ->
                    normalizeTag(values.getString(index)).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        val favouritesArray = root.optJSONArray("favourites") ?: JSONArray()
        require(favouritesArray.length() <= MAX_FAVOURITES)
        val favourites = buildSet {
            repeat(favouritesArray.length()) { index ->
                val uri = favouritesArray.getString(index)
                require(uri.length <= MAX_URI_CHARS)
                add(uri)
            }
        }
        val collectionsArray = root.optJSONArray("collections") ?: JSONArray()
        require(collectionsArray.length() <= MAX_COLLECTIONS)
        val collections = List(collectionsArray.length()) { index ->
            decodeCollection(collectionsArray.getJSONObject(index))
        }
        return PortableLibraryMetadata(
            exportedAtMillis = root.optLong("exportedAtMillis", 0L),
            tagsByUri = tags,
            favourites = favourites,
            collections = collections,
        )
    }

    fun mergePortable(
        local: PortableLibraryMetadata,
        imported: PortableLibraryMetadata,
    ): PortableLibraryMetadata {
        val mergedTags = (local.tagsByUri.keys + imported.tagsByUri.keys).associateWith { uri ->
            (local.tagsByUri[uri].orEmpty() + imported.tagsByUri[uri].orEmpty())
                .map(::normalizeTag).filter(String::isNotBlank).toSet()
        }
        val collections = (local.collections + imported.collections)
            .associateBy(SmartCollection::id).values.sortedBy(SmartCollection::name)
        return PortableLibraryMetadata(
            tagsByUri = mergedTags,
            favourites = local.favourites + imported.favourites,
            collections = collections,
        )
    }

    fun normalizeTag(value: String): String = value.trim().replace(Regex("\\s+"), " ").take(80)

    private fun compareText(values: List<String>, predicate: FileRulePredicate): Boolean {
        val expected = predicate.value.orEmpty()
        val preparedExpected = prepare(expected, predicate.caseSensitive)
        return when (predicate.operator) {
            RuleOperator.EXISTS -> values.any(String::isNotBlank)
            RuleOperator.NOT_EXISTS -> values.none(String::isNotBlank)
            RuleOperator.NOT_EQUALS -> values.none { prepare(it, predicate.caseSensitive) == preparedExpected }
            RuleOperator.EQUALS -> values.any { prepare(it, predicate.caseSensitive) == preparedExpected }
            RuleOperator.CONTAINS -> values.any { prepare(it, predicate.caseSensitive).contains(preparedExpected) }
            RuleOperator.STARTS_WITH -> values.any { prepare(it, predicate.caseSensitive).startsWith(preparedExpected) }
            RuleOperator.ENDS_WITH -> values.any { prepare(it, predicate.caseSensitive).endsWith(preparedExpected) }
            RuleOperator.MATCHES_REGEX -> {
                require(expected.length <= 512) { "Rule regex is too long." }
                val options = if (predicate.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val regex = Regex(expected, options)
                values.any(regex::containsMatchIn)
            }
            else -> false
        }
    }

    private fun compareNumber(actual: Long?, predicate: FileRulePredicate): Boolean {
        return when (predicate.operator) {
            RuleOperator.EXISTS -> actual != null
            RuleOperator.NOT_EXISTS -> actual == null
            else -> {
                val expected = predicate.value?.toLongOrNull() ?: return false
                when (predicate.operator) {
                    RuleOperator.EQUALS -> actual == expected
                    RuleOperator.NOT_EQUALS -> actual != null && actual != expected
                    RuleOperator.GREATER_THAN -> actual != null && actual > expected
                    RuleOperator.GREATER_OR_EQUAL -> actual != null && actual >= expected
                    RuleOperator.LESS_THAN -> actual != null && actual < expected
                    RuleOperator.LESS_OR_EQUAL -> actual != null && actual <= expected
                    else -> false
                }
            }
        }
    }

    private fun comparator(field: RuleField): Comparator<IndexedFileRecord> = when (field) {
        RuleField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER, IndexedFileRecord::displayName)
        RuleField.EXTENSION -> compareBy(String.CASE_INSENSITIVE_ORDER, IndexedFileRecord::extension)
        RuleField.MIME -> compareBy(String.CASE_INSENSITIVE_ORDER, IndexedFileRecord::mimeType)
        RuleField.SIZE -> compareBy(nullsLast(), IndexedFileRecord::sizeBytes)
        RuleField.MODIFIED -> compareBy(nullsLast(), IndexedFileRecord::modifiedAtMillis)
        RuleField.TAG -> compareBy { it.tags.sorted().firstOrNull().orEmpty() }
        RuleField.PARENT -> compareBy { it.parentUri.orEmpty() }
    }

    private fun decodeCollection(value: JSONObject): SmartCollection {
        val predicates = value.getJSONArray("predicates")
        require(predicates.length() in 1..64)
        return SmartCollection(
            id = value.getString("id").take(120),
            name = value.getString("name").take(160),
            combination = RuleCombination.valueOf(value.optString("combination", RuleCombination.ALL.name)),
            sortField = RuleField.valueOf(value.optString("sortField", RuleField.NAME.name)),
            descending = value.optBoolean("descending", false),
            predicates = List(predicates.length()) { index ->
                val predicate = predicates.getJSONObject(index)
                FileRulePredicate(
                    field = RuleField.valueOf(predicate.getString("field")),
                    operator = RuleOperator.valueOf(predicate.getString("operator")),
                    value = if (predicate.isNull("value")) null else predicate.getString("value").take(2_048),
                    caseSensitive = predicate.optBoolean("caseSensitive", false),
                )
            },
        )
    }

    private fun prepare(value: String, caseSensitive: Boolean): String =
        if (caseSensitive) value else value.lowercase(Locale.ROOT)

    private const val MAX_IMPORT_CHARS = 16 * 1024 * 1024
    private const val MAX_TAGGED_URIS = 250_000
    private const val MAX_TAGS_PER_FILE = 128
    private const val MAX_FAVOURITES = 250_000
    private const val MAX_COLLECTIONS = 10_000
    private const val MAX_URI_CHARS = 16_384
}
