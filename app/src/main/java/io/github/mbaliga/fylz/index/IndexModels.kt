package io.github.mbaliga.fylz.index

import java.util.UUID

enum class RuleField {
    NAME,
    PATH,
    EXTENSION,
    MIME,
    SIZE,
    MODIFIED,
    TEXT_CONTENT,
    DIRECTORY,
}

enum class RuleOperator {
    CONTAINS,
    EQUALS,
    STARTS_WITH,
    ENDS_WITH,
    GREATER_THAN,
    LESS_THAN,
    BEFORE,
    AFTER,
    IS,
}

data class SmartRule(
    val field: RuleField,
    val operator: RuleOperator,
    val value: String,
    val negate: Boolean = false,
)

enum class RuleJoin { ALL, ANY }

data class SmartCollection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val join: RuleJoin = RuleJoin.ALL,
    val rules: List<SmartRule>,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(name.isNotBlank())
        require(rules.isNotEmpty())
        require(rules.size <= 32)
    }
}
