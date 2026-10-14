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

/**
 * A user-selected, persisted folder root that local indexing is allowed to traverse.
 *
 * [lastMediaStoreGeneration]/[lastScannedAtMillis] back P1.12's incremental-update decision
 * (see `LocalIndexScheduler.shouldRescan`): for a scope that lives on this device's own internal
 * storage, `MediaStore.getGeneration` gives a cheap, volume-wide "has anything changed at all"
 * check -- unchanged since [lastMediaStoreGeneration], the scope's existing rows are still
 * correct and a whole SAF walk is unnecessary work. Null means either "never scanned yet" (always
 * rescan) or "not on a MediaStore-tracked volume" (rescan on connect/on-demand, unchanged from
 * before P1.12 for a removable/USB/third-party scope).
 */
data class IndexScope(
    val rootUri: String,
    val displayName: String,
    val enabled: Boolean = true,
    val lastMediaStoreGeneration: Long? = null,
    val lastScannedAtMillis: Long? = null,
)

/**
 * One indexed file or directory discovered under an [IndexScope].
 *
 * [parentUri]/[path] are P1.12 additions: the pre-P1.12 index kept neither, so a [RuleField.PATH]
 * rule degraded to a name-only match (see `SmartCollectionEngine`'s own former note on this) and
 * the new FTS table's `path` column would have had nothing real to search. [path] is relative to
 * the owning [IndexScope]'s own root, slash-separated, with no leading or trailing slash (empty
 * for a direct child of the scope root).
 *
 * [textSnippet] is also new: a capped read of a text-previewable file's own content (see
 * `LocalIndexScheduler.readTextSnippet`), letting the FTS table's `text` column match file
 * CONTENTS, not just metadata. Null for anything not classified as text, including every
 * directory.
 */
data class IndexedFile(
    val uri: String,
    val rootUri: String,
    val parentUri: String?,
    val path: String,
    val name: String,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val directory: Boolean,
    val tags: Set<String> = emptySet(),
    val textSnippet: String? = null,
    val indexedAtMillis: Long = System.currentTimeMillis(),
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
