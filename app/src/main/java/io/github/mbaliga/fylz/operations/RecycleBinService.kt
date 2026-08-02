package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Implements non-destructive deletion for Storage Access Framework providers.
 *
 * Fylz never falls back to permanent deletion. A recycle operation succeeds only after the item is
 * copied into a writable recycle root and the copy has been verified by byte count where available.
 */
class RecycleBinService(
    private val context: Context,
    private val store: RecycleBinStore = RecycleBinStore(context),
) {
    suspend fun recycle(
        sourceUri: Uri,
        originalParentUri: Uri?,
        recycleRootUri: Uri,
    ): RecycleRecord = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromSingleUri(context, sourceUri)
            ?: error("Unable to open the selected item.")
        require(source.exists()) { "The selected item no longer exists." }

        val recycleRoot = DocumentFile.fromTreeUri(context, recycleRootUri)
            ?: error("Unable to open the recycle location.")
        require(recycleRoot.canWrite() && recycleRoot.isDirectory) {
            "The selected provider cannot write to its Fylz recycle location."
        }

        val itemId = UUID.randomUUID().toString()
        val container = recycleRoot.createDirectory(itemId)
            ?: error("Unable to create a recycle transaction folder.")
        val displayName = source.name ?: "untitled"

        try {
            val recycled = copyDocument(source, container, displayName)
            verifyCopy(source, recycled)
            check(source.delete()) {
                "The item was copied to the recycle bin, but the provider refused to remove the original."
            }

            RecycleRecord(
                itemId = itemId,
                originalUri = sourceUri,
                recycledUri = recycled.uri,
                originalParentUri = originalParentUri,
                originalDisplayName = displayName,
                providerAuthority = sourceUri.authority,
                sizeBytes = source.length().takeIf { it >= 0L },
                recycledAtMillis = System.currentTimeMillis(),
            ).also(store::put)
        } catch (failure: Throwable) {
            container.delete()
            throw failure
        }
    }

    suspend fun restore(
        itemId: String,
        fallbackDestinationTreeUri: Uri? = null,
        conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    ): Uri = withContext(Dispatchers.IO) {
        val record = store.find(itemId) ?: error("Recycle record not found.")
        val recycled = DocumentFile.fromSingleUri(context, record.recycledUri)
            ?: error("The recycled item is unavailable.")
        require(recycled.exists()) { "The recycled item no longer exists." }

        val destinationUri = record.originalParentUri ?: fallbackDestinationTreeUri
            ?: error("The original folder is unavailable. Choose a restore destination.")
        val destination = DocumentFile.fromTreeUri(context, destinationUri)
            ?: DocumentFile.fromSingleUri(context, destinationUri)
            ?: error("Unable to open the restore destination.")
        require(destination.isDirectory && destination.canWrite()) {
            "The restore destination is not writable."
        }

        val targetName = resolveTargetName(destination, record.originalDisplayName, conflictPolicy)
            ?: return@withContext record.recycledUri
        val restored = copyDocument(recycled, destination, targetName)
        verifyCopy(recycled, restored)
        check(recycled.delete()) {
            "The item was restored, but the provider refused to remove the recycle copy."
        }
        store.remove(itemId)
        restored.uri
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
        val recycled = DocumentFile.fromSingleUri(context, record.recycledUri)
            ?: error("The recycled item is unavailable.")
        check(!recycled.exists() || recycled.delete()) {
            "The provider refused permanent deletion."
        }
        store.remove(itemId)
    }

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
            source.listFiles().forEach { child ->
                copyDocument(child, directory, child.name ?: "untitled")
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
