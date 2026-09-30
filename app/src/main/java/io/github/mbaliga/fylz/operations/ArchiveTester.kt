package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveHandle
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.ArchiveTree
import io.github.mbaliga.fylz.archive.ArchiveTreeEntry
import io.github.mbaliga.fylz.archive.ExtractFrameReader
import io.github.mbaliga.fylz.archive.ExtractFrameSink
import io.github.mbaliga.fylz.archive.ExtractProtocolException
import io.github.mbaliga.fylz.archive.ExtractStreamEnd
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How one tested entry came out (M3.8). */
sealed interface EntryOutcome {
    /** The engine read every byte and its own checks (CRC included) agreed with the header. */
    data object Passed : EntryOutcome

    /** Read to the end, but libarchive attached a non-fatal condition (`fylz-archive`'s `Warning`,
     * never a CRC mismatch -- that is always [Failed] with [FailKind.Crc]). */
    data class PassedWithWarning(val message: String) : EntryOutcome

    /** Lost mid-pass ([ExtractFrameReader]'s `FAIL_*` codes: a CRC mismatch, a lying size, or any
     * other decode failure); [message] is the engine's own text. */
    data class Failed(val kind: Int, val message: String) : EntryOutcome {
        /** A short label for [kind], for a result list a person reads. */
        val kindLabel: String get() = when (kind) {
            ExtractFrameReader.FAIL_CRC -> "CRC mismatch"
            ExtractFrameReader.FAIL_SIZE -> "Unexpected size"
            ExtractFrameReader.FAIL_DECODE -> "Could not be decoded"
            else -> "Failed"
        }
    }
}

/** One tested header: its ordinal, the path the listing carries for it, and how it came out. */
data class ArchiveTestEntryResult(val ordinal: Int, val path: String, val outcome: EntryOutcome)

/** What [ArchiveTester.test] answers. */
sealed interface ArchiveTestOutcome {
    /**
     * The pass ran (to its end, or was cut short): every entry it reached, in archive order.
     * [cancelled] is true when the pass stopped early -- the caller's own [ArchiveTester.test]
     * `cancelled` callback returned true, or the engine went fatal partway through -- so the
     * dialog can say "not every entry was checked" rather than imply a clean, complete pass.
     */
    data class Completed(val entries: List<ArchiveTestEntryResult>, val cancelled: Boolean) : ArchiveTestOutcome

    /** The archive (or its metadata) is password-protected. Testing an encrypted archive is not
     * offered (M3.9's own scope note: the new engine's password field is a disabled stub until a
     * crypto backend is compiled in, so a prompted password could never actually be used here). */
    data object PasswordRequired : ArchiveTestOutcome

    /** The archive could not be listed at all, or the decoder connection was lost/timed out. */
    data object Unavailable : ArchiveTestOutcome

    /** The engine (or the app's own size policy) refused the pass before it produced a verdict. */
    data class Refused(val reason: String) : ArchiveTestOutcome
}

/**
 * "Test archive" (MASTER_PLAN M3.8): verify every entry's CRC without extracting, by running the
 * exact same engine pass M3.4's selective extract already runs -- `extractRanges` over every
 * ordinal in the archive, its FZX1 frames demultiplexed by [ExtractFrameReader] -- except the sink
 * here never creates a destination document: [data] frames are dropped as they arrive, and only
 * [end]/[fail] are recorded. Nothing is queued, journalled or staged: like `ArchiveInspector`, this
 * is a read-only, non-queued action, not a `TransferWorker` job, so cancelling it (or the process
 * dying mid-test) leaves nothing behind by construction -- there is no destination to clean up.
 *
 * Runs on the isolated extraction instance ([extractionClient], `:decoders:extract`), never the
 * browsing one, exactly as [ArchiveExtractor] does -- a long test must not be able to starve or be
 * starved by a concurrent browse fill's own timeout. The archive is listed through [catalog] first
 * (disk-first, single-flight), so a Test right after a browse or an Inspect costs nothing extra.
 */
class ArchiveTester(
    private val catalog: ArchiveCatalog,
    /** A fresh client of the extraction instance per test; unbound when the test ends. */
    private val extractionClient: () -> DecoderClient,
    private val streamInactivityMillis: Long = DecoderClient.STREAM_INACTIVITY_MILLIS,
    private val cancelWaitMillis: Long = DecoderClient.EXTRACTION_CANCEL_WAIT_MILLIS,
) {
    /**
     * Tests every entry of [ref]. [onProgress] is called after each entry ends (`tested`, `total`);
     * [cancelled] is polled by the streaming call the same way an extraction's own cancel is --
     * closing the pipe promptly rather than waiting for the pass to run to its end.
     */
    suspend fun test(
        ref: ArchiveRef,
        onProgress: (tested: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): ArchiveTestOutcome = withContext(Dispatchers.IO) {
        val handle = try {
            catalog.open(ref)
        } catch (failure: ArchiveCatalog.Failure) {
            return@withContext ArchiveTestOutcome.Unavailable
        }
        if (!handle.retain()) return@withContext ArchiveTestOutcome.Unavailable
        try {
            val summary = handle.summary
            if (summary.partial) return@withContext ArchiveTestOutcome.Refused(PARTIAL_REFUSED)
            if (summary.hasEncryptedEntries || summary.hasEncryptedMetadata) return@withContext ArchiveTestOutcome.PasswordRequired
            // Defensive, not reachable through `catalog.open` today (a non-OK summary never leaves
            // a handle behind): kept fail-closed, the same belt the planner wears for the same call.
            if (!summary.isOk) return@withContext ArchiveTestOutcome.Unavailable
            if (!summary.policyAllowed) return@withContext ArchiveTestOutcome.Refused(summary.policyReason ?: "This archive failed safety checks.")
            summary.structuralRefusal?.let { reason -> return@withContext ArchiveTestOutcome.Refused("This archive cannot be tested safely: $reason") }
            val bitmap = allOrdinals(handle.tree)
            if (bitmap.isEmpty) return@withContext ArchiveTestOutcome.Completed(emptyList(), cancelled = false)
            val client = extractionClient()
            try {
                runPass(handle, bitmap, client, onProgress, cancelled)
            } catch (userCancelled: TestCancelledException) {
                // Thrown from DiscardSink.begin, the same way ArchiveExtractor's own frame sink
                // throws its cancel out of the drain: whatever was tested before this stands.
                ArchiveTestOutcome.Completed(userCancelled.results, cancelled = true)
            } finally {
                client.unbind()
            }
        } finally {
            handle.release()
        }
    }

    /** Every ordinal a whole-archive selection would read: files, non-implicit directories, and a
     * hardlink's target -- [ExtractPlanner.expand]'s own walk for [ExtractSelection.All], minus the
     * byte/name bookkeeping this pass has no use for. Symlinks and special files carry no data to
     * verify and are left out, same as an extraction leaves them unwritten. */
    private fun allOrdinals(tree: ArchiveTree): OrdinalBitmap {
        val bitmap = OrdinalBitmap()
        fun visit(entry: ArchiveTreeEntry) {
            when {
                entry.isFile -> bitmap.set(entry.ordinal)
                entry.isDirectory -> {
                    if (!entry.isImplicit) bitmap.set(entry.ordinal)
                    tree.children(entry.path).forEach(::visit)
                }
                entry.isHardlink -> tree.resolveHardlink(entry)?.let { target -> bitmap.set(target.ordinal) }
                else -> Unit
            }
        }
        tree.children("").forEach(::visit)
        return bitmap
    }

    /** One `extractRanges` pass over [bitmap], its frames discarded by [DiscardSink]. */
    private suspend fun runPass(
        handle: ArchiveHandle,
        bitmap: OrdinalBitmap,
        client: DecoderClient,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): ArchiveTestOutcome {
        // The same conservative default an unconsented extraction runs under (design section 2.4):
        // Test still fully decompresses every entry to check it, so the decompression-bomb ceiling
        // matters exactly as much here as it does when the bytes are written to disk.
        val limits = ArchiveLimits.forExtraction(volume = null, consent = false)
        val sink = DiscardSink(handle, bitmap.cardinality, onProgress, cancelled)
        val reader = ExtractFrameReader(isPlanned = { it in bitmap }, planTotalBytes = limits.maxTotalUncompressedBytes)
        var streamEnd: ExtractStreamEnd? = null
        val call: DecoderCall<ArchiveExtractResult> = try {
            handle.openDescriptor().use { pfd ->
                client.callStreaming(
                    archive = pfd,
                    inactivityMillis = streamInactivityMillis,
                    drain = { input -> streamEnd = reader.read(input, sink) },
                    cancelled = cancelled,
                    drainFailureWaitMillis = cancelWaitMillis,
                ) { service, pipe -> service.extractRanges(pfd, limits, bitmap.toByteArray(), pipe) }
            }
        } catch (protocol: ExtractProtocolException) {
            return ArchiveTestOutcome.Refused("The archive stream was inconsistent: ${protocol.message}")
        }
        return when (call) {
            is DecoderCall.Ok -> when (call.value.outcome) {
                // OK: the pass ran to its end. CORRUPT: the engine went fatal partway; whatever it
                // reached stands. CANCELLED: our own `cancelled` closed the pipe. All three leave
                // `sink.results` as the authority for what was actually tested.
                ArchiveExtractResult.OUTCOME_OK, ArchiveExtractResult.OUTCOME_CORRUPT, ArchiveExtractResult.OUTCOME_CANCELLED ->
                    ArchiveTestOutcome.Completed(sink.results, cancelled = streamEnd !is ExtractStreamEnd.Done)
                ArchiveExtractResult.OUTCOME_REFUSED -> ArchiveTestOutcome.Refused(call.value.message ?: "This archive was refused by the size policy.")
                ArchiveExtractResult.OUTCOME_LIMIT_EXCEEDED -> ArchiveTestOutcome.Refused("This archive has too many entries to test.")
                else -> ArchiveTestOutcome.Unavailable
            }
            DecoderCall.TimedOut, DecoderCall.Failed -> ArchiveTestOutcome.Unavailable
        }
    }

    /** The frame sink that makes this "without extracting": every [data] call drops its bytes on
     * the floor instead of writing them anywhere -- no [android.net.Uri], no `DocNode`, no
     * `ContentResolver` call anywhere in this class -- so a cancel or a crash mid-test has nothing
     * to clean up, by construction. [begin] checks [cancelled] once per entry, the same granularity
     * `ArchiveExtractor`'s own `checkCancel` uses, and throws [TestCancelledException] with
     * whatever [results] stand so far -- the outer `cancelled()` the streaming call itself polls is
     * only a second, coarser net for a header pass that never reaches a single entry. */
    private class DiscardSink(
        private val handle: ArchiveHandle,
        total: Int,
        private val onProgress: (Int, Int) -> Unit,
        private val cancelled: () -> Boolean,
    ) : ExtractFrameSink {
        private val total = total
        private var tested = 0
        private var openOrdinal = -1
        private var openPath = ""
        val results = ArrayList<ArchiveTestEntryResult>(total)

        override fun begin(ordinal: Int, declaredBytes: Long, kind: Int, rawPath: ByteArray) {
            if (cancelled()) throw TestCancelledException(results)
            openOrdinal = ordinal
            openPath = handle.rawPaths[ordinal] ?: String(rawPath, Charsets.UTF_8)
        }

        override fun data(ordinal: Int, buffer: ByteArray, offset: Int, length: Int) {
            // Discarded on purpose: this is the one difference from a real extraction's sink.
        }

        override fun end(ordinal: Int, bytes: Long, warning: String?) {
            val outcome = if (warning != null) EntryOutcome.PassedWithWarning(warning) else EntryOutcome.Passed
            record(ordinal, outcome)
        }

        override fun fail(ordinal: Int, kind: Int, message: String) {
            record(ordinal, EntryOutcome.Failed(kind, message))
        }

        private fun record(ordinal: Int, outcome: EntryOutcome) {
            val path = if (ordinal == openOrdinal) openPath else handle.rawPaths[ordinal] ?: openPath
            results += ArchiveTestEntryResult(ordinal, path, outcome)
            tested += 1
            onProgress(tested, total)
        }
    }

    /** [DiscardSink.begin]'s own cancel, carrying whatever [results] it had already recorded --
     * mirrors `ArchiveExtractor`'s `ExtractCancelledException`: an [IOException] so it crosses
     * [io.github.mbaliga.fylz.decoder.DecoderClient.callStreaming]'s drain unmolested (its own
     * catches are for `RemoteException`/`RuntimeException`/`CancellationException`, never a plain
     * [IOException]), stopping the transaction and coming straight back out to [test]. */
    private class TestCancelledException(val results: List<ArchiveTestEntryResult>) : IOException("The test was cancelled.")

    companion object {
        const val PARTIAL_REFUSED = "This archive is damaged; only its readable part could be listed, so Fylz cannot test it in full."
    }
}
