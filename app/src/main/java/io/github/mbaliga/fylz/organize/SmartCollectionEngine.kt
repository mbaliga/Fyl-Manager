package io.github.mbaliga.fylz.organize

import java.util.Locale

data class IndexedFileRecord(
    val uri: String,
    val name: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val indexedAtMillis: Long,
    val parentUri: String,
    val rootUri: String,
    val tags: Set<String> = emptySet(),
    val contentTokens: Set<String> = emptySet(),
    val sha256: String? = null,
)

enum class TextOperator { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, REGEX }
enum class NumberOperator { LESS_THAN, LESS_OR_EQUAL, EQUAL, GREATER_OR_EQUAL, GREATER_THAN }
enum class DateOperator { BEFORE, AFTER }
enum class SetOperator { CONTAINS_ANY, CONTAINS_ALL, CONTAINS_NONE }

enum class RuleField {
    NAME,
    EXTENSION,
    MIME_TYPE,
    PATH,
    SIZE_BYTES,
    MODIFIED_AT,
    TAGS,
    CONTENT_TOKENS,
}

sealed interface CollectionRule {
    data class Text(
        val field: RuleField,
        val operator: TextOperator,
        val value: String,
        val caseSensitive: Boolean = false,
    ) : CollectionRule

    data class Number(
        val field: RuleField,
        val operator: NumberOperator,
        val value: Long,
    ) : CollectionRule

    data class Date(
        val field: RuleField = RuleField.MODIFIED_AT,
        val operator: DateOperator,
        val epochMillis: Long,
    ) : CollectionRule

    data class SetMatch(
        val field: RuleField,
        val operator: SetOperator,
        val values: Set<String>,
    ) : CollectionRule

    data class All(val rules: List<CollectionRule>) : CollectionRule
    data class Any(val rules: List<CollectionRule>) : CollectionRule
    data class Not(val rule: CollectionRule) : CollectionRule
}

data class SmartCollection(
    val id: String,
    val name: String,
    val rule: CollectionRule,
    val sort: SmartSort = SmartSort.MODIFIED_DESC,
)

enum class SmartSort { NAME_ASC, NAME_DESC, MODIFIED_ASC, MODIFIED_DESC, SIZE_ASC, SIZE_DESC }

object SmartCollectionEngine {
    fun matches(record: IndexedFileRecord, rule: CollectionRule): Boolean = when (rule) {
        is CollectionRule.All -> rule.rules.all { matches(record, it) }
        is CollectionRule.Any -> rule.rules.any { matches(record, it) }
        is CollectionRule.Not -> !matches(record, rule.rule)
        is CollectionRule.Text -> matchText(record, rule)
        is CollectionRule.Number -> matchNumber(record, rule)
        is CollectionRule.Date -> matchDate(record, rule)
        is CollectionRule.SetMatch -> matchSet(record, rule)
    }

    fun evaluate(records: Iterable<IndexedFileRecord>, collection: SmartCollection): List<IndexedFileRecord> {
        val result = records.filter { matches(it, collection.rule) }
        return when (collection.sort) {
            SmartSort.NAME_ASC -> result.sortedBy { it.name.lowercase(Locale.ROOT) }
            SmartSort.NAME_DESC -> result.sortedByDescending { it.name.lowercase(Locale.ROOT) }
            SmartSort.MODIFIED_ASC -> result.sortedBy { it.modifiedAtMillis ?: Long.MIN_VALUE }
            SmartSort.MODIFIED_DESC -> result.sortedByDescending { it.modifiedAtMillis ?: Long.MIN_VALUE }
            SmartSort.SIZE_ASC -> result.sortedBy { it.sizeBytes ?: Long.MIN_VALUE }
            SmartSort.SIZE_DESC -> result.sortedByDescending { it.sizeBytes ?: Long.MIN_VALUE }
        }
    }

    private fun matchText(record: IndexedFileRecord, rule: CollectionRule.Text): Boolean {
        val candidate = when (rule.field) {
            RuleField.NAME -> record.name
            RuleField.EXTENSION -> record.extension
            RuleField.MIME_TYPE -> record.mimeType
            RuleField.PATH -> "${record.parentUri}/${record.name}"
            else -> return false
        }
        val left = if (rule.caseSensitive) candidate else candidate.lowercase(Locale.ROOT)
        val right = if (rule.caseSensitive) rule.value else rule.value.lowercase(Locale.ROOT)
        return when (rule.operator) {
            TextOperator.CONTAINS -> right in left
            TextOperator.EQUALS -> left == right
            TextOperator.STARTS_WITH -> left.startsWith(right)
            TextOperator.ENDS_WITH -> left.endsWith(right)
            TextOperator.REGEX -> runCatching {
                Regex(rule.value, if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(candidate)
            }.getOrDefault(false)
        }
    }

    private fun matchNumber(record: IndexedFileRecord, rule: CollectionRule.Number): Boolean {
        val candidate = when (rule.field) {
            RuleField.SIZE_BYTES -> record.sizeBytes
            RuleField.MODIFIED_AT -> record.modifiedAtMillis
            else -> null
        } ?: return false
        return when (rule.operator) {
            NumberOperator.LESS_THAN -> candidate < rule.value
            NumberOperator.LESS_OR_EQUAL -> candidate <= rule.value
            NumberOperator.EQUAL -> candidate == rule.value
            NumberOperator.GREATER_OR_EQUAL -> candidate >= rule.value
            NumberOperator.GREATER_THAN -> candidate > rule.value
        }
    }

    private fun matchDate(record: IndexedFileRecord, rule: CollectionRule.Date): Boolean {
        val candidate = record.modifiedAtMillis ?: return false
        return when (rule.operator) {
            DateOperator.BEFORE -> candidate < rule.epochMillis
            DateOperator.AFTER -> candidate > rule.epochMillis
        }
    }

    private fun matchSet(record: IndexedFileRecord, rule: CollectionRule.SetMatch): Boolean {
        val candidate = when (rule.field) {
            RuleField.TAGS -> record.tags
            RuleField.CONTENT_TOKENS -> record.contentTokens
            else -> return false
        }.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val expected = rule.values.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        return when (rule.operator) {
            SetOperator.CONTAINS_ANY -> candidate.any(expected::contains)
            SetOperator.CONTAINS_ALL -> candidate.containsAll(expected)
            SetOperator.CONTAINS_NONE -> candidate.none(expected::contains)
        }
    }
}
