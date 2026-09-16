package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.ItemIdentity
import io.github.mbaliga.fylz.core.model.ItemRef

sealed interface OperationRetryPlan {
    data class Transfer(
        val type: FileOperationType,
        val sourceRefs: List<ItemRef>,
        val destinationRef: ItemRef,
        val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    ) : OperationRetryPlan

    data class FinishMoveCleanup(
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
        OperationState.CANCELLED,
        OperationState.NEEDS_ATTENTION,
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

    fun plan(operation: FileOperation): OperationRetryPlan? {
        if (operation.state !in retryableStates) return null
        val incomplete = operation.items.filter { it.state != OperationState.SUCCEEDED }
        if (incomplete.isEmpty()) return null

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

        // A partially completed move may contain final file refs for cleanup items. Mixing those
        // with replayable destination-tree refs would risk treating a file as a folder or copying
        // a source twice, so the operation is not replayable as one batch.
        if (incomplete.any { it.errorCode == MOVE_SOURCE_DELETE_PENDING }) return null

        // Transfer replay only — the cleanup branch above never re-copies, so a file-shaped
        // destination is fine THERE; a replayed copy must land in a location's own root, or it
        // silently writes into the tree root instead of the (untracked) subfolder a tray paste
        // originally resolved. ItemIdentity.isRoot replaces what used to be a raw Uri
        // path-segment inspection here — see its KDoc for the correctness fix that came with it.
        if (incomplete.any { item -> item.destination?.let(ItemIdentity::isRoot) == false }) {
            return null
        }

        val destinationKeys = incomplete.mapNotNull(OperationItem::destination).distinct()
        val eligible = canRetry(
            type = operation.type,
            state = operation.state,
            incompleteItemCount = incomplete.size,
            allIncompleteItemsHaveSourceAndDestination = incomplete.all { it.destination != null },
            incompleteItemsShareDestination = destinationKeys.size == 1,
        )
        if (!eligible) return null

        return OperationRetryPlan.Transfer(
            type = operation.type,
            sourceRefs = incomplete.map(OperationItem::source),
            destinationRef = requireNotNull(incomplete.first().destination),
        )
    }

    fun canRetry(operation: FileOperation): Boolean = plan(operation) != null

    fun actionLabel(operation: FileOperation): String = when (plan(operation)) {
        is OperationRetryPlan.FinishMoveCleanup -> "Finish move"
        is OperationRetryPlan.Transfer -> "Retry unfinished"
        null -> "Retry unavailable"
    }
}
