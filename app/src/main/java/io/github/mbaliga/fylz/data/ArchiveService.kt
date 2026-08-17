package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.core.model.ItemRef
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.core.operations.FileOperationType
import io.github.mbaliga.fylz.core.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.core.operations.OperationState
import io.github.mbaliga.fylz.storage.toItemRef
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
import java.io.ByteArrayInputStream
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
                    source = uri.toItemRef(),
                    destination = destinationUri.toItemRef(),
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

            // Physical staging names are synthetic and carry no meaning -- the zip path for
            // each entry is tracked alongside its staged file instead, so a folder's contents
            // never need to be mirrored into nested local directories.
            val staged = File(workspace, "input").apply { mkdirs() }
            val usedNames = mutableSetOf<String>()
            var stagedTotal = 0L
            var stagedFileCount = 0
            val fileEntries = mutableListOf<Pair<File, String>>()
            val directoryEntries = mutableListOf<String>()

            fun stageFile(sourceUri: Uri, displayName: String, zipPath: String) {
                val target = File(staged, "f${stagedFileCount++}")
                val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    target.outputStream().use { output ->
                        copyBounded(input, output, extractionLimits.maxFileBytes)
                    }
                } ?: error("Unable to read $displayName")
                if (Long.MAX_VALUE - stagedTotal < copied) error("Archive input size overflowed.")
                stagedTotal += copied
                require(stagedTotal <= extractionLimits.maxTotalUncompressedBytes) {
                    "Selected files exceed the total archive input limit."
                }
                fileEntries += target to zipPath
            }

            // A folder with no children produces its own entry so it survives round-trip; a
            // folder with children never does -- extraction's parentFile.mkdirs() recreates it
            // implicitly from whatever lives inside, empty or not. depth counts the top-level
            // source folder as 1, so MAX_FOLDER_DEPTH bounds a cycle a misbehaving provider
            // could otherwise turn into unbounded recursion.
            suspend fun stageDirectory(directoryUri: Uri, zipPath: String, depth: Int) {
                require(depth <= MAX_FOLDER_DEPTH) { "Folder nesting is too deep to archive." }
                val children = listChildDocuments(directoryUri)
                if (children.isEmpty()) {
                    directoryEntries += zipPath
                    return
                }
                children.forEach { child ->
                    coroutineContext.ensureActive()
                    val childZipPath = "$zipPath/${sanitizeName(child.name)}"
                    if (child.isDirectory) {
                        stageDirectory(child.uri, childZipPath, depth + 1)
                    } else {
                        stageFile(child.uri, child.name, childZipPath)
                    }
                }
            }

            sourceUris.forEach { uri ->
                coroutineContext.ensureActive()
                // Bundle-args query, not DocumentFile: every uri here already carries tree
                // context, minted by buildDocumentUriUsingTree wherever the app resolves
                // entries, but DocumentFile's own accessors (isFile/isDirectory/name/listFiles)
                // all route through the deprecated 4-String query overload internally, which a
                // real DocumentsProvider hard-refuses once queried in-process (the same seam
                // DocumentRepository.probe's comment documents). queryDocumentSummary and
                // listChildDocuments below use the same working overload probe() does instead.
                val summary = queryDocumentSummary(uri) ?: error("Unable to open a selected source.")
                // Flat top-level namespace: sources keep their own name unless two sources
                // collide, folders included, matching the disambiguation files already got.
                val topName = uniqueName(sanitizeName(summary.name), usedNames)
                if (summary.isDirectory) {
                    stageDirectory(uri, topName, depth = 1)
                } else {
                    stageFile(uri, summary.name, topName)
                }
            }

            val encrypted = !password.isNullOrEmpty()
            val archive = File(workspace, "fylz.zip")
            val zipFile = if (encrypted) ZipFile(archive, password) else ZipFile(archive)
            fileEntries.forEach { (file, zipPath) ->
                coroutineContext.ensureActive()
                zipFile.addFile(
                    file,
                    ZipParameters().apply {
                        fileNameInZip = zipPath
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
            directoryEntries.forEach { zipPath ->
                coroutineContext.ensureActive()
                // zip4j's documented convention for a directory-only entry: no addDirectory
                // API exists, but an empty stream whose name ends in '/' writes no data and
                // is read back with FileHeader.isDirectory set.
                zipFile.addStream(
                    ByteArrayInputStream(ByteArray(0)),
                    ZipParameters().apply {
                        fileNameInZip = "$zipPath/"
                        compressionMethod = CompressionMethod.STORE
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

            journal.put(operation.succeeded(destinationUri.toItemRef()))
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
                    source = archiveUri.toItemRef(),
                    destination = destinationTreeUri.toItemRef(),
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

            journal.put(operation.succeeded(requireNotNull(providerExtractionRoot).uri.toItemRef()))
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

    private fun FileOperation.succeeded(destination: ItemRef): FileOperation = copy(
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

    private fun queryName(uri: Uri): String? {
        // Bundle-args overload, not the deprecated 4-String one: a DocumentsProvider hard-
        // refuses the legacy query shape once queried in-process (DocumentRepository.probe's
        // identical comment/seam).
        val queryArgs: Bundle? = null
        val signal: CancellationSignal? = null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), queryArgs, signal)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    /** [queryDocumentSummary] and [listChildDocuments]'s shared shape: just enough to route a source. */
    private data class DocumentSummary(val uri: Uri, val name: String, val isDirectory: Boolean)

    /** Single-document name/type probe, [queryName]'s sibling -- see its comment for the overload choice. */
    private fun queryDocumentSummary(uri: Uri): DocumentSummary? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val queryArgs: Bundle? = null
        val signal: CancellationSignal? = null
        return runCatching {
            context.contentResolver.query(uri, projection, queryArgs, signal)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (nameIndex < 0) return@use null
                val name = cursor.getString(nameIndex) ?: return@use null
                val mimeType = if (mimeIndex < 0) null else cursor.getString(mimeIndex)
                DocumentSummary(uri, name, mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }.getOrNull()
    }

    /** A folder's immediate children, [queryDocumentSummary]'s counterpart for a listing instead of one document. */
    private fun listChildDocuments(directoryUri: Uri): List<DocumentSummary> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            directoryUri,
            DocumentsContract.getDocumentId(directoryUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val queryArgs: Bundle? = null
        val signal: CancellationSignal? = null
        val entries = mutableListOf<DocumentSummary>()
        context.contentResolver.query(childrenUri, projection, queryArgs, signal)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                entries += DocumentSummary(
                    uri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId),
                    name = cursor.getString(nameIndex) ?: "untitled",
                    isDirectory = cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return entries
    }

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
        const val MAX_FOLDER_DEPTH = 32
    }
}
