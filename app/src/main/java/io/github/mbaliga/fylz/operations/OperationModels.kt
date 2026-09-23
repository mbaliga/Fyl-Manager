package io.github.mbaliga.fylz.operations

import android.net.Uri
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
    PDF,
}

enum class OperationState {
    QUEUED,
    PREFLIGHT,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    /** Some items succeeded and some failed (P1.7) -- every item was attempted; this is not a
     * failure that aborted the batch partway through, which now never happens. [FAILED] means
     * none of the items succeeded; [SUCCEEDED] means all of them did. */
    PARTIAL,
    CANCELLED,
    NEEDS_ATTENTION,
    /** A dead process left this item mid-copy (P0.6); its staged partial write, if any, was
     * deleted by [OperationRunner.recover] and it is safely retryable from scratch. */
    INTERRUPTED,
    /** WorkManager stopped the transfer's worker (P1.2) -- Android 15's six-hour daily `dataSync`
     * budget, memory pressure, or the app being force-stopped -- rather than the user pausing it.
     * [TransferWorker] requests a retry with backoff when this happens. */
    PAUSED_BY_SYSTEM,
}

enum class ConflictPolicy {
    ASK,
    KEEP_BOTH,
    REPLACE,
    /** Replace only when the source is newer than the existing destination item (P1.6) --
     * otherwise the same outcome as [SKIP]. Meaningless, and never selected, for a directory:
     * there is no single "modified" instant for a whole tree to compare. */
    REPLACE_IF_NEWER,
    SKIP,
}

data class OperationItem(
    val id: String = UUID.randomUUID().toString(),
    val source: Uri,
    val destination: Uri? = null,
    val displayName: String,
    val expectedBytes: Long? = null,
    val completedBytes: Long = 0,
    val state: OperationState = OperationState.QUEUED,
    val errorCode: String? = null,
    /** The `.fylz-part-*` document this item is (or was) writing to before verification and the
     * final rename (P0.6). Recorded before the first byte is written, so [OperationRunner.recover]
     * can delete exactly this document -- and nothing else -- for an item a dead process left
     * mid-copy. Cleared once the item reaches a terminal state. */
    val stagingUri: Uri? = null,
    /** The item's actual final document, once it has one (P1.1 storage; not yet populated by any
     * write path -- a later task wires it). */
    val finalUri: Uri? = null,
    /** The transferred content's SHA-256, populated by [FileOperationService.transfer] only when
     * [VerifySettings]/[shouldVerify] call for it (P1.4) -- null otherwise, including for every
     * directory item, which has no single byte stream for one hash to describe. */
    val sha256: String? = null,
)

data class FileOperation(
    val id: String = UUID.randomUUID().toString(),
    val type: FileOperationType,
    val items: List<OperationItem>,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val state: OperationState = OperationState.QUEUED,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    /** The operation's overall target folder, once callers set one (P1.1 storage; distinct from
     * each item's own [OperationItem.destination] -- not yet populated by any write path). */
    val destination: Uri? = null,
) {
    val totalBytes: Long? = items.mapNotNull { it.expectedBytes }.takeIf { it.size == items.size }?.sum()
    val completedBytes: Long = items.sumOf { it.completedBytes }
    val progress: Float? = totalBytes?.takeIf { it > 0 }?.let {
        (completedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    }
}

/** Pure state rules shared by the Android journal and JVM tests. */
object OperationRecoveryPolicy {
    private val interruptedStates = setOf(
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
        OperationState.PARTIAL,
        OperationState.CANCELLED,
        OperationState.NEEDS_ATTENTION,
        OperationState.INTERRUPTED,
    )
}
