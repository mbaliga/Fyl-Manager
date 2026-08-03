package io.github.mbaliga.fylz.index

import java.util.UUID

data class IndexedFileRecord(
    val uri: String,
    val rootUri: String,
    val displayName: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val indexedAtMillis: Long = System.currentTimeMillis(),
    val tags: Set<String> = emptySet(),
    val contentTerms: Set<String> = emptySet(),
    val sha256: String? = null,
)

enum class SmartPredicateField {
    NAME,
    EXTENSION,
    MIME,
    TAG,
    SIZE,
    MODIFIED,
    CONTENT,
}

enum class SmartPredicateOperator {
    CONTAINS,
    EQUALS,
    STARTS_WITH,
    ENDS_WITH,
    GREATER_THAN,
    LESS_THAN,
    BEFORE,
    AFTER,
}

data class SmartPredicate(
    val field: SmartPredicateField,
    val operator: SmartPredicateOperator,
    val value: String,
    val negate: Boolean = false,
)

data class SmartCollection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val predicates: List<SmartPredicate>,
    val matchAll: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(name.isNotBlank())
        require(predicates.isNotEmpty())
        require(predicates.size <= 32)
    }
}

data class IndexSearchQuery(
    val text: String = "",
    val rootUri: String? = null,
    val extensions: Set<String> = emptySet(),
    val mimePrefixes: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val minimumBytes: Long? = null,
    val maximumBytes: Long? = null,
    val modifiedAfterMillis: Long? = null,
    val modifiedBeforeMillis: Long? = null,
    val limit: Int = 500,
) {
    init {
        require(limit in 1..5_000)
    }
}

data class IndexStatus(
    val recordCount: Int,
    val rootCount: Int,
    val lastUpdatedAtMillis: Long?,
    val storageBytes: Long,
)
