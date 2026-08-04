package io.github.mbaliga.fylz.operations

import org.junit.Assert.assertEquals
import org.junit.Test

class OperationRecoveryPolicyTest {
    @Test
    fun `running operation becomes needs attention after process death`() {
        val operation = FileOperation(
            id = "operation-1",
            type = FileOperationType.COPY,
            items = emptyList(),
            state = OperationState.RUNNING,
            createdAtMillis = 100,
            updatedAtMillis = 110,
        )

        val recovered = OperationRecoveryPolicy.recoverAfterProcessDeath(operation, 200)

        assertEquals(OperationState.NEEDS_ATTENTION, recovered.state)
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
