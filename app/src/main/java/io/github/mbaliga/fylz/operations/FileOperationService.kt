package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Provider-neutral, cancellable copy and move implementation for SAF documents. */
class FileOperationService(private val context: Context) {
    data class Progress(
        val itemIndex: Int,
        val itemCount: Int,
        val displayName: String,
        val completedBytes: Long,
        val totalBytes: Long?,
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

        buildList {
            sourceUris.forEachIndexed { index, sourceUri ->
                coroutineContext.ensureActive()
                val source = DocumentFile.fromSingleUri(context, sourceUri)
                    ?: error("Unable to open a selected item.")
                require(source.exists()) { "A selected item no longer exists." }
                val sourceName = source.name ?: "untitled"
                val requestedName = resolveTargetName(destination, sourceName, conflictPolicy)
                if (requestedName == null) return@forEachIndexed

                val total = source.length().takeIf { source.isFile && it >= 0L }
                val copied = copyDocument(
                    source = source,
                    destinationDirectory = destination,
                    requestedName = requestedName,
                    itemIndex = index,
                    itemCount = sourceUris.size,
                    totalBytes = total,
                    onProgress = onProgress,
                )
                verifyCopy(source, copied)
                if (move) {
                    check(source.delete()) {
                        "The item was copied, but the provider refused to remove the original."
                    }
                }
                add(copied.uri)
            }
        }
    }

    private suspend fun copyDocument(
        source: DocumentFile,
        destinationDirectory: DocumentFile,
        requestedName: String,
        itemIndex: Int,
        itemCount: Int,
        totalBytes: Long?,
        onProgress: (Progress) -> Unit,
    ): DocumentFile {
        coroutineContext.ensureActive()
        if (source.isDirectory) {
            val directory = destinationDirectory.createDirectory(requestedName)
                ?: error("Unable to create $requestedName.")
            source.listFiles().forEach { child ->
                copyDocument(
                    source = child,
                    destinationDirectory = directory,
                    requestedName = child.name ?: "untitled",
                    itemIndex = itemIndex,
                    itemCount = itemCount,
                    totalBytes = null,
                    onProgress = onProgress,
                )
            }
            return directory
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
                                displayName = requestedName,
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

    private fun resolveTargetName(
        destination: DocumentFile,
        requestedName: String,
        policy: ConflictPolicy,
    ): String? {
        val existing = destination.findFile(requestedName) ?: return requestedName
        return when (policy) {
            ConflictPolicy.ASK -> error("A file named $requestedName already exists.")
            ConflictPolicy.SKIP -> null
            ConflictPolicy.REPLACE -> {
                check(existing.delete()) { "Unable to replace $requestedName." }
                requestedName
            }
            ConflictPolicy.KEEP_BOTH -> uniqueName(destination, requestedName)
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
