package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.core.operations.ConflictPolicy
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.core.operations.FileOperationType
import io.github.mbaliga.fylz.core.operations.OperationItem
import io.github.mbaliga.fylz.core.operations.OperationState
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.storage.toItemRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Implements non-destructive deletion for Storage Access Framework providers.
 *
 * Fylz never falls back to permanent deletion. A recycle operation succeeds only after the item is
 * copied into a writable recycle root, verified, and durably recorded before the original is removed.
 */
class RecycleBinService(
    private val context: Context,
    private val store: RecycleBinStore = RecycleBinStore(context),
    private val journal: OperationJournal = OperationJournal(context),
) {
    // DocumentRepository-modelled: a second LibraryStore instance reading and writing the same
    // SharedPreferences file, so a permanent delete can drop the tag record it just orphaned
    // without threading a store through every caller of this service.
    private val library = LibraryStore(context.applicationContext)

    private data class RestorePlan(
        val requestedName: String,
        val stagingName: String,
        val existing: DocumentFile? = null,
    )

    suspend fun recycle(
        sourceUri: Uri,
        originalParentUri: Uri?,
        recycleRootUri: Uri,
    ): RecycleRecord = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromSingleUri(context, sourceUri)
            ?: error("Unable to open the selected item.")
        require(source.exists()) { "The selected item no longer exists." }

        val displayName = source.name ?: "untitled"
        val sourceSize = source.length().takeIf { it >= 0L }
        var operation = FileOperation(
            type = FileOperationType.RECYCLE,
            items = listOf(
                OperationItem(
                    source = sourceUri.toItemRef(),
                    destination = recycleRootUri.toItemRef(),
                    displayName = displayName,
                    expectedBytes = sourceSize,
                    state = OperationState.PREFLIGHT,
                ),
            ),
            state = OperationState.PREFLIGHT,
        )
        journal.put(operation)

        try {
            val recycleRoot = DocumentFile.fromTreeUri(context, recycleRootUri)
                ?: DocumentFile.fromSingleUri(context, recycleRootUri)
                ?: error("Unable to open the recycle location.")
            require(recycleRoot.canWrite() && recycleRoot.isDirectory) {
                "The selected provider cannot write to its Fylz recycle location."
            }

            operation = operation.copy(
                state = OperationState.RUNNING,
                items = operation.items.map { it.copy(state = OperationState.RUNNING) },
                updatedAtMillis = System.currentTimeMillis(),
            )
            journal.put(operation)

            val itemId = UUID.randomUUID().toString()
            val container = recycleRoot.createDirectory(itemId)
                ?: error("Unable to create a recycle transaction folder.")
            var recordStored = false

            try {
                val recycled = copyDocument(source, container, displayName)
                verifyCopy(source, recycled)
                val record = RecycleRecord(
                    itemId = itemId,
                    originalUri = sourceUri,
                    recycledUri = recycled.uri,
                    originalParentUri = originalParentUri,
                    originalDisplayName = displayName,
                    providerAuthority = sourceUri.authority,
                    sizeBytes = sourceSize,
                    recycledAtMillis = System.currentTimeMillis(),
                )

                store.put(record)
                recordStored = true
                check(source.delete()) {
                    "The item was copied to the recycle bin, but the provider refused to remove the original."
                }

                operation = operation.copy(
                    state = OperationState.SUCCEEDED,
                    items = operation.items.map {
                        it.copy(
                            destination = recycled.uri.toItemRef(),
                            completedBytes = sourceSize ?: it.completedBytes,
                            state = OperationState.SUCCEEDED,
                        )
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(operation)
                record
            } catch (failure: Throwable) {
                if (recordStored) store.remove(itemId)
                container.delete()
                throw failure
            }
        } catch (cancelled: CancellationException) {
            journal.put(
                operation.copy(
                    state = OperationState.CANCELLED,
                    items = operation.items.map { it.copy(state = OperationState.CANCELLED) },
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            throw cancelled
        } catch (failure: Throwable) {
            journal.put(
                operation.copy(
                    state = OperationState.FAILED,
                    items = operation.items.map {
                        if (it.state == OperationState.SUCCEEDED) it
                        else it.copy(state = OperationState.FAILED, errorCode = failure.errorCode())
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            throw failure
        }
    }

    suspend fun restore(
        itemId: String,
        fallbackDestinationTreeUri: Uri? = null,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    ): Uri = withContext(Dispatchers.IO) {
        val record = store.find(itemId) ?: error("Recycle record not found.")
        val destinationUri = record.originalParentUri ?: fallbackDestinationTreeUri
            ?: error("The original folder is unavailable. Choose a restore destination.")
        var operation = FileOperation(
            type = FileOperationType.RESTORE,
            conflictPolicy = conflictPolicy,
            items = listOf(
                OperationItem(
                    source = record.recycledUri.toItemRef(),
                    destination = destinationUri.toItemRef(),
                    displayName = record.originalDisplayName,
                    expectedBytes = record.sizeBytes,
                    state = OperationState.PREFLIGHT,
                ),
            ),
            state = OperationState.PREFLIGHT,
        )
        journal.put(operation)

        try {
            val recycled = DocumentFile.fromSingleUri(context, record.recycledUri)
                ?: error("The recycled item is unavailable.")
            require(recycled.exists()) { "The recycled item no longer exists." }

            val destination = DocumentFile.fromTreeUri(context, destinationUri)
                ?: DocumentFile.fromSingleUri(context, destinationUri)
                ?: error("Unable to open the restore destination.")
            require(destination.isDirectory && destination.canWrite()) {
                "The restore destination is not writable."
            }

            val plan = resolveRestorePlan(destination, record.originalDisplayName, conflictPolicy)
            if (plan == null) {
                journal.put(
                    operation.copy(
                        state = OperationState.SUCCEEDED,
                        items = operation.items.map { it.copy(state = OperationState.SUCCEEDED) },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                return@withContext record.recycledUri
            }

            operation = operation.copy(
                state = OperationState.RUNNING,
                items = operation.items.map { it.copy(state = OperationState.RUNNING) },
                updatedAtMillis = System.currentTimeMillis(),
            )
            journal.put(operation)

            val staged = copyDocument(recycled, destination, plan.stagingName)
            var destinationFinalized = false
            try {
                verifyCopy(recycled, staged)
                val restored = finalizeRestore(plan, staged)
                destinationFinalized = true
                check(recycled.delete()) {
                    "The item was restored, but the provider refused to remove the recycle copy."
                }
                recycled.parentFile?.delete()
                store.remove(itemId)

                journal.put(
                    operation.copy(
                        state = OperationState.SUCCEEDED,
                        items = operation.items.map {
                            it.copy(
                                destination = restored.uri.toItemRef(),
                                completedBytes = record.sizeBytes ?: it.completedBytes,
                                state = OperationState.SUCCEEDED,
                            )
                        },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                restored.uri
            } catch (failure: Throwable) {
                if (!destinationFinalized && staged.exists()) staged.delete()
                throw failure
            }
        } catch (cancelled: CancellationException) {
            journal.put(
                operation.copy(
                    state = OperationState.CANCELLED,
                    items = operation.items.map { it.copy(state = OperationState.CANCELLED) },
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            throw cancelled
        } catch (failure: Throwable) {
            journal.put(
                operation.copy(
                    state = OperationState.FAILED,
                    items = operation.items.map {
                        if (it.state == OperationState.SUCCEEDED) it
                        else it.copy(state = OperationState.FAILED, errorCode = failure.errorCode())
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            throw failure
        }
    }

    suspend fun permanentlyDelete(
        itemId: String,
        confirmed: Boolean,
    ) = withContext(Dispatchers.IO) {
        require(
            RecycleBinPolicy.allowPermanentDelete(
                invokedFromRecycleBin = true,
                explicitAdvancedAction = false,
                confirmed = confirmed,
            ),
        ) { "Permanent deletion requires explicit confirmation from the recycle bin." }

        val record = store.find(itemId) ?: error("Recycle record not found.")
        var operation = FileOperation(
            type = FileOperationType.PERMANENT_DELETE,
            items = listOf(
                OperationItem(
                    source = record.recycledUri.toItemRef(),
                    displayName = record.originalDisplayName,
                    expectedBytes = record.sizeBytes,
                    state = OperationState.RUNNING,
                ),
            ),
            state = OperationState.RUNNING,
        )
        journal.put(operation)

        try {
            val recycled = DocumentFile.fromSingleUri(context, record.recycledUri)
                ?: error("The recycled item is unavailable.")
            val transactionFolder = recycled.parentFile
            check(!recycled.exists() || recycled.delete()) {
                "The provider refused permanent deletion."
            }
            transactionFolder?.delete()
            store.remove(itemId)
            // The item is irreversibly gone now -- its tags: record, keyed on either URI it has
            // ever answered to, is truly orphaned rather than merely absent from this listing.
            library.pruneOrphanedTags(listOf(record.recycledUri, record.originalUri))
            operation = operation.copy(
                state = OperationState.SUCCEEDED,
                items = operation.items.map { it.copy(state = OperationState.SUCCEEDED) },
                updatedAtMillis = System.currentTimeMillis(),
            )
            journal.put(operation)
        } catch (failure: Throwable) {
            journal.put(
                operation.copy(
                    state = OperationState.FAILED,
                    items = operation.items.map {
                        it.copy(state = OperationState.FAILED, errorCode = failure.errorCode())
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            throw failure
        }
    }

    /**
     * Every recycled item this device knows about, durable across process death and restart --
     * every recycle-bin surface (the trash sheet, its bulge/tab entry point, the settings
     * dialog) must read this directly rather than intersecting it with a UI-local, session-scoped
     * membership list. This store is already the single source of truth on disk; a second,
     * narrower notion of "what's in the bin" kept only in Compose state is what caused two
     * surfaces to disagree, not a gap here.
     */
    fun records(): List<RecycleRecord> = store.list()

    private suspend fun copyDocument(
        source: DocumentFile,
        destinationDirectory: DocumentFile,
        requestedName: String,
    ): DocumentFile {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            val directory = destinationDirectory.createDirectory(requestedName)
                ?: error("Unable to create $requestedName.")
            try {
                source.listFiles().forEach { child ->
                    copyDocument(child, directory, child.name ?: "untitled")
                }
                return directory
            } catch (failure: Throwable) {
                directory.delete()
                throw failure
            }
        }

        val target = destinationDirectory.createFile(
            source.type ?: "application/octet-stream",
            requestedName,
        ) ?: error("Unable to create $requestedName.")

        try {
            val input = context.contentResolver.openInputStream(source.uri)
                ?: error("Unable to read $requestedName.")
            val output = context.contentResolver.openOutputStream(target.uri, "w")
                ?: error("Unable to write $requestedName.")
            input.use { sourceStream ->
                output.use { targetStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        targetStream.write(buffer, 0, count)
                    }
                    targetStream.flush()
                }
            }
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private fun verifyCopy(source: DocumentFile, target: DocumentFile) {
        val sourceLength = source.length()
        val targetLength = target.length()
        if (source.isFile && sourceLength >= 0L && targetLength >= 0L) {
            check(sourceLength == targetLength) {
                "Copy verification failed: expected $sourceLength bytes, wrote $targetLength bytes."
            }
        }
    }

    private fun finalizeRestore(plan: RestorePlan, staged: DocumentFile): DocumentFile {
        val existing = plan.existing ?: return staged
        check(existing.delete()) {
            staged.delete()
            "Unable to replace ${plan.requestedName}; the existing item was left untouched."
        }
        if (plan.stagingName == plan.requestedName) return staged
        staged.renameTo(plan.requestedName)
        return staged
    }

    private fun resolveRestorePlan(
        destination: DocumentFile,
        requestedName: String,
        policy: ConflictPolicy,
    ): RestorePlan? {
        val existing = destination.findFile(requestedName)
            ?: return RestorePlan(requestedName, requestedName)
        return when (policy) {
            ConflictPolicy.ASK -> error("A file named $requestedName already exists.")
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> {
                val unique = uniqueName(destination, requestedName)
                RestorePlan(unique, unique)
            }
            ConflictPolicy.REPLACE -> RestorePlan(
                requestedName = requestedName,
                stagingName = uniqueStagingName(destination, requestedName),
                existing = existing,
            )
        }
    }

    private fun uniqueStagingName(destination: DocumentFile, requestedName: String): String {
        val safeName = requestedName.replace('/', '_')
        while (true) {
            val candidate = ".fylz-restore-${UUID.randomUUID()}-$safeName"
            if (destination.findFile(candidate) == null) return candidate
        }
    }

    private fun uniqueName(destination: DocumentFile, requestedName: String): String {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$extension"
            if (destination.findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private fun Throwable.errorCode(): String = when (this) {
        is SecurityException -> "PERMISSION_DENIED"
        is IllegalArgumentException -> "INVALID_REQUEST"
        is IllegalStateException -> "OPERATION_FAILED"
        else -> "UNEXPECTED_ERROR"
    }
}
