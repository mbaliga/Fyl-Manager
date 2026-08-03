package io.github.mbaliga.fylz.operations

import android.net.Uri

data class OperationRetryPlan(
    val type: FileOperationType,
    val sourceUris: List<Uri>,
    val destinationTreeUri: Uri,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
)

/**
 * Conservative retry rules for durable operation records.
 *
 * Only copy and move can be replayed from journal metadata. Recycle, restore, permanent delete,
 * rename, archive, and extraction require fresh user review because their environment may have
 * changed and replaying them could destroy or duplicate data.
 */
object OperationRetryPolicy {
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

    /**
     * Builds a retry from only unfinished items.
     *
     * Successfully completed items may contain their final file URI rather than the destination
     * folder URI. They are deliberately excluded so a partial transfer is never duplicated and a
     * final file URI is never mistaken for a writable destination tree.
     */
    fun plan(operation: FileOperation): OperationRetryPlan? {
        val incomplete = operation.items.filter { it.state != OperationState.SUCCEEDED }
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

        return OperationRetryPlan(
            type = operation.type,
            sourceUris = incomplete.map(OperationItem::source),
            destinationTreeUri = requireNotNull(incomplete.first().destination),
        )
    }

    fun canRetry(operation: FileOperation): Boolean = plan(operation) != null

    /** Retries always keep both because an earlier attempt may have produced recoverable output. */
    fun retryConflictPolicy(operation: FileOperation): ConflictPolicy {
        require(canRetry(operation)) { "This operation cannot be retried safely." }
        return ConflictPolicy.KEEP_BOTH
    }
}
