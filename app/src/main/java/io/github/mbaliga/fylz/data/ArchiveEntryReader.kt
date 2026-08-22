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
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * One archive, listed as a navigable set of members and able to hand back a single entry's bytes.
 *
 * This is the data half of "browse inside an archive, then preview a file inside it". Listing
 * delegates to the two services that already know how to walk each family -- [ArchiveService] for
 * ZIP (which also reports encryption and the extraction preflight) and
 * [ExtendedArchiveBrowserService] for 7z/tar/cpio/ar/arj and lone compressed streams -- and
 * normalizes both into [ArchiveMember]s so the browser has one shape to render.
 *
 * Extraction is deliberately narrow: exactly one entry, bounded, into a content-addressed file in
 * a self-pruning cache directory. Nothing here ever writes an archive-controlled path to disk.
 */
class ArchiveEntryReader(private val context: Context) {

    /**
     * A whole archive as the browser needs it.
     *
     * Every field is something the archive actually declared, and the two nullable ones are the
     * point: [entryCount] and [expandedBytes] are null exactly when the walk stopped early and the
     * archive's real totals are therefore something this code has not seen. A truncated listing
     * knows how many entries it read; it does not know how many there were, and must not print a
     * number as though it did.
     *
     * [unsafeMemberCount] is the number of entries dropped for failing [ArchiveTree.safePath],
     * counted rather than silently swallowed so the browser can say the list is short.
     */
    data class Listing(
        val formatLabel: String,
        val members: List<ArchiveMember>,
        val entryCount: Int?,
        val expandedBytes: Long?,
        val encrypted: Boolean,
        val truncated: Boolean,
        val singleCompressedStream: Boolean,
        val blockedReason: String?,
        val unsafeMemberCount: Int,
    )

    /** Lists [archiveUri]'s entries, or throws when Fylz has no reader for the format. */
    suspend fun list(archiveUri: Uri, fileName: String): Listing {
        val family = requireFamily(fileName)
        // Opening any archive is also when the extracted-member cache gets swept. Pruning only on
        // extraction would leave a user who browsed once and never opened a member carrying that
        // session's files until some unrelated preview happened to clean up after them.
        withContext(Dispatchers.IO) { prune(cacheDirectory()) }
        return when (family) {
            ArchiveFormats.Family.ZIP -> listZip(archiveUri)
            else -> listExtended(archiveUri, fileName)
        }
    }

    /**
     * Extracts exactly one [member] to a cache file and returns it.
     *
     * The cache file is named from a digest of the archive URI, the entry path and the entry's own
     * declared size/timestamp -- never from the entry name -- so a hostile path cannot steer the
     * write anywhere, and re-opening the same member reuses the earlier extraction instead of
     * paying for it twice. [maxBytes] bounds both the declared size (refused up front) and the
     * bytes actually written (a declared size is untrusted metadata and may lie).
     */
    suspend fun extract(
        archiveUri: Uri,
        fileName: String,
        member: ArchiveMember,
        maxBytes: Long = MAX_MEMBER_BYTES,
    ): File = withContext(Dispatchers.IO) {
        require(!member.directory) { "Folders inside an archive have no contents of their own to preview." }
        require(maxBytes > 0L) { "Invalid extraction limit." }
        val path = ArchiveTree.safePath(member.path)
            ?: error("This entry's name is not safe to extract, so Fylz did not open it.")
        val declared = member.sizeBytes
        if (declared != null && declared > maxBytes) {
            error("This entry is larger than the ${maxBytes / (1024L * 1024L)} MiB preview limit.")
        }
        val family = requireFamily(fileName)
        val directory = cacheDirectory()
        prune(directory)
        val target = File(directory, cacheName(archiveUri, path, member))
        if (target.isFile && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val staging = File(directory, ".${UUID.randomUUID()}$PARTIAL_SUFFIX")
        try {
            when (family) {
                ArchiveFormats.Family.ZIP -> extractFromZip(archiveUri, path, staging, maxBytes)
                ArchiveFormats.Family.SEVEN_Z -> extractFromSevenZip(archiveUri, path, staging, maxBytes)
                ArchiveFormats.Family.STREAM -> extractFromStream(archiveUri, fileName, path, staging, maxBytes)
                ArchiveFormats.Family.SINGLE -> extractSingleStream(archiveUri, staging, maxBytes)
            }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
        } catch (failure: Throwable) {
            staging.delete()
            throw failure
        }
        target
    }

    private suspend fun listZip(archiveUri: Uri): Listing {
        val inspection = ArchiveService(context).inspectZip(archiveUri, maxVisibleEntries = ZIP_VISIBLE_ENTRIES)
        var unsafe = 0
        val members = mutableListOf<ArchiveMember>()
        for (entry in inspection.visibleEntries) {
            val path = ArchiveTree.safePath(entry.name)
            if (path == null) {
                unsafe += 1
                continue
            }
            members += ArchiveMember(
                path = path,
                directory = entry.directory || entry.name.endsWith("/"),
                sizeBytes = entry.uncompressedBytes.takeIf { it >= 0L },
            )
        }
        return Listing(
            formatLabel = "ZIP-compatible archive",
            members = members,
            // Both totals come from the ZIP's central directory, which lists every entry whether
            // or not this preview rendered it -- so they stay true even when the row list is cut.
            entryCount = inspection.entryCount,
            expandedBytes = inspection.totalUncompressedBytes,
            encrypted = inspection.encrypted,
            truncated = inspection.entriesTruncated,
            singleCompressedStream = false,
            blockedReason = inspection.extractionDecision.reason.takeIf { !inspection.extractionDecision.allowed },
            unsafeMemberCount = unsafe,
        )
    }

    private suspend fun listExtended(archiveUri: Uri, fileName: String): Listing {
        val listing = ExtendedArchiveBrowserService(context).list(archiveUri, fileName)
        var unsafe = 0
        val members = mutableListOf<ArchiveMember>()
        for (entry in listing.entries) {
            val path = ArchiveTree.safePath(entry.name)
            if (path == null) {
                unsafe += 1
                continue
            }
            members += ArchiveMember(
                path = path,
                directory = entry.directory,
                sizeBytes = entry.declaredSize,
                lastModifiedMillis = entry.lastModifiedMillis,
            )
        }
        val label = listing.format.replaceFirstChar { it.uppercase() }
        return Listing(
            formatLabel = if (listing.compressedSingleStream) "$label compressed file" else "$label archive",
            members = members,
            // A sequential walk that hit its entry ceiling has counted only what it read. Both
            // totals go null rather than under-reporting the archive as though the walk finished.
            entryCount = listing.entries.size.takeIf { !listing.truncated },
            expandedBytes = listing.totalDeclaredBytes.takeIf { it > 0L && !listing.truncated },
            encrypted = false,
            truncated = listing.truncated,
            singleCompressedStream = listing.compressedSingleStream,
            blockedReason = null,
            unsafeMemberCount = unsafe,
        )
    }

    private suspend fun extractFromZip(archiveUri: Uri, path: String, target: File, maxBytes: Long) {
        openStream(archiveUri).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && ArchiveTree.safePath(entry.name) == path) {
                        // The JDK's ZIP reader handles STORED and DEFLATE. A member written with
                        // any other method (or an encrypted one) fails here rather than silently
                        // producing garbage, and says which of the two it was.
                        try {
                            target.outputStream().use { output -> copyBounded(zip, output, maxBytes) }
                        } catch (failure: ZipException) {
                            error(
                                "This entry is encrypted or uses a compression method Fylz cannot " +
                                    "decode (${failure.message ?: "unsupported ZIP entry"}).",
                            )
                        }
                        return
                    }
                    zip.closeEntry()
                }
            }
        }
        error("That entry is no longer present in this archive.")
    }

    private suspend fun extractFromSevenZip(archiveUri: Uri, path: String, target: File, maxBytes: Long) {
        val workspace = File(context.cacheDir, "$WORKSPACE_DIRECTORY/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val local = File(workspace, "input.7z")
            openStream(archiveUri).use { input ->
                local.outputStream().use { output -> copyBounded(input, output, MAX_STAGED_ARCHIVE_BYTES) }
            }
            SevenZFile(local).use { sevenZ ->
                while (true) {
                    coroutineContext.ensureActive()
                    val item = sevenZ.nextEntry ?: break
                    if (item.isDirectory || ArchiveTree.safePath(item.name) != path) continue
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = sevenZ.read(buffer)
                            if (count < 0) break
                            total += count.toLong()
                            require(total <= maxBytes) { "This entry exceeds the preview extraction limit." }
                            output.write(buffer, 0, count)
                        }
                    }
                    return
                }
            }
        } finally {
            workspace.deleteRecursively()
        }
        error("That entry is no longer present in this archive.")
    }

    private suspend fun extractFromStream(
        archiveUri: Uri,
        fileName: String,
        path: String,
        target: File,
        maxBytes: Long,
    ) {
        val extension = FileFormatRegistry.compoundExtension(fileName)
        openStream(archiveUri).use { raw ->
            BufferedInputStream(raw).use { buffered ->
                // Same layering rule the listing walk uses: the tgz/tbz/txz shorthands and the
                // dotted tar.gz-style compounds are a TAR under one compression layer, and
                // ArchiveStreamFactory's string-keyed overload builds a bare TAR reader that would
                // otherwise choke on the compressor's magic bytes.
                val layered: InputStream = if (extension in ArchiveFormats.COMPRESSED_TAR_EXTENSIONS) {
                    CompressorStreamFactory().createCompressorInputStream(buffered)
                } else {
                    buffered
                }
                @Suppress("UNCHECKED_CAST")
                val archive = ArchiveStreamFactory()
                    .createArchiveInputStream(ArchiveFormats.streamFormat(extension), layered)
                    as ArchiveInputStream<ArchiveEntry>
                archive.use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val item = input.nextEntry ?: break
                        if (item.isDirectory || ArchiveTree.safePath(item.name) != path) continue
                        if (!input.canReadEntryData(item)) {
                            error("Fylz has no codec for this entry's compression method.")
                        }
                        target.outputStream().use { output -> copyBounded(input, output, maxBytes) }
                        return
                    }
                }
            }
        }
        error("That entry is no longer present in this archive.")
    }

    private suspend fun extractSingleStream(archiveUri: Uri, target: File, maxBytes: Long) {
        openStream(archiveUri).use { raw ->
            BufferedInputStream(raw).use { buffered ->
                CompressorStreamFactory().createCompressorInputStream(buffered).use { input ->
                    target.outputStream().use { output -> copyBounded(input, output, maxBytes) }
                }
            }
        }
    }

    private suspend fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count.toLong()
            require(total <= maxBytes) { "This entry exceeds the preview extraction limit." }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun openStream(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Unable to read this archive.")

    private fun cacheDirectory(): File =
        File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }

    /**
     * A stable, archive-controlled-input-free file name: a digest over the archive URI, the
     * normalized entry path and the entry's declared size and timestamp. Including the declared
     * metadata means a rebuilt archive at the same URI misses the cache rather than serving the
     * previous build's bytes under the new name.
     */
    private fun cacheName(archiveUri: Uri, path: String, member: ArchiveMember): String {
        val key = buildString {
            append(archiveUri.toString())
            append('\u0000').append(path)
            append('\u0000').append(member.sizeBytes ?: -1L)
            append('\u0000').append(member.lastModifiedMillis ?: -1L)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(40)
        val extension = FileFormatRegistry.compoundExtension(path)
        return if (extension.isEmpty()) hex else "$hex.$extension"
    }

    private fun prune(directory: File) {
        val files = directory.listFiles() ?: return
        val snapshot = files.filter { it.isFile }.map {
            ArchiveMemberCachePolicy.CachedFile(
                name = it.name,
                sizeBytes = it.length(),
                lastUsedMillis = it.lastModified(),
                partial = it.name.endsWith(PARTIAL_SUFFIX),
            )
        }
        val doomed = ArchiveMemberCachePolicy.evictions(snapshot, System.currentTimeMillis()).toSet()
        files.forEach { if (it.name in doomed) it.delete() }
    }

    private fun requireFamily(fileName: String): ArchiveFormats.Family {
        val extension = FileFormatRegistry.compoundExtension(fileName)
        return ArchiveFormats.familyOf(extension)
            ?: error("Fylz has no bundled reader for .$extension archives.")
    }

    companion object {
        /** Per-entry extraction ceiling. A preview never needs more than this from one member. */
        const val MAX_MEMBER_BYTES = 64L * 1024L * 1024L

        /** Ceiling on a 7z staged locally because that format needs random access. */
        const val MAX_STAGED_ARCHIVE_BYTES = 512L * 1024L * 1024L

        /** How many ZIP entries the browser asks for -- generous enough to navigate real trees. */
        const val ZIP_VISIBLE_ENTRIES = 5_000

        private const val CACHE_DIRECTORY = "archive-member"
        private const val WORKSPACE_DIRECTORY = "archive-member-work"
        private const val PARTIAL_SUFFIX = ".part"

        /** True when [fileName] names an archive family this reader can actually open. */
        fun supports(fileName: String): Boolean =
            ArchiveFormats.familyOf(FileFormatRegistry.compoundExtension(fileName)) != null
    }
}
