package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
    private val resolver: ContentResolver get() = context.contentResolver

    private data class RestorePlan(
        val requestedName: String,
        val stagingName: String,
        val existing: DocNode? = null,
    )

    /**
     * Finds `.fylz-trash` directly under the tree rooted at [rootTreeUri] by listing its children
     * (A1) -- never `DocumentFile.findFile`, which is a per-call linear provider query with its
     * own failure modes on some providers -- creating it if absent.
     */
    suspend fun recycleRootFor(rootTreeUri: Uri): DocNode = withContext(Dispatchers.IO) {
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            rootTreeUri,
            DocumentsContract.getTreeDocumentId(rootTreeUri),
        )
        val root = DocNode.load(resolver, rootDocumentUri) ?: error("Unable to open the selected root.")
        findRecycleRoot(root) ?: root.createChild(resolver, DocumentsContract.Document.MIME_TYPE_DIR, RECYCLE_DIRECTORY)
    }

    /** Finds `.fylz-trash` directly under [root]; never creates it. */
    private fun findRecycleRoot(root: DocNode): DocNode? =
        root.children(resolver).firstOrNull { it.isDirectory && it.name == RECYCLE_DIRECTORY }

    suspend fun recycle(
        sourceUri: Uri,
        originalParentUri: Uri?,
        recycleRootUri: Uri,
    ): RecycleRecord = withContext(Dispatchers.IO) {
        val source = DocNode.load(resolver, sourceUri) ?: error("Unable to open the selected item.")
        val recycleRoot = DocNode.load(resolver, recycleRootUri)
            ?: error("Unable to open the recycle location.")
        require(recycleRoot.isDirectory && recycleRoot.canWrite) {
            "The selected provider cannot write to its Fylz recycle location."
        }
        recycleNode(source, sourceUri, originalParentUri, recycleRoot, journaled = true)
    }

    /**
     * The core recycle transaction, shared by the public [recycle] (a user-initiated delete) and
     * [replaceWithRecycleFallback] (an item a Replace conflict is about to overwrite). Only the
     * user-initiated path is journaled as its own [FileOperation]: a replace-fallback recycle is a
     * sub-step of whatever copy, move or restore operation is already journaling itself.
     */
    private suspend fun recycleNode(
        source: DocNode,
        sourceUri: Uri,
        originalParentUri: Uri?,
        recycleRoot: DocNode,
        journaled: Boolean,
    ): RecycleRecord {
        val displayName = source.name
        val sourceSize = source.size
        var operation = FileOperation(
            type = FileOperationType.RECYCLE,
            items = listOf(
                OperationItem(
                    source = sourceUri,
                    destination = recycleRoot.uri,
                    displayName = displayName,
                    expectedBytes = sourceSize,
                    state = OperationState.PREFLIGHT,
                ),
            ),
            state = OperationState.PREFLIGHT,
        )
        if (journaled) journal.put(operation)

        try {
            if (journaled) {
                operation = operation.copy(
                    state = OperationState.RUNNING,
                    items = operation.items.map { it.copy(state = OperationState.RUNNING) },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                journal.put(operation)
            }

            val itemId = UUID.randomUUID().toString()
            val container = recycleRoot.createChild(resolver, DocumentsContract.Document.MIME_TYPE_DIR, itemId)
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
                    containerUri = container.uri,
                )

                store.put(record)
                recordStored = true
                check(source.delete(resolver)) {
                    "The item was copied to the recycle bin, but the provider refused to remove the original."
                }

                if (journaled) {
                    operation = operation.copy(
                        state = OperationState.SUCCEEDED,
                        items = operation.items.map {
                            it.copy(
                                destination = recycled.uri,
                                completedBytes = sourceSize ?: it.completedBytes,
                                state = OperationState.SUCCEEDED,
                            )
                        },
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    journal.put(operation)
                }
                return record
            } catch (failure: Throwable) {
                if (recordStored) store.remove(itemId)
                container.delete(resolver)
                throw failure
            }
        } catch (cancelled: CancellationException) {
            if (journaled) {
                journal.put(
                    operation.copy(
                        state = OperationState.CANCELLED,
                        items = operation.items.map { it.copy(state = OperationState.CANCELLED) },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            if (journaled) {
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
            }
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
                    source = record.recycledUri,
                    destination = destinationUri,
                    displayName = record.originalDisplayName,
                    expectedBytes = record.sizeBytes,
                    state = OperationState.PREFLIGHT,
                ),
            ),
            state = OperationState.PREFLIGHT,
        )
        journal.put(operation)

        try {
            val recycled = DocNode.load(resolver, record.recycledUri)
                ?: error("The recycled item no longer exists.")

            val destination = DocNode.load(resolver, destinationUri)
                ?: error("Unable to open the restore destination.")
            require(destination.isDirectory && destination.canWrite) {
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
                // On a failed finalize (rename/replace), the recycle record must stay exactly as
                // it was: the recycled copy is still there, still restorable, and this throws
                // rather than reporting success.
                val restored = finalizeRestore(destination, plan, staged, destinationUri)
                destinationFinalized = true
                check(recycled.delete(resolver)) {
                    "The item was restored, but the provider refused to remove the recycle copy."
                }
                // The <uuid> container DocumentFile.fromSingleUri's null parentFile used to leak
                // (defect 1): now tracked explicitly and removed once it's empty.
                record.containerUri?.let { DocNode.load(resolver, it)?.delete(resolver) }
                store.remove(itemId)

                journal.put(
                    operation.copy(
                        state = OperationState.SUCCEEDED,
                        items = operation.items.map {
                            it.copy(
                                destination = restored.uri,
                                completedBytes = record.sizeBytes ?: it.completedBytes,
                                state = OperationState.SUCCEEDED,
                            )
                        },
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                restored.uri
            } catch (failure: Throwable) {
                if (!destinationFinalized) staged.refresh(resolver)?.delete(resolver)
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
                    source = record.recycledUri,
                    displayName = record.originalDisplayName,
                    expectedBytes = record.sizeBytes,
                    state = OperationState.RUNNING,
                ),
            ),
            state = OperationState.RUNNING,
        )
        journal.put(operation)

        try {
            val recycled = DocNode.load(resolver, record.recycledUri)
            check(recycled == null || recycled.delete(resolver)) {
                "The provider refused permanent deletion."
            }
            record.containerUri?.let { DocNode.load(resolver, it)?.delete(resolver) }
            store.remove(itemId)
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

    fun records(): List<RecycleRecord> = store.list()

    /**
     * The Replace conflict policy, shared by [restore]'s [finalizeRestore] and
     * [FileOperationService]'s equivalent: the existing item is renamed aside, never deleted,
     * until the replacement has actually landed under the requested name. Today's code deleted
     * the existing item first, which left a window where a failed rename destroyed data with
     * nothing left to recover.
     *
     * 1. Rename the existing item aside to `.fylz-replaced-<uuid>-<name>`.
     * 2. Rename the staged item into place.
     * 3. Recycle the aside item into `destinationRoot`'s `.fylz-trash` if it has one (so the
     *    replaced version is still restorable), otherwise delete it -- the user explicitly chose
     *    Replace, so no recycle bin at that root is not itself a reason to refuse.
     * 4. If step 2 fails, rename the aside item back and report the failure; nothing is lost.
     */
    suspend fun replaceWithRecycleFallback(
        destinationRoot: DocNode,
        existing: DocNode,
        staged: DocNode,
        requestedName: String,
        originalParentUri: Uri?,
    ): DocNode = withContext(Dispatchers.IO) {
        val aside = existing.rename(resolver, ".fylz-replaced-${UUID.randomUUID()}-${existing.name}")
        val finalNode = try {
            staged.rename(resolver, requestedName)
        } catch (failure: Exception) {
            runCatching { aside.rename(resolver, existing.name) }
            throw IllegalStateException(
                "The replacement data is safe, but the provider could not restore the requested " +
                    "name. It remains as ${staged.name}.",
                failure,
            )
        }
        val recycleRoot = findRecycleRoot(destinationRoot)
        if (recycleRoot != null) {
            runCatching { recycleNode(aside, aside.uri, originalParentUri, recycleRoot, journaled = false) }
                .onFailure { aside.refresh(resolver)?.delete(resolver) }
        } else {
            aside.delete(resolver)
        }
        finalNode
    }

    private suspend fun copyDocument(
        source: DocNode,
        destinationDirectory: DocNode,
        requestedName: String,
    ): DocNode {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            val directory = destinationDirectory.createChild(resolver, DocumentsContract.Document.MIME_TYPE_DIR, requestedName)
            try {
                source.children(resolver).forEach { child ->
                    copyDocument(child, directory, child.name)
                }
                return directory
            } catch (failure: Throwable) {
                directory.delete(resolver)
                throw failure
            }
        }

        val target = destinationDirectory.createChild(resolver, source.mimeType, requestedName)
        try {
            val input = resolver.openInputStream(source.uri) ?: error("Unable to read $requestedName.")
            val output = resolver.openOutputStream(target.uri, "w") ?: error("Unable to write $requestedName.")
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
            return target.refresh(resolver) ?: error("$requestedName was written but has already vanished.")
        } catch (failure: Throwable) {
            target.delete(resolver)
            throw failure
        }
    }

    private fun verifyCopy(source: DocNode, target: DocNode) {
        val expected = source.size
        val actual = target.size
        if (expected != null && actual != null) {
            check(expected == actual) {
                "Copy verification failed: expected $expected bytes, wrote $actual bytes."
            }
        }
    }

    private suspend fun finalizeRestore(
        destinationRoot: DocNode,
        plan: RestorePlan,
        staged: DocNode,
        destinationUri: Uri,
    ): DocNode {
        val existing = plan.existing ?: return staged
        return replaceWithRecycleFallback(destinationRoot, existing, staged, plan.requestedName, destinationUri)
    }

    private fun resolveRestorePlan(
        destination: DocNode,
        requestedName: String,
        policy: ConflictPolicy,
    ): RestorePlan? {
        val existing = destination.findChild(resolver, requestedName)
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

    private fun uniqueStagingName(destination: DocNode, requestedName: String): String {
        val safeName = requestedName.replace('/', '_')
        while (true) {
            val candidate = ".fylz-restore-${UUID.randomUUID()}-$safeName"
            if (destination.children(resolver).none { it.name == candidate }) return candidate
        }
    }

    private fun uniqueName(destination: DocNode, requestedName: String): String {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        val existingNames = destination.children(resolver).mapTo(mutableSetOf()) { it.name }
        var index = 2
        while (true) {
            val candidate = "$base ($index)$extension"
            if (candidate !in existingNames) return candidate
            index += 1
        }
    }

    private fun Throwable.errorCode(): String = when (this) {
        is SecurityException -> "PERMISSION_DENIED"
        is IllegalArgumentException -> "INVALID_REQUEST"
        is IllegalStateException -> "OPERATION_FAILED"
        else -> "UNEXPECTED_ERROR"
    }

    companion object {
        /** Must match [io.github.mbaliga.fylz.search.RecursiveSearchEngine.RECYCLE_DIRECTORY]. */
        const val RECYCLE_DIRECTORY: String = ".fylz-trash"
    }
}
