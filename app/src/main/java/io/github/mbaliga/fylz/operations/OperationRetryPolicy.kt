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

    fun canRetry(operation: FileOperation): Boolean {
        if (operation.state !in retryableStates) return false
        if (operation.type !in retryableTypes) return false
        if (operation.items.isEmpty()) return false
        return operation.items.all { item ->
            item.source.toString().isNotBlank() && item.destination != null
        }
    }

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
