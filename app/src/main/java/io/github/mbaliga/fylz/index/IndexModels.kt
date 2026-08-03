package io.github.mbaliga.fylz.index

import java.util.UUID

data class IndexedFile(
    val uri: String,
    val rootUri: String,
    val name: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val directory: Boolean,
    val tags: Set<String> = emptySet(),
    val indexedAtMillis: Long = System.currentTimeMillis(),
)

data class IndexScope(
    val rootUri: String,
    val displayName: String,
    val enabled: Boolean = true,
)

data class IndexState(
    val paused: Boolean = false,
    val lastStartedAtMillis: Long? = null,
    val lastCompletedAtMillis: Long? = null,
    val lastError: String? = null,
    val indexedFiles: Int = 0,
    val truncated: Boolean = false,
)

enum class RuleField { NAME, EXTENSION, MIME, SIZE, MODIFIED, TAG, DIRECTORY }
enum class RuleOperator { CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, GREATER_THAN, LESS_THAN, BEFORE, AFTER, IS }

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
