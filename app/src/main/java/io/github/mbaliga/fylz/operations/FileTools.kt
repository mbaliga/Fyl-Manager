package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
                        DuplicateGroup(hash, items.first().second, items.map { it.first })
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
     */
    suspend fun executeBatchRename(plans: List<BatchRenamePlan>): List<Uri> =
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

            data class RenameStep(
                val plan: BatchRenamePlan,
                val document: DocumentFile,
                val temporaryName: String,
                var staged: Boolean = false,
                var finalized: Boolean = false,
            )

            val steps = mutableListOf<RenameStep>()
            try {
                val documents = plans.map { plan ->
                    val document = DocumentFile.fromSingleUri(context, plan.source)
                        ?: error("Unable to open ${plan.oldName}.")
                    require(document.exists()) { "${plan.oldName} no longer exists." }
                    require(document.canWrite()) { "${plan.oldName} cannot be renamed by this provider." }
                    plan to document
                }

                val parents = documents.map { (_, document) ->
                    document.parentFile ?: error("The provider does not expose a parent folder for rename preflight.")
                }
                val parentUris = parents.map { it.uri.toString() }.toSet()
                require(parentUris.size == 1) { "Batch rename currently requires all items to share one folder." }
                val parent = parents.first()
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
                    steps += RenameStep(plan, document, temporaryName)
                }

                operation = operation.copy(
                    state = OperationState.RUNNING,
                    items = operation.items.map { it.copy(state = OperationState.RUNNING) },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(operation)

                steps.forEach { step ->
                    coroutineContext.ensureActive()
                    check(step.document.renameTo(step.temporaryName)) {
                        "Unable to stage ${step.plan.oldName} for batch rename."
                    }
                    step.staged = true
                }

                steps.forEach { step ->
                    coroutineContext.ensureActive()
                    check(step.document.renameTo(step.plan.newName)) {
                        "Unable to rename ${step.plan.oldName} to ${step.plan.newName}."
                    }
                    step.finalized = true
                }

                val completed = operation.copy(
                    state = OperationState.SUCCEEDED,
                    items = operation.items.mapIndexed { index, item ->
                        item.copy(
                            destination = steps[index].document.uri,
                            displayName = steps[index].plan.newName,
                            state = OperationState.SUCCEEDED,
                        )
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(completed)
                steps.map { it.document.uri }
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

    private fun rollbackRenames(steps: List<Any>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val typedSteps = steps as List<dynamicRenameStep>
        return typedSteps.asReversed().all { step ->
            if (!step.staged) true else runCatching {
                step.document.renameTo(step.plan.oldName)
            }.getOrDefault(false)
        }
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

    /** Private structural type kept outside executeBatchRename for rollback helper access. */
    private data class dynamicRenameStep(
        val plan: BatchRenamePlan,
        val document: DocumentFile,
        val temporaryName: String,
        var staged: Boolean,
        var finalized: Boolean,
    )
}
