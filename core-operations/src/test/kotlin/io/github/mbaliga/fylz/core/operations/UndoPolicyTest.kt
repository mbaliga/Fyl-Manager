package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.ItemRef
import io.github.mbaliga.fylz.core.operations.UndoPolicy.UndoPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoPolicyTest {

    private fun ref(id: String) = ItemRef("saf", "tree", id)

    private fun item(id: String, destination: String? = "dst-$id", errorCode: String? = null) =
        OperationItem(
            id = id,
            source = ref("src-$id"),
            destination = destination?.let(::ref),
            displayName = id,
            state = OperationState.SUCCEEDED,
            errorCode = errorCode,
        )

    private fun operation(
        type: FileOperationType,
        items: List<OperationItem> = listOf(item("a"), item("b")),
        state: OperationState = OperationState.SUCCEEDED,
        sourceParentRoot: ItemRef? = ref("home-tree"),
        sourceParentSegments: List<String> = listOf("Documents", "Reports"),
        destinationRoot: ItemRef? = ref("dest-tree"),
        undone: Boolean = false,
        updatedAtMillis: Long = 100,
    ) = FileOperation(
        id = "op-$type-$updatedAtMillis",
        type = type,
        items = items,
        state = state,
        createdAtMillis = 50,
        updatedAtMillis = updatedAtMillis,
        sourceParentRoot = sourceParentRoot,
        sourceParentSegments = sourceParentSegments,
        destinationRoot = destinationRoot,
        undone = undone,
    )

    // ── MOVE ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a completed move plans a move back to the recorded home`() {
        val plan = UndoPolicy.plan(operation(FileOperationType.MOVE))
        assertTrue(plan is UndoPlan.MoveBack)
        plan as UndoPlan.MoveBack
        assertEquals(listOf(ref("dst-a"), ref("dst-b")), plan.files)
        assertEquals(ref("home-tree"), plan.toRoot)
        assertEquals(listOf("Documents", "Reports"), plan.toSegments)
    }

    @Test
    fun `a move without a recorded source home is refused with the reason`() {
        val plan = UndoPolicy.plan(operation(FileOperationType.MOVE, sourceParentRoot = null))
        assertTrue(plan is UndoPlan.NotUndoable)
    }

    @Test
    fun `conflict-skipped items are excluded from the move back`() {
        val plan = UndoPolicy.plan(
            operation(
                FileOperationType.MOVE,
                items = listOf(item("a"), item("skip", errorCode = UndoPolicy.SKIPPED_CONFLICT)),
            ),
        ) as UndoPlan.MoveBack
        assertEquals(listOf(ref("dst-a")), plan.files)
    }

    @Test
    fun `an all-skipped operation has nothing to undo`() {
        val plan = UndoPolicy.plan(
            operation(
                FileOperationType.MOVE,
                items = listOf(item("skip", errorCode = UndoPolicy.SKIPPED_CONFLICT)),
            ),
        )
        assertTrue(plan is UndoPlan.NotUndoable)
    }

    @Test
    fun `a move item missing its destination refuses rather than part-undoing`() {
        val plan = UndoPolicy.plan(
            operation(FileOperationType.MOVE, items = listOf(item("a"), item("b", destination = null))),
        )
        assertTrue(plan is UndoPlan.NotUndoable)
    }

    // ── COPY and RECYCLE ─────────────────────────────────────────────────────────────

    @Test
    fun `a completed copy plans recycling the copies in the destination tree`() {
        val plan = UndoPolicy.plan(operation(FileOperationType.COPY)) as UndoPlan.RecycleCopies
        assertEquals(listOf(ref("dst-a"), ref("dst-b")), plan.files)
        assertEquals(ref("dest-tree"), plan.inRoot)
    }

    @Test
    fun `a completed recycle plans restoring the originals`() {
        val plan = UndoPolicy.plan(operation(FileOperationType.RECYCLE)) as UndoPlan.RestoreRecycled
        assertEquals(listOf(ref("src-a"), ref("src-b")), plan.originals)
    }

    // ── Refusals ─────────────────────────────────────────────────────────────────────

    @Test
    fun `incomplete, undone, and permanent operations are refused`() {
        assertTrue(
            UndoPolicy.plan(operation(FileOperationType.MOVE, state = OperationState.NEEDS_ATTENTION))
                is UndoPlan.NotUndoable,
        )
        assertTrue(UndoPolicy.plan(operation(FileOperationType.MOVE, undone = true)) is UndoPlan.NotUndoable)
        assertTrue(UndoPolicy.plan(operation(FileOperationType.PERMANENT_DELETE)) is UndoPlan.NotUndoable)
        assertTrue(UndoPolicy.plan(operation(FileOperationType.RESTORE)) is UndoPlan.NotUndoable)
        assertTrue(UndoPolicy.plan(operation(FileOperationType.RENAME)) is UndoPlan.NotUndoable)
    }

    // ── lastUndoable ─────────────────────────────────────────────────────────────────

    @Test
    fun `last undoable passes over refused heads to an undoable operation beneath`() {
        val undoableOld = operation(FileOperationType.COPY, updatedAtMillis = 100)
        val refusedNewer = operation(FileOperationType.PERMANENT_DELETE, updatedAtMillis = 200)
        val undoneNewest = operation(FileOperationType.MOVE, undone = true, updatedAtMillis = 300)
        assertEquals(
            undoableOld.id,
            UndoPolicy.lastUndoable(listOf(undoableOld, refusedNewer, undoneNewest))?.id,
        )
    }

    @Test
    fun `last undoable is null when nothing qualifies`() {
        assertNull(UndoPolicy.lastUndoable(listOf(operation(FileOperationType.RENAME))))
        assertNull(UndoPolicy.lastUndoable(emptyList()))
    }

    @Test
    fun `the most recently touched undoable operation wins`() {
        val older = operation(FileOperationType.COPY, updatedAtMillis = 100)
        val newer = operation(FileOperationType.MOVE, updatedAtMillis = 200)
        assertEquals(newer.id, UndoPolicy.lastUndoable(listOf(older, newer))?.id)
    }
}
