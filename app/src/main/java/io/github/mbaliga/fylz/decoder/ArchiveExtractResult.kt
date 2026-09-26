package io.github.mbaliga.fylz.decoder

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * What `IDecoderService.extractEntry` returns (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section
 * 2.2): the bytes streamed into the caller's sink when [outcome] is [OUTCOME_OK], else the
 * engine's verdict as data with its message -- never an exception across Binder. The outcome
 * codes are [ArchiveInspection]'s plus [OUTCOME_NOT_FOUND] (no header at the ordinal, or a
 * different path there: the archive changed under its listing). M3.4's `extractRanges` extends
 * the same type with its per-entry counts: [entriesWritten] and [entriesFailed] over the pass, and
 * [stopOrdinal] -- the header the pass stopped at on a fatal or a cancel ([NO_STOP_ORDINAL] when
 * it ran to the end), which is where a re-issue resumes. A single-entry `extractEntry` leaves the
 * three at their defaults.
 */
@Parcelize
data class ArchiveExtractResult(
    val outcome: Int,
    val message: String?,
    val bytesWritten: Long,
    val entriesWritten: Int = 0,
    val entriesFailed: Int = 0,
    val stopOrdinal: Int = NO_STOP_ORDINAL,
) : Parcelable {

    @IgnoredOnParcel
    val isOk: Boolean = outcome == OUTCOME_OK

    companion object {
        const val OUTCOME_OK = ArchiveInspection.OUTCOME_OK
        const val OUTCOME_NOT_SEEKABLE = ArchiveInspection.OUTCOME_NOT_SEEKABLE
        const val OUTCOME_UNSUPPORTED = ArchiveInspection.OUTCOME_UNSUPPORTED
        const val OUTCOME_CORRUPT = ArchiveInspection.OUTCOME_CORRUPT
        const val OUTCOME_LIMIT_EXCEEDED = ArchiveInspection.OUTCOME_LIMIT_EXCEEDED
        const val OUTCOME_INTERNAL = ArchiveInspection.OUTCOME_INTERNAL
        /** No entry with the expected path at the ordinal: the listing is stale, or the id forged. */
        const val OUTCOME_NOT_FOUND = 6
        /** The caller cancelled (M3.4: the sink's reader went away); nothing is wrong with the archive. */
        const val OUTCOME_CANCELLED = 7
        /** The engine's selection-scoped size policy refused the extraction before any byte moved (M3.4). */
        const val OUTCOME_REFUSED = 8

        /** [stopOrdinal] when the pass ran to its end (or the result is a single entry's). */
        const val NO_STOP_ORDINAL = -1

        fun ok(bytesWritten: Long): ArchiveExtractResult = ArchiveExtractResult(OUTCOME_OK, null, bytesWritten)

        fun failed(outcome: Int, message: String?): ArchiveExtractResult {
            require(outcome != OUTCOME_OK) { "a failed extraction needs a failure outcome" }
            return ArchiveExtractResult(outcome, message, 0L)
        }
    }
}
