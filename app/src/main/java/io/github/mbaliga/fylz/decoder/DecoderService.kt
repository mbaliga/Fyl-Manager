package io.github.mbaliga.fylz.decoder

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.mbaliga.fylz.core.ArchiveExtractRecord
import io.github.mbaliga.fylz.core.ArchiveInspectionRecord
import io.github.mbaliga.fylz.core.ArchiveLimitsRecord
import io.github.mbaliga.fylz.core.ArchiveOrdinalRangeRecord
import io.github.mbaliga.fylz.core.FylzCore
import io.github.mbaliga.fylz.operations.OrdinalBitmap
import kotlinx.coroutines.runBlocking

/** The engine call behind [DecoderService.inspectArchive]: a raw fd, the limits, the row cap. */
typealias ArchiveEngine = (fd: Int, limits: ArchiveLimitsRecord, maxRows: Int) -> ArchiveInspectionRecord

/** The engine call behind [DecoderService.listArchive]: the archive fd, the limits, the sink fd. */
typealias ArchiveListEngine = (fd: Int, limits: ArchiveLimitsRecord, sinkFd: Int) -> ArchiveInspectionRecord

/** The engine call behind [DecoderService.extractEntry]; returns the bytes written. */
typealias ArchiveExtractEngine = (fd: Int, ordinal: Int, expectedPath: String, limits: ArchiveLimitsRecord, sinkFd: Int) -> Long

/** The engine call behind [DecoderService.extractRanges] (M3.4): the archive fd, the ordinal ranges, the limits, the sink fd. */
typealias ArchiveExtractRangesEngine = (fd: Int, ranges: List<ArchiveOrdinalRangeRecord>, limits: ArchiveLimitsRecord, sinkFd: Int) -> ArchiveExtractRecord

/** The engine call behind [DecoderService.writeArchive] (M3.5a): the input fd, the output fd, the options. */
typealias ArchiveWriteEngine = (inFd: Int, outFd: Int, options: io.github.mbaliga.fylz.core.WriteOptionsRecord) -> io.github.mbaliga.fylz.core.ArchiveWriteReportRecord

/**
 * The isolated decoder process (docs/agent/MASTER_PLAN.md section 4.4): parsing untrusted files
 * with native code happens here, in a separate, `android:isolatedProcess="true"` process, never
 * in the UI process. A crash here marks that one file unsafe to preview; it must never take the
 * host app down. [DecoderClient] is the only caller and owns the timeout/kill/restart contract
 * this process is designed to be disposable under.
 *
 * `sniff` delegates to [FylzCore.sniffFile], backed by the `fylz-sniff` crate's real content
 * detection (M2.5). Its `runBlocking` is deliberate: AIDL calls run on a Binder thread-pool
 * thread with no caller waiting on anything else, so blocking it for the length of one native
 * call costs nothing a coroutine would save. (The archive calls need none, because the archive
 * engine's uniffi functions are synchronous -- a `suspend` function that never awaits is a
 * pattern not to copy.)
 *
 * `inspectArchive` (M3.2) reads the archive through the caller's descriptor with `fylz-archive`
 * and answers with an [ArchiveInspection] whose `outcome` carries the engine's verdict as data:
 * nothing is ever thrown across Binder from here. `listArchive` and `extractEntry` (M3.3) are the
 * browsing pair: the same header pass writing the full listing into a pipe the client owns, and
 * one entry by header ordinal streamed into another (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md`
 * section 2.2); this process closes its dup of each sink when the engine returns, which is the
 * EOF half of the pipe protocol (the client closes its own write end). `extractRanges` (M3.4,
 * `docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.3) is bulk extraction: the caller's
 * ordinal **bitmap** becomes exact inclusive ranges here, in-process (no Binder-size cap to merge
 * around), and one engine pass writes every selected entry as frames into the sink; the engine
 * answers with a record, never an exception, so the counts survive a fatal. The same service class
 * serves the browsing process (`:decoders`) and the isolated extraction instance
 * (`:decoders:extract`, `bindIsolatedService`); which one a call lands on is the client's choice.
 * `writeArchive` (M3.5a, `docs/agent/DESIGN-M35-CREATE.md` section 2.5) is the create pass: `FZW1`
 * frames in from one client-owned pipe, the raw archive stream out through a second -- served by
 * the same service class, on a third isolated instance (`:decoders:write`) `DecoderClient.writer()`
 * binds. Every engine is a constructor-injected lambda defaulting to the real [FylzCore] call, so the *mapping* -- every
 * `ArchiveEngineException` subclass to its outcome, any other `Throwable` to `OUTCOME_INTERNAL`,
 * the record-to-Parcelable copy -- is unit-tested on the JVM without a native library
 * (`DecoderServiceMappingTest`). Android instantiates the service through the no-argument
 * constructor Kotlin generates for the all-defaults primary one.
 */
class DecoderService(
    private val engine: ArchiveEngine = FylzCore::inspectArchive,
    private val listEngine: ArchiveListEngine = FylzCore::listArchive,
    private val extractEngine: ArchiveExtractEngine = FylzCore::extractEntryAt,
    private val extractRangesEngine: ArchiveExtractRangesEngine = FylzCore::extractRanges,
    private val writeEngine: ArchiveWriteEngine = FylzCore::writeFrames,
) : Service() {

    private val binder = object : IDecoderService.Stub() {
        override fun ping(): Boolean = true

        override fun sniff(pfd: ParcelFileDescriptor): String {
            pfd.use { open ->
                return runBlocking { FylzCore.sniffFile(open.fd) }
            }
        }

        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection =
            this@DecoderService.inspectArchive(archive, limits, maxRows)

        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection =
            this@DecoderService.listArchive(archive, limits, sink)

        override fun extractEntry(
            archive: ParcelFileDescriptor,
            ordinal: Int,
            expectedPath: String,
            limits: ArchiveLimits,
            sink: ParcelFileDescriptor,
        ): ArchiveExtractResult = this@DecoderService.extractEntry(archive, ordinal, expectedPath, limits, sink)

        override fun extractRanges(
            archive: ParcelFileDescriptor,
            limits: ArchiveLimits,
            ordinalsBitmap: ByteArray,
            sink: ParcelFileDescriptor,
        ): ArchiveExtractResult = this@DecoderService.extractRanges(archive, limits, ordinalsBitmap, sink)

        override fun writeArchive(
            input: ParcelFileDescriptor,
            options: ArchiveWriteOptions,
            output: ParcelFileDescriptor,
        ): ArchiveWriteResult = this@DecoderService.writeArchive(input, options, output)
    }

    /**
     * The Binder method's body, on the service itself so a test can call it directly. `use`
     * closes this process's dup of the descriptor when the engine returns, whatever the outcome;
     * the caller's own descriptor is untouched. The fd crosses into Rust as a plain integer and
     * is never wrapped in anything that would close it.
     */
    internal fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection =
        archive.use { open ->
            try {
                engine(open.fd, limits.toRecord(), maxRows).toInspection()
            } catch (failure: Throwable) {
                failure.toFailedInspection()
            }
        }

    /** As [inspectArchive]; both descriptors are closed here when the engine returns. */
    internal fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection =
        archive.use { open ->
            sink.use { out ->
                try {
                    listEngine(open.fd, limits.toRecord(), out.fd).toInspection()
                } catch (failure: Throwable) {
                    failure.toFailedInspection()
                }
            }
        }

    /** As [inspectArchive]; both descriptors are closed here when the engine returns. */
    internal fun extractEntry(
        archive: ParcelFileDescriptor,
        ordinal: Int,
        expectedPath: String,
        limits: ArchiveLimits,
        sink: ParcelFileDescriptor,
    ): ArchiveExtractResult = archive.use { open ->
        sink.use { out ->
            try {
                ArchiveExtractResult.ok(extractEngine(open.fd, ordinal, expectedPath, limits.toRecord(), out.fd))
            } catch (failure: Throwable) {
                failure.toFailedExtraction()
            }
        }
    }

    /**
     * As [extractEntry]: both descriptors are closed here when the engine returns. The bitmap is
     * decoded to inclusive ranges first ([OrdinalBitmap.ranges]); an empty selection is a valid,
     * empty pass. An engine exception here would be a bug (the engine returns a record for every
     * verdict), and maps like the others so nothing is ever thrown across Binder.
     */
    internal fun extractRanges(
        archive: ParcelFileDescriptor,
        limits: ArchiveLimits,
        ordinalsBitmap: ByteArray,
        sink: ParcelFileDescriptor,
    ): ArchiveExtractResult = archive.use { open ->
        sink.use { out ->
            try {
                val ranges = OrdinalBitmap.fromByteArray(ordinalsBitmap).ranges().map { range ->
                    ArchiveOrdinalRangeRecord(first = range.first.toUInt(), last = range.last.toUInt())
                }
                extractRangesEngine(open.fd, ranges, limits.toRecord(), out.fd).toResult()
            } catch (failure: Throwable) {
                failure.toFailedExtraction()
            }
        }
    }

    /**
     * As [extractRanges]: both descriptors are closed here when the engine returns (M3.5a,
     * design section 2.5). Runs on the same Binder thread as every other call here -- the
     * *isolation* of create from browsing/extraction is [DecoderClient.writer]'s own separate
     * bound instance and dedicated executor, not anything this method does; this class simply
     * serves whichever process it was started as.
     */
    internal fun writeArchive(
        input: ParcelFileDescriptor,
        options: ArchiveWriteOptions,
        output: ParcelFileDescriptor,
    ): ArchiveWriteResult = input.use { openInput ->
        output.use { openOutput ->
            try {
                writeEngine(openInput.fd, openOutput.fd, options.toRecord()).toResult()
            } catch (failure: Throwable) {
                failure.toFailedWrite()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
