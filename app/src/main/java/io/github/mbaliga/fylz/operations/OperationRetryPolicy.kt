package io.github.mbaliga.fylz.operations

import android.net.Uri

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

    /**
     * Whether a journaled destination URI can be handed back to a transfer verbatim.
     *
     * Only a plain tree URI (or a tree-document URI still pointing at the tree root) replays
     * correctly: `DocumentFile.fromTreeUri` resolves every tree-shaped URI to its TREE root, so
     * replaying a subfolder destination — journaled by a tray paste into a nested folder —
     * would silently land the files in the wrong folder. Those operations refuse retry
     * instead; the tray still holds the items, so redoing the paste is one tap, and
     * conservative refusal is this policy's whole character.
     */
    internal fun isReplayableDestination(pathSegments: List<String>): Boolean {
        if (pathSegments.size < 2 || pathSegments[0] != "tree") return false
        if (pathSegments.size == 2) return true
        return pathSegments.size == 4 &&
            pathSegments[2] == "document" &&
            pathSegments[3] == pathSegments[1]
    }

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

        // A partially completed move may contain final file URIs for cleanup items. Mixing those
        // with replayable destination-tree URIs would risk treating a file as a folder or copying
        // a source twice, so the operation is not replayable as one batch.
        if (incomplete.any { it.errorCode == MOVE_SOURCE_DELETE_PENDING }) return null

        // Transfer replay only — the cleanup branch above never re-copies, so a file-shaped
        // destination is fine THERE; a replayed copy would resolve it to the tree root.
        if (incomplete.any { item -> item.destination?.pathSegments?.let(::isReplayableDestination) == false }) {
            return null
        }

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
        null -> "Retry unavailable"
    }
}
