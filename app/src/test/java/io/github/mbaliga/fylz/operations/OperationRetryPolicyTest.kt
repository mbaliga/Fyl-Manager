package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRetryPolicyTest {
    @Test
    fun `failed copy with complete metadata can retry`() {
        assertTrue(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.FAILED,
                itemCount = 1,
                allItemsHaveSourceAndDestination = true,
            ),
        )
    }

    @Test
    fun `destructive operation cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.PERMANENT_DELETE,
                state = OperationState.FAILED,
                itemCount = 1,
                allItemsHaveSourceAndDestination = true,
            ),
        )
    }

    @Test
    fun `copy without destination cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.NEEDS_ATTENTION,
                itemCount = 1,
                allItemsHaveSourceAndDestination = false,
            ),
        )
    }

    @Test
    fun `successful operation cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.MOVE,
                state = OperationState.SUCCEEDED,
                itemCount = 1,
                allItemsHaveSourceAndDestination = true,
            ),
        )
    }
}
