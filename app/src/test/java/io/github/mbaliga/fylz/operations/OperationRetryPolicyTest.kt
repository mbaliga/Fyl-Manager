package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Most cases here test the pure [OperationRetryPolicy.canRetry]/[OperationRetryPolicy.isMoveCleanupRetry]
 *  functions directly and need no Android classes at all; the [OperationRetryPolicy.plan] cases
 *  build a real [FileOperation] with [Uri.parse]-backed URIs, which needs Robolectric. */
@RunWith(RobolectricTestRunner::class)
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
    fun `a PARTIAL operation can retry, replaying only its failed items`() {
        assertTrue(
            "P1.7: PARTIAL means some items succeeded and some failed; retrying replays the failed ones",
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.PARTIAL,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `plan for a PARTIAL operation replays exactly the non-succeeded items`() {
        val operation = FileOperation(
            id = "op-partial",
            type = FileOperationType.COPY,
            items = listOf(
                OperationItem(
                    source = Uri.parse("content://fylz/a"),
                    destination = Uri.parse("content://fylz/tree/dest"),
                    displayName = "a.txt",
                    state = OperationState.SUCCEEDED,
                ),
                OperationItem(
                    source = Uri.parse("content://fylz/b"),
                    destination = Uri.parse("content://fylz/tree/dest"),
                    displayName = "b.txt",
                    state = OperationState.FAILED,
                    errorCode = "IOException",
                ),
            ),
            state = OperationState.PARTIAL,
        )

        val plan = OperationRetryPolicy.plan(operation)

        assertTrue(plan is OperationRetryPlan.Transfer)
        assertEquals(listOf(Uri.parse("content://fylz/b")), (plan as OperationRetryPlan.Transfer).sourceUris)
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
}
