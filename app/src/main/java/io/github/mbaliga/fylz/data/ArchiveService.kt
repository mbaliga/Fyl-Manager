package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID

data class ArchiveInspection(
    val encrypted: Boolean,
    val archiveBytes: Long,
    val entryCount: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val totalUncompressedBytes: Long?,
    val visibleEntries: List<ArchiveEntryMetadata>,
    val entriesTruncated: Boolean,
    val extractionDecision: ArchiveExtractionDecision,
)

/** Provider-neutral, bounded ZIP creation, inspection, and extraction. */
class ArchiveService(
    private val context: Context,
    private val extractionLimits: ArchiveExtractionLimits = ArchiveExtractionLimits(),
    private val journal: OperationJournal = OperationJournal(context),
) {
    suspend fun createZip(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        password: CharArray? = null,
    ) = withContext(Dispatchers.IO) {
        require(sourceUris.isNotEmpty()) { "Choose at least one file." }
        var operation = FileOperation(
            type = FileOperationType.ARCHIVE,
            items = sourceUris.mapIndexed { index, uri ->
                OperationItem(
                    source = uri,
                    destination = destinationUri,
                    displayName = queryName(uri) ?: "file-${index + 1}",
                    state = OperationState.PREFLIGHT,
                )
            },
            state = OperationState.PREFLIGHT,
        )
        journal.put(operation)
        val workspace = newWorkspace()
        try {
            operation = operation.running()
            journal.put(operation)

            val staged = File(workspace, "input").apply { mkdirs() }
            val usedNames = mutableSetOf<String>()
            val sourceFiles = sourceUris.mapIndexed { index, uri ->
                val requestedName = queryName(uri) ?: "file-${index + 1}"
                val safeName = uniqueName(sanitizeName(requestedName), usedNames)
                File(staged, safeName).also { target ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use(input::copyTo)
                    } ?: error("Unable to read $requestedName")
                }
            }

            val encrypted = !password.isNullOrEmpty()
            val archive = File(workspace, "fylz.zip")
            val zipFile = if (encrypted) ZipFile(archive, password) else ZipFile(archive)
            sourceFiles.forEach { file ->
                zipFile.addFile(
                    file,
                    ZipParameters().apply {
                        fileNameInZip = file.name
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.NORMAL
                        if (encrypted) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    },
                )
            }

            context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                archive.inputStream().use { it.copyTo(output) }
            } ?: error("Unable to write the destination archive.")

            journal.put(operation.succeeded(destinationUri))
        } catch (cancelled: CancellationException) {
            journal.put(operation.cancelled())
            throw cancelled
        } catch (failure: Throwable) {
            journal.put(operation.failed(failure.errorCode()))
            throw failure
        } finally {
            password?.fill('\u0000')
            workspace.deleteRecursively()
        }
    }

    suspend fun inspectZip(
        archiveUri: Uri,
        maxVisibleEntries: Int = DEFAULT_VISIBLE_ENTRY_LIMIT,
    ): ArchiveInspection = withContext(Dispatchers.IO) {
        require(maxVisibleEntries in 1..extractionLimits.maxEntries) {
            "Invalid archive preview entry limit."
        }
        val workspace = newWorkspace()
        try {
            val archive = stageArchive(archiveUri, workspace)
            val zipFile = ZipFile(archive)
            val metadata = readMetadata(zipFile)
            val decision = ArchiveExtractionPolicy.evaluate(
                archiveBytes = archive.length(),
                entries = metadata,
                limits = extractionLimits,
            )
            val files = metadata.filterNot(ArchiveEntryMetadata::directory)
            ArchiveInspection(
                encrypted = zipFile.isEncrypted,
                archiveBytes = archive.length(),
                entryCount = metadata.size,
                fileCount = files.size,
                directoryCount = metadata.size - files.size,
                totalUncompressedBytes = metadata.sumKnownUncompressedBytes(),
                visibleEntries = metadata.take(maxVisibleEntries),
                entriesTruncated = metadata.size > maxVisibleEntries,
                extractionDecision = decision,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    suspend fun extractZip(
        archiveUri: Uri,
        destinationTreeUri: Uri,
        password: CharArray? = null,
    ) = withContext(Dispatchers.IO) {
        val archiveDisplayName = queryName(archiveUri) ?: "archive.zip"
        var operation = FileOperation(
            type = FileOperationType.EXTRACT,
            items = listOf(
                OperationItem(
                    source = archiveUri,
                    destination = destinationTreeUri,
                    displayName = archiveDisplayName,
                    state = OperationState.PREFLIGHT,
                ),
            ),
            state = OperationState.PREFLIGHT,
        )
        journal.put(operation)
        val workspace = newWorkspace()
        var providerExtractionRoot: DocumentFile? = null
        try {
            operation = operation.running()
            journal.put(operation)

            val archive = stageArchive(archiveUri, workspace)
            val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
                ?: error("Unable to open the destination folder.")
            require(destination.isDirectory && destination.canWrite()) {
                "The destination folder is not writable."
            }

            val extracted = File(workspace, "extracted").apply { mkdirs() }
            val zipFile = ZipFile(archive)
            if (zipFile.isEncrypted) {
                require(!password.isNullOrEmpty()) { "This archive requires a password." }
                zipFile.setPassword(password)
            }

            val metadata = readMetadata(zipFile)
            val decision = ArchiveExtractionPolicy.evaluate(
                archiveBytes = archive.length(),
                entries = metadata,
                limits = extractionLimits,
            )
            require(decision.allowed) { decision.reason ?: "Archive extraction was refused." }

            val canonicalRoot = extracted.canonicalFile
            zipFile.fileHeaders.forEach { header ->
                val target = File(canonicalRoot, header.fileName).canonicalFile
                check(
                    target.path == canonicalRoot.path ||
                        target.path.startsWith(canonicalRoot.path + File.separator),
                ) { "Unsafe archive path: ${header.fileName}" }
            }
            zipFile.extractAll(canonicalRoot.path)

            val requestedFolderName = extractionFolderBaseName(archiveDisplayName)
            val extractionFolderName = uniqueDirectoryName(destination, requestedFolderName)
            providerExtractionRoot = destination.createDirectory(extractionFolderName)
                ?: error("Unable to create the extraction folder.")
            canonicalRoot.listFiles().orEmpty().forEach { source ->
                copyIntoProvider(source, requireNotNull(providerExtractionRoot))
            }

            journal.put(operation.succeeded(requireNotNull(providerExtractionRoot).uri))
        } catch (cancelled: CancellationException) {
            val rollbackComplete = rollbackExtraction(providerExtractionRoot)
            journal.put(
                if (rollbackComplete) operation.cancelled()
                else operation.needsAttention("ROLLBACK_INCOMPLETE"),
            )
            throw cancelled
        } catch (failure: Throwable) {
            val rollbackComplete = rollbackExtraction(providerExtractionRoot)
            journal.put(
                if (rollbackComplete) operation.failed(failure.errorCode())
                else operation.needsAttention("ROLLBACK_INCOMPLETE"),
            )
            throw failure
        } finally {
            password?.fill('\u0000')
            workspace.deleteRecursively()
        }
    }

    private fun rollbackExtraction(root: DocumentFile?): Boolean {
        if (root == null) return true
        return runCatching { root.delete() }.getOrDefault(false)
    }

    private fun extractionFolderBaseName(archiveName: String): String {
        val withoutExtension = archiveName.substringBeforeLast('.', archiveName)
        return sanitizeName(withoutExtension).ifBlank { "Extracted archive" }
    }

    private fun uniqueDirectoryName(destination: DocumentFile, requestedName: String): String {
        if (destination.findFile(requestedName) == null) return requestedName
        var index = 2
        while (true) {
            val candidate = "$requestedName ($index)"
            if (destination.findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private fun stageArchive(archiveUri: Uri, workspace: File): File {
        val archive = File(workspace, "input.zip")
        context.contentResolver.openInputStream(archiveUri)?.use { input ->
            archive.outputStream().use { output ->
                copyBounded(input, output, extractionLimits.maxArchiveBytes)
            }
        } ?: error("Unable to read the archive.")
        return archive
    }

    private fun readMetadata(zipFile: ZipFile): List<ArchiveEntryMetadata> =
        zipFile.fileHeaders.map { header ->
            ArchiveEntryMetadata(
                name = header.fileName,
                directory = header.isDirectory,
                compressedBytes = header.compressedSize,
                uncompressedBytes = header.uncompressedSize,
            )
        }

    private fun List<ArchiveEntryMetadata>.sumKnownUncompressedBytes(): Long? {
        var total = 0L
        for (entry in this) {
            if (entry.uncompressedBytes < 0L || Long.MAX_VALUE - total < entry.uncompressedBytes) return null
            total += entry.uncompressedBytes
        }
        return total
    }

    private fun copyIntoProvider(source: File, destination: DocumentFile) {
        check(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not extracted." }
        if (source.isDirectory) {
            check(destination.findFile(source.name) == null) {
                "Archive contains colliding paths: ${source.name}"
            }
            val child = destination.createDirectory(source.name)
                ?: error("Unable to create ${source.name}")
            source.listFiles().orEmpty().forEach { copyIntoProvider(it, child) }
            return
        }
        check(destination.findFile(source.name) == null) {
            "Archive contains colliding paths: ${source.name}"
        }
        val mimeType = java.net.URLConnection.guessContentTypeFromName(source.name)
            ?: "application/octet-stream"
        val target = destination.createFile(mimeType, source.name)
            ?: error("Unable to create ${source.name}")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } ?: error("Unable to write ${source.name}")
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Archive exceeds the allowed input size." }
            output.write(buffer, 0, count)
        }
        output.flush()
    }

    private fun FileOperation.running(): FileOperation = copy(
        state = OperationState.RUNNING,
        items = items.map { it.copy(state = OperationState.RUNNING) },
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun FileOperation.succeeded(destination: Uri): FileOperation = copy(
        state = OperationState.SUCCEEDED,
        items = items.map {
            it.copy(destination = destination, state = OperationState.SUCCEEDED, errorCode = null)
        },
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun FileOperation.cancelled(): FileOperation = copy(
        state = OperationState.CANCELLED,
        items = items.map { it.copy(state = OperationState.CANCELLED, errorCode = "USER_CANCELLED") },
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun FileOperation.failed(code: String): FileOperation = copy(
        state = OperationState.FAILED,
        items = items.map { it.copy(state = OperationState.FAILED, errorCode = code) },
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun FileOperation.needsAttention(code: String): FileOperation = copy(
        state = OperationState.NEEDS_ATTENTION,
        items = items.map { it.copy(state = OperationState.NEEDS_ATTENTION, errorCode = code) },
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun Throwable.errorCode(): String = when (this) {
        is SecurityException -> "PERMISSION_DENIED"
        is IllegalArgumentException -> "INVALID_ARCHIVE"
        is IllegalStateException -> "ARCHIVE_OPERATION_FAILED"
        else -> "UNEXPECTED_ERROR"
    }

    private fun queryName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun newWorkspace(): File =
        File(context.cacheDir, "archive-work/${UUID.randomUUID()}").apply { mkdirs() }

    private fun sanitizeName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").ifBlank { "untitled" }

    private fun uniqueName(requested: String, used: MutableSet<String>): String {
        if (used.add(requested.lowercase())) return requested
        val base = requested.substringBeforeLast('.', requested)
        val extension = requested.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
            if (used.add(candidate.lowercase())) return candidate
            index += 1
        }
    }

    private companion object {
        const val DEFAULT_VISIBLE_ENTRY_LIMIT = 500
    }
}
