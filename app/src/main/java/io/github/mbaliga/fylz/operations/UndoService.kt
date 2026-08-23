package io.github.mbaliga.fylz.operations

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.core.operations.ConflictPolicy
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.core.operations.UndoPolicy
import io.github.mbaliga.fylz.core.operations.UndoPolicy.UndoPlan
import io.github.mbaliga.fylz.storage.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes what [UndoPolicy] decides. No policy lives here: this class looks up the last
 * undoable record, hands it to the policy, and drives the SAME services the original
 * operations went through — an undo is an ordinary journaled operation wearing a purpose, which
 * is also what makes redo emerge (undoing a move journals a move back, itself undoable).
 *
 * Conflict policy for every inverse is KEEP_BOTH, never ASK and never replace: an undo runs
 * without a dialog, and the one thing it must never do is destroy something that appeared at
 * the original location since. If a name is taken, the undone file arrives as a sibling and
 * the outcome message says what happened where.
 */
class UndoService(
    private val context: Context,
    private val journal: OperationJournal,
    private val fileOperations: FileOperationService,
    private val recycleBin: RecycleBinService,
) {

    sealed interface Outcome {
        /** [description] is user-ready: what was undone, in words. */
        data class Undone(val description: String) : Outcome
        data class NothingToUndo(val reason: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Undoes the most recent undoable operation, or says why there is none. */
    suspend fun undoLast(): Outcome {
        val candidate = UndoPolicy.lastUndoable(journal.list())
            ?: return Outcome.NothingToUndo("Nothing to undo.")
        return undo(candidate)
    }

    suspend fun undo(operation: FileOperation): Outcome {
        val plan = UndoPolicy.plan(operation)
        return try {
            when (plan) {
                is UndoPlan.NotUndoable -> Outcome.NothingToUndo(plan.reason)
                is UndoPlan.MoveBack -> {
                    fileOperations.move(
                        sourceUris = plan.files.map { it.toUri() },
                        destinationTreeUri = plan.toRoot.toUri(),
                        conflictPolicy = ConflictPolicy.KEEP_BOTH,
                        destinationPathSegments = plan.toSegments,
                        // The inverse's own Undo fields: it came FROM the original operation's
                        // destination, so undoing the undo (redo) knows where to send it.
                        sourceParentTreeUri = operation.destinationRoot?.toUri(),
                        sourceParentSegments = operation.destinationSegments,
                    )
                    journal.markUndone(operation.id)
                    Outcome.Undone(countOf(plan.files.size, "moved back"))
                }
                is UndoPlan.RecycleCopies -> {
                    val recycleRoot = withContext(Dispatchers.IO) { ensureRecycleRoot(plan.inRoot.toUri()) }
                    plan.files.forEach { file ->
                        recycleBin.recycle(
                            sourceUri = file.toUri(),
                            // The copy's parent is only addressable as root+walk; a bin record
                            // with no original parent asks on restore instead of guessing.
                            originalParentUri = null,
                            recycleRootUri = recycleRoot,
                        )
                    }
                    journal.markUndone(operation.id)
                    Outcome.Undone(countOf(plan.files.size, "recycled (the copies, not the originals)"))
                }
                is UndoPlan.RestoreRecycled -> {
                    val records = recycleBin.records()
                    var restored = 0
                    plan.originals.forEach { original ->
                        val record = records
                            .filter { it.originalUri == original.toUri() }
                            .maxByOrNull { it.recycledAtMillis }
                        if (record != null) {
                            recycleBin.restore(record.itemId, conflictPolicy = ConflictPolicy.KEEP_BOTH)
                            restored += 1
                        }
                    }
                    if (restored == 0) {
                        Outcome.Failed("Those items are no longer in the Recycle Bin.")
                    } else {
                        journal.markUndone(operation.id)
                        val note = if (restored < plan.originals.size) {
                            " (${plan.originals.size - restored} no longer in the bin)"
                        } else ""
                        Outcome.Undone(countOf(restored, "restored") + note)
                    }
                }
            }
        } catch (t: Throwable) {
            // The inverse operation journals its own failure state; this message is the toast.
            Outcome.Failed(t.message ?: "The undo could not complete.")
        }
    }

    /** The `.fylz-trash` convention, same as the browser's own recycle path. */
    private fun ensureRecycleRoot(treeUri: android.net.Uri): android.net.Uri {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Unable to open the destination tree to recycle the copies.")
        val bin = root.findFile(RECYCLE_DIRECTORY)?.takeIf(DocumentFile::isDirectory)
            ?: root.createDirectory(RECYCLE_DIRECTORY)
            ?: error("This provider cannot create a recycle location.")
        return bin.uri
    }

    private fun countOf(count: Int, verb: String): String =
        if (count == 1) "1 item $verb" else "$count items $verb"

    private companion object {
        const val RECYCLE_DIRECTORY = ".fylz-trash"
    }
}
