package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRetryPolicyTest {
    @Test
    fun `failed copy with one shared destination can retry`() {
        assertTrue(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.FAILED,
                incompleteItemCount = 2,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `destructive operation cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.PERMANENT_DELETE,
                state = OperationState.FAILED,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `copy without destination cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.NEEDS_ATTENTION,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = false,
                incompleteItemsShareDestination = false,
            ),
        )
    }

    @Test
    fun `items with different destinations cannot retry together`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteItemCount = 2,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = false,
            ),
        )
    }

    @Test
    fun `successful operation cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.MOVE,
                state = OperationState.SUCCEEDED,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `verified move copy uses cleanup retry instead of transfer replay`() {
        assertTrue(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING),
                allIncompleteItemsHaveDestination = true,
            ),
        )
    }

    @Test
    fun `mixed move errors cannot be treated as cleanup only`() {
        assertFalse(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(
                    OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING,
                    "COPY_FAILED",
                ),
                allIncompleteItemsHaveDestination = true,
            ),
        )
    }

    @Test
    fun `cleanup retry requires a verified destination URI`() {
        assertFalse(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING),
                allIncompleteItemsHaveDestination = false,
            ),
        )
    }

    @Test
    fun `only a tree-root destination is replayable`() {
        // Picker-shaped tree URI: replayable.
        assertTrue(OperationRetryPolicy.isReplayableDestination(listOf("tree", "primary")))
        // Tree-document URI still pointing at the tree root: equivalent, replayable.
        assertTrue(
            OperationRetryPolicy.isReplayableDestination(listOf("tree", "primary", "document", "primary")),
        )
        // A subfolder resolved by a tray paste: replay would land in the ROOT — refuse.
        assertFalse(
            OperationRetryPolicy.isReplayableDestination(listOf("tree", "primary", "document", "primary:Sub")),
        )
        // Plain document URI (a cleanup item's final file): not a transfer destination.
        assertFalse(OperationRetryPolicy.isReplayableDestination(listOf("document", "primary:file.txt")))
        assertFalse(OperationRetryPolicy.isReplayableDestination(emptyList()))
    }
}
