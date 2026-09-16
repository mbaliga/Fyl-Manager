package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.ItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRetryPolicyTest {

    private fun root(location: String = "t") = ItemRef("file", location, location)
    private fun child(name: String, location: String = "t") = ItemRef("file", location, "$location:$name")

    private fun item(
        name: String,
        state: OperationState,
        destination: ItemRef? = root(),
        errorCode: String? = null,
    ) = OperationItem(source = child(name), destination = destination, displayName = name, state = state, errorCode = errorCode)

    // ── The primitive checks (unchanged shape, ItemRef doesn't touch these) ────────────

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
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `succeeded operation cannot retry`() {
        assertFalse(
            OperationRetryPolicy.canRetry(
                type = FileOperationType.COPY,
                state = OperationState.SUCCEEDED,
                incompleteItemCount = 1,
                allIncompleteItemsHaveSourceAndDestination = true,
                incompleteItemsShareDestination = true,
            ),
        )
    }

    @Test
    fun `mixed move errors cannot be treated as cleanup only`() {
        assertFalse(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING, "COPY_FAILED"),
                allIncompleteItemsHaveDestination = true,
            ),
        )
    }

    @Test
    fun `cleanup retry requires a verified destination ref`() {
        assertFalse(
            OperationRetryPolicy.isMoveCleanupRetry(
                type = FileOperationType.MOVE,
                state = OperationState.NEEDS_ATTENTION,
                incompleteErrorCodes = listOf(OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING),
                allIncompleteItemsHaveDestination = false,
            ),
        )
    }

    // ── plan(): the full decision, now pure-JVM testable (no Uri, no Robolectric needed) ──

    @Test
    fun `retrying an interrupted copy replays only the unfinished items`() {
        val done = item("done.txt", OperationState.SUCCEEDED)
        val live = item("live.txt", OperationState.RUNNING)
        val operation = FileOperation(type = FileOperationType.COPY, items = listOf(done, live), state = OperationState.NEEDS_ATTENTION)

        val plan = OperationRetryPolicy.plan(operation) as OperationRetryPlan.Transfer

        assertEquals(listOf(live.source), plan.sourceRefs)
        assertTrue("a finished item must never be copied again", done.source !in plan.sourceRefs)
        assertEquals(root(), plan.destinationRef)
    }

    @Test
    fun `move with a committed destination offers cleanup and never a re-copy`() {
        val pendingCleanup = item(
            "moved.txt",
            OperationState.NEEDS_ATTENTION,
            destination = child("moved.txt"),
            errorCode = OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING,
        )
        val operation = FileOperation(type = FileOperationType.MOVE, items = listOf(pendingCleanup), state = OperationState.NEEDS_ATTENTION)

        assertEquals(
            OperationRetryPlan.FinishMoveCleanup(operation.id),
            OperationRetryPolicy.plan(operation),
        )
    }

    @Test
    fun `a move mixing cleanup and replay items refuses one-batch retry`() {
        val pendingCleanup = item(
            "moved.txt",
            OperationState.NEEDS_ATTENTION,
            destination = child("moved.txt"),
            errorCode = OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING,
        )
        val interrupted = item("live.txt", OperationState.RUNNING)
        val operation = FileOperation(
            type = FileOperationType.MOVE,
            items = listOf(pendingCleanup, interrupted),
            state = OperationState.NEEDS_ATTENTION,
        )

        assertNull(OperationRetryPolicy.plan(operation))
    }

    @Test
    fun `an interrupted paste into a subfolder refuses replay rather than landing in the root`() {
        // The destination ref names a SUBFOLDER (opaqueItemId != locationId) -- not the
        // location's own root -- so replaying through a fresh copy would resolve to the tree
        // root and silently write into the wrong folder.
        val subfolderPaste = item("live.txt", OperationState.RUNNING, destination = child("nested"))
        val operation = FileOperation(type = FileOperationType.COPY, items = listOf(subfolderPaste), state = OperationState.NEEDS_ATTENTION)

        assertNull(OperationRetryPolicy.plan(operation))
    }

    @Test
    fun `a bare-tree and a tree-document-at-root destination are recognized as the same folder`() {
        // Both spellings of "the tree's own root" collapse to one ItemRef (opaqueItemId ==
        // locationId either way), so two items journaled through either shape still share a
        // destination and retry as one batch -- exactly the latent bug ItemIdentity.isRoot's
        // KDoc describes fixing (raw Uri.toString() equality would have seen these as two
        // different destinations).
        val a = item("a.txt", OperationState.RUNNING, destination = ItemRef("file", "t", "t"))
        val b = item("b.txt", OperationState.RUNNING, destination = ItemRef("file", "t", "t"))
        val operation = FileOperation(type = FileOperationType.COPY, items = listOf(a, b), state = OperationState.NEEDS_ATTENTION)

        val plan = OperationRetryPolicy.plan(operation)
        assertTrue(plan is OperationRetryPlan.Transfer)
        assertEquals(2, (plan as OperationRetryPlan.Transfer).sourceRefs.size)
    }

    @Test
    fun `non-transfer operations interrupted by a crash are never auto-replayable`() {
        val nonTransfer = FileOperationType.entries - setOf(FileOperationType.COPY, FileOperationType.MOVE)
        for (type in nonTransfer) {
            val operation = FileOperation(
                type = type,
                items = listOf(item("x-$type", OperationState.RUNNING)),
                state = OperationState.NEEDS_ATTENTION,
            )
            assertNull("$type must not offer an automatic retry", OperationRetryPolicy.plan(operation))
        }
    }

    @Test
    fun `finished items inside an interrupted operation are excluded from the replay`() {
        val done = item("done.txt", OperationState.SUCCEEDED)
        val skipped = item("skip.txt", OperationState.SUCCEEDED, errorCode = "SKIPPED_CONFLICT")
        val live = item("live.txt", OperationState.RUNNING)
        val operation = FileOperation(type = FileOperationType.COPY, items = listOf(done, skipped, live), state = OperationState.NEEDS_ATTENTION)

        val plan = OperationRetryPolicy.plan(operation) as OperationRetryPlan.Transfer
        assertEquals(listOf(live.source), plan.sourceRefs)
    }
}
