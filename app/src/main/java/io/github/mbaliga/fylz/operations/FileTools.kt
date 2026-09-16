package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.core.model.ItemRef
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.core.operations.FileOperationType
import io.github.mbaliga.fylz.core.operations.OperationItem
import io.github.mbaliga.fylz.core.operations.OperationState
import io.github.mbaliga.fylz.storage.toItemRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class DuplicateGroup(val sha256: String, val sizeBytes: Long, val items: List<ItemRef>)

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
    // Fired per item once its final rename in executeBatchRename succeeds. No store type leaks
    // in here; callers translate. Never fired on rollback.
    private val onItemRelocated: ((Uri, Uri) -> Unit)? = null,
) {
    private data class RenameStep(
        val plan: BatchRenamePlan,
        val temporaryName: String,
        // The item's uri as of its last successful rename -- DocumentsContract.renameDocument
        // is called directly (see executeBatchRename's comment on why), so nothing here ever
        // reads a DocumentFile's own (unrefreshed) uri field to track this.
        var currentUri: Uri,
        var staged: Boolean = false,
        var finalized: Boolean = false,
        // True once the finalize rename actually told onItemRelocated about a changed uri --
        // only that case needs its rollback reported back too.
        var relocatedForward: Boolean = false,
    )

    suspend fun findDuplicates(
        uris: List<Uri>,
        maxBytesPerFile: Long = 2L * 1024L * 1024L * 1024L,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { uri ->
            DocumentFile.fromSingleUri(context, uri)
                ?.takeIf { it.isFile && it.exists() && it.length() in 0..maxBytesPerFile }
                ?.let { uri to it.length() }
        }
        files.groupBy { it.second }
            .filterValues { it.size > 1 }
            .values
            .flatMap { sameSize ->
                sameSize.groupBy { (uri, _) -> sha256(uri) }
                    .filterValues { it.size > 1 }
                    .map { (hash, items) ->
                        DuplicateGroup(hash, items.first().second, items.map { it.first.toItemRef() })
                    }
            }
            .sortedByDescending(DuplicateGroup::sizeBytes)
    }

    fun planBatchRename(
        items: List<Pair<Uri, String>>,
        prefix: String,
        startAt: Int = 1,
        padding: Int = 2,
    ): List<BatchRenamePlan> {
        require(items.isNotEmpty())
        require(prefix.isNotBlank())
        require(startAt >= 0)
        require(padding in 1..8)
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
     * @param parentUri the folder every plan's source lives in, if the caller already has it (it
     *   always does -- batch rename only ever runs against the currently browsed folder). Needed
     *   because `DocumentFile.fromSingleUri(...).getParentFile()` is unconditionally null (it
     *   never wires a parent chain, confirmed by decompiling the pinned documentfile artifact);
     *   omitting this reproduces that same "no parent" preflight failure every time, same as
     *   before this parameter existed.
     */
    suspend fun executeBatchRename(plans: List<BatchRenamePlan>, parentUri: Uri? = null): List<Uri> =
        withContext(Dispatchers.IO) {
            val validation = BatchRenamePolicy.validate(plans)
            require(validation.valid) { validation.message ?: "Invalid rename plan." }

            val createdAt = System.currentTimeMillis()
            var operation = FileOperation(
                type = FileOperationType.RENAME,
                items = plans.map { plan ->
                    OperationItem(
                        source = plan.source.toItemRef(),
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
                val documents = plans.map { plan ->
                    val document = DocumentFile.fromSingleUri(context, plan.source)
                        ?: error("Unable to open ${plan.oldName}.")
                    require(document.exists()) { "${plan.oldName} no longer exists." }
                    require(document.canWrite()) { "${plan.oldName} cannot be renamed by this provider." }
                    plan to document
                }

                val parent = if (parentUri != null) {
                    DocumentFile.fromTreeUri(context, parentUri)
                        ?: error("Unable to open the containing folder.")
                } else {
                    // No caller-supplied folder: fall back to the (always-null) parentFile path,
                    // preserving this signature's old behaviour -- and its old failure -- for a
                    // caller that omits the new parameter.
                    val parents = documents.map { (_, document) ->
                        document.parentFile
                            ?: error("The provider does not expose a parent folder for rename preflight.")
                    }
                    val parentUris = parents.map { it.uri.toString() }.toSet()
                    require(parentUris.size == 1) { "Batch rename currently requires all items to share one folder." }
                    parents.first()
                }
                require(parent.canWrite()) { "The containing folder is not writable." }

                val selectedUris = documents.map { it.second.uri.toString() }.toSet()
                val targetNames = plans.map { it.newName.lowercase() }.toSet()
                val externalCollision = parent.listFiles().firstOrNull { existing ->
                    existing.uri.toString() !in selectedUris &&
                        existing.name?.lowercase() in targetNames
                }
                require(externalCollision == null) {
                    "A different item named ${externalCollision?.name} already exists."
                }

                documents.forEach { (plan, document) ->
                    var temporaryName: String
                    do {
                        temporaryName = ".fylz-rename-${UUID.randomUUID()}"
                    } while (parent.findFile(temporaryName) != null)
                    steps += RenameStep(plan, temporaryName, currentUri = document.uri)
                }

                operation = operation.copy(
                    state = OperationState.RUNNING,
                    items = operation.items.map { it.copy(state = OperationState.RUNNING) },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(operation)

                steps.forEach { step ->
                    coroutineContext.ensureActive()
                    // DocumentsContract.renameDocument directly, not DocumentFile.renameTo:
                    // every step's document opened via fromSingleUri, and
                    // SingleDocumentFile.renameTo() is an unconditional
                    // UnsupportedOperationException in the pinned documentfile artifact.
                    val staged = DocumentsContract.renameDocument(context.contentResolver, step.currentUri, step.temporaryName)
                        ?: error("Unable to stage ${step.plan.oldName} for batch rename.")
                    step.currentUri = staged
                    step.staged = true
                }

                steps.forEach { step ->
                    coroutineContext.ensureActive()
                    val finalUri = DocumentsContract.renameDocument(context.contentResolver, step.currentUri, step.plan.newName)
                        ?: error("Unable to rename ${step.plan.oldName} to ${step.plan.newName}.")
                    step.currentUri = finalUri
                    step.finalized = true
                    // Some providers keep the document ID stable across a rename; only a
                    // genuinely new URI needs its identity-keyed metadata carried over.
                    if (finalUri != step.plan.source) {
                        onItemRelocated?.invoke(step.plan.source, finalUri)
                        step.relocatedForward = true
                    }
                }

                val completed = operation.copy(
                    state = OperationState.SUCCEEDED,
                    items = operation.items.mapIndexed { index, item ->
                        item.copy(
                            destination = steps[index].currentUri.toItemRef(),
                            displayName = steps[index].plan.newName,
                            state = OperationState.SUCCEEDED,
                        )
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(completed)
                steps.map { it.currentUri }
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

    private fun rollbackRenames(steps: List<RenameStep>): Boolean {
        var complete = true
        steps.asReversed().forEach { step ->
            if (step.staged) {
                val forwardUri = step.currentUri
                val restored = runCatching {
                    DocumentsContract.renameDocument(context.contentResolver, forwardUri, step.plan.oldName)
                }.getOrNull()
                if (restored == null) {
                    complete = false
                } else {
                    step.currentUri = restored
                    // The finalize loop already told onItemRelocated about forwardUri; undo that
                    // report too, or Shelf/Library/History are left pointed at a uri the provider
                    // just renamed away from underneath them.
                    if (step.relocatedForward && restored != forwardUri) {
                        onItemRelocated?.invoke(forwardUri, restored)
                    }
                }
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
