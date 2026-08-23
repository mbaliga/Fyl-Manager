package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.ItemRef

/**
 * The Undo verb's decision layer: what reversing a journaled operation MEANS, decided from the
 * record alone — pure, so every rule here is testable without a provider, and the executing
 * side (`app/operations/UndoService`) contains no policy at all.
 *
 * The journal is what makes this possible: every operation already records what happened to
 * which items. Undo is therefore not a parallel bookkeeping system — it is a *reading* of the
 * journal, and an operation the journal cannot support reversing is refused with the reason
 * said out loud, never guessed at. The inverse of an undoable operation is itself an ordinary
 * journaled operation, which is what makes redo emerge for free: undoing a MOVE journals a
 * MOVE back (with its own v4 fields), so Ctrl+Z after Ctrl+Z walks the chain.
 *
 * The rules:
 *
 * - **MOVE** → move the landed files back to where the sources lived, addressed exactly the
 *   way the original addressed its destination (tree root + display-name walk, schema v4).
 *   A rename along that walk makes the undo FAIL LOUDLY at execution, the same conservative
 *   behavior the retry policy chose over silently landing files in the tree root.
 * - **COPY** → recycle the created copies. Recycle, never delete: an undone copy must itself
 *   be recoverable, so undo of COPY produces a bin entry, not a gap.
 * - **RECYCLE** → restore the recycled originals (matched through the recycle bin's own
 *   records by original identity; the executor owns that lookup).
 * - **RESTORE, RENAME** → not yet undoable, each for a stated reason rather than a shrug.
 * - **PERMANENT_DELETE** → never undoable; the copy explains rather than apologises.
 *
 * Conflict-skipped items (`SKIPPED_CONFLICT`) changed nothing and are excluded from every
 * plan; an operation whose every item was skipped has nothing to undo.
 */
object UndoPolicy {

    const val SKIPPED_CONFLICT = "SKIPPED_CONFLICT"

    sealed interface UndoPlan {
        /** Move [files] into the folder addressed by [toRoot] + [toSegments]. */
        data class MoveBack(
            val files: List<ItemRef>,
            val toRoot: ItemRef,
            val toSegments: List<String>,
        ) : UndoPlan

        /** Recycle [files]; the bin lives under [inRoot]'s tree. */
        data class RecycleCopies(val files: List<ItemRef>, val inRoot: ItemRef) : UndoPlan

        /** Restore the most recent bin record for each of [originals]. */
        data class RestoreRecycled(val originals: List<ItemRef>) : UndoPlan

        data class NotUndoable(val reason: String) : UndoPlan
    }

    fun plan(operation: FileOperation): UndoPlan {
        if (operation.undone) return UndoPlan.NotUndoable("This operation was already undone.")
        if (operation.state != OperationState.SUCCEEDED) {
            return UndoPlan.NotUndoable("Only completed operations can be undone.")
        }
        val acted = operation.items.filter { it.errorCode != SKIPPED_CONFLICT }
        if (acted.isEmpty()) {
            return UndoPlan.NotUndoable("Every item was skipped; nothing changed.")
        }
        return when (operation.type) {
            FileOperationType.MOVE -> {
                val root = operation.sourceParentRoot
                    ?: return UndoPlan.NotUndoable("This move predates undo support or its sources had no single home.")
                val files = acted.mapNotNull(OperationItem::destination)
                if (files.size != acted.size) {
                    return UndoPlan.NotUndoable("The journal does not hold every moved file's destination.")
                }
                UndoPlan.MoveBack(files, root, operation.sourceParentSegments)
            }
            FileOperationType.COPY -> {
                val root = operation.destinationRoot
                    ?: return UndoPlan.NotUndoable("This copy predates undo support.")
                val files = acted.mapNotNull(OperationItem::destination)
                if (files.size != acted.size) {
                    return UndoPlan.NotUndoable("The journal does not hold every copied file's destination.")
                }
                UndoPlan.RecycleCopies(files, root)
            }
            FileOperationType.RECYCLE -> UndoPlan.RestoreRecycled(acted.map(OperationItem::source))
            FileOperationType.RESTORE ->
                UndoPlan.NotUndoable("A restore is undone by recycling the file again from its folder.")
            FileOperationType.RENAME ->
                UndoPlan.NotUndoable("Renames cannot be undone yet.")
            FileOperationType.PERMANENT_DELETE ->
                UndoPlan.NotUndoable("Permanently deleted files cannot come back; that is what permanent means.")
        }
    }

    /**
     * The operation Ctrl+Z acts on: the most recently touched record whose plan is executable.
     * Records already undone, incomplete, or refused by [plan] are passed over — Undo never
     * gets stuck on an un-undoable head while an undoable operation sits beneath it.
     */
    fun lastUndoable(operations: List<FileOperation>): FileOperation? =
        operations
            .filter { plan(it) !is UndoPlan.NotUndoable }
            .maxByOrNull(FileOperation::updatedAtMillis)
}
