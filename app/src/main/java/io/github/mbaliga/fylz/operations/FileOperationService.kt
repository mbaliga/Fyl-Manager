package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
        val existing: DocumentFile? = null,
    )

    suspend fun copy(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
        destinationPathSegments: List<String> = emptyList(),
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> =
        transfer(sourceUris, destinationTreeUri, false, conflictPolicy, destinationPathSegments, onProgress)

    suspend fun move(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
        destinationPathSegments: List<String> = emptyList(),
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> =
        transfer(sourceUris, destinationTreeUri, true, conflictPolicy, destinationPathSegments, onProgress)

    fun operations(): List<FileOperation> = journal.list()

    /**
     * Completes a move whose verified destination was committed but whose original could not be
     * removed. This never copies again, so retry cannot create another destination duplicate.
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
            val destination = DocumentFile.fromSingleUri(context, destinationUri)
            if (destination?.exists() != true) {
                return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_MISSING)
            }
            val source = DocumentFile.fromSingleUri(context, item.source)
            if (source?.exists() != true) {
                changed = true
                return@map item.copy(state = OperationState.SUCCEEDED, errorCode = null)
            }
            if (source.isFile && destination.isFile) {
                val expected = source.length()
                val actual = destination.length()
                if (expected >= 0L && actual >= 0L && expected != actual) {
                    return@map item.copy(state = OperationState.NEEDS_ATTENTION, errorCode = MOVE_DESTINATION_UNVERIFIED)
                }
            }
            if (source.delete()) {
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
        destinationPathSegments: List<String>,
        onProgress: (Progress) -> Unit,
    ): List<Uri> = withContext(Dispatchers.IO) {
        require(sourceUris.isNotEmpty()) { "Choose at least one item." }
        // Subfolder destinations are reached by walking display names down from the granted
        // tree root. Walking is the only provider-neutral resolution: synthesising a tree URI
        // rooted at the subfolder works on our own provider and fails permission checks on
        // third-party ones, and parsing the document ID for a path assumes an ID scheme no
        // contract promises (acceptance law #8).
        val root = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: error("Unable to open the destination folder.")
        val destination = destinationPathSegments.fold(root) { folder, segment ->
            folder.findFile(segment)
                ?.takeIf(DocumentFile::isDirectory)
                ?: error("The destination folder “$segment” no longer exists.")
        }
        require(destination.isDirectory && destination.canWrite()) {
            "The destination folder is not writable."
        }
        // Journaled destination: the resolved folder's own URI when walking happened. The
        // retry policy refuses to replay these (it cannot re-walk display names), which is the
        // conservative outcome — a replay against the raw tree URI would land files in the
        // tree ROOT, silently the wrong folder.
        val journaledDestination = if (destinationPathSegments.isEmpty()) destinationTreeUri else destination.uri

        val operation = FileOperation(
            type = if (move) FileOperationType.MOVE else FileOperationType.COPY,
            items = sourceUris.map { uri ->
                val source = DocumentFile.fromSingleUri(context, uri)
                OperationItem(
                    source = uri,
                    destination = journaledDestination,
                    displayName = source?.name ?: "untitled",
                    expectedBytes = source?.length()?.takeIf { source.isFile && it >= 0L },
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
                    val source = DocumentFile.fromSingleUri(context, sourceUri)
                        ?: error("Unable to open a selected item.")
                    require(source.exists()) { "A selected item no longer exists." }
                    val sourceName = source.name ?: "untitled"
                    val plan = resolveTargetPlan(destination, sourceName, conflictPolicy)
                    if (plan == null) {
                        current = updateItem(current, index) {
                            it.copy(state = OperationState.SUCCEEDED, errorCode = "SKIPPED_CONFLICT")
                        }
                        journal.put(current)
                        return@forEachIndexed
                    }

                    val total = source.length().takeIf { source.isFile && it >= 0L }
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
                    verifyCopy(source, staged)
                    val copied = finalizeTarget(plan, staged)

                    if (move && !source.delete()) {
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

    private suspend fun copyDocument(
        source: DocumentFile,
        destinationDirectory: DocumentFile,
        requestedName: String,
        progressName: String,
        itemIndex: Int,
        itemCount: Int,
        totalBytes: Long?,
        onProgress: (Progress) -> Unit,
    ): DocumentFile {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            val directory = destinationDirectory.createDirectory(requestedName)
                ?: error("Unable to create $progressName.")
            try {
                source.listFiles().forEach { child ->
                    copyDocument(
                        source = child,
                        destinationDirectory = directory,
                        requestedName = child.name ?: "untitled",
                        progressName = child.name ?: progressName,
                        itemIndex = itemIndex,
                        itemCount = itemCount,
                        totalBytes = null,
                        onProgress = onProgress,
                    )
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
        ) ?: error("Unable to create $progressName.")

        try {
            val input = context.contentResolver.openInputStream(source.uri)
                ?: error("Unable to read $progressName.")
            val output = context.contentResolver.openOutputStream(target.uri, "w")
                ?: error("Unable to write $progressName.")
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
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private fun verifyCopy(source: DocumentFile, target: DocumentFile) {
        val expected = source.length()
        val actual = target.length()
        if (source.isFile && expected >= 0L && actual >= 0L) {
            check(expected == actual) {
                "Copy verification failed: expected $expected bytes, wrote $actual bytes."
            }
        }
    }

    private fun finalizeTarget(plan: TargetPlan, staged: DocumentFile): DocumentFile {
        val existing = plan.existing ?: return staged
        check(existing.delete()) {
            staged.delete()
            "Unable to replace ${plan.requestedName}; the original was left untouched."
        }
        if (plan.stagingName == plan.requestedName) return staged
        check(staged.renameTo(plan.requestedName)) {
            "The replacement data is safe, but the provider could not restore the requested name. " +
                "It remains as ${staged.name ?: plan.stagingName}."
        }
        return staged
    }

    private fun resolveTargetPlan(
        destination: DocumentFile,
        requestedName: String,
        policy: ConflictPolicy,
    ): TargetPlan? {
        val existing = destination.findFile(requestedName)
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

    private fun uniqueStagingName(destination: DocumentFile, requestedName: String): String {
        val safeName = requestedName.replace('/', '_')
        while (true) {
            val candidate = ".fylz-replace-${UUID.randomUUID()}-$safeName"
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

    private companion object {
        const val MOVE_SOURCE_DELETE_PENDING = "MOVE_SOURCE_DELETE_PENDING"
        const val MOVE_DESTINATION_MISSING = "MOVE_DESTINATION_MISSING"
        const val MOVE_DESTINATION_UNVERIFIED = "MOVE_DESTINATION_UNVERIFIED"
    }
}
