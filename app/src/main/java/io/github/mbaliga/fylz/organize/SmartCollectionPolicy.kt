package io.github.mbaliga.fylz.organize

import java.util.Locale

data class IndexedFileFacts(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val tags: Set<String> = emptySet(),
    val sha256: String? = null,
    val duplicateGroupId: String? = null,
)

enum class MatchMode { ALL, ANY }
enum class TextField { NAME, EXTENSION, MIME, TAG }
enum class TextOperator { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, REGEX }
enum class NumberField { SIZE_BYTES, MODIFIED_AT_MILLIS }
enum class NumberOperator { LESS_THAN, LESS_OR_EQUAL, EQUAL, GREATER_OR_EQUAL, GREATER_THAN, BETWEEN }

sealed interface CollectionPredicate {
    data class Text(
        val field: TextField,
        val operator: TextOperator,
        val value: String,
        val caseSensitive: Boolean = false,
    ) : CollectionPredicate

    data class Number(
        val field: NumberField,
        val operator: NumberOperator,
        val first: Long,
        val second: Long? = null,
    ) : CollectionPredicate

    data class HasTag(val tag: String) : CollectionPredicate
    data object IsDuplicate : CollectionPredicate
    data object HasKnownHash : CollectionPredicate
}

data class SmartCollectionRule(
    val name: String,
    val mode: MatchMode = MatchMode.ALL,
    val predicates: List<CollectionPredicate>,
) {
    init {
        require(name.isNotBlank())
        require(predicates.isNotEmpty())
        require(predicates.size <= MAX_PREDICATES)
    }

    fun matches(file: IndexedFileFacts): Boolean {
        val values = predicates.map { it.matches(file) }
        return if (mode == MatchMode.ALL) values.all(Boolean::identity) else values.any(Boolean::identity)
    }

    private fun CollectionPredicate.matches(file: IndexedFileFacts): Boolean = when (this) {
        is CollectionPredicate.Text -> matchText(file)
        is CollectionPredicate.Number -> matchNumber(file)
        is CollectionPredicate.HasTag -> file.tags.any { it.equals(tag, ignoreCase = true) }
        CollectionPredicate.IsDuplicate -> file.duplicateGroupId != null
        CollectionPredicate.HasKnownHash -> file.sha256?.matches(SHA_256) == true
    }

    private fun CollectionPredicate.Text.matchText(file: IndexedFileFacts): Boolean {
        val candidates = when (field) {
            TextField.NAME -> listOf(file.name)
            TextField.EXTENSION -> listOf(file.name.substringAfterLast('.', ""))
            TextField.MIME -> listOf(file.mimeType)
            TextField.TAG -> file.tags.toList()
        }
        val expected = if (caseSensitive) value else value.lowercase(Locale.ROOT)
        return candidates.any { candidateRaw ->
            val candidate = if (caseSensitive) candidateRaw else candidateRaw.lowercase(Locale.ROOT)
            when (operator) {
                TextOperator.CONTAINS -> candidate.contains(expected)
                TextOperator.EQUALS -> candidate == expected
                TextOperator.STARTS_WITH -> candidate.startsWith(expected)
                TextOperator.ENDS_WITH -> candidate.endsWith(expected)
                TextOperator.REGEX -> runCatching {
                    Regex(value, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(candidateRaw)
                }.getOrDefault(false)
            }
        }
    }

    private fun CollectionPredicate.Number.matchNumber(file: IndexedFileFacts): Boolean {
        val actual = when (field) {
            NumberField.SIZE_BYTES -> file.sizeBytes
            NumberField.MODIFIED_AT_MILLIS -> file.modifiedAtMillis
        } ?: return false
        return when (operator) {
            NumberOperator.LESS_THAN -> actual < first
            NumberOperator.LESS_OR_EQUAL -> actual <= first
            NumberOperator.EQUAL -> actual == first
            NumberOperator.GREATER_OR_EQUAL -> actual >= first
            NumberOperator.GREATER_THAN -> actual > first
            NumberOperator.BETWEEN -> second?.let { actual in minOf(first, it)..maxOf(first, it) } ?: false
        }
    }

    private companion object {
        const val MAX_PREDICATES = 64
        val SHA_256 = Regex("[0-9a-fA-F]{64}")
    }
}

data class RenameTemplate(
    val prefix: String = "",
    val suffix: String = "",
    val find: String = "",
    val replaceWith: String = "",
    val counterStart: Int = 1,
    val counterPadding: Int = 0,
    val includeCounter: Boolean = false,
) {
    init {
        require(counterPadding in 0..12)
    }

    fun apply(originalName: String, index: Int): String {
        val dot = originalName.lastIndexOf('.').takeIf { it > 0 }
        val stem = if (dot == null) originalName else originalName.substring(0, dot)
        val extension = if (dot == null) "" else originalName.substring(dot)
        val replaced = if (find.isEmpty()) stem else stem.replace(find, replaceWith)
        val counter = if (includeCounter) {
            val value = counterStart + index
            if (counterPadding > 0) value.toString().padStart(counterPadding, '0') else value.toString()
        } else ""
        return "$prefix$replaced$counter$suffix$extension"
    }
}

data class RenamePlanItem(val uri: String, val before: String, val after: String, val valid: Boolean, val reason: String? = null)

object BatchRenamePlanner {
    fun plan(files: List<IndexedFileFacts>, template: RenameTemplate): List<RenamePlanItem> {
        val planned = files.mapIndexed { index, file ->
            val after = template.apply(file.name, index).trim()
            val reason = when {
                after.isBlank() -> "The generated name is blank."
                after == "." || after == ".." -> "Dot paths are not valid names."
                '/' in after || '\\' in after -> "Generated names cannot contain path separators."
                after.length > 255 -> "The generated name is longer than 255 characters."
                after.any { it.code in 0..31 || it.code == 127 } -> "The generated name contains control characters."
                else -> null
            }
            RenamePlanItem(file.uri, file.name, after, reason == null, reason)
        }
        val collisions = planned.groupBy { it.after.lowercase(Locale.ROOT) }.filterValues { it.size > 1 }.keys
        return planned.map { item ->
            if (item.after.lowercase(Locale.ROOT) in collisions) item.copy(valid = false, reason = "Generated names collide.") else item
        }
    }
}
