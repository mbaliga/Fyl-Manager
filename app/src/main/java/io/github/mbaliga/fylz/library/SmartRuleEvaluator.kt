package io.github.mbaliga.fylz.library

import java.util.Locale

data class IndexedFileMetadata(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
    val tags: Set<String> = emptySet(),
)

object SmartRuleEvaluator {
    fun matches(rule: SmartRule, file: IndexedFileMetadata): Boolean {
        if (!rule.enabled) return false
        return when (rule.field) {
            SmartRuleField.NAME -> compareText(file.name, rule.operator, rule.value)
            SmartRuleField.EXTENSION -> compareText(extension(file.name), rule.operator, rule.value.removePrefix("."))
            SmartRuleField.MIME_TYPE -> compareText(file.mimeType, rule.operator, rule.value)
            SmartRuleField.TAG -> file.tags.any { compareText(it, rule.operator, rule.value) }
            SmartRuleField.SIZE_BYTES -> compareNumber(file.sizeBytes, rule.operator, rule.value)
            SmartRuleField.MODIFIED_MILLIS -> compareNumber(file.modifiedMillis, rule.operator, rule.value)
        }
    }

    fun filter(
        files: Iterable<IndexedFileMetadata>,
        rules: Iterable<SmartRule>,
        requireAll: Boolean = true,
    ): List<IndexedFileMetadata> {
        val active = rules.filter(SmartRule::enabled)
        if (active.isEmpty()) return emptyList()
        return files.filter { file ->
            if (requireAll) active.all { matches(it, file) } else active.any { matches(it, file) }
        }
    }

    private fun compareText(actual: String, operator: SmartRuleOperator, expected: String): Boolean {
        val left = actual.lowercase(Locale.ROOT)
        val right = expected.lowercase(Locale.ROOT)
        return when (operator) {
            SmartRuleOperator.CONTAINS -> right in left
            SmartRuleOperator.EQUALS -> left == right
            SmartRuleOperator.STARTS_WITH -> left.startsWith(right)
            SmartRuleOperator.ENDS_WITH -> left.endsWith(right)
            SmartRuleOperator.GREATER_THAN -> left > right
            SmartRuleOperator.LESS_THAN -> left < right
        }
    }

    private fun compareNumber(actual: Long?, operator: SmartRuleOperator, expected: String): Boolean {
        val left = actual ?: return false
        val right = expected.toLongOrNull() ?: return false
        return when (operator) {
            SmartRuleOperator.EQUALS -> left == right
            SmartRuleOperator.GREATER_THAN -> left > right
            SmartRuleOperator.LESS_THAN -> left < right
            SmartRuleOperator.CONTAINS,
            SmartRuleOperator.STARTS_WITH,
            SmartRuleOperator.ENDS_WITH,
            -> false
        }
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
