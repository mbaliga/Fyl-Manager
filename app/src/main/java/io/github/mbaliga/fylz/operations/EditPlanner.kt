package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveFormatFamily
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.ArchiveTree
import io.github.mbaliga.fylz.archive.ArchiveTreeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M3.6 (edit ZIP archives in place): what one edit changes, relative to the archive's tree as it
 * is right now. Each field is independently optional so the same [EditPlanner] serves a single
 * targeted action (the archive's own Rename or Recycle acting on its entries, `fylz.archive.add-
 * entries`) and a combined edit (add, delete and rename together) alike -- the UI in this app only
 * ever submits one kind of change per request (`ui/actions/ArchiveEditFlow.kt`), but nothing here
 * requires that.
 *
 * - [deletions]: entries to drop, subtree included for a directory. Each id's `ordinal` is checked
 *   against the tree exactly as [ExtractPlanner] checks a selection -- a stale id (the archive
 *   changed since the id was built) refuses the whole edit rather than silently dropping the wrong
 *   entry.
 * - [rename]: one entry (matching `fylz.rename`'s own `selection.size == 1` rule) and the new last
 *   path segment it takes; its parent folder never changes. A directory's whole subtree moves with
 *   it.
 * - [addAtPath]: the normalised in-archive folder [addSources] are added into (the folder the user
 *   was browsing when they triggered `fylz.archive.add-entries`); `""` is the archive root.
 * - [addSources]: local documents (never archive entries -- that composition is out of scope for
 *   M3.6) to add, each walked recursively when it is itself a folder.
 */
data class ArchiveEditRequest(
    val archive: ArchiveRef,
    val deletions: List<ArchiveDocumentId> = emptyList(),
    val rename: Pair<ArchiveDocumentId, String>? = null,
    val addAtPath: String = "",
    val addSources: List<Uri> = emptyList(),
)

sealed interface EditPlanResult {
    /** Ready to persist ([OperationJournal.putWithCreatePlan]) and enqueue ([OperationRunner.enqueueCreate]). */
    data class Planned(val operation: FileOperation, val plan: CompressPlan, val manifest: List<CompressManifestEntry>) : EditPlanResult

    data class Refused(val reason: String) : EditPlanResult
}

/**
 * Plans an edit as one new archive rewritten from a manifest that mixes the surviving entries of
 * the archive itself with any newly added local sources, then one `ArchiveCreator` run whose
 * finalise step replaces the original document (`docs/agent/MASTER_PLAN.md`'s M3.6: "rewrite to
 * staging then atomic replace; the old archive goes to the recycle bin" -- see
 * [CompressPlan.replaceOriginalUri]).
 *
 * **Architecture choice** (recorded in `docs/agent/REVIEW_QUEUE.md`'s M3.6 entry): this planner
 * builds an ordinary [CompressPlan]/[CompressManifestEntry] list and hands it to the *existing*
 * `FileOperationType.ARCHIVE` queue -- the same claim/cancel/staging/verification machinery M3.5's
 * `ArchiveCreator` already has, plus the one extra replace-with-recycle finalise step -- rather
 * than a new operation type with its own journal tables and worker branch. An edit and a compress
 * are the same operation shape (write one manifest to one new archive); the only thing M3.6 adds is
 * *what the manifest is built from* (this file) and *what happens to the destination name after*
 * ([ArchiveCreator.Run.finalizeParts]).
 *
 * **Unchanged entries never touch the native engine's write side directly**: an entry this planner
 * keeps is a [CompressManifestEntry] whose `sourceUri` is the same `ArchiveDocumentId` Uri
 * [io.github.mbaliga.fylz.operations.CompressPlanner] already builds for an archive-sourced
 * compress source (`visitArchiveEntry`), which `ArchiveCreator.feedFile` reads through
 * `ContentResolver.openFileDescriptor`/`openInputStream` exactly like any other source -- resolved
 * by `ArchiveDocumentsProvider.openDocument`, which materialises the entry through
 * [io.github.mbaliga.fylz.archive.ArchiveEntryCache] (the same `extract_entry_at` pass a browsed
 * archive's own preview/copy-out already runs). This is the "less code churn" path the design
 * brief offered as an alternative to a new native copy-through function: `fylz-archive`/
 * `fylz-ffi-android` need **no changes at all** for M3.6, because the app already treats "a file
 * inside an archive" as an ordinary `Uri` source end to end.
 *
 * Scope, both narrower than the plan's own text and recorded as deviations:
 * - **ZIP only.** 7z has no writer (`M3.5`'s own scope, `ArchiveWriteFormat` has no 7z member); an
 *   edit of any other format is refused outright, never attempted.
 * - **Top-level archives only.** [ArchiveRef.chain] must be empty: editing an archive nested inside
 *   another archive would also have to rewrite the outer one, which is well beyond this milestone.
 */
class EditPlanner(
    private val resolver: ContentResolver,
    private val catalog: ArchiveCatalog,
) {
    suspend fun plan(request: ArchiveEditRequest, destinationFolder: Uri): EditPlanResult = withContext(Dispatchers.IO) {
        if (request.deletions.isEmpty() && request.rename == null && request.addSources.isEmpty()) {
            return@withContext EditPlanResult.Refused("Nothing to change.")
        }
        if (request.archive.chain.isNotEmpty()) {
            return@withContext EditPlanResult.Refused(NESTED_REFUSED)
        }
        val handle = try {
            catalog.open(request.archive)
        } catch (failure: ArchiveCatalog.Failure) {
            return@withContext EditPlanResult.Refused(failure.message ?: "The archive could not be read.")
        }
        val summary = handle.summary
        if (!ArchiveFormatFamily.isZip(summary.formatCode)) return@withContext EditPlanResult.Refused(NON_ZIP_REFUSED)
        if (!summary.isOk || summary.partial) return@withContext EditPlanResult.Refused(UNVERIFIED_REFUSED)
        summary.structuralRefusal?.let { reason -> return@withContext EditPlanResult.Refused("This archive cannot be edited safely: $reason") }
        if (summary.hasEncryptedEntries || summary.hasEncryptedMetadata) return@withContext EditPlanResult.Refused(ENCRYPTED_REFUSED)

        val originalNode = DocNode.load(resolver, request.archive.source) ?: return@withContext EditPlanResult.Refused("The archive no longer exists.")
        val destination = DocNode.loadDestination(resolver, destinationFolder) ?: return@withContext EditPlanResult.Refused("The archive's folder could not be opened.")
        if (!destination.isDirectory || !destination.canWrite) return@withContext EditPlanResult.Refused("The archive's folder is not writable.")

        val builder = ManifestBuilder(request.archive, handle.tree)
        val refusal = builder.applyDeletionsAndRename(request.deletions, request.rename)
            ?: builder.applyAdditions(resolver, request.addAtPath, request.addSources)
        if (refusal != null) return@withContext EditPlanResult.Refused(refusal)
        if (builder.entries.isEmpty()) return@withContext EditPlanResult.Refused("The archive would be empty.")

        val operation = FileOperation(
            type = FileOperationType.ARCHIVE,
            items = listOf(
                OperationItem(
                    source = request.archive.source,
                    destination = destinationFolder,
                    displayName = originalNode.name,
                    expectedBytes = builder.knownBytesTotal,
                    state = OperationState.QUEUED,
                ),
            ),
            conflictPolicy = ConflictPolicy.SKIP,
            state = OperationState.QUEUED,
            destination = destinationFolder,
        )
        val plan = CompressPlan(
            operationId = operation.id,
            format = CompressFormat.ZIP,
            level = CompressFormat.ZIP.levelFor(CompressLevelTier.NORMAL),
            split = SplitSize.Off,
            relativeToSelection = true,
            archiveName = originalNode.name,
            destinationUri = destinationFolder,
            totalEstimate = builder.knownBytesTotal,
            entryCount = builder.entries.size,
            conflictPolicy = ConflictPolicy.SKIP,
            replaceOriginalUri = originalNode.uri,
        )
        EditPlanResult.Planned(operation, plan, builder.entries)
    }

    /** Builds the mixed manifest: [applyDeletionsAndRename] walks [tree] once, [applyAdditions]
     * appends any newly picked local sources. Each returns a refusal reason, or `null` to proceed. */
    private class ManifestBuilder(private val archive: ArchiveRef, private val tree: ArchiveTree) {
        val entries = mutableListOf<CompressManifestEntry>()
        var knownBytesTotal: Long? = 0L
            private set
        private var nextOrdinal = 0

        fun applyDeletionsAndRename(deletions: List<ArchiveDocumentId>, rename: Pair<ArchiveDocumentId, String>?): String? {
            val deletedPaths = mutableSetOf<String>()
            for (id in deletions) {
                val entry = resolveStable(id) ?: return STALE_SELECTION
                deletedPaths += entry.path
            }
            var renameFrom: String? = null
            var renameTo: String? = null
            if (rename != null) {
                val (id, newName) = rename
                val entry = resolveStable(id) ?: return STALE_SELECTION
                if (entry.path in deletedPaths) return STALE_SELECTION
                val sanitized = sanitizeEntryName(newName)
                if (sanitized.isEmpty()) return "Choose a name."
                val parent = entry.parentPath
                val target = if (parent.isEmpty()) sanitized else "$parent/$sanitized"
                if (target != entry.path && tree.entry(target) != null) return "\"$sanitized\" already exists here."
                renameFrom = entry.path
                renameTo = target
            }
            return visit("", deletedPaths, renameFrom, renameTo)
        }

        /** One entry's id must still name the exact header it did when the selection was built --
         * the same staleness rule [ExtractPlanner.plan] applies to `ExtractSelection.Entries`. */
        private fun resolveStable(id: ArchiveDocumentId): ArchiveTreeEntry? {
            if (id.archive != archive || id.isRoot) return null
            val entry = tree.entry(id.path) ?: return null
            if (!entry.isImplicit && entry.ordinal != id.ordinal) return null
            return entry
        }

        /** Recurses [tree] under [parentPath], returning the first refusal reason a surviving
         * entry forces (a symlink, hardlink or special entry the edit cannot preserve -- a
         * deliberate scope limit, `REVIEW_QUEUE.md`'s M3.6 entry), or `null` once the whole subtree
         * has been added. */
        private fun visit(parentPath: String, deletedPaths: Set<String>, renameFrom: String?, renameTo: String?): String? {
            for (entry in tree.children(parentPath)) {
                if (entry.path in deletedPaths) continue
                val effectivePath = effectivePathOf(entry.path, renameFrom, renameTo)
                when {
                    entry.isDirectory -> {
                        val sourceUri = ArchiveDocumentId(archive.source, archive.chain, entry.ordinal, entry.path).toUri()
                        val mtime = if (entry.mtimeKnown) entry.mtimeEpochSeconds * 1000L else 0L
                        addDirectory(effectivePath, sourceUri, mtime)
                        visit(entry.path, deletedPaths, renameFrom, renameTo)?.let { return it }
                    }
                    entry.isFile -> addFile(archive, entry, effectivePath)
                    else -> return LINKS_REFUSED
                }
            }
            return null
        }

        private fun effectivePathOf(path: String, renameFrom: String?, renameTo: String?): String {
            if (renameFrom == null || renameTo == null) return path
            if (path == renameFrom) return renameTo
            if (path.startsWith("$renameFrom/")) return renameTo + path.substring(renameFrom.length)
            return path
        }

        fun applyAdditions(resolver: ContentResolver, addAtPath: String, addSources: List<Uri>): String? {
            if (addSources.isEmpty()) return null
            // The names already surviving at `addAtPath` in *this manifest* -- built from `entries`
            // rather than re-reading `tree`, so a delete or rename applied earlier in this same
            // request is already reflected (round-tripping add+delete+rename in one edit).
            val used = existingChildNames(addAtPath)
            for (uri in addSources) {
                val node = DocNode.load(resolver, uri) ?: return "A selected item could not be opened."
                val name = uniqueName(sanitizeEntryName(node.name).ifEmpty { "item" }, used)
                val path = if (addAtPath.isEmpty()) name else "$addAtPath/$name"
                addLocal(resolver, node, path)
            }
            return null
        }

        private fun addLocal(resolver: ContentResolver, node: DocNode, path: String) {
            if (node.isDirectory) {
                addDirectory(path, node.uri, node.lastModified ?: 0L)
                val used = HashSet<String>()
                for (child in node.children(resolver)) {
                    val name = uniqueName(sanitizeEntryName(child.name).ifEmpty { "item" }, used)
                    addLocal(resolver, child, "$path/$name")
                }
            } else {
                entries += CompressManifestEntry(
                    ordinal = nextOrdinal++,
                    isDirectory = false,
                    archivePath = path,
                    sourceUri = node.uri,
                    mtimeEpochMillis = node.lastModified ?: 0L,
                    needsSpooling = false,
                )
                knownBytesTotal = knownBytesTotal?.let { total -> node.size?.let { total + it } }
            }
        }

        private fun existingChildNames(parentPath: String): HashSet<String> = entries.mapNotNullTo(HashSet()) { entry ->
            val ownPath = entry.archivePath.removeSuffix("/")
            val parent = ownPath.substringBeforeLast('/', "")
            (ownPath.substringAfterLast('/').lowercase()).takeIf { parent == parentPath }
        }

        private fun uniqueName(name: String, used: HashSet<String>): String {
            var candidate = name
            var index = 2
            while (!used.add(candidate.lowercase())) {
                val dot = name.lastIndexOf('.')
                candidate = if (dot > 0) "${name.substring(0, dot)} ($index)${name.substring(dot)}" else "$name ($index)"
                index += 1
            }
            return candidate
        }

        private fun addDirectory(path: String, sourceUri: Uri, mtimeEpochMillis: Long) {
            entries += CompressManifestEntry(
                ordinal = nextOrdinal++,
                isDirectory = true,
                archivePath = "$path/",
                sourceUri = sourceUri,
                mtimeEpochMillis = mtimeEpochMillis,
                needsSpooling = false,
            )
        }

        private fun addFile(archive: ArchiveRef, entry: ArchiveTreeEntry, path: String) {
            entries += CompressManifestEntry(
                ordinal = nextOrdinal++,
                isDirectory = false,
                archivePath = path,
                sourceUri = ArchiveDocumentId(archive.source, archive.chain, entry.ordinal, entry.path).toUri(),
                mtimeEpochMillis = if (entry.mtimeKnown) entry.mtimeEpochSeconds * 1000L else 0L,
                needsSpooling = false,
            )
            knownBytesTotal = knownBytesTotal?.let { total -> if (entry.sizeKnown) total + entry.uncompressedBytes else null }
        }
    }

    companion object {
        const val NON_ZIP_REFUSED = "Only ZIP archives can be edited in place; other formats arrive with a later update."
        const val NESTED_REFUSED = "An archive nested inside another archive cannot be edited in place yet."
        const val UNVERIFIED_REFUSED = "Fylz could not verify this archive; refresh and try again."
        const val ENCRYPTED_REFUSED = "Password-protected archives cannot be edited yet."
        const val STALE_SELECTION = "The selected entries are no longer in the archive; refresh and try again."
        const val LINKS_REFUSED = "This archive contains a link or special entry, which cannot be preserved by an edit yet."

        /** [Char]s a ZIP entry name may not contain, mirroring [CompressPlanner]'s own sanitiser --
         * `/` and `\` would change the path shape, a NUL is unrepresentable. */
        private val ILLEGAL_NAME_CHARACTERS = setOf('/', '\\', '\u0000')

        fun sanitizeEntryName(name: String): String =
            name.map { c -> if (c in ILLEGAL_NAME_CHARACTERS || c.code < 0x20) '_' else c }.joinToString("").trim()
    }
}
