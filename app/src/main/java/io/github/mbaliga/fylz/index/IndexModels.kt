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

/** A user-selected, persisted folder root that local indexing is allowed to traverse. */
data class IndexScope(
    val rootUri: String,
    val displayName: String,
    val enabled: Boolean = true,
)

/** One indexed file or directory discovered under an [IndexScope]. */
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
    /** A short sample of the file's own text (PDF, .docx/.xlsx/.pptx), for [RuleField.TEXT_CONTENT]
     * -- null for every other kind, and for one of these that failed to extract, not just "not yet
     * sampled". See [io.github.mbaliga.fylz.index.ContentTextExtractor]. */
    val textSample: String? = null,
)

/** Status of the local index rebuild job, persisted so the UI survives process death. */
data class IndexState(
    val paused: Boolean = false,
    val lastStartedAtMillis: Long? = null,
    val lastCompletedAtMillis: Long? = null,
    val lastError: String? = null,
    val indexedFiles: Int = 0,
    val truncated: Boolean = false,
)
