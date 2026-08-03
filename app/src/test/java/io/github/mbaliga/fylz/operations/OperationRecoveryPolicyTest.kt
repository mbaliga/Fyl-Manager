package io.github.mbaliga.fylz.operations

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationRecoveryPolicyTest {
    @Test
    fun `running operation becomes needs attention after process death`() {
        val operation = FileOperation(
            id = "operation-1",
            type = FileOperationType.COPY,
            items = listOf(
                OperationItem(
                    id = "item-1",
                    source = Uri.EMPTY,
                    displayName = "example.txt",
                    expectedBytes = 10,
                    completedBytes = 4,
                    state = OperationState.RUNNING,
                ),
            ),
            state = OperationState.RUNNING,
            createdAtMillis = 100,
            updatedAtMillis = 110,
        )

        val recovered = OperationRecoveryPolicy.recoverAfterProcessDeath(operation, 200)

        assertEquals(OperationState.NEEDS_ATTENTION, recovered.state)
        assertEquals(OperationState.NEEDS_ATTENTION, recovered.items.single().state)
        assertEquals("PROCESS_INTERRUPTED", recovered.items.single().errorCode)
        assertEquals(200, recovered.updatedAtMillis)
    }

    @Test
    fun `terminal operation remains unchanged`() {
        val operation = FileOperation(
            id = "operation-2",
            type = FileOperationType.MOVE,
            items = emptyList(),
            state = OperationState.SUCCEEDED,
            createdAtMillis = 100,
            updatedAtMillis = 150,
        )

        assertEquals(operation, OperationRecoveryPolicy.recoverAfterProcessDeath(operation, 200))
    }
}
