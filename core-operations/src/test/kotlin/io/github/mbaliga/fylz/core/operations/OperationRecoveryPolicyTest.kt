package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.ItemRef
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationRecoveryPolicyTest {

    private fun ref(id: String) = ItemRef("file", "t", id)

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
        // QUEUED counts as interrupted: the transfer path's first durable write is a PREFLIGHT
        // operation with QUEUED items, and anything queued in a crashed process was
        // interrupted before it began.
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
    fun `queued items inside an interrupted operation are marked interrupted too`() {
        // Now that OperationItem carries an ItemRef instead of android.net.Uri, this item-level
        // assertion no longer needs Robolectric -- it moved out of the Android-coupled crash
        // suite (OperationJournalCrashInjectionTest) and into this pure test alongside the
        // operation-level one above.
        val operation = FileOperation(
            id = "operation-4",
            type = FileOperationType.COPY,
            items = listOf(
                OperationItem(id = "item-1", source = ref("a.txt"), displayName = "a.txt", state = OperationState.QUEUED),
            ),
            state = OperationState.PREFLIGHT,
            createdAtMillis = 100,
            updatedAtMillis = 110,
        )

        val recovered = OperationRecoveryPolicy.recoverAfterProcessDeath(operation, 200)

        assertEquals(OperationState.NEEDS_ATTENTION, recovered.state)
        assertEquals(OperationState.NEEDS_ATTENTION, recovered.items.single().state)
        assertEquals("PROCESS_INTERRUPTED", recovered.items.single().errorCode)
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
