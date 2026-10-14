package io.github.mbaliga.fylz.decoder

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * What `IDecoderService.inspectArchive` returns (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md` section
 * 2.2): the archive's structure as the engine's one header pass saw it, the extraction policy's
 * verdict, and the first `maxRows` entries in archive order -- **never** the whole listing. The
 * Binder transaction buffer is 1 MB per process and shared by every in-flight transaction, so a
 * full listing would overflow it around ten thousand entries; the 500 rows every M3.2 caller
 * needs are about 50 KB, and M3.3 designs the full-listing transport.
 *
 * [outcome] says whether the rest is meaningful. Anything but [OUTCOME_OK] is the engine's own
 * answer about the file (or the service's about itself), carried as data rather than thrown
 * across Binder: [message] is the engine's error text, never a stack trace. A failed inspection
 * has zero counts, [UNKNOWN_SIZE] sizes, no rows and `policyAllowed = false`.
 *
 * Decisions key on [formatCode] (libarchive's `archive_format() & ARCHIVE_FORMAT_BASE_MASK`, the
 * format *family*: `0x50000` ZIP, `0xE0000` 7-Zip, `0x40000` ISO 9660, `0x30000` tar, ...), never
 * on [formatName], which for ZIP names the compression method of whichever entry the reader last
 * positioned on.
 */
@Parcelize
data class ArchiveInspection(
    val outcome: Int,
    val message: String?,
    val formatCode: Int,
    val formatName: String?,
    /** Compression filters between the file and the format reader (`["gzip"]`, `["xz"]`, ...);
     * empty for a plain archive. */
    val filters: List<String>,
    val archiveBytes: Long,
    val entryCount: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val linkCount: Int,
    /** Declared sizes of every non-directory entry summed; [UNKNOWN_SIZE] when any is unknown. */
    val totalUncompressedBytes: Long,
    val hasEncryptedEntries: Boolean,
    val hasEncryptedMetadata: Boolean,
    val hasLossyNames: Boolean,
    /** The Rust extraction policy's verdict (`fylz-archive`'s `policy::evaluate`) and its reason
     * string when refused. Until M3.4 `ArchiveService.extractZip` re-checks with the Kotlin
     * rules, so the two can disagree on the same ZIP (a logged deviation). */
    val policyAllowed: Boolean,
    val policyReason: String?,
    val rows: List<ArchiveEntryInfo>,
    val rowsTruncated: Boolean,
    /** `listArchive` only (M3.3): the header pass stopped on a damaged header after at least one
     * entry; the listing holds what was read, and [partialMessage] is libarchive's text for the
     * damage. Always `false` from `inspectArchive`, which reports that damage as [OUTCOME_CORRUPT].
     * Defaulted so every existing construction site stays as it is. */
    val partial: Boolean = false,
    val partialMessage: String? = null,
    /** The policy's verdict with every size rule switched off (M3.3a): a reason only when a rule no
     * destination size could relax fired -- an unsafe or duplicate path, an escaping link, an unknown
     * size, an overflowing sum. `null` when the archive is structurally sound. M3.4 refuses
     * extraction from an archive whose structural verdict is a refusal or unknown. */
    val structuralRefusal: String? = null,
) : Parcelable {

    @IgnoredOnParcel
    val isOk: Boolean = outcome == OUTCOME_OK

    companion object {
        const val OUTCOME_OK = 0
        /** The engine refused the descriptor as not a regular file. Unreachable through the app's
         * `ArchiveSource` (which stages non-seekable input first); a bug if seen. */
        const val OUTCOME_NOT_SEEKABLE = 1
        /** No format recognised the input, or a 7-Zip archive whose header is encrypted. */
        const val OUTCOME_UNSUPPORTED = 2
        /** libarchive failed mid-way: damaged (or lying) input. */
        const val OUTCOME_CORRUPT = 3
        /** `ArchiveLimits.maxListingEntries` stopped the header pass. */
        const val OUTCOME_LIMIT_EXCEEDED = 4
        /** An exception in the decoder process that is not the engine's verdict about the file;
         * [message] is the exception's class name. */
        const val OUTCOME_INTERNAL = 5

        const val UNKNOWN_SIZE = -1L

        /** A non-OK result: counts zeroed, sizes unknown, no rows, extraction not allowed. */
        fun failed(outcome: Int, message: String?): ArchiveInspection {
            require(outcome != OUTCOME_OK) { "a failed inspection needs a failure outcome" }
            return ArchiveInspection(
                outcome = outcome,
                message = message,
                formatCode = 0,
                formatName = null,
                filters = emptyList(),
                archiveBytes = UNKNOWN_SIZE,
                entryCount = 0,
                fileCount = 0,
                directoryCount = 0,
                linkCount = 0,
                totalUncompressedBytes = UNKNOWN_SIZE,
                hasEncryptedEntries = false,
                hasEncryptedMetadata = false,
                hasLossyNames = false,
                policyAllowed = false,
                policyReason = null,
                rows = emptyList(),
                rowsTruncated = false,
            )
        }
    }
}
