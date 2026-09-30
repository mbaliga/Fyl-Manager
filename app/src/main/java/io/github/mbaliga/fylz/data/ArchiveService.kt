package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationItem
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationState
import io.github.mbaliga.fylz.storage.queryRootAvailableBytes
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

/**
 * Provider-neutral, bounded ZIP creation, and password-protected ZIP extraction, with zip4j.
 * Inspection left this class in M3.2 (`archive.ArchiveInspector`, the isolated decoder process,
 * no copying); selective extraction of a plain archive left it in M3.4c
 * (`operations.ExtractPlanner`/`ArchiveExtractor`, the transfer queue). [extractZip] now stays
 * only for **encrypted ZIP files**, until M3.9 gives every format a password prompt through that
 * same queue and M3.10 removes zip4j -- selective extraction from an encrypted archive is refused
 * there today (`operations.ExtractPlanner.ENCRYPTED_REFUSED`). Its structural decision comes from
 * the same engine [ExtractPlanner] uses, over the staged copy this class already makes: a second
 * `client.inspectArchive` call, whose `policyAllowed`/`policyReason` replaces the deleted Kotlin
 * `ArchiveExtractionPolicy` (design `DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.7). [createZip]'s
 * own numbers come from [limits] too, where [ArchiveExtractionLimits] gave them before.
 */
class ArchiveService(
    private val context: Context,
    private val client: DecoderClient,
    private val limits: ArchiveLimits = ArchiveLimits.forInspection(),
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
                            copyBounded(input, output, limits.maxFileBytes)
                        }
                    } ?: error("Unable to read $requestedName")
                    if (Long.MAX_VALUE - stagedTotal < copied) error("Archive input size overflowed.")
                    stagedTotal += copied
                    require(stagedTotal <= limits.maxTotalUncompressedBytes) {
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
                require(archive.length() <= limits.maxArchiveBytes) {
                    "The generated archive exceeds the output safety limit."
                }
            }

            context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                archive.inputStream().use { input -> copyBounded(input, output, limits.maxArchiveBytes) }
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

    /**
     * Encrypted ZIPs only (M3.4c, design section 2.7): still zip4j, still staged -- the whole
     * archive is copied to cache first, same as before -- but the structural decision now comes
     * from [client]'s own `inspectArchive` over that staged copy (the same engine `ExtractPlanner`
     * asks), not the deleted Kotlin `ArchiveExtractionPolicy`. A plain ZIP is refused here: it
     * belongs to `operations.ExtractPlanner`/`ArchiveExtractor` through the transfer queue now.
     */
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
            require(zipFile.isEncrypted) { "This archive is not password-protected; use Extract instead." }
            require(!password.isNullOrEmpty()) { "This archive requires a password." }
            zipFile.setPassword(password)

            val summary = inspectStaged(archive)
            require(summary.policyAllowed) { summary.policyReason ?: "Archive extraction was refused." }
            val totalUncompressed = summary.totalUncompressedBytes.takeIf { it >= 0L }
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
                queryRootAvailableBytes(context, destinationTreeUri),
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
            require(extractedEntries <= limits.maxEntries) {
                "Archive contains too many extracted entries."
            }
            val target = safeExtractionTarget(root, header)
            if (header.isDirectory) {
                check(target.mkdirs() || target.isDirectory) { "Unable to create ${header.fileName}." }
                return@forEach
            }
            check(target.parentFile?.mkdirs() != false) { "Unable to create extraction folders." }
            val expected = header.uncompressedSize
            require(expected in 0L..limits.maxFileBytes) {
                "Archive contains a file larger than the extraction limit."
            }
            val copied = zipFile.getInputStream(header).use { input ->
                target.outputStream().use { output ->
                    copyBounded(input, output, limits.maxFileBytes)
                }
            }
            require(copied == expected) {
                "Extracted size did not match archive metadata for ${header.fileName}."
            }
            if (Long.MAX_VALUE - extractedBytes < copied) error("Extracted size overflowed.")
            extractedBytes += copied
            require(extractedBytes <= limits.maxTotalUncompressedBytes) {
                "Archive expands beyond the total extraction limit."
            }
        }
    }

    private fun safeExtractionTarget(root: File, header: FileHeader): File {
        val normalized = header.fileName.replace('\\', '/').trimEnd('/')
        val depth = normalized.split('/').count { it.isNotEmpty() }
        require(depth in 1..limits.maxPathDepth) { "Archive path nesting is too deep." }
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

    /**
     * The whole-archive copy M3.2 removed from inspection and M3.4 removes from extraction; kept
     * only for [extractZip], because zip4j reads a `File`. (`archive.ArchiveSource` is the
     * seek-or-stage replacement.)
     */
    private fun stageArchive(archiveUri: Uri, workspace: File): File {
        val archive = File(workspace, "input.zip")
        context.contentResolver.openInputStream(archiveUri)?.use { input ->
            archive.outputStream().use { output ->
                copyBounded(input, output, limits.maxArchiveBytes)
            }
        } ?: error("Unable to read the archive.")
        return archive
    }

    /**
     * [archive]'s structural verdict from the same engine `ExtractPlanner` asks (design section
     * 2.7): a fresh descriptor on the already-staged file, through [client]'s own `inspectArchive`
     * -- never a descriptor shared with anything else, same rule the catalog follows. A non-`OK`
     * inspection (an encrypted central directory has no metadata to give a verdict over) fails
     * closed with the engine's own message.
     */
    private suspend fun inspectStaged(archive: File): ArchiveInspection {
        val pfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        val call = try {
            client.inspectArchive(pfd, limits, DecoderClient.DEFAULT_MAX_ROWS, DecoderClient.STRUCTURE_TIMEOUT_MILLIS)
        } finally {
            pfd.close()
        }
        val summary = when (call) {
            is DecoderCall.Ok -> call.value
            DecoderCall.TimedOut -> error("The archive took too long to read.")
            DecoderCall.Failed -> error("The archive could not be read safely.")
        }
        require(summary.isOk) { summary.message ?: "The archive could not be read." }
        return summary
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
                source.inputStream().use { input -> copyBounded(input, output, limits.maxFileBytes) }
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
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 256
    }
}
