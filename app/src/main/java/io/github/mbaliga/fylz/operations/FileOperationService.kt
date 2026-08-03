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
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> = transfer(
        sourceUris = sourceUris,
        destinationTreeUri = destinationTreeUri,
        move = false,
        conflictPolicy = conflictPolicy,
        onProgress = onProgress,
    )

    suspend fun move(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
        onProgress: (Progress) -> Unit = {},
    ): List<Uri> = transfer(
        sourceUris = sourceUris,
        destinationTreeUri = destinationTreeUri,
        move = true,
        conflictPolicy = conflictPolicy,
        onProgress = onProgress,
    )

    fun operations(): List<FileOperation> = journal.list()

    private suspend fun transfer(
        sourceUris: List<Uri>,
        destinationTreeUri: Uri,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        onProgress: (Progress) -> Unit,
    ): List<Uri> = withContext(Dispatchers.IO) {
        require(sourceUris.isNotEmpty()) { "Choose at least one item." }
        val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: error("Unable to open the destination folder.")
        require(destination.isDirectory && destination.canWrite()) {
            "The destination folder is not writable."
        }

        val operation = FileOperation(
            type = if (move) FileOperationType.MOVE else FileOperationType.COPY,
            items = sourceUris.map { uri ->
                val source = DocumentFile.fromSingleUri(context, uri)
                OperationItem(
                    source = uri,
                    destination = destinationTreeUri,
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

                    // A move is intentionally copy -> verify -> finalize destination -> remove source.
                    // The source is never deleted when any earlier step fails.
                    if (move) {
                        check(source.delete()) {
                            "The item was copied, but the provider refused to remove the original."
                        }
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
            current = current.copy(
                state = OperationState.SUCCEEDED,
                updatedAtMillis = System.currentTimeMillis(),
            )
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
                state = OperationState.FAILED,
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

    /**
     * Completes a replacement only after the new item has been fully copied and verified.
     * The old item is never removed during preflight or while bytes are still being written.
     */
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
}
