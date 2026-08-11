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
    fun `queued operation is interrupted by process death too`() {
        // QUEUED counts as interrupted since the WP-0.6 crash suite found the gap: the transfer
        // path's first durable write is a PREFLIGHT operation with QUEUED items, and anything
        // queued in a crashed process was interrupted before it began. The item-level mapping
        // (QUEUED item → NEEDS_ATTENTION + PROCESS_INTERRUPTED) is pinned in
        // OperationJournalCrashInjectionTest — items carry a Uri, which this pure-JVM test
        // cannot construct.
        val operation = FileOperation(
            id = "operation-3",
            type = FileOperationType.COPY,
            items = emptyList(),
            state = OperationState.QUEUED,
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
