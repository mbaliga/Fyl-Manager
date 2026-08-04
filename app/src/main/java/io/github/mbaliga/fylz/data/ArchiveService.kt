package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
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
import kotlin.coroutines.coroutineContext

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
    val temporarySpaceRequiredBytes: Long? = null,
    val temporarySpaceAvailableBytes: Long? = null,
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
        require(password == null || password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "Archive passwords must contain between $MIN_PASSWORD_LENGTH and $MAX_PASSWORD_LENGTH characters."
        }
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
            var stagedTotal = 0L
            val sourceFiles = sourceUris.mapIndexed { index, uri ->
                coroutineContext.ensureActive()
                val source = DocumentFile.fromSingleUri(context, uri)
                    ?: error("Unable to open a selected source.")
                require(source.isFile) { "Folders cannot be added to an archive yet." }
                val requestedName = source.name ?: queryName(uri) ?: "file-${index + 1}"
                val safeName = uniqueName(sanitizeName(requestedName), usedNames)
                File(staged, safeName).also { target ->
                    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output ->
                            copyBounded(input, output, extractionLimits.maxFileBytes)
                        }
                    } ?: error("Unable to read $requestedName")
                    if (Long.MAX_VALUE - stagedTotal < copied) error("Archive input size overflowed.")
                    stagedTotal += copied
                    require(stagedTotal <= extractionLimits.maxTotalUncompressedBytes) {
                        "Selected files exceed the total archive input limit."
                    }
                }
            }

            val encrypted = !password.isNullOrEmpty()
            val archive = File(workspace, "fylz.zip")
            val zipFile = if (encrypted) ZipFile(archive, password) else ZipFile(archive)
            sourceFiles.forEach { file ->
                coroutineContext.ensureActive()
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
                require(archive.length() <= extractionLimits.maxArchiveBytes) {
                    "The generated archive exceeds the output safety limit."
                }
            }

            context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                archive.inputStream().use { input -> copyBounded(input, output, extractionLimits.maxArchiveBytes) }
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
            val uncompressed = metadata.sumKnownUncompressedBytes()
            val requirements = uncompressed?.let { ArchiveSpacePolicy.requirements(archive.length(), it) }
            ArchiveInspection(
                encrypted = zipFile.isEncrypted,
                archiveBytes = archive.length(),
                entryCount = metadata.size,
                fileCount = files.size,
                directoryCount = metadata.size - files.size,
                totalUncompressedBytes = uncompressed,
                visibleEntries = metadata.take(maxVisibleEntries),
                entriesTruncated = metadata.size > maxVisibleEntries,
                extractionDecision = decision,
                temporarySpaceRequiredBytes = requirements?.temporaryBytes,
                temporarySpaceAvailableBytes = availableCacheBytes(),
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
            val totalUncompressed = metadata.sumKnownUncompressedBytes()
                ?: error("Archive size metadata is incomplete or overflowed.")
            val requirements = ArchiveSpacePolicy.requirements(archive.length(), totalUncompressed)
                ?: error("Archive storage requirements overflowed.")
            val cacheDecision = ArchiveSpacePolicy.evaluate(
                requirements.temporaryBytes,
                availableCacheBytes(),
                "temporary",
            )
            require(cacheDecision.allowed) { cacheDecision.reason ?: "Insufficient temporary storage." }
            val destinationDecision = ArchiveSpacePolicy.evaluate(
                requirements.destinationBytes,
                queryProviderAvailableBytes(destinationTreeUri),
                "destination",
            )
            require(destinationDecision.allowed) { destinationDecision.reason ?: "Insufficient destination storage." }

            val extracted = File(workspace, "extracted").apply { mkdirs() }
            extractBounded(zipFile, extracted)

            val requestedFolderName = extractionFolderBaseName(archiveDisplayName)
            val extractionFolderName = uniqueDirectoryName(destination, requestedFolderName)
            providerExtractionRoot = destination.createDirectory(extractionFolderName)
                ?: error("Unable to create the extraction folder.")
            extracted.listFiles().orEmpty().forEach { source ->
                coroutineContext.ensureActive()
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

    private suspend fun extractBounded(zipFile: ZipFile, destination: File) {
        val root = destination.canonicalFile
        var extractedEntries = 0
        var extractedBytes = 0L
        zipFile.fileHeaders.forEach { header ->
            coroutineContext.ensureActive()
            extractedEntries += 1
            require(extractedEntries <= extractionLimits.maxEntries) {
                "Archive contains too many extracted entries."
            }
            val target = safeExtractionTarget(root, header)
            if (header.isDirectory) {
                check(target.mkdirs() || target.isDirectory) { "Unable to create ${header.fileName}." }
                return@forEach
            }
            check(target.parentFile?.mkdirs() != false) { "Unable to create extraction folders." }
            val expected = header.uncompressedSize
            require(expected in 0L..extractionLimits.maxFileBytes) {
                "Archive contains a file larger than the extraction limit."
            }
            val copied = zipFile.getInputStream(header).use { input ->
                target.outputStream().use { output ->
                    copyBounded(input, output, extractionLimits.maxFileBytes)
                }
            }
            require(copied == expected) {
                "Extracted size did not match archive metadata for ${header.fileName}."
            }
            if (Long.MAX_VALUE - extractedBytes < copied) error("Extracted size overflowed.")
            extractedBytes += copied
            require(extractedBytes <= extractionLimits.maxTotalUncompressedBytes) {
                "Archive expands beyond the total extraction limit."
            }
        }
    }

    private fun safeExtractionTarget(root: File, header: FileHeader): File {
        val normalized = header.fileName.replace('\\', '/').trimEnd('/')
        val depth = normalized.split('/').count { it.isNotEmpty() }
        require(depth in 1..extractionLimits.maxPathDepth) { "Archive path nesting is too deep." }
        val target = File(root, normalized).canonicalFile
        check(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Unsafe archive path: ${header.fileName}"
        }
        return target
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
        while (index <= 9_999) {
            val candidate = "$requestedName ($index)"
            if (destination.findFile(candidate) == null) return candidate
            index += 1
        }
        error("Unable to find an available extraction folder name.")
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
        try {
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                source.inputStream().use { input -> copyBounded(input, output, extractionLimits.maxFileBytes) }
            } ?: error("Unable to write ${source.name}")
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (Long.MAX_VALUE - total < count) error("Byte count overflowed.")
            total += count
            require(total <= maxBytes) { "Data exceeds the allowed size." }
            output.write(buffer, 0, count)
        }
        output.flush()
        return total
    }

    private fun availableCacheBytes(): Long? = runCatching {
        StatFs(context.cacheDir.absolutePath).availableBytes
    }.getOrNull()

    private fun queryProviderAvailableBytes(treeUri: Uri): Long? = runCatching {
        val authority = treeUri.authority ?: return@runCatching null
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val expectedRootId = documentId.substringBefore(':')
        val rootsUri = DocumentsContract.buildRootsUri(authority)
        val projection = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        context.contentResolver.query(rootsUri, projection, null, null, null)?.use { cursor ->
            val rootIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
            val bytesIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
            while (cursor.moveToNext()) {
                if (rootIndex < 0 || bytesIndex < 0 || cursor.isNull(bytesIndex)) continue
                if (cursor.getString(rootIndex) == expectedRootId) {
                    return@use cursor.getLong(bytesIndex).takeIf { it >= 0L }
                }
            }
            null
        }
    }.getOrNull()

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
        is ZipException -> "INVALID_PASSWORD_OR_ARCHIVE"
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
        while (index <= 9_999) {
            val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
            if (used.add(candidate.lowercase())) return candidate
            index += 1
        }
        error("Unable to generate a unique archive entry name.")
    }

    private companion object {
        const val DEFAULT_VISIBLE_ENTRY_LIMIT = 500
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 256
    }
}
