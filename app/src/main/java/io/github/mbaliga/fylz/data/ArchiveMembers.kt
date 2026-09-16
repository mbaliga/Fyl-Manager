package io.github.mbaliga.fylz.data

import org.apache.commons.compress.archivers.ArchiveStreamFactory

/**
 * One entry inside an archive, addressed by a normalized POSIX-style path.
 *
 * [path] is always the output of [ArchiveTree.safePath]: forward slashes, no leading slash, no
 * empty/`.`/`..` segments, no trailing slash even for a directory (the [directory] flag carries
 * that instead). A member is therefore safe to use as a map key, to compare against a second
 * archive walk, and -- critically -- can never escape a directory when joined to one.
 *
 * [sizeBytes] and [lastModifiedMillis] are nullable on purpose. TAR/cpio/7z entries may declare
 * neither, and a preview must not invent a number it cannot substantiate: a null renders as
 * nothing at all, never as "0 B" or "unknown date".
 */
data class ArchiveMember(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long? = null,
    val lastModifiedMillis: Long? = null,
) {
    /** Final path segment -- what the browser shows as this member's name. */
    val name: String get() = path.substringAfterLast('/')

    /** Containing directory path, `""` for a member at the archive's root. */
    val parent: String get() = path.substringBeforeLast('/', "")
}

/** One hop in the archive browser's breadcrumb trail. */
data class ArchiveCrumb(val label: String, val path: String)

/**
 * Pure directory-tree arithmetic over a flat archive listing.
 *
 * Archives are flat lists of paths; folders are implied by the separators inside those paths and
 * are frequently not present as entries of their own (a tar written from a file list has no
 * directory records at all). Everything here is deliberately free of Android and of IO so the
 * navigation rules can be unit-tested directly.
 */
object ArchiveTree {
    /** Maximum number of path segments accepted in an entry name. */
    const val MAX_DEPTH = 64

    /** Maximum length of a single path segment, matching [ArchiveExtractionLimits.maxNameLength]. */
    const val MAX_SEGMENT_LENGTH = 255

    private val DRIVE_LETTER = Regex("^[A-Za-z]:$")

    /**
     * Normalizes an archive entry name, or returns null when the name is not safe to use.
     *
     * This is the zip-slip guard. Rejected outright: null bytes, `.` and `..` segments, drive
     * qualifiers (`C:`), over-deep paths and over-long segments. Backslashes are folded to `/`
     * first so a Windows-authored `..\..\etc` cannot slip past a `/`-only check, and leading and
     * repeated separators are collapsed so `/etc/passwd` normalizes to a relative `etc/passwd`
     * rather than staying absolute.
     *
     * Callers still never join this value onto a directory on disk -- extraction writes to a
     * content-addressed cache name (see [ArchiveEntryReader]) -- so this is the second of two
     * independent defences, not the only one.
     */
    fun safePath(raw: String?): String? {
        if (raw == null) return null
        if (raw.indexOf('\u0000') >= 0) return null
        val segments = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.size > MAX_DEPTH) return null
        for (segment in segments) {
            if (segment == "." || segment == "..") return null
            if (segment.isBlank()) return null
            if (segment.length > MAX_SEGMENT_LENGTH) return null
            if (DRIVE_LETTER.matches(segment)) return null
        }
        return segments.joinToString("/")
    }

    /**
     * The immediate children of [directory] (`""` for the archive root), folders first and then
     * files, each group ordered case-insensitively by name.
     *
     * Folders that only exist implicitly -- because some deeper entry mentions them -- are
     * synthesized here with a null size, which is the truth: the archive declares no size for a
     * record it does not contain. A path that appears both as a file entry and as a parent of some
     * other entry resolves to the folder, since that is the one the user can descend into.
     */
    fun children(members: List<ArchiveMember>, directory: String): List<ArchiveMember> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val files = LinkedHashMap<String, ArchiveMember>()
        val directories = LinkedHashSet<String>()
        for (member in members) {
            val path = member.path
            if (prefix.isNotEmpty() && !path.startsWith(prefix)) continue
            if (path.length <= prefix.length) continue
            val remainder = path.substring(prefix.length)
            val cut = remainder.indexOf('/')
            when {
                cut >= 0 -> directories += prefix + remainder.substring(0, cut)
                member.directory -> directories += path
                else -> files[path] = member
            }
        }
        files.keys.removeAll(directories)
        val folderRows = directories.map { ArchiveMember(it, directory = true) }
        return folderRows.sortedWith(ORDER) + files.values.sortedWith(ORDER)
    }

    /**
     * Immediate-child counts for every directory in the listing, keyed by directory path (`""` is
     * the root). Computed once for a whole listing because the browser would otherwise re-walk
     * every entry for every row it draws.
     *
     * These are counts of what this listing actually contains. When a listing was truncated the
     * caller must not present them as the archive's true contents.
     */
    fun childCounts(members: List<ArchiveMember>): Map<String, Int> {
        val nodes = HashSet<String>()
        for (member in members) {
            var path = member.path
            while (path.isNotEmpty()) {
                // Once a node is already known, every ancestor of it is known too.
                if (!nodes.add(path)) break
                path = path.substringBeforeLast('/', "")
            }
        }
        val counts = HashMap<String, Int>()
        for (node in nodes) {
            val parent = node.substringBeforeLast('/', "")
            counts[parent] = (counts[parent] ?: 0) + 1
        }
        return counts
    }

    /** Breadcrumb hops from the archive root down to [directory], excluding the root itself. */
    fun breadcrumbs(directory: String): List<ArchiveCrumb> {
        if (directory.isEmpty()) return emptyList()
        val crumbs = mutableListOf<ArchiveCrumb>()
        val builder = StringBuilder()
        for (segment in directory.split('/')) {
            if (segment.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('/')
            builder.append(segment)
            crumbs += ArchiveCrumb(segment, builder.toString())
        }
        return crumbs
    }

    /** The directory containing [directory], or null when already at the archive root. */
    fun parentOf(directory: String): String? =
        if (directory.isEmpty()) null else directory.substringBeforeLast('/', "")

    private val ORDER = compareBy<ArchiveMember>({ it.name.lowercase() }, { it.path })
}

/**
 * Which reader can open a given archive extension.
 *
 * Deliberately narrow: every family listed here is one Fylz actually opens with a bundled codec.
 * RAR, LZH/LHA, CAB, ISO/DMG and Zstandard are absent because nothing on the classpath can read
 * them -- they keep falling through to the universal inspector rather than being advertised.
 */
object ArchiveFormats {
    enum class Family {
        /** Anything the JDK's own ZIP reader opens, including the OOXML/ODF/EPUB containers. */
        ZIP,

        /** 7z, which needs random access and therefore a staged local copy. */
        SEVEN_Z,

        /** Sequential commons-compress archives: tar (plain or compressed), cpio, ar, arj. */
        STREAM,

        /** A lone compressor wrapping exactly one inner file: .gz, .bz2, .xz, .lzma. */
        SINGLE,
    }

    val ZIP_EXTENSIONS = setOf(
        "zip", "zipx", "apk", "aab", "apks", "xapk", "apkm", "jar", "war", "ear",
        "cbz", "3mf", "kmz", "usdz", "vsdx", "nupkg", "whl", "ipa", "appx", "msix", "vsix",
        "docx", "docm", "dotx", "pptx", "pptm", "ppsx", "xlsx", "xlsm",
        "odt", "ods", "odp", "odg", "epub",
    )

    /** Sequential commons-compress archive families. */
    val STREAM_EXTENSIONS = setOf(
        "tar", "tgz", "tbz", "tbz2", "txz", "tar.gz", "tar.bz2", "tar.xz", "cpio", "ar", "arj",
    )

    /**
     * Extensions [streamFormat] maps to TAR whose bytes are compressed rather than raw -- the bare
     * shorthands and the equivalent dotted compounds alike.
     * `CompressorStreamFactory.createCompressorInputStream` autodetects the specific codec from the
     * stream's own magic bytes, so one layering rule covers all of them.
     */
    val COMPRESSED_TAR_EXTENSIONS = setOf("tgz", "tbz", "tbz2", "txz", "tar.gz", "tar.bz2", "tar.xz")

    /** A single compressed file with no archive container inside. */
    val SINGLE_STREAM_EXTENSIONS = setOf("gz", "gzip", "bz2", "xz", "lzma")

    /** The family for a compound extension, or null when Fylz has no reader for it. */
    fun familyOf(extension: String): Family? = when (extension) {
        in ZIP_EXTENSIONS -> Family.ZIP
        "7z" -> Family.SEVEN_Z
        in STREAM_EXTENSIONS -> Family.STREAM
        in SINGLE_STREAM_EXTENSIONS -> Family.SINGLE
        else -> null
    }

    /** The `ArchiveStreamFactory` format key for a sequential archive extension. */
    fun streamFormat(extension: String): String = when (extension) {
        "tar", "tgz", "tbz", "tbz2", "txz", "tar.gz", "tar.bz2", "tar.xz" -> ArchiveStreamFactory.TAR
        "cpio" -> ArchiveStreamFactory.CPIO
        "ar" -> ArchiveStreamFactory.AR
        "arj" -> ArchiveStreamFactory.ARJ
        else -> extension.ifBlank { ArchiveStreamFactory.TAR }
    }
}

/**
 * Eviction rules for the extracted-archive-member cache.
 *
 * Previewing a file inside an archive means writing that one entry to the cache directory. Without
 * a rule for taking things back out, that directory only ever grows -- so this decides, purely and
 * testably, which cached members go.
 */
object ArchiveMemberCachePolicy {
    /** Total budget for extracted members before the oldest are dropped. */
    const val MAX_CACHE_BYTES = 192L * 1024L * 1024L

    /** Number of extracted members kept before the oldest are dropped. */
    const val MAX_CACHE_FILES = 48

    /** Age after which an extracted member is dropped regardless of budget. */
    const val MAX_AGE_MILLIS = 60L * 60L * 1000L

    /**
     * @param partial a half-written staging file from an extraction that may still be in flight.
     *   Partials are only ever evicted by age, never by budget pressure, so one preview cannot
     *   delete another's work in progress.
     */
    data class CachedFile(
        val name: String,
        val sizeBytes: Long,
        val lastUsedMillis: Long,
        val partial: Boolean = false,
    )

    /** Names to delete, given the cache directory's current contents. */
    fun evictions(
        files: List<CachedFile>,
        nowMillis: Long,
        maxBytes: Long = MAX_CACHE_BYTES,
        maxFiles: Int = MAX_CACHE_FILES,
        maxAgeMillis: Long = MAX_AGE_MILLIS,
    ): List<String> {
        val doomed = mutableListOf<String>()
        val survivors = mutableListOf<CachedFile>()
        for (file in files) {
            if (nowMillis - file.lastUsedMillis > maxAgeMillis) doomed += file.name else survivors += file
        }
        val budgeted = survivors.filterNot { it.partial }.sortedBy { it.lastUsedMillis }
        var count = budgeted.size
        var total = budgeted.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        for (file in budgeted) {
            if (count <= maxFiles && total <= maxBytes) break
            doomed += file.name
            count -= 1
            total -= file.sizeBytes.coerceAtLeast(0L)
        }
        return doomed
    }
}
