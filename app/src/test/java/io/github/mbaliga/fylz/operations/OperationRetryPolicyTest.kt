package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRetryPolicyTest {
    private val source = Uri.parse("content://example/source")
    private val destination = Uri.parse("content://example/destination")

    @Test
    fun `failed copy with destination can retry`() {
        val operation = FileOperation(
            type = FileOperationType.COPY,
            items = listOf(
                OperationItem(
                    source = source,
                    destination = destination,
                    displayName = "example.txt",
                    state = OperationState.FAILED,
                ),
            ),
            state = OperationState.FAILED,
        )

        assertTrue(OperationRetryPolicy.canRetry(operation))
        assertEquals(ConflictPolicy.KEEP_BOTH, OperationRetryPolicy.retryConflictPolicy(operation))
    }

    @Test
    fun `destructive operation cannot retry`() {
        val operation = FileOperation(
            type = FileOperationType.PERMANENT_DELETE,
            items = listOf(
                OperationItem(
                    source = source,
                    displayName = "example.txt",
                    state = OperationState.FAILED,
                ),
            ),
            state = OperationState.FAILED,
        )

        assertFalse(OperationRetryPolicy.canRetry(operation))
    }

    @Test
    fun `copy without destination cannot retry`() {
        val operation = FileOperation(
            type = FileOperationType.COPY,
            items = listOf(
                OperationItem(
                    source = source,
                    displayName = "example.txt",
                    state = OperationState.NEEDS_ATTENTION,
                ),
            ),
            state = OperationState.NEEDS_ATTENTION,
        )

        assertFalse(OperationRetryPolicy.canRetry(operation))
    }
}
