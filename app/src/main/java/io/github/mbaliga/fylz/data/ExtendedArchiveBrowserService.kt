package io.github.mbaliga.fylz.data

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
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
        // Compound-aware, same as the rest of the preview pipeline (FileFormatRegistry.describe(),
        // PreviewPane/QuickLook's own routing sets): a plain substringAfterLast('.') would reduce
        // "backup.tar.gz" to the bare "gz" and misroute it into listCompressedStream() below --
        // reporting one fake entry for the still-compressed inner .tar instead of the tar's real
        // contents -- exactly the double-extension case compoundExtension() exists to resolve.
        val extension = FileFormatRegistry.compoundExtension(fileName)
        when (extension) {
            "7z" -> listSevenZip(archiveUri, maxEntries, maxDeclaredBytes, maxArchiveBytes)
            // The compound dotted spellings are a TAR archive under one compression layer, not a
            // lone compressed file -- route them the same as their tgz/tbz/tbz2/txz shorthand
            // cousins, into listStreamingArchive's TAR + layered-decompression path.
            // Zstandard (.zst/.tar.zst) is deliberately NOT here: commons-compress's zstd codec
            // delegates to com.github.luben:zstd-jni, which Fylz does not bundle, so claiming the
            // extension would fail on every real file. Those fall through to the universal
            // inspector instead, like RAR.
            "tar.gz", "tar.bz2", "tar.xz" -> listStreamingArchive(archiveUri, extension, maxEntries, maxDeclaredBytes)
            "gz", "gzip", "bz2", "xz", "lzma" -> listCompressedStream(
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
                // "tgz"/"tbz"/"tbz2"/"txz" resolve to the plain TAR format above, but the bytes on
                // disk are a compressed tar -- ArchiveStreamFactory's string-keyed overload builds
                // a bare TarArchiveInputStream with no decompression layer, so it would otherwise
                // choke on the compressed magic bytes instead of the tar header they wrap. Layer
                // the matching compressor first for exactly those shorthand extensions; every other
                // extension (plain tar/cpio/ar/arj) is unaffected.
                val layered = if (extension in COMPRESSED_TAR_EXTENSIONS) {
                    CompressorStreamFactory().createCompressorInputStream(buffered)
                } else {
                    buffered
                }
                // createArchiveInputStream's raw generic return type left Kotlin unable to infer
                // the entry type (cascading into "Cannot infer type" / "Unresolved reference
                // 'nextEntry'" across this whole block); the explicit cast below is what the
                // stray @Suppress was originally guarding and had been dropped.
                @Suppress("UNCHECKED_CAST")
                val archive = ArchiveStreamFactory().createArchiveInputStream(format, layered)
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

    /**
     * Normalization and the zip-slip guard, both delegated to [ArchiveTree.safePath] so this
     * listing and the single-entry extraction in [ArchiveEntryReader] agree on exactly which paths
     * exist and how they are spelled -- a browser that lists "docs/a.txt" and an extractor that
     * looks for "./docs/a.txt" would never find each other.
     *
     * This also fixes a listing-wide failure: the previous hand-rolled check split on '/' and
     * rejected any blank segment, so a directory record -- which TAR, cpio and 7z all write with a
     * trailing slash, as "docs/" -- produced a blank final segment and threw, taking the entire
     * archive's listing down with it. A trailing separator is now what it has always meant, a
     * directory, and the `isDirectory` flag carries that instead of the name.
     */
    private fun validatedEntryName(raw: String?): String =
        ArchiveTree.safePath(raw) ?: error("Archive entry contains an unsafe path.")

    /** Shared with [ArchiveEntryReader] so listing and extraction open the same reader. */
    private fun archiveFormat(extension: String): String = ArchiveFormats.streamFormat(extension)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20_000
        const val DEFAULT_MAX_DECLARED_BYTES = 20L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L

        /** Extensions [archiveFormat] maps to TAR whose bytes are compressed, not raw -- the bare
         *  shorthands (tgz/tbz/tbz2/txz) and the equivalent dotted compounds (tar.gz/tar.bz2/
         *  tar.xz) alike. [CompressorStreamFactory.createCompressorInputStream] autodetects
         *  the specific codec from the stream's own magic bytes, so one layering rule covers all.
         *  No zstd spellings: the codec needs zstd-jni, which Fylz does not bundle. */
        private val COMPRESSED_TAR_EXTENSIONS = ArchiveFormats.COMPRESSED_TAR_EXTENSIONS
    }
}
