package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.ItemRef
import io.github.mbaliga.fylz.core.model.VersionStamp
import java.util.UUID

enum class FileOperationType {
    COPY,
    MOVE,
    RECYCLE,
    RESTORE,
    PERMANENT_DELETE,
    RENAME,
    CREATE_DIRECTORY,
    CREATE_FILE,
    ARCHIVE,
    EXTRACT,
}

enum class OperationState {
    QUEUED,
    PREFLIGHT,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NEEDS_ATTENTION,
}

enum class ConflictPolicy {
    ASK,
    KEEP_BOTH,
    REPLACE,
    SKIP,
}

/**
 * One item within a [FileOperation].
 *
 * [source] and [destination] are [ItemRef] (WP-1.1), not `android.net.Uri` — this is the
 * module boundary that makes `core-operations` pure-JVM. The Android layer
 * (`app/operations/FileOperationService.kt` and its siblings) converts at the edge, via
 * `app/storage/ItemRefs.kt`'s adapter, exactly where it already talks to `DocumentFile`.
 *
 * Both fields still change meaning across an item's lifecycle exactly as they did before this
 * move — [source] can point at a document that no longer exists the instant a move or rename
 * succeeds, and [destination] starts as "the folder to write into" and becomes "the file that
 * was written" once one exists. That is a pre-existing design property this migration
 * preserves, not one it introduces or resolves.
 */
data class OperationItem(
    val id: String = UUID.randomUUID().toString(),
    val source: ItemRef,
    val destination: ItemRef? = null,
    val displayName: String,
    val expectedBytes: Long? = null,
    val completedBytes: Long = 0,
    val state: OperationState = OperationState.QUEUED,
    val errorCode: String? = null,
    /**
     * Version evidence captured at the moment a MOVE's source delete first failed (schema v3):
     * [sourceStamp] is the still-present source, [destinationStamp] the just-verified committed
     * destination. Consumed by [MoveCleanupPolicy] when cleanup later retries the delete. Null
     * on every pre-v3 record and on non-MOVE items — null decides nothing on its own; it routes
     * the policy to [MoveCleanupPolicy.Decision.InsufficientEvidence].
     */
    val sourceStamp: VersionStamp? = null,
    val destinationStamp: VersionStamp? = null,
)

data class FileOperation(
    val id: String = UUID.randomUUID().toString(),
    val type: FileOperationType,
    val items: List<OperationItem>,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val state: OperationState = OperationState.QUEUED,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    val totalBytes: Long? = items.mapNotNull { it.expectedBytes }.takeIf { it.size == items.size }?.sum()
    val completedBytes: Long = items.sumOf { it.completedBytes }
    val progress: Float? = totalBytes?.takeIf { it > 0 }?.let {
        (completedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    }
}

/** Pure state rules shared by the Android journal and JVM tests. */
object OperationRecoveryPolicy {
    // QUEUED belongs here even though no service persists a QUEUED *operation*: the transfer
    // path's first durable write is a PREFLIGHT operation whose ITEMS are QUEUED, and a queued
    // item inside a crashed operation was interrupted before it began — leaving it QUEUED after
    // recovery reads as "still waiting" inside an operation that will never run, with no error
    // code to explain it. (Found by the WP-0.6 crash-injection suite on its first run.)
    private val interruptedStates = setOf(
        OperationState.QUEUED,
        OperationState.PREFLIGHT,
        OperationState.RUNNING,
        OperationState.PAUSED,
    )

    fun recoverAfterProcessDeath(
        operation: FileOperation,
        recoveredAtMillis: Long,
    ): FileOperation {
        if (operation.state !in interruptedStates) return operation
        return operation.copy(
            state = OperationState.NEEDS_ATTENTION,
            items = operation.items.map { item ->
                if (item.state in interruptedStates) {
                    item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = "PROCESS_INTERRUPTED")
                } else {
                    item
                }
            },
            updatedAtMillis = recoveredAtMillis,
        )
    }

    fun isTerminal(state: OperationState): Boolean = state in setOf(
        OperationState.SUCCEEDED,
        OperationState.FAILED,
        OperationState.CANCELLED,
        OperationState.NEEDS_ATTENTION,
    )
}
