package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Read-only listing for non-ZIP archive families supported by Apache Commons Compress.
 * Entry data is never extracted by this class and all counts/sizes are bounded.
 */
class ExtendedArchiveBrowserService(private val context: Context) {
    data class Entry(
        val name: String,
        val directory: Boolean,
        val declaredSize: Long?,
        val lastModifiedMillis: Long?,
    )

    data class Listing(
        val format: String,
        val entries: List<Entry>,
        val totalDeclaredBytes: Long,
        val truncated: Boolean,
        val compressedSingleStream: Boolean = false,
    )

    suspend fun list(
        archiveUri: Uri,
        fileName: String,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        maxDeclaredBytes: Long = DEFAULT_MAX_DECLARED_BYTES,
        maxArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    ): Listing = withContext(Dispatchers.IO) {
        require(maxEntries in 1..100_000)
        require(maxDeclaredBytes > 0L)
        require(maxArchiveBytes > 0L)
        val extension = fileName.lowercase().substringAfterLast('.', "")
        when (extension) {
            "7z" -> listSevenZip(archiveUri, maxEntries, maxDeclaredBytes, maxArchiveBytes)
            "gz", "gzip", "bz2", "xz", "zst", "lzma" -> listCompressedStream(
                archiveUri = archiveUri,
                fileName = fileName,
                maxArchiveBytes = maxArchiveBytes,
            )
            else -> listStreamingArchive(archiveUri, extension, maxEntries, maxDeclaredBytes)
        }
    }

    private suspend fun listSevenZip(
        uri: Uri,
        maxEntries: Int,
        maxDeclaredBytes: Long,
        maxArchiveBytes: Long,
    ): Listing {
        val workspace = File(context.cacheDir, "archive-list/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val local = File(workspace, "input.7z")
            copyBounded(uri, local, maxArchiveBytes)
            val entries = mutableListOf<Entry>()
            var total = 0L
            var truncated = false
            SevenZFile(local).use { sevenZ ->
                while (true) {
                    coroutineContext.ensureActive()
                    val item = sevenZ.nextEntry ?: break
                    if (entries.size >= maxEntries) {
                        truncated = true
                        break
                    }
                    val size = item.size.takeIf { it >= 0L }
                    if (size != null) {
                        total = Math.addExact(total, size)
                        require(total <= maxDeclaredBytes) { "Archive expands beyond the configured safety limit." }
                    }
                    entries += Entry(
                        name = validatedEntryName(item.name),
                        directory = item.isDirectory,
                        declaredSize = size,
                        lastModifiedMillis = item.lastModifiedDate?.time,
                    )
                }
            }
            return Listing("7z", entries, total, truncated)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private suspend fun listStreamingArchive(
        uri: Uri,
        extension: String,
        maxEntries: Int,
        maxDeclaredBytes: Long,
    ): Listing {
        val source = context.contentResolver.openInputStream(uri) ?: error("Unable to read this archive.")
        source.use { raw ->
            BufferedInputStream(raw).use { buffered ->
                val format = archiveFormat(extension)
                // createArchiveInputStream's raw generic return type left Kotlin unable to infer
                // the entry type (cascading into "Cannot infer type" / "Unresolved reference
                // 'nextEntry'" across this whole block); the explicit cast below is what the
                // stray @Suppress was originally guarding and had been dropped.
                @Suppress("UNCHECKED_CAST")
                val archive = ArchiveStreamFactory().createArchiveInputStream(format, buffered)
                    as ArchiveInputStream<ArchiveEntry>
                archive.use { input ->
                    val entries = mutableListOf<Entry>()
                    var total = 0L
                    var truncated = false
                    while (true) {
                        coroutineContext.ensureActive()
                        val item = input.nextEntry ?: break
                        if (entries.size >= maxEntries) {
                            truncated = true
                            break
                        }
                        val size = item.size.takeIf { it >= 0L }
                        if (size != null) {
                            total = Math.addExact(total, size)
                            require(total <= maxDeclaredBytes) {
                                "Archive expands beyond the configured safety limit."
                            }
                        }
                        entries += item.toListingEntry()
                    }
                    return Listing(format, entries, total, truncated)
                }
            }
        }
    }

    private fun listCompressedStream(
        archiveUri: Uri,
        fileName: String,
        maxArchiveBytes: Long,
    ): Listing {
        val source = context.contentResolver.openInputStream(archiveUri) ?: error("Unable to read this stream.")
        source.use { raw ->
            BufferedInputStream(raw).use { buffered ->
                val compressor = CompressorStreamFactory().createCompressorInputStream(buffered)
                compressor.use { input ->
                    var expanded = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        expanded = Math.addExact(expanded, count.toLong())
                        require(expanded <= maxArchiveBytes) {
                            "Compressed stream expands beyond the configured preview limit."
                        }
                    }
                    val outputName = fileName.substringBeforeLast('.', fileName)
                    return Listing(
                        format = compressor.javaClass.simpleName.removeSuffix("CompressorInputStream"),
                        entries = listOf(Entry(validatedEntryName(outputName), false, expanded, null)),
                        totalDeclaredBytes = expanded,
                        truncated = false,
                        compressedSingleStream = true,
                    )
                }
            }
        }
    }

    private suspend fun copyBounded(uri: Uri, target: File, maxBytes: Long) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this archive.")
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total = Math.addExact(total, count.toLong())
                    require(total <= maxBytes) { "Archive exceeds the local inspection limit." }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun ArchiveEntry.toListingEntry(): Entry = Entry(
        name = validatedEntryName(name),
        directory = isDirectory,
        declaredSize = size.takeIf { it >= 0L },
        lastModifiedMillis = lastModifiedDate?.time,
    )

    private fun validatedEntryName(raw: String?): String {
        val value = raw?.replace('\\', '/')?.trimStart('/') ?: error("Archive entry has no name.")
        require(value.isNotBlank()) { "Archive entry has an empty name." }
        val segments = value.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Archive entry contains an unsafe path."
        }
        require(segments.size <= 128 && segments.all { it.length <= 255 }) {
            "Archive entry path exceeds safety limits."
        }
        return value
    }

    private fun archiveFormat(extension: String): String = when (extension) {
        "tar", "tgz", "tbz", "tbz2", "txz" -> ArchiveStreamFactory.TAR
        "cpio" -> ArchiveStreamFactory.CPIO
        "ar" -> ArchiveStreamFactory.AR
        "arj" -> ArchiveStreamFactory.ARJ
        else -> extension.ifBlank { ArchiveStreamFactory.TAR }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20_000
        const val DEFAULT_MAX_DECLARED_BYTES = 20L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
