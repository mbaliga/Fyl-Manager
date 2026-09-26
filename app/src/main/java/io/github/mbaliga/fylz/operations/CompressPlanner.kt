package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveFormatFamily
import io.github.mbaliga.fylz.archive.ArchiveHandle
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.ArchiveTreeEntry
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.VolumeInfo
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Aborts the whole plan with [reason], the specific entry named -- caught once, at the top of
 * [CompressPlanner.plan]. Never escapes this file. */
private class PlanRefused(val reason: String) : Exception(reason)

/**
 * Planning: sources, names, sizes, preflight, conflicts (`docs/agent/DESIGN-M35-CREATE.md` section
 * 2.2). Nothing is persisted here; the caller writes the result's [FileOperation] and [CompressPlan]
 * (plus the manifest rows) into the journal atomically (`OperationJournal.putWithCompressPlan`).
 *
 * The walk is bounded (a visited set of document ids, [MAX_DEPTH]) against a directory-symlink
 * loop the provider's own path check does not catch; entries named with the staging prefix or the recycle-bin name
 * are skipped, the same convention the search engine excludes. A source under the archive
 * provider's authority is planned against the [ArchiveCatalog] tree directly -- kind, encryption,
 * size and the archive's own structural verdict are all known there, so a link, an encrypted
 * entry, an oversized entry or a refused archive fails the plan with the specific entry named,
 * before any output byte is written, rather than only being discovered when a fill fails mid-way.
 * A source archive above [ARCHIVE_ENTRY_SOURCE_LIMIT] entries in a stream or solid-7z format is
 * refused outright ("Extract it first") rather than accepted at O(N) full-decompression cost.
 */
class CompressPlanner(
    private val resolver: ContentResolver,
    private val catalog: ArchiveCatalog,
    private val volumeFor: suspend (destination: Uri) -> VolumeInfo?,
) {
    suspend fun plan(request: CompressRequest, ui: CompressPlannerUi): CompressPlanResult = withContext(Dispatchers.IO) {
        try {
            planOrThrow(request, ui)
        } catch (refused: PlanRefused) {
            CompressPlanResult.Refused(refused.reason)
        }
    }

    private suspend fun planOrThrow(request: CompressRequest, ui: CompressPlannerUi): CompressPlanResult {
        if (request.sources.isEmpty()) throw PlanRefused("Choose at least one item.")
        if (request.destinationFolder == null && request.split !is SplitSize.Off) {
            throw PlanRefused("A destination with no folder access cannot hold a split archive; choose a folder first.")
        }

        // 1-4. Walk every source, bounded; paths sanitised and case-insensitively uniquified per
        // directory as they are discovered.
        val walker = Walker(request.relativeToSelection, request.format)
        for (uri in request.sources) {
            coroutineContext.ensureActive()
            walker.walkRoot(uri)
        }
        if (walker.entries.isEmpty() && walker.problems.isEmpty()) throw PlanRefused("Nothing to compress.")

        val estimate = if (walker.hasUnknownSize) null else walker.knownBytesTotal + (walker.knownBytesTotal * OVERHEAD_FRACTION).toLong() + walker.entries.size * OVERHEAD_PER_ENTRY_BYTES

        val destination = request.destinationFolder
        val volume = destination?.let { volumeFor(it) }
        val isVfat = volume?.filesystemType == "vfat"
        var split = request.split
        val suggestSplit = split is SplitSize.Off && isVfat && estimate != null && estimate > SplitSize.FAT32

        val summary = CompressSummary(walker.entries.size, estimate, walker.problems, suggestSplit)
        val decision = if (summary.problems.isEmpty() && !suggestSplit) {
            CompressProblemDecision()
        } else {
            ui.resolveProblems(summary) ?: return declined(ui)
        }
        if (!decision.proceed) return declined(ui)
        if (decision.acceptSuggestedSplit) split = SplitSize.At(SplitSize.FAT32)
        coroutineContext.ensureActive()

        val keptEntries = walker.entries.filter { it.sourceUri !in decision.skip }
        if (keptEntries.isEmpty()) throw PlanRefused("Every item was skipped.")

        // 5. Preflight the archive name.
        val requestedArchiveName = "${sanitizeArchiveBaseName(request.archiveName)}.${request.format.extension}"
        val destinationNode = destination?.let { dest ->
            val node = DocNode.loadDestination(resolver, dest) ?: throw PlanRefused("The destination folder could not be opened.")
            if (!node.isDirectory || !node.canWrite) throw PlanRefused("The destination folder is not writable.")
            if (request.sources.contains(node.uri)) throw PlanRefused("Choose a destination outside the items being compressed.")
            node
        }

        // 6. Conflicts, resolved as one unit -- never per part.
        var conflictPolicy = ConflictPolicy.SKIP
        var nameOverride: String? = null
        if (destinationNode != null) {
            val index = NameIndex(destinationNode.children(resolver), volume?.caseInsensitive == true)
            val baseWithoutExtension = requestedArchiveName.removeSuffix(".${request.format.extension}")
            val existingBase = index.find(requestedArchiveName)?.name
            // Numbered PARTS only -- the base itself is named separately (existingBaseName below),
            // so a plain, non-split conflict reports an empty part list rather than [the base].
            val existingParts = (1..MAX_SPLIT_PARTS_CHECKED).mapNotNull { n -> index.find(partName(baseWithoutExtension, request.format, n))?.name }
            if (existingBase != null || existingParts.isNotEmpty()) {
                conflictPolicy = ui.resolveConflict(requestedArchiveName, existingParts) ?: return CompressPlanResult.Cancelled
                if (conflictPolicy == ConflictPolicy.KEEP_BOTH) {
                    nameOverride = index.uniqueName(requestedArchiveName).removeSuffix(".${request.format.extension}")
                }
            }
        }
        coroutineContext.ensureActive()

        val operation = FileOperation(
            type = FileOperationType.ARCHIVE,
            items = request.sources.mapIndexed { index, uri ->
                OperationItem(
                    source = uri,
                    destination = destination,
                    displayName = queryDisplayName(uri) ?: "item-${index + 1}",
                    expectedBytes = estimate?.let { it / request.sources.size.coerceAtLeast(1) },
                    state = OperationState.QUEUED,
                )
            },
            conflictPolicy = ConflictPolicy.SKIP,
            state = OperationState.QUEUED,
            destination = destination,
        )
        val plan = CompressPlan(
            operationId = operation.id,
            format = request.format,
            level = request.level,
            split = split,
            relativeToSelection = request.relativeToSelection,
            archiveName = if (nameOverride != null) "$nameOverride.${request.format.extension}" else requestedArchiveName,
            destinationUri = destination,
            totalEstimate = estimate,
            entryCount = keptEntries.size,
            conflictPolicy = conflictPolicy,
            nameOverride = nameOverride,
        )
        return CompressPlanResult.Planned(operation, plan, keptEntries, summary)
    }

    private fun declined(ui: CompressPlannerUi): CompressPlanResult =
        if (ui is HeadlessCompressUi) CompressPlanResult.Refused(ui.refusal ?: "This compress needs explicit confirmation.") else CompressPlanResult.Cancelled

    // ---------------------------------------------------------------------------------------- walk

    private inner class Walker(private val relativeToSelection: Boolean, private val format: CompressFormat) {
        val entries = mutableListOf<CompressManifestEntry>()
        val problems = mutableListOf<CompressProblem>()
        var knownBytesTotal = 0L
        var hasUnknownSize = false
        private var nextOrdinal = 0
        private val visitedDocumentIds = HashSet<String>()
        private val usedNamesByDirectory = HashMap<String, HashSet<String>>()

        suspend fun walkRoot(uri: Uri) {
            if (ArchiveDocumentId.isArchiveUri(uri)) {
                walkArchiveRoot(uri)
                return
            }
            val node = DocNode.load(resolver, uri) ?: throw PlanRefused("A selected item could not be opened.")
            // Design section 2.2 step 2: "on" (the default) names entries from the path below the
            // grant root -- this item's own name is the archive's top segment; "off" prefixes with
            // that same root-relative path of the *parent* folder the selection sits in, derived
            // from the source's own document id (never `FolderTab`'s UI display-name stack).
            val prefix = if (relativeToSelection) "" else parentRootRelativePath(node)
            val rootLabel = topLevelName(node.name, prefix)
            visitLocal(node, rootLabel, depth = 0)
        }

        private fun topLevelName(rawName: String, prefix: String): String {
            val sanitizedName = sanitizeSegment(rawName, usedNamesByDirectory.getOrPut(prefix) { HashSet() })
            return if (prefix.isEmpty()) sanitizedName else "$prefix/$sanitizedName"
        }

        /** The parent folder's own root-relative path, from [documentRelativePath]'s `root:a/b/c`
         * shape -- `""` when the id has no such shape (a provider whose ids are opaque, or the
         * overlay's multi-picked files, which then get flat, uniquified names regardless of the
         * toggle, exactly as [relativeToSelection] "on" already would). */
        private fun parentRootRelativePath(node: DocNode): String {
            val full = documentRelativePath(node) ?: return ""
            return full.substringBeforeLast('/', "")
        }

        /** The `root:relative/path` shape most SAF providers (and Fylz's own) expose through the
         * document id. `null` when the id has no such shape. */
        private fun documentRelativePath(node: DocNode): String? {
            val colon = node.documentId.indexOf(':')
            if (colon < 0) return null
            return node.documentId.substring(colon + 1).trim('/')
        }

        private suspend fun visitLocal(node: DocNode, archivePath: String, depth: Int) {
            coroutineContext.ensureActive()
            if (depth > MAX_DEPTH) return
            if (!visitedDocumentIds.add(node.documentId)) {
                problems += CompressProblem.SymlinkLoop(node.uri, node.name)
                return
            }
            if (isStagingOrTrash(node.name) && depth > 0) return
            if (node.isDirectory) {
                entries += CompressManifestEntry(nextOrdinal++, isDirectory = true, archivePath = "$archivePath/", sourceUri = node.uri, mtimeEpochMillis = node.lastModified ?: 0L, needsSpooling = false)
                val childDirKey = archivePath
                val used = usedNamesByDirectory.getOrPut(childDirKey) { HashSet() }
                for (child in node.children(resolver)) {
                    if (isStagingOrTrash(child.name)) continue
                    val childName = sanitizeSegment(child.name, used)
                    visitLocal(child, "$archivePath/$childName", depth + 1)
                }
            } else {
                addFile(node.uri, archivePath, node.size, node.lastModified ?: 0L)
            }
        }

        /** A source archive that cannot be opened, is damaged, or the engine itself refuses
         * structurally is a skippable problem for THIS source, not a whole-plan refusal (class doc
         * comment) -- another selected source unrelated to it still compresses fine. Mirrors
         * `ExtractPlanner`'s own "structural verdict is the gate" checks, over [ArchiveCatalog.Failure]
         * / `summary.isOk` / `summary.partial` / `summary.structuralRefusal`. */
        private suspend fun walkArchiveRoot(uri: Uri) {
            val id = try {
                ArchiveDocumentId.parse(uri)
            } catch (invalid: IllegalArgumentException) {
                throw PlanRefused("A selected archive item could not be opened.")
            }
            val handle = try {
                catalog.open(id.archive)
            } catch (failure: ArchiveCatalog.Failure) {
                problems += CompressProblem.ArchiveRefused(uri, id.name, failure.message ?: "The archive could not be read.")
                return
            }
            try {
                val summary = handle.summary
                val refusalReason = when {
                    !summary.isOk -> "the archive could not be verified"
                    summary.partial -> "the archive is damaged"
                    summary.structuralRefusal != null -> summary.structuralRefusal!!
                    else -> null
                }
                if (refusalReason != null) {
                    problems += CompressProblem.ArchiveRefused(uri, id.name, refusalReason)
                    return
                }
                if (id.isRoot) {
                    val rootLabel = topLevelName(ArchiveDocumentId.root(id.archive).name, "")
                    val entryLimitReason = checkEntryCountLimit(handle)
                    if (entryLimitReason != null) {
                        problems += CompressProblem.StreamFormatTooManyEntries(uri, rootLabel)
                        return
                    }
                    entries += CompressManifestEntry(nextOrdinal++, isDirectory = true, archivePath = "$rootLabel/", sourceUri = uri, mtimeEpochMillis = 0L, needsSpooling = false)
                    val used = usedNamesByDirectory.getOrPut(rootLabel) { HashSet() }
                    handle.tree.children("").forEach { child ->
                        val childName = sanitizeSegment(child.name, used)
                        visitArchiveEntry(id.archive, handle, child, "$rootLabel/$childName")
                    }
                } else {
                    val entry = handle.tree.entry(id.path) ?: throw PlanRefused("The selected archive entry is no longer present.")
                    val rootLabel = topLevelName(entry.name, "")
                    if (entry.isDirectory) {
                        val entryLimitReason = checkEntryCountLimit(handle)
                        if (entryLimitReason != null) {
                            problems += CompressProblem.StreamFormatTooManyEntries(uri, rootLabel)
                            return
                        }
                    }
                    visitArchiveEntry(id.archive, handle, entry, rootLabel)
                }
            } finally {
                handle.release()
            }
        }

        /** `null` when this archive is within [ARCHIVE_ENTRY_SOURCE_LIMIT] or a plain (non-solid,
         * non-streaming) format the tree can address randomly (ZIP); a reason string otherwise. */
        private fun checkEntryCountLimit(handle: ArchiveHandle): String? {
            if (ArchiveFormatFamily.isZip(handle.summary.formatCode)) return null
            if (handle.summary.entryCount <= ARCHIVE_ENTRY_SOURCE_LIMIT) return null
            return "Extract it first."
        }

        private fun visitArchiveEntry(archive: ArchiveRef, handle: ArchiveHandle, entry: ArchiveTreeEntry, archivePath: String) {
            when {
                entry.isSymlink || entry.isHardlink -> problems += CompressProblem.LinkOrEncrypted(entryUri(archive, entry), entry.name, "links cannot be compressed")
                entry.encryptedData || entry.encryptedMetadata -> problems += CompressProblem.LinkOrEncrypted(entryUri(archive, entry), entry.name, "encrypted entries cannot be compressed yet")
                entry.isDirectory -> {
                    entries += CompressManifestEntry(nextOrdinal++, isDirectory = true, archivePath = "$archivePath/", sourceUri = entryUri(archive, entry), mtimeEpochMillis = entryMtimeMillis(entry), needsSpooling = false)
                    val used = usedNamesByDirectory.getOrPut(archivePath) { HashSet() }
                    handle.tree.children(entry.path).forEach { child ->
                        val childName = sanitizeSegment(child.name, used)
                        visitArchiveEntry(archive, handle, child, "$archivePath/$childName")
                    }
                }
                entry.isFile -> {
                    val cap = minOf(ArchiveLimits.forInspection().maxFileBytes, ARCHIVE_SOURCE_MAX_BYTES)
                    if (entry.sizeKnown && entry.uncompressedBytes > cap) {
                        problems += CompressProblem.TooLarge(entryUri(archive, entry), entry.name)
                    } else {
                        addFile(entryUri(archive, entry), archivePath, entry.uncompressedBytes.takeIf { entry.sizeKnown }, entryMtimeMillis(entry))
                    }
                }
                else -> Unit
            }
        }

        private fun entryUri(archive: ArchiveRef, entry: ArchiveTreeEntry): Uri =
            ArchiveDocumentId(archive.source, archive.chain, entry.ordinal, entry.path).toUri()

        private fun entryMtimeMillis(entry: ArchiveTreeEntry): Long = if (entry.mtimeKnown) entry.mtimeEpochSeconds * 1000 else 0L

        private fun addFile(uri: Uri, archivePath: String, size: Long?, mtimeMillis: Long) {
            if (size != null) knownBytesTotal += size else hasUnknownSize = true
            // Only a tar family needs the exact size ahead of its header (design section 2.2 step 4);
            // zip tolerates an unknown size directly, so spooling an unknown-size entry for it would
            // only cost time and disk for no correctness benefit.
            val needsSpooling = size == null && format.isTar
            entries += CompressManifestEntry(nextOrdinal++, isDirectory = false, archivePath = archivePath, sourceUri = uri, mtimeEpochMillis = mtimeMillis, needsSpooling = needsSpooling)
        }
    }

    // ---------------------------------------------------------------------------------- sanitise

    /** `/`, `\` and C0 control characters mapped to `_`; a leading `.`/empty segment prefixed with
     * `_`; case-insensitively uniquified within [usedInDirectory] (design section 2.2 step 2). Runs
     * as each segment is discovered (not as a second pass) so the per-directory uniqueness set is
     * naturally scoped to real siblings. */
    private fun sanitizeSegment(segment: String, usedInDirectory: HashSet<String>): String {
        var name = segment.map { c -> if (c == '/' || c == '\\' || c.code < 0x20) '_' else c }.joinToString("")
        if (name.isEmpty() || name.startsWith(".")) name = "_$name"
        var candidate = name
        var index = 2
        while (!usedInDirectory.add(candidate.lowercase())) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)} ($index)${name.substring(dot)}" else "$name ($index)"
            index += 1
        }
        return candidate
    }

    private fun sanitizeArchiveBaseName(name: String): String =
        name.map { c -> if (c in ILLEGAL_NAME_CHARACTERS || c.code < 0x20) '_' else c }.joinToString("").ifBlank { "archive" }

    private fun isStagingOrTrash(name: String): Boolean = isStagingName(name) || name == RecycleBinService.RECYCLE_DIRECTORY

    /** [DocNode.load]'s own `(Bundle?, CancellationSignal?)` overload, not the classic
     * `(selection, selectionArgs, sortOrder)` one that `DocumentsProvider`'s base class throws
     * from -- confirmed the hard way under this test suite's Robolectric-hosted real provider,
     * exactly the pitfall [DocNode.load]'s own KDoc already documents. */
    private fun queryDisplayName(uri: Uri): String? = DocNode.load(resolver, uri)?.name

    companion object {
        const val MAX_DEPTH = 64

        private val ILLEGAL_NAME_CHARACTERS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

        /** A plan-time guide margin (design section 2.2 step 3): 5% plus 1 KiB per entry --
         * container overhead is real (roughly 76 B + 2*name + extras + a descriptor for zip, 512 B
         * + padding for tar), and this is only ever a guide, never a written-format promise. */
        const val OVERHEAD_FRACTION = 0.05
        const val OVERHEAD_PER_ENTRY_BYTES = 1024L

        /** Above this entry count, a stream-format or solid-7z archive source is refused rather
         * than accepted at O(N) full-decompression cost (design section 2.2 step 1). */
        const val ARCHIVE_ENTRY_SOURCE_LIMIT = 200

        /** The archive provider's own per-entry materialisation cap (design section 1; matches
         * `ArchiveEntryCache`'s "min(maxFileBytes, 512 MiB)" rule). */
        const val ARCHIVE_SOURCE_MAX_BYTES = 512L * 1024 * 1024

        /** How many numbered parts a conflict check probes for (`base.001`..`base.020`) -- far
         * beyond what any real split set needs, found by a single destination listing. */
        const val MAX_SPLIT_PARTS_CHECKED = 20

        fun partName(baseWithoutExtension: String, format: CompressFormat, partNumber: Int): String =
            "$baseWithoutExtension.${format.extension}.${partNumber.toString().padStart(3, '0')}"
    }
}
