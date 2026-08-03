package io.github.mbaliga.fylz.index

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

object SmartCollectionPolicy {
    fun matches(collection: SmartCollection, file: IndexedFile): Boolean {
        val results = collection.rules.map { rule ->
            val matched = matches(rule, file)
            if (rule.negate) !matched else matched
        }
        return when (collection.join) {
            RuleJoin.ALL -> results.all { it }
            RuleJoin.ANY -> results.any { it }
        }
    }

    fun matches(rule: SmartRule, file: IndexedFile): Boolean = when (rule.field) {
        RuleField.NAME -> compareText(file.name, rule.operator, rule.value)
        RuleField.EXTENSION -> compareText(file.extension, rule.operator, rule.value.removePrefix("."))
        RuleField.MIME -> compareText(file.mimeType, rule.operator, rule.value)
        RuleField.TAG -> file.tags.any { compareText(it, rule.operator, rule.value) }
        RuleField.DIRECTORY -> rule.operator == RuleOperator.IS &&
            file.directory == rule.value.equals("true", true)
        RuleField.SIZE -> compareNumber(file.sizeBytes, rule.operator, parseSize(rule.value))
        RuleField.MODIFIED -> compareDate(file.modifiedAtMillis, rule.operator, rule.value)
    }

    fun parseSize(value: String): Long? {
        val normalized = value.trim().lowercase(Locale.ROOT).replace(" ", "")
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)(b|kb|kib|mb|mib|gb|gib|tb|tib)?$").matchEntire(normalized)
            ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "kb" -> 1_000L
            "kib" -> 1_024L
            "mb" -> 1_000_000L
            "mib" -> 1_048_576L
            "gb" -> 1_000_000_000L
            "gib" -> 1_073_741_824L
            "tb" -> 1_000_000_000_000L
            "tib" -> 1_099_511_627_776L
            else -> 1L
        }
        return (number * multiplier).takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE }?.toLong()
    }

    private fun compareText(actual: String, operator: RuleOperator, expected: String): Boolean {
        val left = actual.lowercase(Locale.ROOT)
        val right = expected.lowercase(Locale.ROOT)
        return when (operator) {
            RuleOperator.CONTAINS -> right in left
            RuleOperator.EQUALS, RuleOperator.IS -> left == right
            RuleOperator.STARTS_WITH -> left.startsWith(right)
            RuleOperator.ENDS_WITH -> left.endsWith(right)
            else -> false
        }
    }

    private fun compareNumber(actual: Long?, operator: RuleOperator, expected: Long?): Boolean {
        if (actual == null || expected == null) return false
        return when (operator) {
            RuleOperator.EQUALS, RuleOperator.IS -> actual == expected
            RuleOperator.GREATER_THAN -> actual > expected
            RuleOperator.LESS_THAN -> actual < expected
            else -> false
        }
    }

    private fun compareDate(actual: Long?, operator: RuleOperator, expected: String): Boolean {
        if (actual == null) return false
        val target = try {
            expected.trim().toLongOrNull() ?: Instant.parse(expected.trim()).toEpochMilli()
        } catch (_: DateTimeParseException) {
            return false
        }
        return when (operator) {
            RuleOperator.BEFORE, RuleOperator.LESS_THAN -> actual < target
            RuleOperator.AFTER, RuleOperator.GREATER_THAN -> actual > target
            RuleOperator.EQUALS, RuleOperator.IS -> actual == target
            else -> false
        }
    }
}
