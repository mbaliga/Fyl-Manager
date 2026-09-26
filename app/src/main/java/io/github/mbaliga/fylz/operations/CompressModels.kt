package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveWriteFormat

/**
 * The Compress sheet's format choice (`docs/agent/DESIGN-M35-CREATE.md` section 2.1/2.4). `SEVEN_Z`
 * is deliberately absent: the sheet shows it disabled ("7z creation arrives with the 7-Zip pack",
 * §0/REVIEW_QUEUE item 1) and this enum has nothing to carry for it.
 */
enum class CompressFormat(val extension: String, val writeFormat: ArchiveWriteFormat) {
    ZIP("zip", ArchiveWriteFormat.ZIP),
    TAR_GZ("tar.gz", ArchiveWriteFormat.TAR_GZ),
    TAR_XZ("tar.xz", ArchiveWriteFormat.TAR_XZ),
    TAR_ZSTD("tar.zst", ArchiveWriteFormat.TAR_ZSTD),
    TAR_BZIP2("tar.bz2", ArchiveWriteFormat.TAR_BZIP2),
    TAR_LZ4("tar.lz4", ArchiveWriteFormat.TAR_LZ4),
    ;

    val isTar: Boolean get() = this != ZIP
}

/** Fast/Normal/Best resolved to each format's own numeric level (design section 2.4's table).
 * `xz`'s "Best" and `bzip2`'s "Best" are deliberately equal to "Normal" (the format itself has no
 * finer knob worth the memory at the top end); every other format's three tiers are distinct. */
enum class CompressLevelTier { FAST, NORMAL, BEST }

fun CompressFormat.levelFor(tier: CompressLevelTier): Int = when (this) {
    CompressFormat.ZIP -> when (tier) {
        CompressLevelTier.FAST -> 1
        CompressLevelTier.NORMAL -> 6
        CompressLevelTier.BEST -> 9
    }
    CompressFormat.TAR_GZ -> when (tier) {
        CompressLevelTier.FAST -> 1
        CompressLevelTier.NORMAL -> 6
        CompressLevelTier.BEST -> 9
    }
    // Capped at 6 for the :decoders 256 MB memory target (level 7 needs ~185 MiB, 9 ~673 MiB).
    CompressFormat.TAR_XZ -> when (tier) {
        CompressLevelTier.FAST -> 1
        CompressLevelTier.NORMAL, CompressLevelTier.BEST -> 6
    }
    CompressFormat.TAR_ZSTD -> when (tier) {
        CompressLevelTier.FAST -> 1
        CompressLevelTier.NORMAL -> 3
        CompressLevelTier.BEST -> 19
    }
    CompressFormat.TAR_BZIP2 -> when (tier) {
        CompressLevelTier.FAST -> 1
        CompressLevelTier.NORMAL, CompressLevelTier.BEST -> 9
    }
    CompressFormat.TAR_LZ4 -> when (tier) {
        CompressLevelTier.FAST -> 1
        CompressLevelTier.NORMAL -> 3
        CompressLevelTier.BEST -> 9
    }
}

/** Off, or a raw byte split at this size (design section 2.1's "Split size" field). */
sealed interface SplitSize {
    data object Off : SplitSize
    data class At(val bytes: Long) : SplitSize

    companion object {
        const val HUNDRED_MB: Long = 100L * 1024 * 1024
        const val SEVEN_HUNDRED_MB: Long = 700L * 1024 * 1024
        /** FAT32: 4 GiB - 1, [PreflightPolicy.VFAT_MAX_FILE_BYTES]. */
        const val FAT32: Long = PreflightPolicy.VFAT_MAX_FILE_BYTES
    }
}

/** One request out of the Compress sheet, the overlay's plain "Create ZIP", or a headless caller. */
data class CompressRequest(
    val sources: List<Uri>,
    val format: CompressFormat,
    val level: Int,
    val split: SplitSize,
    val relativeToSelection: Boolean,
    val archiveName: String,
    /** The destination folder: a tree grant or a resolved document Uri. `null` means "Save as…"
     * (a destination with no tree grant), which [CompressPlanner] handles distinctly: no
     * conflict/NameIndex machinery, and split is refused since one system-picked document is the
     * only output such a destination can offer. */
    val destinationFolder: Uri?,
)

/** One manifest row (design section 2.2 step 1, section 2.5): no size -- read at feed time. */
data class CompressManifestEntry(
    val ordinal: Int,
    val isDirectory: Boolean,
    /** The in-archive path, already relative-or-prefixed and sanitised (design section 2.2 step 2). */
    val archivePath: String,
    /** The source: a SAF/tree document Uri, or an `ArchiveDocumentId` Uri for an archive-sourced entry. */
    val sourceUri: Uri,
    val mtimeEpochMillis: Long,
    /** `tar.*` only (design section 2.2 step 4): this source's size is not known ahead of the feed
     * and must be spooled to a bounded temp file first so its exact size is known before the tar
     * header is written. Always `false` for `zip`, which tolerates an unknown size directly. */
    val needsSpooling: Boolean,
)

/** The persisted half of a CREATE operation (design section 2.2 step 7, `create_plans`). */
data class CompressPlan(
    val operationId: String,
    val format: CompressFormat,
    val level: Int,
    val split: SplitSize,
    val relativeToSelection: Boolean,
    val archiveName: String,
    /** `null` for a "Save as…" destination with no tree grant. */
    val destinationUri: Uri?,
    /** The plan-time best-effort estimate (design section 2.2 step 3): a guide for the split/vfat
     * suggestion and the space check, never a written-format guarantee. */
    val totalEstimate: Long?,
    val entryCount: Int,
    val conflictPolicy: ConflictPolicy,
    /** The Keep-both base name the whole set is finalised under, when conflict resolution picked one. */
    val nameOverride: String? = null,
    val cancelRequested: Boolean = false,
    val restartCount: Int = 0,
    /**
     * M3.6 (edit in place): the archive document this create is rewriting, when this plan is an
     * edit rather than a fresh compress -- `null` for every M3.5 create. When set,
     * `ArchiveCreator.Run.finalizeParts` replaces this document with the freshly written one
     * through `RecycleBinService.replaceWithRecycleFallback` (the old archive lands in the
     * destination's own `.fylz-trash`) instead of a plain rename; `resolveConflictsAsOneUnit`
     * stays a no-op for an edit because [conflictPolicy] is always [ConflictPolicy.SKIP] for one --
     * there is nothing to pre-clear.
     */
    val replaceOriginalUri: Uri? = null,
)

/** One output document of a CREATE operation -- the single archive, or one split part (design
 * section 2.3 step 2): rows are inserted at plan time (item 0) and, for a split output, again at
 * runtime as the drain crosses each boundary and opens the next part. */
data class CompressPlanItem(
    val itemIndex: Int,
    val requestedName: String,
    val stagingUri: Uri? = null,
    val sha256: String? = null,
    val bytesWritten: Long = 0L,
    val state: OperationState = OperationState.QUEUED,
)

/** One problem [CompressPlanner.plan] found with a specific source, before any output exists. */
sealed interface CompressProblem {
    val sourceUri: Uri
    val name: String

    data class LinkOrEncrypted(override val sourceUri: Uri, override val name: String, val reason: String) : CompressProblem
    data class TooLarge(override val sourceUri: Uri, override val name: String) : CompressProblem
    data class ArchiveRefused(override val sourceUri: Uri, override val name: String, val reason: String) : CompressProblem
    data class StreamFormatTooManyEntries(override val sourceUri: Uri, override val name: String) : CompressProblem
    data class SymlinkLoop(override val sourceUri: Uri, override val name: String) : CompressProblem
}

/** The Compress sheet's numbers before it enqueues (mirrors [ExtractSummary]'s shape). */
data class CompressSummary(
    val entryCount: Int,
    val totalEstimate: Long?,
    val problems: List<CompressProblem>,
    /** Set when the destination is vfat and [CompressSummary.totalEstimate] exceeds
     * [SplitSize.FAT32] and split is Off -- a dismissible suggestion, never a hard requirement. */
    val suggestSplit: Boolean,
)

sealed interface CompressPlanResult {
    data class Planned(val operation: FileOperation, val plan: CompressPlan, val manifest: List<CompressManifestEntry>, val summary: CompressSummary) : CompressPlanResult
    data class Refused(val reason: String) : CompressPlanResult
    data object Cancelled : CompressPlanResult
}

/** [CompressPlanner]'s questions to whoever is driving it -- the sheet's own preflight, or
 * [HeadlessCompressUi] for a caller with no user in front of it. */
interface CompressPlannerUi {
    /** The problems found (broken links, oversize entries, refused nested archives) and the
     * vfat/split suggestion; `null` cancels, otherwise the set of sources to drop and whether the
     * suggested split size was accepted. */
    suspend fun resolveProblems(summary: CompressSummary): CompressProblemDecision?

    /** [existingBaseName] itself already exists at the destination; [existingParts] is any of its
     * numbered split parts (`base.zip.001`, ...) that also already exist, empty for a plain,
     * unsplit conflict. Resolved as one unit -- never per part. */
    suspend fun resolveConflict(existingBaseName: String, existingParts: List<String>): ConflictPolicy?
}

data class CompressProblemDecision(
    val skip: Set<Uri> = emptySet(),
    val acceptSuggestedSplit: Boolean = false,
    val proceed: Boolean = true,
)

/** Design section 2.2's headless mode: no prompts, defined semantics, no consumer yet (Addendum
 * §C4/§C5, REVIEW_QUEUE item 16). */
class HeadlessCompressUi(
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    private val skipProblems: Boolean = true,
    private val acceptSuggestedSplit: Boolean = false,
) : CompressPlannerUi {
    var refusal: String? = null
        private set

    override suspend fun resolveProblems(summary: CompressSummary): CompressProblemDecision? {
        if (summary.problems.isNotEmpty() && !skipProblems) {
            refusal = "${summary.problems.size} source(s) cannot be compressed."
            return null
        }
        return CompressProblemDecision(
            skip = summary.problems.map { it.sourceUri }.toSet(),
            acceptSuggestedSplit = acceptSuggestedSplit,
            proceed = true,
        )
    }

    override suspend fun resolveConflict(existingBaseName: String, existingParts: List<String>): ConflictPolicy = conflictPolicy
}
