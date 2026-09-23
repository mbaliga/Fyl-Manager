package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class DuplicateGroup(val sha256: String, val sizeBytes: Long, val items: List<Uri>)

data class BatchRenamePlan(val source: Uri, val oldName: String, val newName: String)

data class BatchRenameValidation(
    val valid: Boolean,
    val message: String? = null,
)

/** Pure validation rules used by the UI, execution engine, and JVM tests. */
object BatchRenamePolicy {
    fun validate(plans: List<BatchRenamePlan>): BatchRenameValidation {
        if (plans.isEmpty()) return BatchRenameValidation(false, "Choose at least one item.")
        if (plans.map { it.source.toString() }.toSet().size != plans.size) {
            return BatchRenameValidation(false, "The rename plan contains the same item more than once.")
        }
        val invalid = plans.firstOrNull { !isValidName(it.newName) }
        if (invalid != null) {
            return BatchRenameValidation(false, "Invalid target name: ${invalid.newName}")
        }
        val duplicateTarget = plans.groupBy { it.newName.lowercase() }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (duplicateTarget != null) {
            return BatchRenameValidation(false, "Multiple items would be named ${duplicateTarget.value.first().newName}.")
        }
        return BatchRenameValidation(true)
    }

    fun isValidName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            trimmed != "." &&
            trimmed != ".." &&
            '/' !in trimmed &&
            '\u0000' !in trimmed
    }
}

class FileTools(
    private val context: Context,
    private val journal: OperationJournal = OperationJournal(context),
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** [current] is reassigned after every successful rename ([DocNode.rename] returns a new,
     * immutable node, possibly with a changed uri) so rollback always operates on the item's
     * actual, current location -- whichever of [BatchRenamePlan.oldName], [temporaryName] or
     * [BatchRenamePlan.newName] that happens to be. */
    private data class RenameStep(
        val plan: BatchRenamePlan,
        var current: DocNode,
        val temporaryName: String,
        var staged: Boolean = false,
        var finalized: Boolean = false,
    )

    suspend fun findDuplicates(
        uris: List<Uri>,
        maxBytesPerFile: Long = 2L * 1024L * 1024L * 1024L,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { uri ->
            DocNode.load(resolver, uri)
                ?.takeIf { !it.isDirectory && (it.size ?: -1) in 0..maxBytesPerFile }
                ?.let { uri to (it.size ?: 0) }
        }
        files.groupBy { it.second }
            .filterValues { it.size > 1 }
            .values
            .flatMap { sameSize ->
                sameSize.groupBy { (uri, _) -> sha256(uri) }
                    .filterValues { it.size > 1 }
                    .map { (hash, items) ->
                        DuplicateGroup(hash, items.first().second, items.map { it.first })
                    }
            }
            .sortedByDescending(DuplicateGroup::sizeBytes)
    }

    /**
     * Pure and non-destructive: never touches storage, so a caller can call this on every
     * keystroke of a prefix field for a live preview. Still throws [IllegalArgumentException] on
     * a plan the prefix can't legally produce (P0.4) -- the caller decides what a thrown message
     * means to the user (an inline dialog error here), rather than this crashing them.
     */
    fun planBatchRename(
        items: List<Pair<Uri, String>>,
        prefix: String,
        startAt: Int = 1,
        padding: Int = 2,
    ): List<BatchRenamePlan> {
        require(items.isNotEmpty()) { "Choose at least one item." }
        require(prefix.isNotBlank()) { "Enter a prefix." }
        require(startAt >= 0) { "Start must not be negative." }
        require(padding in 1..8) { "Padding must be between 1 and 8." }
        return items.mapIndexed { index, (uri, oldName) ->
            val extension = oldName.substringAfterLast('.', "").takeIf {
                oldName.contains('.') && !oldName.startsWith('.')
            }
            val base = "$prefix${(startAt + index).toString().padStart(padding, '0')}"
            BatchRenamePlan(uri, oldName, if (extension == null) base else "$base.$extension")
        }.also { plans ->
            val validation = BatchRenamePolicy.validate(plans)
            require(validation.valid) { validation.message ?: "Invalid rename plan." }
        }
    }

    /**
     * Executes a rename set in two phases: original names -> unique temporary names -> final names.
     *
     * This supports swaps and cycles without collisions. Any failure triggers a best-effort rollback
     * to the original names. If rollback itself is incomplete, the journal marks the operation as
     * NEEDS_ATTENTION instead of claiming a clean failure.
     *
     * [parentUri] is the folder every item in [plans] lives in, supplied by the caller (A2) rather
     * than derived from `DocumentFile.fromSingleUri(...).parentFile`, which is always null and
     * made every batch rename fail (P0.4, defect 2).
     */
    suspend fun executeBatchRename(parentUri: Uri, plans: List<BatchRenamePlan>): List<Uri> =
        withContext(Dispatchers.IO) {
            val validation = BatchRenamePolicy.validate(plans)
            require(validation.valid) { validation.message ?: "Invalid rename plan." }

            val createdAt = System.currentTimeMillis()
            var operation = FileOperation(
                type = FileOperationType.RENAME,
                items = plans.map { plan ->
                    OperationItem(
                        source = plan.source,
                        displayName = plan.oldName,
                        state = OperationState.PREFLIGHT,
                    )
                },
                state = OperationState.PREFLIGHT,
                createdAtMillis = createdAt,
                updatedAtMillis = createdAt,
            )
            journal.put(operation)

            val steps = mutableListOf<RenameStep>()
            try {
                val parent = DocNode.load(resolver, parentUri) ?: error("Unable to open the containing folder.")
                require(parent.isDirectory && parent.canWrite) { "The containing folder is not writable." }

                val nodes = plans.map { plan ->
                    val node = DocNode.load(resolver, plan.source) ?: error("${plan.oldName} no longer exists.")
                    require(node.canWrite) { "${plan.oldName} cannot be renamed by this provider." }
                    plan to node
                }

                val selectedUris = nodes.map { it.second.uri.toString() }.toSet()
                val targetNames = plans.map { it.newName.lowercase() }.toSet()
                val siblings = parent.children(resolver)
                val externalCollision = siblings.firstOrNull { existing ->
                    existing.uri.toString() !in selectedUris && existing.name.lowercase() in targetNames
                }
                require(externalCollision == null) {
                    "A different item named ${externalCollision?.name} already exists."
                }

                val siblingNames = siblings.mapTo(mutableSetOf()) { it.name }
                nodes.forEach { (plan, node) ->
                    var temporaryName: String
                    do {
                        temporaryName = ".fylz-rename-${UUID.randomUUID()}"
                    } while (temporaryName in siblingNames)
                    siblingNames += temporaryName
                    steps += RenameStep(plan, node, temporaryName)
                }

                operation = operation.copy(
                    state = OperationState.RUNNING,
                    items = operation.items.map { it.copy(state = OperationState.RUNNING) },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(operation)

                steps.forEach { step ->
                    coroutineContext.ensureActive()
                    step.current = step.current.rename(resolver, step.temporaryName)
                    step.staged = true
                }

                steps.forEach { step ->
                    coroutineContext.ensureActive()
                    step.current = step.current.rename(resolver, step.plan.newName)
                    step.finalized = true
                }

                val completed = operation.copy(
                    state = OperationState.SUCCEEDED,
                    items = operation.items.mapIndexed { index, item ->
                        item.copy(
                            destination = steps[index].current.uri,
                            displayName = steps[index].plan.newName,
                            state = OperationState.SUCCEEDED,
                        )
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(completed)
                steps.map { it.current.uri }
            } catch (cancelled: CancellationException) {
                val rollbackComplete = rollbackRenames(steps)
                journal.put(
                    operation.copy(
                        state = if (rollbackComplete) OperationState.CANCELLED else OperationState.NEEDS_ATTENTION,
                        items = operation.items.map {
                            it.copy(
                                state = if (rollbackComplete) OperationState.CANCELLED else OperationState.NEEDS_ATTENTION,
                                errorCode = if (rollbackComplete) null else "ROLLBACK_INCOMPLETE",
                            )
                        },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                throw cancelled
            } catch (failure: Throwable) {
                val rollbackComplete = rollbackRenames(steps)
                journal.put(
                    operation.copy(
                        state = if (rollbackComplete) OperationState.FAILED else OperationState.NEEDS_ATTENTION,
                        items = operation.items.map {
                            it.copy(
                                state = if (rollbackComplete) OperationState.FAILED else OperationState.NEEDS_ATTENTION,
                                errorCode = if (rollbackComplete) failure.errorCode() else "ROLLBACK_INCOMPLETE",
                            )
                        },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                throw failure
            }
        }

    /** Renames every already-[RenameStep.staged] item back to its original name, most recently
     * staged first -- correct whether a step got as far as [RenameStep.finalized] (current name
     * is the new name) or only as far as staged (current name is the temporary name): either way
     * [RenameStep.current] tracks where the item actually is right now. */
    private fun rollbackRenames(steps: List<RenameStep>): Boolean {
        var complete = true
        steps.asReversed().forEach { step ->
            if (step.staged) {
                val restored = runCatching { step.current.rename(resolver, step.plan.oldName) }.getOrNull()
                if (restored != null) step.current = restored else complete = false
            }
        }
        return complete
    }

    private suspend fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Unable to read a file for duplicate detection.")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Throwable.errorCode(): String = when (this) {
        is SecurityException -> "PERMISSION_DENIED"
        is IllegalArgumentException -> "INVALID_REQUEST"
        is IllegalStateException -> "OPERATION_FAILED"
        else -> "UNEXPECTED_ERROR"
    }
}
