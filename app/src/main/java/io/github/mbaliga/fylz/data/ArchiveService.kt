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
import java.nio.file.Files
import java.util.UUID

/**
 * Provider-neutral ZIP engine used by the forthcoming selection/action UI.
 *
 * Files are staged only inside app-private cache storage. Password-protected archives use AES-256.
 * Extraction validates every archive path before Zip4j is allowed to write it, then copies only
 * ordinary files and directories into the user-selected Storage Access Framework destination.
 */
class ArchiveService(private val context: Context) {

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

            val archive = File(workspace, "fylz.zip")
            val zipFile = if (password.isNullOrEmpty()) ZipFile(archive) else ZipFile(archive, password)
            sourceFiles.forEach { file ->
                zipFile.addFile(
                    file,
                    ZipParameters().apply {
                        fileNameInZip = file.name
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.NORMAL
                        if (!password.isNullOrEmpty()) {
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
            val archive = File(workspace, "input.zip")
            context.contentResolver.openInputStream(archiveUri)?.use { input ->
                archive.outputStream().use(input::copyTo)
            } ?: error("Unable to read the archive.")

            val extracted = File(workspace, "extracted").apply { mkdirs() }
            val zipFile = ZipFile(archive)
            if (zipFile.isEncrypted) {
                require(!password.isNullOrEmpty()) { "This archive requires a password." }
                zipFile.setPassword(password)
            }

            val canonicalRoot = extracted.canonicalFile
            zipFile.fileHeaders.forEach { header ->
                val target = File(canonicalRoot, header.fileName).canonicalFile
                check(
                    target.path == canonicalRoot.path ||
                        target.path.startsWith(canonicalRoot.path + File.separator),
                ) { "Unsafe archive path: ${header.fileName}" }
            }
            zipFile.extractAll(canonicalRoot.path)

            val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
                ?: error("Unable to open the destination folder.")
            canonicalRoot.listFiles().orEmpty().forEach { copyIntoProvider(it, destination) }
        } finally {
            workspace.deleteRecursively()
        }
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
}
