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

/** Provider-neutral, cancellable copy and move implementation for SAF documents. */
class FileOperationService(
    private val context: Context,
    private val journal: OperationJournal = OperationJournal(context),
) {
    private val resolver: ContentResolver get() = context.contentResolver

    data class Progress(
        val itemIndex: Int,
        val itemCount: Int,
        val displayName: String,
        val completedBytes: Long,
        val totalBytes: Long?,
    )

    private data class TargetPlan(
        val requestedName: String,
        val stagingName: String,
        val existing: DocNode? = null,
    )

    suspend fun copy(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> = transfer(sourceUris, destinationTreeUri, false, conflictPolicy, onProgress)

    suspend fun move(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> = transfer(sourceUris, destinationTreeUri, true, conflictPolicy, onProgress)

    fun operations(): List<FileOperation> = journal.list()

    /**
     * Completes a move whose verified destination was committed but whose original could not be
     * removed. This never copies again, so retry cannot create another destination duplicate.
     *
     * Uses [androidx.documentfile.provider.DocumentFile.fromSingleUri]-style single-URI lookups
     * (via [DocNode.load]) rather than a full [DocNode] tree walk: every check here is a plain
     * exists/length/delete on one already-known URI, none of which need a parent or children, so
     * this isn't the `fromSingleUri` defect P0.1 fixes (that defect is `listFiles()`/`parentFile`
     * on a single-URI node, neither of which this method calls).
     */
    suspend fun finishMoveCleanup(operationId: String): FileOperation = withContext(Dispatchers.IO) {
        val operation = journal.find(operationId) ?: error("Move operation not found.")
        require(operation.type == FileOperationType.MOVE) { "Only move operations support source cleanup." }
        var changed = false
        val items = operation.items.map { item ->
            if (item.errorCode != MOVE_SOURCE_DELETE_PENDING) return@map item
            coroutineContext.ensureActive()
            val destinationUri = item.destination
                ?: return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_MISSING)
            val destination = DocNode.load(resolver, destinationUri)
            if (destination == null) {
                return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_MISSING)
            }
            val source = DocNode.load(resolver, item.source)
            if (source == null) {
                changed = true
                return@map item.copy(state = OperationState.SUCCEEDED, errorCode = null)
            }
            if (!source.isDirectory && !destination.isDirectory) {
                val expected = source.size
                val actual = destination.size
                if (expected != null && actual != null && expected != actual) {
                    return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_UNVERIFIED)
                }
            }
            if (source.delete(resolver)) {
                changed = true
                item.copy(state = OperationState.SUCCEEDED, errorCode = null)
            } else {
                item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_SOURCE_DELETE_PENDING)
            }
        }
        val state = if (items.all { it.state == OperationState.SUCCEEDED }) {
            OperationState.SUCCEEDED
        } else {
            OperationState.NEEDS_ATTENTION
        }
        val updated = operation.copy(
            items = items,
            state = state,
            updatedAtMillis = if (changed) System.currentTimeMillis() else operation.updatedAtMillis,
        )
        journal.put(updated)
        updated
    }

    private suspend fun transfer(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        onProgress: (Progress) -> Unit,
    ): List<Uri> = withContext(Dispatchers.IO) {
        require(sourceUris.isNotEmpty()) { "Choose at least one item." }
        // destinationTreeUri comes from the system OpenDocumentTree picker (A2), so it names a
        // tree, not yet a document within it; its own root is the destination document.
        val destinationRootUri = DocumentsContract.buildDocumentUriUsingTree(
            destinationTreeUri,
            DocumentsContract.getTreeDocumentId(destinationTreeUri),
        )
        val destination = DocNode.load(resolver, destinationRootUri)
            ?: error("Unable to open the destination folder.")
        require(destination.isDirectory && destination.canWrite) {
            "The destination folder is not writable."
        }

        val operation = FileOperation(
            type = if (move) FileOperationType.MOVE else FileOperationType.COPY,
            items = sourceUris.map { uri ->
                val source = DocNode.load(resolver, uri)
                OperationItem(
                    source = uri,
                    destination = destinationTreeUri,
                    displayName = source?.name ?: "untitled",
                    expectedBytes = source?.size?.takeIf { source.isDirectory.not() },
                    state = OperationState.QUEUED,
                )
            },
            conflictPolicy = conflictPolicy,
            state = OperationState.PREFLIGHT,
        )
        journal.put(operation)
        var current = operation.copy(
            state = OperationState.RUNNING,
            items = operation.items.map { it.copy(state = OperationState.RUNNING) },
            updatedAtMillis = System.currentTimeMillis(),
        )
        journal.put(current)

        try {
            val result = buildList {
                sourceUris.forEachIndexed { index, sourceUri ->
                    coroutineContext.ensureActive()
                    val source = DocNode.load(resolver, sourceUri)
                        ?: error("Unable to open a selected item.")
                    val sourceName = source.name
                    val plan = resolveTargetPlan(destination, sourceName, conflictPolicy)
                    if (plan == null) {
                        current = updateItem(current, index) {
                            it.copy(state = OperationState.SUCCEEDED, errorCode = "SKIPPED_CONFLICT")
                        }
                        journal.put(current)
                        return@forEachIndexed
                    }

                    val total = source.size.takeIf { !source.isDirectory }
                    val staged = copyDocument(
                        source = source,
                        destinationDirectory = destination,
                        requestedName = plan.stagingName,
                        progressName = plan.requestedName,
                        itemIndex = index,
                        itemCount = sourceUris.size,
                        totalBytes = total,
                    ) { progress ->
                        current = updateItem(current, index) { item ->
                            item.copy(
                                completedBytes = progress.completedBytes,
                                expectedBytes = progress.totalBytes ?: item.expectedBytes,
                                state = OperationState.RUNNING,
                            )
                        }
                        journal.put(current)
                        onProgress(progress)
                    }
                    val copied = finalizeTarget(plan, staged)

                    if (move && !source.delete(resolver)) {
                        current = updateItem(current, index) { item ->
                            item.copy(
                                destination = copied.uri,
                                completedBytes = item.expectedBytes ?: item.completedBytes,
                                state = OperationState.NEEDS_ATTENTION,
                                errorCode = MOVE_SOURCE_DELETE_PENDING,
                            )
                        }
                        journal.put(current)
                        add(copied.uri)
                        return@forEachIndexed
                    }

                    current = updateItem(current, index) { item ->
                        item.copy(
                            destination = copied.uri,
                            completedBytes = item.expectedBytes ?: item.completedBytes,
                            state = OperationState.SUCCEEDED,
                            errorCode = null,
                        )
                    }
                    journal.put(current)
                    add(copied.uri)
                }
            }
            val finalState = if (current.items.any { it.state == OperationState.NEEDS_ATTENTION }) {
                OperationState.NEEDS_ATTENTION
            } else {
                OperationState.SUCCEEDED
            }
            current = current.copy(state = finalState, updatedAtMillis = System.currentTimeMillis())
            journal.put(current)
            result
        } catch (cancelled: CancellationException) {
            current = current.copy(
                state = OperationState.CANCELLED,
                items = current.items.map {
                    if (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) {
                        it.copy(state = OperationState.CANCELLED, errorCode = "USER_CANCELLED")
                    } else it
                },
                updatedAtMillis = System.currentTimeMillis(),
            )
            journal.put(current)
            throw cancelled
        } catch (failure: Throwable) {
            current = current.copy(
                state = if (current.items.any { it.state == OperationState.NEEDS_ATTENTION }) {
                    OperationState.NEEDS_ATTENTION
                } else {
                    OperationState.FAILED
                },
                items = current.items.map {
                    if (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) {
                        it.copy(state = OperationState.FAILED, errorCode = failure::class.java.simpleName)
                    } else it
                },
                updatedAtMillis = System.currentTimeMillis(),
            )
            journal.put(current)
            throw failure
        }
    }

    private fun updateItem(
        operation: FileOperation,
        index: Int,
        transform: (OperationItem) -> OperationItem,
    ): FileOperation = operation.copy(
        items = operation.items.mapIndexed { itemIndex, item ->
            if (itemIndex == index) transform(item) else item
        },
        updatedAtMillis = System.currentTimeMillis(),
    )

    /**
     * Copies [source] (a file or a whole tree) under [destinationDirectory] as [requestedName].
     *
     * Every file this recursion touches, at every depth, is verified against its source right
     * after it's written -- not just the top-level item, which is what let a truncated nested
     * file in a large folder copy go unnoticed before. A failure at any depth deletes exactly the
     * node this call created (the recursion above it does the same for its own node) and
     * rethrows, so a move's source delete (in [transfer]) is only ever reached once every nested
     * item has verified clean.
     */
    private suspend fun copyDocument(
        source: DocNode,
        destinationDirectory: DocNode,
        requestedName: String,
        progressName: String,
        itemIndex: Int,
        itemCount: Int,
        totalBytes: Long?,
        onProgress: (Progress) -> Unit,
    ): DocNode {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            val directory = destinationDirectory.createChild(
                resolver,
                DocumentsContract.Document.MIME_TYPE_DIR,
                requestedName,
            )
            try {
                source.children(resolver).forEach { child ->
                    copyDocument(
                        source = child,
                        destinationDirectory = directory,
                        requestedName = child.name,
                        progressName = child.name,
                        itemIndex = itemIndex,
                        itemCount = itemCount,
                        totalBytes = null,
                        onProgress = onProgress,
                    )
                }
                return directory
            } catch (failure: Throwable) {
                directory.delete(resolver)
                throw failure
            }
        }

        val target = destinationDirectory.createChild(resolver, source.mimeType, requestedName)
        try {
            val input = resolver.openInputStream(source.uri) ?: error("Unable to read $progressName.")
            val output = resolver.openOutputStream(target.uri, "w") ?: error("Unable to write $progressName.")
            var completed = 0L
            input.use { sourceStream ->
                output.use { targetStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        targetStream.write(buffer, 0, count)
                        completed += count
                        onProgress(
                            Progress(
                                itemIndex = itemIndex,
                                itemCount = itemCount,
                                displayName = progressName,
                                completedBytes = completed,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                    targetStream.flush()
                }
            }
            val written = target.refresh(resolver) ?: error("$progressName was written but has already vanished.")
            verifyFile(source, written)
            return written
        } catch (failure: Throwable) {
            target.delete(resolver)
            throw failure
        }
    }

    private fun verifyFile(source: DocNode, target: DocNode) {
        val expected = source.size
        val actual = target.size
        if (expected != null && actual != null) {
            check(expected == actual) {
                "Copy verification failed for ${source.name}: expected $expected bytes, wrote $actual bytes."
            }
        }
    }

    private fun finalizeTarget(plan: TargetPlan, staged: DocNode): DocNode {
        val existing = plan.existing ?: return staged
        if (!existing.delete(resolver)) {
            staged.delete(resolver)
            error("Unable to replace ${plan.requestedName}; the original was left untouched.")
        }
        if (plan.stagingName == plan.requestedName) return staged
        return try {
            staged.rename(resolver, plan.requestedName)
        } catch (failure: Exception) {
            error(
                "The replacement data is safe, but the provider could not restore the requested " +
                    "name. It remains as ${staged.name}.",
            )
        }
    }

    private fun resolveTargetPlan(
        destination: DocNode,
        requestedName: String,
        policy: ConflictPolicy,
    ): TargetPlan? {
        val existing = destination.findChild(resolver, requestedName)
            ?: return TargetPlan(requestedName = requestedName, stagingName = requestedName)
        return when (policy) {
            ConflictPolicy.ASK -> error("A file named $requestedName already exists.")
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> {
                val unique = uniqueName(destination, requestedName)
                TargetPlan(requestedName = unique, stagingName = unique)
            }
            ConflictPolicy.REPLACE -> TargetPlan(
                requestedName = requestedName,
                stagingName = uniqueStagingName(destination, requestedName),
                existing = existing,
            )
        }
    }

    private fun uniqueStagingName(destination: DocNode, requestedName: String): String {
        val safeName = requestedName.replace('/', '_')
        while (true) {
            val candidate = ".fylz-replace-${UUID.randomUUID()}-$safeName"
            if (destination.findChild(resolver, candidate) == null) return candidate
        }
    }

    private fun uniqueName(destination: DocNode, requestedName: String): String {
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$extension"
            if (destination.findChild(resolver, candidate) == null) return candidate
            index += 1
        }
    }

    private companion object {
        const val MOVE_SOURCE_DELETE_PENDING = "MOVE_SOURCE_DELETE_PENDING"
        const val MOVE_DESTINATION_MISSING = "MOVE_DESTINATION_MISSING"
        const val MOVE_DESTINATION_UNVERIFIED = "MOVE_DESTINATION_UNVERIFIED"
    }
}

/** Mirrors `DocumentFile.canWrite()`'s own flag check; [DocNode] exposes raw flags only. */
private val DocNode.canWrite: Boolean
    get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0 ||
        flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0 ||
        (isDirectory && flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)

/** The one child named [name], or null. One [DocNode.children] query per call, same cost as the
 * `DocumentFile.findFile` calls this replaces. */
private fun DocNode.findChild(resolver: ContentResolver, name: String): DocNode? =
    children(resolver).firstOrNull { it.name == name }
