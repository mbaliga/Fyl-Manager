package io.github.mbaliga.fylz.decoder

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * What `IDecoderService.writeArchive` returns (design section 2.5): the engine's own report when
 * [outcome] is [OUTCOME_OK] -- entries written, source bytes fed ([bytesIn]), archive bytes
 * produced ([bytesOut]) -- else the engine's verdict as data with its message, never an exception
 * across Binder, exactly like [ArchiveInspection]/[ArchiveExtractResult]. [OUTCOME_PROTOCOL_ERROR]
 * is this type's own addition to that shared vocabulary: a malformed `FZW1` frame the app-side
 * feeder should never have sent (`ArchiveEngineException.Failed`, which the extraction pair folds
 * into [ArchiveInspection.OUTCOME_INTERNAL] instead, since a create's frame parser is the one place
 * "the feeder sent nonsense" is its own distinct, loggable condition -- `ArchiveCreator` maps it to
 * `PROTOCOL_ERROR` rather than the generic `ARCHIVE_UNAVAILABLE`).
 */
@Parcelize
data class ArchiveWriteResult(
    val outcome: Int,
    val message: String?,
    val entries: Int = 0,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
) : Parcelable {

    @IgnoredOnParcel
    val isOk: Boolean = outcome == OUTCOME_OK

    companion object {
        const val OUTCOME_OK = 0
        /** A negative descriptor, or the platform reporting one isn't a regular file. */
        const val OUTCOME_NOT_SEEKABLE = 1
        /** The `C.UTF-8` locale is unavailable on this device -- the write never started a frame. */
        const val OUTCOME_UNSUPPORTED = 2
        /** libarchive itself reported a fatal error mid-write; the writer was poisoned before free. */
        const val OUTCOME_CORRUPT = 3
        /** The `FZW1` frame stream was malformed -- a bug in the feeder, never tolerated silently. */
        const val OUTCOME_PROTOCOL_ERROR = 4
        /** `ABORT`, or the sink pipe closed (the app cancelled); nothing is wrong with the engine. */
        const val OUTCOME_CANCELLED = 5
        /** An exception in the decoder process that is not the engine's own verdict. */
        const val OUTCOME_INTERNAL = 6

        fun ok(entries: Int, bytesIn: Long, bytesOut: Long): ArchiveWriteResult =
            ArchiveWriteResult(OUTCOME_OK, null, entries, bytesIn, bytesOut)

        fun failed(outcome: Int, message: String?): ArchiveWriteResult {
            require(outcome != OUTCOME_OK) { "a failed write needs a failure outcome" }
            return ArchiveWriteResult(outcome, message)
        }
    }
}
