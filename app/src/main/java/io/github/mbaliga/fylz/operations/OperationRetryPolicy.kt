package io.github.mbaliga.fylz.operations

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
        itemCount: Int,
        allItemsHaveSourceAndDestination: Boolean,
    ): Boolean =
        state in retryableStates &&
            type in retryableTypes &&
            itemCount > 0 &&
            allItemsHaveSourceAndDestination

    fun canRetry(operation: FileOperation): Boolean = canRetry(
        type = operation.type,
        state = operation.state,
        itemCount = operation.items.size,
        allItemsHaveSourceAndDestination = operation.items.all { item ->
            item.source.toString().isNotBlank() && item.destination != null
        },
    )

    /**
     * Retries always use KEEP_BOTH, regardless of the original conflict policy.
     *
     * A prior attempt may already have produced a complete or partial destination. KEEP_BOTH avoids
     * deleting or replacing that evidence while the user resolves the interrupted operation.
     */
    fun retryConflictPolicy(operation: FileOperation): ConflictPolicy {
        require(canRetry(operation)) { "This operation cannot be retried safely." }
        return ConflictPolicy.KEEP_BOTH
    }
}
