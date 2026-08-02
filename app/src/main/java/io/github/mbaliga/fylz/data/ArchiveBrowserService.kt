package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.io.File
import java.util.UUID

/** Reads ZIP directory metadata without extracting file contents. */
class ArchiveBrowserService(private val context: Context) {
    data class Entry(
        val name: String,
        val directory: Boolean,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val encrypted: Boolean,
        val lastModifiedMillis: Long?,
    )

    data class Listing(
        val encrypted: Boolean,
        val entries: List<Entry>,
        val totalUncompressedBytes: Long,
    )

    suspend fun listZip(
        archiveUri: Uri,
        maxEntries: Int = 20_000,
        maxDeclaredUncompressedBytes: Long = 20L * 1024L * 1024L * 1024L,
    ): Listing = withContext(Dispatchers.IO) {
        require(maxEntries in 1..100_000)
        require(maxDeclaredUncompressedBytes > 0L)
        val workspace = File(context.cacheDir, "archive-browse/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val localArchive = File(workspace, "input.zip")
            context.contentResolver.openInputStream(archiveUri)?.use { input ->
                localArchive.outputStream().use(input::copyTo)
            } ?: error("Unable to read the archive.")

            val zip = ZipFile(localArchive)
            val headers = zip.fileHeaders
            require(headers.size <= maxEntries) {
                "Archive contains too many entries (${headers.size})."
            }
            var total = 0L
            val entries = headers.map { header ->
                val declared = header.uncompressedSize.coerceAtLeast(0L)
                total = Math.addExact(total, declared)
                require(total <= maxDeclaredUncompressedBytes) {
                    "Archive expands beyond the configured safety limit."
                }
                Entry(
                    name = header.fileName,
                    directory = header.isDirectory,
                    compressedSize = header.compressedSize.coerceAtLeast(0L),
                    uncompressedSize = declared,
                    encrypted = header.isEncrypted,
                    lastModifiedMillis = header.lastModifiedTimeEpoch.takeIf { it > 0L },
                )
            }
            Listing(
                encrypted = zip.isEncrypted,
                entries = entries,
                totalUncompressedBytes = total,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }
}
