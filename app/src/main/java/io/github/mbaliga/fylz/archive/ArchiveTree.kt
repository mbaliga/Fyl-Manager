package io.github.mbaliga.fylz.archive

import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo

/**
 * One node of an [ArchiveTree]: a listed entry under its normalised [path], or a directory the
 * listing never named ([ordinal] == [ArchiveDocumentId.IMPLICIT_ORDINAL], unknown mtime), which
 * the tree synthesises so every file has a parent.
 */
data class ArchiveTreeEntry(
    val path: String,
    val ordinal: Int,
    val kind: Int,
    val flags: Int,
    val uncompressedBytes: Long,
    val mtimeEpochSeconds: Long,
    val mode: Int,
    /** The link target exactly as listed (not normalised); see [ArchiveTree.resolveHardlink]. */
    val linkTarget: String?,
) {
    val name: String get() = path.substringAfterLast('/')
    val parentPath: String get() = path.substringBeforeLast('/', "")
    val isDirectory: Boolean get() = kind == ArchiveEntryInfo.KIND_DIRECTORY
    val isFile: Boolean get() = kind == ArchiveEntryInfo.KIND_FILE
    val isSymlink: Boolean get() = kind == ArchiveEntryInfo.KIND_SYMLINK
    val isHardlink: Boolean get() = kind == ArchiveEntryInfo.KIND_HARDLINK
    val isOther: Boolean get() = kind == ArchiveEntryInfo.KIND_OTHER
    val isImplicit: Boolean get() = ordinal == ArchiveDocumentId.IMPLICIT_ORDINAL
    val encryptedData: Boolean get() = flags and ArchiveListingCodec.FLAG_ENCRYPTED_DATA != 0
    val encryptedMetadata: Boolean get() = flags and ArchiveListingCodec.FLAG_ENCRYPTED_METADATA != 0
    val nameLossy: Boolean get() = flags and ArchiveListingCodec.FLAG_NAME_LOSSY != 0
    val sizeKnown: Boolean get() = uncompressedBytes != ArchiveEntryInfo.UNKNOWN_SIZE
    val mtimeKnown: Boolean get() = mtimeEpochSeconds != ArchiveEntryInfo.UNKNOWN_MTIME
}

/**
 * An archive's listing as a browsable tree (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section
 * 2.3). Built from the raw records in archive order, **normalising** every path the way the engine
 * treats libarchive's ISO/tar root (`is_archive_root`: a directory named `.` or `./` is the root,
 * not an entry, and entries under it lose the prefix):
 *
 * - a leading `./` (repeatedly), a leading `/` and a trailing `/` are stripped; `//` and `.`
 *   segments collapse; a `..` segment pops its parent; ZIP only: `\` becomes `/`;
 * - a path whose `..` climbs above the root is **quarantined** (dropped, counted in
 *   [quarantined]); so is a non-directory whose path normalises to nothing;
 * - duplicates after normalisation: the **last member wins** (tar semantics) and the earlier
 *   ordinals are hidden; the winner keeps the position of the first appearance;
 * - a file and a directory with one normalised path: the directory wins and the file is
 *   quarantined, whichever came first (a directory may be explicit, or implied by a child path);
 * - implicit directories (`a/b/c.txt` with no `a/` or `a/b/` row) are synthesised with
 *   [ArchiveDocumentId.IMPLICIT_ORDINAL] and an unknown mtime; an explicit row for the same path
 *   later takes over its metadata.
 *
 * Children of a path are therefore unique by name, so every row Uri the provider builds is unique
 * (LazyColumn keys). Case is significant: `README` and `readme` are two entries. Memory is one
 * map of entries plus per-directory child lists; at the 200,000-entry listing bound a tree is a
 * few tens of megabytes, and the catalog keeps at most two.
 */
class ArchiveTree private constructor(
    private val byPath: Map<String, ArchiveTreeEntry>,
    private val childrenByParent: Map<String, List<ArchiveTreeEntry>>,
    /** Records dropped for unsafe or colliding paths. */
    val quarantined: Int,
    /** Directories the listing never named. */
    val implicitDirectories: Int,
    val formatCode: Int,
) {
    /** Every entry, implicit directories included. */
    val size: Int get() = byPath.size

    fun entry(path: String): ArchiveTreeEntry? = byPath[path]

    /** The children of [parentPath] (`""` for the root) in archive order. */
    fun children(parentPath: String): List<ArchiveTreeEntry> = childrenByParent[parentPath] ?: emptyList()

    /** Whether [parentPath] is the root or a directory of this tree. */
    fun isDirectory(parentPath: String): Boolean = parentPath.isEmpty() || byPath[parentPath]?.isDirectory == true

    /**
     * The entry a hardlink points at, when its target (normalised by the same rules) is a file of
     * this tree; `null` for a target that is missing, not a file, or itself a link.
     */
    fun resolveHardlink(link: ArchiveTreeEntry): ArchiveTreeEntry? {
        if (!link.isHardlink) return null
        val target = link.linkTarget ?: return null
        val normalized = normalize(target, zipBackslashes = formatCode == ArchiveFormatFamily.ZIP) ?: return null
        val entry = byPath[normalized] ?: return null
        return entry.takeIf { it.isFile }
    }

    companion object {
        /**
         * Normalises a raw archive path: `null` when it must be quarantined (a `..` climbs above the
         * root), `""` for the root itself, otherwise `/`-separated segments none of which is empty,
         * `.` or `..`. [zipBackslashes] rewrites `\` to `/` first (ZIP only).
         */
        fun normalize(raw: String, zipBackslashes: Boolean): String? {
            val path = if (zipBackslashes) raw.replace('\\', '/') else raw
            val segments = ArrayList<String>()
            var start = 0
            while (start <= path.length) {
                val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
                val segment = path.substring(start, end)
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                    else -> segments += segment
                }
                start = end + 1
            }
            return segments.joinToString("/")
        }

        fun build(listing: ArchiveListing, formatCode: Int): ArchiveTree {
            val zip = formatCode == ArchiveFormatFamily.ZIP
            val byPath = LinkedHashMap<String, ArchiveTreeEntry>(listing.records.size * 4 / 3 + 16)
            var quarantined = 0
            var implicit = 0

            /** Makes sure every ancestor of [path] is a directory; a file in the way is quarantined. */
            fun ensureParents(path: String) {
                var slash = path.indexOf('/')
                while (slash > 0) {
                    val parent = path.substring(0, slash)
                    val existing = byPath[parent]
                    if (existing == null) {
                        byPath[parent] = ArchiveTreeEntry(
                            path = parent,
                            ordinal = ArchiveDocumentId.IMPLICIT_ORDINAL,
                            kind = ArchiveEntryInfo.KIND_DIRECTORY,
                            flags = 0,
                            uncompressedBytes = ArchiveEntryInfo.UNKNOWN_SIZE,
                            mtimeEpochSeconds = ArchiveEntryInfo.UNKNOWN_MTIME,
                            mode = 0,
                            linkTarget = null,
                        )
                        implicit += 1
                    } else if (!existing.isDirectory) {
                        // A file where a directory must be: the directory wins.
                        byPath[parent] = ArchiveTreeEntry(
                            path = parent,
                            ordinal = ArchiveDocumentId.IMPLICIT_ORDINAL,
                            kind = ArchiveEntryInfo.KIND_DIRECTORY,
                            flags = 0,
                            uncompressedBytes = ArchiveEntryInfo.UNKNOWN_SIZE,
                            mtimeEpochSeconds = ArchiveEntryInfo.UNKNOWN_MTIME,
                            mode = 0,
                            linkTarget = null,
                        )
                        implicit += 1
                        quarantined += 1
                    }
                    slash = path.indexOf('/', slash + 1)
                }
            }

            for (record in listing.records) {
                val path = normalize(record.path, zip)
                if (path == null) {
                    quarantined += 1
                    continue
                }
                if (path.isEmpty()) {
                    // The archive's own root (`.`, `./`, `/`): not an entry. A file with no name is unsafe.
                    if (!record.isDirectory) quarantined += 1
                    continue
                }
                ensureParents(path)
                val entry = ArchiveTreeEntry(
                    path = path,
                    ordinal = record.ordinal,
                    kind = record.kind,
                    flags = record.flags,
                    uncompressedBytes = record.uncompressedBytes,
                    mtimeEpochSeconds = record.mtimeEpochSeconds,
                    mode = record.mode,
                    linkTarget = record.linkTarget,
                )
                val existing = byPath[path]
                when {
                    existing == null -> byPath[path] = entry
                    existing.isDirectory && !entry.isDirectory -> quarantined += 1
                    existing.isDirectory && entry.isDirectory -> {
                        // An explicit row for an implicit directory, or a later duplicate: its metadata wins.
                        if (existing.isImplicit) implicit -= 1
                        byPath[path] = entry
                    }
                    !existing.isDirectory && entry.isDirectory -> {
                        quarantined += 1
                        byPath[path] = entry
                    }
                    else -> byPath[path] = entry // last member wins, position of the first kept
                }
            }

            val children = HashMap<String, MutableList<ArchiveTreeEntry>>()
            for (entry in byPath.values) {
                children.getOrPut(entry.parentPath) { ArrayList() } += entry
            }
            return ArchiveTree(byPath, children, quarantined, implicit, formatCode)
        }
    }
}
