package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveDocumentId

sealed interface OperationRetryPlan {
    data class Transfer(
        val type: FileOperationType,
        val sourceUris: List<Uri>,
        val destinationTreeUri: Uri,
        val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    ) : OperationRetryPlan

    data class FinishMoveCleanup(
        val operationId: String,
    ) : OperationRetryPlan

    /**
     * M3.4: an EXTRACT operation with a plan is retried by **re-claiming the same operation** --
     * `OperationJournal.retryExtract` moves it and its unfinished items back to `QUEUED` in one
     * transaction and `OperationRunner.enqueueExtract` runs it again -- where a copy retry is a
     * new operation over the unfinished sources.
     */
    data class ReclaimExtract(
        val operationId: String,
    ) : OperationRetryPlan
}

/**
 * Conservative retry rules for durable operation records.
 *
 * A transfer retry may replay only unfinished copy/move items whose destination is still the
 * original destination tree. A move whose verified destination already exists uses a separate
 * cleanup action that attempts only to remove the original source and can never copy again.
 */
object OperationRetryPolicy {
    const val MOVE_SOURCE_DELETE_PENDING = "MOVE_SOURCE_DELETE_PENDING"

    private val retryableStates = setOf(
        OperationState.FAILED,
        // P1.7: a PARTIAL operation's own incomplete items (state != SUCCEEDED) are exactly its
        // failed items, so the existing plan()/replay-incomplete-items logic below already IS
        // "Retry failed items" for this state -- no separate plan type needed.
        OperationState.PARTIAL,
        OperationState.CANCELLED,
        OperationState.NEEDS_ATTENTION,
        OperationState.INTERRUPTED,
    )

    private val retryableTypes = setOf(
        FileOperationType.COPY,
        FileOperationType.MOVE,
    )

    internal fun canRetry(
        type: FileOperationType,
        state: OperationState,
        incompleteItemCount: Int,
        allIncompleteItemsHaveSourceAndDestination: Boolean,
        incompleteItemsShareDestination: Boolean,
    ): Boolean =
        state in retryableStates &&
            type in retryableTypes &&
            incompleteItemCount > 0 &&
            allIncompleteItemsHaveSourceAndDestination &&
            incompleteItemsShareDestination

    internal fun isMoveCleanupRetry(
        type: FileOperationType,
        state: OperationState,
        incompleteErrorCodes: List<String?>,
        allIncompleteItemsHaveDestination: Boolean,
    ): Boolean =
        type == FileOperationType.MOVE &&
            state in retryableStates &&
            incompleteErrorCodes.isNotEmpty() &&
            allIncompleteItemsHaveDestination &&
            incompleteErrorCodes.all { it == MOVE_SOURCE_DELETE_PENDING }

    /**
     * M3.4: a planned extraction is recognisable from its row alone -- an EXTRACT with a
     * destination whose every item's source is an archive document; the legacy zip4j path
     * (`ArchiveService.extractZip`) writes plain file sources and no destination. The DAO's
     * `retryExtract` still checks that the plan row exists before anything moves.
     */
    fun isPlannedExtract(operation: FileOperation): Boolean =
        operation.type == FileOperationType.EXTRACT &&
            operation.destination != null &&
            operation.items.isNotEmpty() &&
            operation.items.all { ArchiveDocumentId.isArchiveUri(it.source) }

    fun plan(operation: FileOperation): OperationRetryPlan? {
        if (operation.state !in retryableStates) return null
        val incomplete = operation.items.filter { it.state != OperationState.SUCCEEDED }
        if (incomplete.isEmpty()) return null
        if (isPlannedExtract(operation)) return OperationRetryPlan.ReclaimExtract(operation.id)

        if (
            isMoveCleanupRetry(
                type = operation.type,
                state = operation.state,
                incompleteErrorCodes = incomplete.map(OperationItem::errorCode),
                allIncompleteItemsHaveDestination = incomplete.all { it.destination != null },
            )
        ) {
            return OperationRetryPlan.FinishMoveCleanup(operation.id)
        }

        // A partially completed move may contain final file URIs for cleanup items. Mixing those
        // with replayable destination-tree URIs would risk treating a file as a folder or copying
        // a source twice, so the operation is not replayable as one batch.
        if (incomplete.any { it.errorCode == MOVE_SOURCE_DELETE_PENDING }) return null

        val destinationKeys = incomplete.mapNotNull { it.destination?.toString() }.distinct()
        val eligible = canRetry(
            type = operation.type,
            state = operation.state,
            incompleteItemCount = incomplete.size,
            allIncompleteItemsHaveSourceAndDestination = incomplete.all { item ->
                item.source.toString().isNotBlank() && item.destination != null
            },
            incompleteItemsShareDestination = destinationKeys.size == 1,
        )
        if (!eligible) return null

        return OperationRetryPlan.Transfer(
            type = operation.type,
            sourceUris = incomplete.map(OperationItem::source),
            destinationTreeUri = requireNotNull(incomplete.first().destination),
        )
    }

    fun canRetry(operation: FileOperation): Boolean = plan(operation) != null

    fun actionLabel(operation: FileOperation): String = when (plan(operation)) {
        is OperationRetryPlan.FinishMoveCleanup -> "Finish move"
        is OperationRetryPlan.Transfer -> "Retry unfinished"
        is OperationRetryPlan.ReclaimExtract -> "Retry extraction"
        null -> "Retry unavailable"
    }
}
