package io.github.mbaliga.fylz.archive

import android.net.Uri
import android.util.Log
import io.github.mbaliga.fylz.data.ArchiveSpacePolicy
import io.github.mbaliga.fylz.data.ArchiveSpaceRequirements
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient

/**
 * What the UI gets back from [ArchiveInspector.inspect] (`docs/agent/DESIGN-M32-SEEKABLE-PFD.md`
 * section 2.6): the summary when the engine answered, or which of the four ways it did not --
 * each worded differently to the user, because they mean different things.
 */
sealed class ArchiveInspectionResult {
    /** The summary when this is [Ready], else `null`: for a caller that only needs one field. */
    open val summaryOrNull: ArchiveInspection?
        get() = null

    /**
     * The engine listed the archive. [temporarySpace] is what *extraction* would need in cache
     * today (it still stages the whole archive until M3.4), recomputed here with
     * [ArchiveSpacePolicy.requirements]; `null` when the expanded size is unknown or overflows.
     * [staged] says the archive had to be copied to cache to be read at all; the copy is already
     * gone by the time this value exists.
     */
    data class Ready(
        val summary: ArchiveInspection,
        val staged: Boolean,
        val temporarySpace: ArchiveSpaceRequirements?,
        val temporarySpaceAvailable: Long?,
    ) : ArchiveInspectionResult() {
        override val summaryOrNull: ArchiveInspection
            get() = summary
    }

    /** The engine's own answer about the file: one of `ArchiveInspection.OUTCOME_*` other than OK. */
    data class Refused(val outcome: Int, val message: String?) : ArchiveInspectionResult()

    /** The structure budget passed: "took too long to read", not a crash. */
    data object TimedOut : ArchiveInspectionResult()

    /** The decoder process died or could not be bound: "could not be read safely". */
    data object Unavailable : ArchiveInspectionResult()

    /** The archive never reached the engine: no descriptor, no space to stage, too large, or a lying provider. */
    data class SourceFailed(val cause: ArchiveSourceException) : ArchiveInspectionResult()

    /** One sentence for the user when this is not [Ready]; `null` when it is. */
    fun failureMessage(): String? = when (this) {
        is Ready -> null
        is Refused -> when (outcome) {
            ArchiveInspection.OUTCOME_UNSUPPORTED -> "This file is not an archive Fylz can open."
            ArchiveInspection.OUTCOME_CORRUPT -> "The archive is damaged or could not be read" + detail()
            ArchiveInspection.OUTCOME_LIMIT_EXCEEDED -> "The archive has too many entries to list" + detail()
            else -> "The archive could not be opened" + detail()
        }
        TimedOut -> "The archive took too long to read."
        Unavailable -> "The archive could not be read safely."
        is SourceFailed -> cause.message ?: "Unable to read the archive."
    }

    private fun Refused.detail(): String = message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."
}

/**
 * What the UI calls to inspect an archive (section 2.6): resolve the `Uri` to a seekable
 * descriptor through [ArchiveSource], hand it to the decoder process through [DecoderClient],
 * map the answer. Nothing outlives the call -- a staged copy is deleted as soon as the summary
 * is in hand, because the dialog shows the summary, not the archive. Application-scoped (one
 * per process, in `FylzApplication`); the shared `DecoderClient` unbinds after 60 s idle (M3.3).
 */
class ArchiveInspector(
    private val source: ArchiveSource,
    private val client: DecoderClient,
    private val limits: ArchiveLimits,
    /** MASTER_PLAN section 4.4's structure budget; injectable so a test's hung stub times out in milliseconds. */
    private val structureTimeoutMillis: Long = DecoderClient.STRUCTURE_TIMEOUT_MILLIS,
    /** Runs the once-per-process cache sweep (M3.3a moved it here from `ArchiveSource`); `null` in tests that want none. */
    private val sweeper: ArchiveCacheSweeper? = null,
) {
    suspend fun inspect(uri: Uri, maxRows: Int = DecoderClient.DEFAULT_MAX_ROWS): ArchiveInspectionResult {
        sweeper?.sweepOnce()
        val resolved = try {
            source.resolve(uri)
        } catch (failure: ArchiveSourceException) {
            return ArchiveInspectionResult.SourceFailed(failure)
        }
        val staged = resolved is ArchiveSource.Resolved.Staged
        val call = resolved.use { open -> client.inspectArchive(open.pfd, limits, maxRows, structureTimeoutMillis) }
        return when (call) {
            is DecoderCall.Ok -> {
                val summary = call.value
                if (summary.isOk) {
                    ArchiveInspectionResult.Ready(
                        summary = summary,
                        staged = staged,
                        temporarySpace = summary.totalUncompressedBytes.takeIf { it >= 0L }
                            ?.let { ArchiveSpacePolicy.requirements(summary.archiveBytes, it) },
                        temporarySpaceAvailable = source.availableCacheBytes(),
                    )
                } else {
                    if (summary.outcome == ArchiveInspection.OUTCOME_NOT_SEEKABLE) {
                        // ArchiveSource exists to make this unreachable; seeing it is a bug, not a retry.
                        Log.w(TAG, "Engine refused a descriptor ArchiveSource resolved as seekable: ${summary.message}")
                    }
                    ArchiveInspectionResult.Refused(summary.outcome, summary.message)
                }
            }
            DecoderCall.TimedOut -> ArchiveInspectionResult.TimedOut
            DecoderCall.Failed -> ArchiveInspectionResult.Unavailable
        }
    }

    private companion object {
        const val TAG = "ArchiveInspector"
    }
}
