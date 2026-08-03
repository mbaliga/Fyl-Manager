package io.github.mbaliga.fylz.index

import java.util.Locale

object SmartCollectionEngine {
    fun matches(file: IndexedFile, collection: SmartCollection): Boolean {
        val outcomes = collection.rules.map { rule ->
            val matched = matches(file, rule)
            if (rule.negate) !matched else matched
        }
        return when (collection.join) {
            RuleJoin.ALL -> outcomes.all(Boolean::booleanValue)
            RuleJoin.ANY -> outcomes.any(Boolean::booleanValue)
        }
    }

    fun filter(
        files: Iterable<IndexedFile>,
        collection: SmartCollection,
        limit: Int = 5_000,
    ): List<IndexedFile> {
        require(limit in 1..50_000)
        return files.asSequence().filter { matches(it, collection) }.take(limit).toList()
    }

    fun matches(file: IndexedFile, rule: SmartRule): Boolean = when (rule.field) {
        RuleField.NAME -> compareText(file.name, rule.operator, rule.value)
        // The local index does not retain a folder-relative path, only the file name and its
        // enclosing scope; PATH rules degrade to a name match rather than silently never matching.
        RuleField.PATH -> compareText(file.name, rule.operator, rule.value)
        RuleField.EXTENSION -> compareText(file.extension, rule.operator, rule.value.trimStart('.'))
        RuleField.MIME -> compareText(file.mimeType, rule.operator, rule.value)
        // No text-content sampling is captured by this index, so a content rule can never match.
        // This fails closed (excludes the file) instead of pretending a sample was inspected.
        RuleField.TEXT_CONTENT -> false
        RuleField.SIZE -> compareLong(file.sizeBytes, rule.operator, parseSize(rule.value))
        RuleField.MODIFIED -> compareLong(file.modifiedAtMillis, rule.operator, rule.value.toLongOrNull())
        RuleField.DIRECTORY -> compareBoolean(file.directory, rule.operator, parseBoolean(rule.value))
    }

    private fun compareText(actual: String, operator: RuleOperator, expected: String): Boolean {
        val a = actual.lowercase(Locale.ROOT)
        val e = expected.lowercase(Locale.ROOT)
        return when (operator) {
            RuleOperator.CONTAINS -> e in a
            RuleOperator.EQUALS, RuleOperator.IS -> a == e
            RuleOperator.STARTS_WITH -> a.startsWith(e)
            RuleOperator.ENDS_WITH -> a.endsWith(e)
            else -> false
        }
    }

    private fun compareLong(actual: Long?, operator: RuleOperator, expected: Long?): Boolean {
        if (actual == null || expected == null) return false
        return when (operator) {
            RuleOperator.EQUALS, RuleOperator.IS -> actual == expected
            RuleOperator.GREATER_THAN, RuleOperator.AFTER -> actual > expected
            RuleOperator.LESS_THAN, RuleOperator.BEFORE -> actual < expected
            else -> false
        }
    }

    private fun compareBoolean(actual: Boolean, operator: RuleOperator, expected: Boolean?): Boolean =
        expected != null && operator in setOf(RuleOperator.IS, RuleOperator.EQUALS) && actual == expected

    fun parseSize(value: String): Long? {
        val normalized = value.trim().lowercase(Locale.ROOT).replace(" ", "")
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)(b|kb|kib|mb|mib|gb|gib|tb|tib)?$").matchEntire(normalized)
            ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val factor = when (match.groupValues[2]) {
            "", "b" -> 1.0
            "kb" -> 1_000.0
            "kib" -> 1_024.0
            "mb" -> 1_000_000.0
            "mib" -> 1_048_576.0
            "gb" -> 1_000_000_000.0
            "gib" -> 1_073_741_824.0
            "tb" -> 1_000_000_000_000.0
            "tib" -> 1_099_511_627_776.0
            else -> return null
        }
        val result = number * factor
        return result.takeIf { it.isFinite() && it in 0.0..Long.MAX_VALUE.toDouble() }?.toLong()
    }

    private fun parseBoolean(value: String): Boolean? = when (value.trim().lowercase(Locale.ROOT)) {
        "true", "yes", "1", "directory", "folder" -> true
        "false", "no", "0", "file" -> false
        else -> null
    }
}
