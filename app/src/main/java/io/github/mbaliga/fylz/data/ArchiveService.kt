package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
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

/**
 * Provider-neutral ZIP engine.
 *
 * Files are staged only inside app-private cache storage. Password-protected archives use AES-256.
 * Inspection and extraction share the same bounded metadata preflight so the preview cannot claim
 * an archive is safe when extraction would reject it.
 */
class ArchiveService(
    private val context: Context,
    private val extractionLimits: ArchiveExtractionLimits = ArchiveExtractionLimits(),
) {

    suspend fun createZip(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        password: CharArray? = null,
    ) = withContext(Dispatchers.IO) {
        require(sourceUris.isNotEmpty()) { "Choose at least one file." }
        val workspace = newWorkspace()
        try {
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

            val encrypted = password != null && password.isNotEmpty()
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
            val fileEntries = metadata.filterNot(ArchiveEntryMetadata::directory)
            ArchiveInspection(
                encrypted = zipFile.isEncrypted,
                archiveBytes = archive.length(),
                entryCount = metadata.size,
                fileCount = fileEntries.size,
                directoryCount = metadata.size - fileEntries.size,
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
        val workspace = newWorkspace()
        try {
            val archive = stageArchive(archiveUri, workspace)

            val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
                ?: error("Unable to open the destination folder.")
            require(destination.isDirectory && destination.canWrite()) {
                "The destination folder is not writable."
            }

            val extracted = File(workspace, "extracted").apply { mkdirs() }
            val zipFile = ZipFile(archive)
            if (zipFile.isEncrypted) {
                require(password != null && password.isNotEmpty()) {
                    "This archive requires a password."
                }
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

            canonicalRoot.listFiles().orEmpty().forEach { copyIntoProvider(it, destination) }
        } finally {
            password?.fill('\u0000')
            workspace.deleteRecursively()
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
            if (entry.uncompressedBytes < 0L || Long.MAX_VALUE - total < entry.uncompressedBytes) {
                return null
            }
            total += entry.uncompressedBytes
        }
        return total
    }

    private fun copyIntoProvider(source: File, destination: DocumentFile) {
        check(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not extracted." }
        if (source.isDirectory) {
            val child = destination.findFile(source.name)
                ?.takeIf(DocumentFile::isDirectory)
                ?: destination.createDirectory(source.name)
                ?: error("Unable to create ${source.name}")
            source.listFiles().orEmpty().forEach { copyIntoProvider(it, child) }
            return
        }

        val mimeType = java.net.URLConnection.guessContentTypeFromName(source.name)
            ?: "application/octet-stream"
        val target = destination.findFile(source.name)
            ?: destination.createFile(mimeType, source.name)
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
