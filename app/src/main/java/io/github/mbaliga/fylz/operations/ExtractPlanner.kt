package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveFormatFamily
import io.github.mbaliga.fylz.archive.ArchiveHandle
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.ArchiveTreeEntry
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.preview.FileFormatRegistry
import io.github.mbaliga.fylz.storage.VolumeInfo
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What to extract: the whole archive, or the listed entries (files and folders) of it. */
sealed interface ExtractSelection {
    data object All : ExtractSelection

    data class Entries(val ids: List<ArchiveDocumentId>) : ExtractSelection
}

/** The shape asked for (design section 2.1): straight into the folder, or into a new folder inside it. */
sealed interface ExtractLayoutRequest {
    data object Here : ExtractLayoutRequest

    data class IntoFolder(val name: String) : ExtractLayoutRequest
}

data class ExtractRequest(
    val archive: ArchiveRef,
    val selection: ExtractSelection,
    /** The destination folder: a tree grant or a resolved document Uri (`DocNode.resolveDestinationUri`). */
    val destinationFolder: Uri,
    val layout: ExtractLayoutRequest,
)

/** One top-level item with a same-named sibling at the destination, for the conflict sheet. */
data class ExtractConflict(
    val itemIndex: Int,
    val name: String,
    val isDirectory: Boolean,
    val bytes: Long?,
    val mtimeEpochSeconds: Long?,
    val existing: DocNode,
)

/** The confirm sheet's numbers (design section 2.2 step 7). */
data class ExtractSummary(
    val itemCount: Int,
    val entryCount: Int,
    val totalBytes: Long,
    val archiveBytes: Long,
    /** `totalBytes / archiveBytes`, or `null` when the archive size is unknown or zero. */
    val ratio: Double?,
    val needsConsent: Boolean,
    val skippedLinks: Int,
    val sanitizedComponents: Int,
)

/** What the preflight sheet decided: items or entries to leave out (by their archive document Uri), and whether to go on at all. */
data class PreflightDecision(
    val skip: Set<Uri> = emptySet(),
    val proceed: Boolean = true,
)

enum class HereChoice { HERE, INTO_FOLDER }

/**
 * The planner's questions to whoever is driving it: the Extract flow's sheets in the UI, or
 * [HeadlessPlannerUi] for a caller with no user in front of it. `null` from any of them cancels.
 */
interface PlannerUi {
    /** `Here` with more than the threshold of roots: extract them directly, or into `<folderName>/`? */
    suspend fun chooseHereLayout(rootCount: Int, folderName: String): HereChoice?

    /** The preflight result over the top-level items (plus vfat's per-entry rule), to skip or auto-rename. */
    suspend fun resolvePreflight(preflight: PreflightResult): PreflightDecision?

    /** Every top-level conflict; a missing key falls back to `SKIP`. */
    suspend fun resolveConflicts(conflicts: List<ExtractConflict>): Map<Int, ConflictPolicy>?

    /** The final numbers; `true` proceeds (and is the consent tick when [ExtractSummary.needsConsent]). */
    suspend fun confirm(summary: ExtractSummary): Boolean?
}

/**
 * Design section 2.2's headless mode: no prompts. Conflicts take [conflictPolicy]; a large `Here`
 * goes into the folder when [largeHereAsFolder]; preflight problems are skipped when
 * [skipProblems] (else the plan is refused); above the consent thresholds the plan is refused
 * unless [allowLarge]; an insufficient-space verdict is refused unless [proceedDespiteSpace].
 */
class HeadlessPlannerUi(
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
    private val allowLarge: Boolean = false,
    private val largeHereAsFolder: Boolean = true,
    private val skipProblems: Boolean = true,
    private val proceedDespiteSpace: Boolean = false,
) : PlannerUi {
    /** Why the last [confirm]/[resolvePreflight] declined, for the caller's refusal message. */
    var refusal: String? = null
        private set

    override suspend fun chooseHereLayout(rootCount: Int, folderName: String): HereChoice =
        if (largeHereAsFolder) HereChoice.INTO_FOLDER else HereChoice.HERE

    override suspend fun resolvePreflight(preflight: PreflightResult): PreflightDecision? {
        if (preflight.insufficientSpace != null && !proceedDespiteSpace) {
            refusal = "Not enough space at the destination (${preflight.insufficientSpace.requiredBytes} bytes needed, " +
                "${preflight.insufficientSpace.availableBytes} available)."
            return null
        }
        val unfixable = preflight.problems.filter {
            it is PreflightProblem.FileTooLargeForVfat || it is PreflightProblem.NameTooLong || it is PreflightProblem.NameCollision
        }
        if (unfixable.isNotEmpty() && !skipProblems) {
            refusal = "${unfixable.size} item(s) cannot be written to this destination."
            return null
        }
        return PreflightDecision(skip = unfixable.map { it.item.sourceUri }.toSet(), proceed = true)
    }

    override suspend fun resolveConflicts(conflicts: List<ExtractConflict>): Map<Int, ConflictPolicy> =
        conflicts.associate { it.itemIndex to conflictPolicy }

    override suspend fun confirm(summary: ExtractSummary): Boolean {
        if (summary.needsConsent && !allowLarge) {
            refusal = "This extraction (${summary.entryCount} entries, ${summary.totalBytes} bytes) needs explicit confirmation."
            return false
        }
        return true
    }
}

sealed interface ExtractPlanResult {
    /** Ready to persist (`OperationJournal.putWithExtractPlan`) and enqueue (`OperationRunner.enqueueExtract`). */
    data class Planned(val operation: FileOperation, val plan: ExtractPlan, val summary: ExtractSummary) : ExtractPlanResult

    data class Refused(val reason: String) : ExtractPlanResult

    data object Cancelled : ExtractPlanResult

    /** An encrypted ZIP with the whole archive selected: `ArchiveService.extractZip` (zip4j) until M3.9. */
    data object LegacyEncryptedZip : ExtractPlanResult
}

/**
 * Design section 2.2, planning before enqueue: the listing through the catalog (single-flight,
 * disk-first), the structural verdict read fail-closed from the persisted summary, the selection
 * expanded over the tree into a bitmap of ordinals with hardlink targets pulled in, the top-level
 * items laid out, preflight and conflicts resolved through [PlannerUi], consent asked when the
 * numbers call for it, and the [ExtractPlan] built with `ArchiveLimits.forExtraction`. Nothing is
 * persisted here; the result's operation and plan go into the journal atomically by the caller.
 *
 * The Kotlin planner is the gate for **structural** rules (paths, duplicates, links, unknown sizes
 * -- the summary's `structuralRefusal`); the engine is the gate for **size** rules over the
 * selection at run time (`policy::evaluate_selection`). Symlinks and special files are skipped and
 * counted; a hardlink adds its target's ordinal and is written under the link's path by the
 * extractor.
 */
class ExtractPlanner(
    private val resolver: ContentResolver,
    private val catalog: ArchiveCatalog,
    /** The destination's volume facts (free space, filesystem, case rule), or `null` when unknown. */
    private val volumeFor: suspend (destination: Uri) -> VolumeInfo?,
    private val hereRootThreshold: Int = HERE_ROOT_THRESHOLD,
) {

    suspend fun plan(request: ExtractRequest, ui: PlannerUi): ExtractPlanResult = withContext(Dispatchers.IO) {
        // 1. Listing.
        val handle = try {
            catalog.open(request.archive)
        } catch (failure: ArchiveCatalog.Failure) {
            return@withContext ExtractPlanResult.Refused(failure.message ?: "The archive could not be read.")
        }
        val summary = handle.summary
        if (summary.partial) return@withContext ExtractPlanResult.Refused(PARTIAL_REFUSED)

        // Selection roots, validated against the tree as it is now.
        val roots: List<ArchiveTreeEntry> = when (val selection = request.selection) {
            ExtractSelection.All -> handle.tree.children("")
            is ExtractSelection.Entries -> {
                if (selection.ids.isEmpty()) return@withContext ExtractPlanResult.Refused("Nothing is selected.")
                val resolved = selection.ids.map { id ->
                    if (id.archive != request.archive || id.isRoot) return@withContext ExtractPlanResult.Refused(STALE_SELECTION)
                    val entry = handle.tree.entry(id.path) ?: return@withContext ExtractPlanResult.Refused(STALE_SELECTION)
                    if (!entry.isImplicit && entry.ordinal != id.ordinal) return@withContext ExtractPlanResult.Refused(STALE_SELECTION)
                    entry
                }
                dropDescendants(resolved)
            }
        }

        // Encryption: a whole ZIP with protected entries -> the legacy zip4j path; a selection that
        // touches a protected entry (or an archive with encrypted metadata) waits for M3.9; a
        // selection of plain entries inside a partly protected archive extracts normally.
        val wholeArchive = request.selection is ExtractSelection.All
        val anyEncrypted = summary.hasEncryptedMetadata ||
            (wholeArchive && summary.hasEncryptedEntries) ||
            roots.any { root -> anyEncryptedUnder(handle, root) }
        if (anyEncrypted) {
            return@withContext if (summary.formatCode == ArchiveFormatFamily.ZIP && wholeArchive) {
                ExtractPlanResult.LegacyEncryptedZip
            } else {
                ExtractPlanResult.Refused(ENCRYPTED_REFUSED)
            }
        }

        // 2. Structural verdict, fail closed.
        if (!summary.isOk) return@withContext ExtractPlanResult.Refused(UNVERIFIED_REFUSED)
        summary.structuralRefusal?.let { reason -> return@withContext ExtractPlanResult.Refused("This archive cannot be extracted safely: $reason") }

        // 4 (first half). Layout: a large `Here` asks.
        var layout: ExtractLayoutRequest = request.layout
        if (layout is ExtractLayoutRequest.Here && roots.size > hereRootThreshold) {
            val folderName = defaultFolderName(request.archive)
            when (ui.chooseHereLayout(roots.size, folderName)) {
                HereChoice.HERE -> Unit
                HereChoice.INTO_FOLDER -> layout = ExtractLayoutRequest.IntoFolder(folderName)
                null -> return@withContext ExtractPlanResult.Cancelled
            }
        }
        coroutineContext.ensureActive()

        val destination = DocNode.loadDestination(resolver, request.destinationFolder)
            ?: return@withContext ExtractPlanResult.Refused("The destination folder could not be opened.")
        if (!destination.isDirectory || !destination.canWrite) return@withContext ExtractPlanResult.Refused("The destination folder is not writable.")
        val volume = volumeFor(request.destinationFolder)
        val sanitize = volume?.filesystemType in FAT_FAMILY
        val isVfat = volume?.filesystemType == "vfat"
        val caseInsensitive = volume?.caseInsensitive == true || sanitize

        // 3 and 4. Expansion per top-level item.
        val planLayout: ExtractLayout
        val folderName: String?
        val itemSpecs: List<ItemSpec>
        when (val chosen = layout) {
            is ExtractLayoutRequest.IntoFolder -> {
                planLayout = ExtractLayout.INTO_FOLDER
                folderName = chosen.name
                itemSpecs = listOf(ItemSpec(rootPath = "", rootEntry = null, rawName = chosen.name, isDirectory = true, roots = roots))
            }
            ExtractLayoutRequest.Here -> {
                planLayout = if (request.selection is ExtractSelection.All) ExtractLayout.HERE else ExtractLayout.ENTRIES
                folderName = null
                itemSpecs = roots.map { root -> ItemSpec(rootPath = root.path, rootEntry = root, rawName = root.name, isDirectory = root.isDirectory, roots = listOf(root)) }
            }
        }
        if (itemSpecs.isEmpty()) return@withContext ExtractPlanResult.Refused("The archive has nothing to extract.")

        val expansions = itemSpecs.map { spec -> expand(handle, spec) }
        val skippedLinks = expansions.sumOf { it.skippedLinks }
        var sanitizedComponents = expansions.indices.sumOf { expansions[it].sanitizedComponents(sanitize, itemSpecs[it].rootPath) }

        // Top-level names: sanitised on a FAT family, uniquified when sanitisation merges two.
        val usedNames = HashSet<String>()
        val requestedNames = itemSpecs.map { spec ->
            var name = if (sanitize) PreflightPolicy.sanitizedName(spec.rawName) else spec.rawName
            if (name != spec.rawName) sanitizedComponents += 1
            val key = { candidate: String -> if (caseInsensitive) candidate.lowercase() else candidate }
            if (!usedNames.add(key(name))) {
                var index = 2
                val dot = name.lastIndexOf('.')
                val base = if (dot > 0) name.substring(0, dot) else name
                val extension = if (dot > 0) name.substring(dot) else ""
                while (!usedNames.add(key("$base ($index)$extension"))) index += 1
                name = "$base ($index)$extension"
            }
            name
        }

        // 5. Preflight over the top-level items, plus vfat's per-entry rule.
        val itemUris = itemSpecs.map { spec -> itemSourceUri(request.archive, spec) }
        val topLevel = itemSpecs.mapIndexed { index, spec ->
            PreflightItem(sourceUri = itemUris[index], name = requestedNames[index], isDirectory = spec.isDirectory, totalBytes = expansions[index].knownBytes)
        }
        var preflight = volume?.let { PreflightPolicy.evaluate(topLevel, it) } ?: PreflightResult(emptyList())
        val oversizeByUri = HashMap<Uri, Pair<Int, ArchiveTreeEntry>>()
        if (isVfat && volume != null) {
            val oversize = expansions.flatMapIndexed { index, expansion ->
                expansion.files.filter { it.sizeKnown && it.uncompressedBytes > PreflightPolicy.VFAT_MAX_FILE_BYTES }.map { entry ->
                    val uri = entryUri(request.archive, entry)
                    oversizeByUri[uri] = index to entry
                    PreflightItem(sourceUri = uri, name = entry.name, isDirectory = false, totalBytes = entry.uncompressedBytes)
                }
            }
            if (oversize.isNotEmpty()) {
                val entryProblems = PreflightPolicy.evaluate(oversize, volume).problems.filterIsInstance<PreflightProblem.FileTooLargeForVfat>()
                preflight = preflight.copy(problems = preflight.problems + entryProblems)
            }
        }
        val decision = if (preflight.isClean) PreflightDecision() else (ui.resolvePreflight(preflight) ?: return@withContext declined(ui))
        if (!decision.proceed) return@withContext declined(ui)
        coroutineContext.ensureActive()

        val keptIndices = itemSpecs.indices.filter { itemUris[it] !in decision.skip }
        if (keptIndices.isEmpty()) return@withContext ExtractPlanResult.Refused("Every item was skipped.")
        decision.skip.forEach { uri -> oversizeByUri[uri]?.let { (index, entry) -> expansions[index].drop(entry) } }

        // 6. Conflicts, from one destination listing.
        val index = NameIndex(destination.children(resolver), caseInsensitive)
        val conflicts = keptIndices.mapNotNull { i ->
            val existing = index.find(requestedNames[i]) ?: return@mapNotNull null
            val spec = itemSpecs[i]
            ExtractConflict(
                itemIndex = i,
                name = requestedNames[i],
                isDirectory = spec.isDirectory,
                bytes = expansions[i].knownBytes,
                mtimeEpochSeconds = spec.rootEntry?.takeIf { it.mtimeKnown }?.mtimeEpochSeconds,
                existing = existing,
            )
        }
        val resolutions = if (conflicts.isEmpty()) emptyMap() else (ui.resolveConflicts(conflicts) ?: return@withContext ExtractPlanResult.Cancelled)
        coroutineContext.ensureActive()

        // 7. Limits and consent.
        val ordinals = OrdinalBitmap()
        var totalBytes = 0L
        keptIndices.forEach { i ->
            val expansion = expansions[i]
            expansion.ordinals.ordinals().forEach(ordinals::set)
            totalBytes += expansion.knownBytes ?: 0L
        }
        val entryCount = ordinals.cardinality
        val needsConsent = ArchiveLimits.needsConsent(totalBytes, entryCount)
        val archiveBytes = summary.archiveBytes
        val extractSummary = ExtractSummary(
            itemCount = keptIndices.size,
            entryCount = entryCount,
            totalBytes = totalBytes,
            archiveBytes = archiveBytes,
            ratio = if (archiveBytes > 0L) totalBytes.toDouble() / archiveBytes.toDouble() else null,
            needsConsent = needsConsent,
            skippedLinks = skippedLinks,
            sanitizedComponents = sanitizedComponents,
        )
        when (ui.confirm(extractSummary)) {
            true -> Unit
            false, null -> return@withContext declined(ui)
        }
        val consent = needsConsent
        val limits = ArchiveLimits.forExtraction(volume, consent)

        // The operation and its plan; item indices are positions in the kept list.
        val operation = FileOperation(
            type = FileOperationType.EXTRACT,
            items = keptIndices.map { i ->
                OperationItem(
                    source = itemUris[i],
                    destination = request.destinationFolder,
                    displayName = requestedNames[i],
                    expectedBytes = expansions[i].knownBytes,
                    state = OperationState.QUEUED,
                )
            },
            conflictPolicy = ConflictPolicy.SKIP,
            state = OperationState.QUEUED,
            destination = request.destinationFolder,
        )
        val planItems = keptIndices.mapIndexed { position, i ->
            val policy = resolutions[i] ?: ConflictPolicy.SKIP
            val override = if (policy == ConflictPolicy.KEEP_BOTH && index.find(requestedNames[i]) != null) index.uniqueName(requestedNames[i]) else null
            if (override == null) index.reserve(requestedNames[i])
            ExtractPlanItem(
                itemIndex = position,
                rootPath = itemSpecs[i].rootPath,
                requestedName = requestedNames[i],
                conflictPolicy = policy,
                nameOverride = override,
            )
        }
        val plan = ExtractPlan(
            operationId = operation.id,
            archiveUri = ArchiveDocumentId.root(request.archive).toUri(),
            catalogKey = handle.key,
            layout = planLayout,
            folderName = folderName,
            ordinals = ordinals,
            limits = limits,
            consent = consent,
            sanitize = sanitize,
            items = planItems,
        )
        ExtractPlanResult.Planned(operation, plan, extractSummary)
    }

    /** A `null`/`false` from the UI: a cancel for a person, a refusal with its reason for the headless mode. */
    private fun declined(ui: PlannerUi): ExtractPlanResult =
        if (ui is HeadlessPlannerUi) ExtractPlanResult.Refused(ui.refusal ?: CONSENT_REFUSED) else ExtractPlanResult.Cancelled

    /** One top-level item before expansion. */
    private class ItemSpec(
        val rootPath: String,
        val rootEntry: ArchiveTreeEntry?,
        val rawName: String,
        val isDirectory: Boolean,
        /** The tree entries the walk starts from: the root entry itself, or the archive's root children. */
        val roots: List<ArchiveTreeEntry>,
    )

    /** What the walk of one item found. */
    private class Expansion {
        val ordinals = OrdinalBitmap()
        val files = ArrayList<ArchiveTreeEntry>()
        val directories = ArrayList<ArchiveTreeEntry>()
        var skippedLinks = 0
        private var unknownSizes = 0
        private var bytes = 0L

        val knownBytes: Long? get() = if (unknownSizes > 0) null else bytes

        fun addFile(entry: ArchiveTreeEntry) {
            ordinals.set(entry.ordinal)
            files += entry
            if (entry.sizeKnown) bytes += entry.uncompressedBytes else unknownSizes += 1
        }

        /** A hardlink to [target]: the target is read (its ordinal) and written once more under the link's path. */
        fun addHardlink(link: ArchiveTreeEntry, target: ArchiveTreeEntry) {
            ordinals.set(target.ordinal)
            files += link
            if (target.sizeKnown) bytes += target.uncompressedBytes else unknownSizes += 1
        }

        /** A vfat-oversize entry the user skipped: its ordinal and bytes leave the item. */
        fun drop(entry: ArchiveTreeEntry) {
            if (!files.remove(entry)) return
            ordinals.clear(entry.ordinal)
            if (entry.sizeKnown) bytes -= entry.uncompressedBytes else unknownSizes -= 1
        }

        /** Nested components (below the item's own root, which the top-level rule counts) that sanitisation changes. */
        fun sanitizedComponents(sanitize: Boolean, rootPath: String): Int {
            if (!sanitize) return 0
            return (files.asSequence() + directories.asSequence())
                .filter { it.path != rootPath }
                .count { PreflightPolicy.sanitizedName(it.name) != it.name }
        }
    }

    private fun expand(handle: ArchiveHandle, spec: ItemSpec): Expansion {
        val expansion = Expansion()
        val tree = handle.tree
        fun visit(entry: ArchiveTreeEntry) {
            when {
                entry.isFile -> expansion.addFile(entry)
                entry.isDirectory -> {
                    if (!entry.isImplicit) expansion.ordinals.set(entry.ordinal)
                    expansion.directories += entry
                    tree.children(entry.path).forEach(::visit)
                }
                entry.isHardlink -> {
                    val target = tree.resolveHardlink(entry)
                    // The target is read once and written under the link's path too (the extractor
                    // maps the target ordinal to every destination path); its bytes count once per path.
                    if (target == null) expansion.skippedLinks += 1 else expansion.addHardlink(entry, target)
                }
                else -> expansion.skippedLinks += 1
            }
        }
        spec.roots.forEach(::visit)
        return expansion
    }

    private fun anyEncryptedUnder(handle: ArchiveHandle, root: ArchiveTreeEntry): Boolean {
        if (root.encryptedData || root.encryptedMetadata) return true
        if (!root.isDirectory) return false
        return handle.tree.children(root.path).any { anyEncryptedUnder(handle, it) }
    }

    /** Selected entries inside another selected entry are already covered by it. */
    private fun dropDescendants(entries: List<ArchiveTreeEntry>): List<ArchiveTreeEntry> {
        val distinct = entries.distinctBy { it.path }
        val directories = distinct.filter { it.isDirectory }.map { it.path }.toSet()
        return distinct.filter { entry ->
            var parent = entry.parentPath
            while (parent.isNotEmpty()) {
                if (parent in directories) return@filter false
                parent = parent.substringBeforeLast('/', "")
            }
            true
        }
    }

    private fun itemSourceUri(archive: ArchiveRef, spec: ItemSpec): Uri {
        val root = spec.rootEntry ?: return ArchiveDocumentId.root(archive).toUri()
        return entryUri(archive, root)
    }

    private fun entryUri(archive: ArchiveRef, entry: ArchiveTreeEntry): Uri =
        ArchiveDocumentId(archive.source, archive.chain, entry.ordinal, entry.path).toUri()

    /** `photos` for `photos.tar.gz`: the archive's name without its (compound) extension. */
    private fun defaultFolderName(archive: ArchiveRef): String {
        val name = archive.chain.lastOrNull()?.substringAfterLast('/')
            ?: DocNode.load(resolver, archive.source)?.name
            ?: archive.source.lastPathSegment
            ?: "archive"
        return extractionFolderBaseName(name)
    }

    companion object {
        /** Above this many roots, "Extract here" asks whether to use a folder instead (design section 2.2 step 4). */
        const val HERE_ROOT_THRESHOLD = 200

        const val PARTIAL_REFUSED = "This archive is damaged; its readable part can be browsed, not extracted yet."
        const val UNVERIFIED_REFUSED = "Fylz could not verify this archive; refresh and try again."
        const val ENCRYPTED_REFUSED = "This archive has password-protected entries. Extracting protected entries arrives with the password prompt (M3.9)."
        const val STALE_SELECTION = "The selected entries are no longer in the archive; refresh and try again."
        const val CONSENT_REFUSED = "This extraction needs explicit confirmation."

        private val FAT_FAMILY = setOf("vfat", "exfat")

        /** The archive's name without its compound extension (`FileFormatRegistry.compoundExtension`), never empty. */
        fun extractionFolderBaseName(archiveName: String): String {
            val extension = FileFormatRegistry.compoundExtension(archiveName)
            val base = if (extension.isNotEmpty() && archiveName.length > extension.length + 1) {
                archiveName.substring(0, archiveName.length - extension.length - 1)
            } else {
                archiveName
            }
            return base.ifBlank { archiveName.ifBlank { "archive" } }
        }
    }
}
