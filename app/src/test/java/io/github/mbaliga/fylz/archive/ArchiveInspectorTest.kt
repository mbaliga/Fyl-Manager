package io.github.mbaliga.fylz.archive

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import io.github.mbaliga.fylz.data.ArchiveSpacePolicy
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.decoder.ArchiveWriteOptions
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.decoder.IDecoderService
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * `ArchiveInspector` (docs/agent/DESIGN-M32-SEEKABLE-PFD.md section 2.6): a real `DecoderClient`
 * over the `bind`/`unbind` seam with a fake `IDecoderService.Stub` (no native code, no isolated
 * process), a real `ArchiveSource` over the hosted file provider or the pipe provider, and the
 * mapping of every outcome. The staging case also proves the copy is gone by the time the result
 * is in hand.
 */
class ArchiveInspectorTest : FylzDocumentsProviderTestBase() {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val componentName = ComponentName("io.github.mbaliga.fylz", "io.github.mbaliga.fylz.decoder.DecoderService")
    private val limits = ArchiveLimits()
    private val payload = ByteArray(8 * 1024) { it.toByte() }

    private val okInspection = ArchiveInspection(
        outcome = ArchiveInspection.OUTCOME_OK,
        message = null,
        formatCode = ArchiveFormatFamily.ZIP,
        formatName = "ZIP 2.0 (deflation)",
        filters = emptyList(),
        archiveBytes = 1_514L,
        entryCount = 8,
        fileCount = 5,
        directoryCount = 3,
        linkCount = 0,
        totalUncompressedBytes = 1_626L,
        hasEncryptedEntries = true,
        hasEncryptedMetadata = false,
        hasLossyNames = false,
        policyAllowed = true,
        policyReason = null,
        rows = listOf(ArchiveEntryInfo("hello.txt", ArchiveEntryInfo.KIND_FILE, null, 11L, 1_577_836_800L, 0x1a4, false, false, false)),
        rowsTruncated = true,
    )

    private lateinit var pipeProvider: PipeDocumentsProvider

    @Before
    fun installPipeProvider() {
        pipeProvider = PipeDocumentsProvider.install().apply { bytes = payload }
    }

    /** A stub that records the descriptors it was handed and answers with [answer] (or throws it). */
    private class RecordingStub(private val answer: Any) : IDecoderService.Stub() {
        var calls = 0
        var lastStatSize: Long = -2L
        override fun ping() = true
        override fun listArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveInspection = error("not used")
        override fun extractEntry(archive: ParcelFileDescriptor, ordinal: Int, expectedPath: String, limits: ArchiveLimits, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
        override fun extractRanges(archive: ParcelFileDescriptor, limits: ArchiveLimits, ordinalsBitmap: ByteArray, sink: ParcelFileDescriptor): ArchiveExtractResult = error("not used")
        override fun writeArchive(input: ParcelFileDescriptor, options: ArchiveWriteOptions, output: ParcelFileDescriptor): ArchiveWriteResult = error("not used")
        override fun sniff(pfd: ParcelFileDescriptor) = "ok"
        override fun inspectArchive(archive: ParcelFileDescriptor, limits: ArchiveLimits, maxRows: Int): ArchiveInspection {
            calls += 1
            lastStatSize = archive.statSize
            return when (answer) {
                is ArchiveInspection -> answer
                is Throwable -> throw answer
                is Long -> { Thread.sleep(answer); okInspectionAfterHang }
                else -> error("unexpected answer $answer")
            }
        }

        companion object {
            val okInspectionAfterHang = ArchiveInspection.failed(ArchiveInspection.OUTCOME_INTERNAL, "too-late")
        }
    }

    private var bindCalls = 0

    private fun client(stub: IDecoderService.Stub) = DecoderClient(
        bind = { connection -> bindCalls++; connection.onServiceConnected(componentName, stub); true },
        unbind = {},
    )

    private fun localArchiveUri(): Uri {
        File(rootDir, "sample.zip").writeBytes(payload)
        return FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "sample.zip")
    }

    private fun directSource(availableCacheBytes: () -> Long? = { 123_456L }) =
        ArchiveSource(context, limits, availableCacheBytes = availableCacheBytes)

    private fun stagingSource(availableCacheBytes: () -> Long? = { Long.MAX_VALUE }) =
        ArchiveSource(context, limits, isSeekable = { false }, availableCacheBytes = availableCacheBytes)

    private fun workspaces(): List<File> = File(context.cacheDir, ArchiveSource.WORK_DIRECTORY).listFiles().orEmpty().toList()

    @Test
    fun `a local archive is Ready with the summary, not staged, and the space rows recomputed in Kotlin`() = runBlocking {
        val stub = RecordingStub(okInspection)
        val inspector = ArchiveInspector(directSource(), client(stub), limits)
        val result = inspector.inspect(localArchiveUri())
        val ready = result as? ArchiveInspectionResult.Ready ?: error("expected Ready, got $result")
        assertEquals(okInspection, ready.summary)
        assertEquals(okInspection, result.summaryOrNull)
        assertFalse(ready.staged)
        assertEquals(ArchiveSpacePolicy.requirements(1_514L, 1_626L), ready.temporarySpace)
        assertEquals(123_456L, ready.temporarySpaceAvailable)
        assertNull(result.failureMessage())
        assertEquals(1, stub.calls)
        assertEquals("the engine saw the provider's own file", payload.size.toLong(), stub.lastStatSize)
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `an unknown expanded size leaves the space rows empty`() = runBlocking {
        val stub = RecordingStub(okInspection.copy(totalUncompressedBytes = ArchiveInspection.UNKNOWN_SIZE))
        val ready = ArchiveInspector(directSource(), client(stub), limits).inspect(localArchiveUri()) as ArchiveInspectionResult.Ready
        assertNull(ready.temporarySpace)
        assertEquals(123_456L, ready.temporarySpaceAvailable)
    }

    @Test
    fun `an engine refusal is Refused with the outcome and the engine's text`() = runBlocking {
        val stub = RecordingStub(ArchiveInspection.failed(ArchiveInspection.OUTCOME_CORRUPT, "Truncated input file"))
        val result = ArchiveInspector(directSource(), client(stub), limits).inspect(localArchiveUri())
        assertEquals(ArchiveInspectionResult.Refused(ArchiveInspection.OUTCOME_CORRUPT, "Truncated input file"), result)
        assertNull(result.summaryOrNull)
        assertEquals("The archive is damaged or could not be read: Truncated input file", result.failureMessage())
        val unsupported = RecordingStub(ArchiveInspection.failed(ArchiveInspection.OUTCOME_UNSUPPORTED, "Unrecognized archive format"))
        assertEquals(
            "This file is not an archive Fylz can open.",
            ArchiveInspector(directSource(), client(unsupported), limits).inspect(localArchiveUri()).failureMessage(),
        )
    }

    @Test
    fun `a hung decoder is TimedOut`() = runBlocking {
        val stub = RecordingStub(1_500L)
        val inspector = ArchiveInspector(directSource(), client(stub), limits, structureTimeoutMillis = 100)
        val start = System.nanoTime()
        val result = inspector.inspect(localArchiveUri())
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertEquals(ArchiveInspectionResult.TimedOut, result)
        assertTrue("returned after $elapsedMillis ms", elapsedMillis < 1_000)
        assertEquals("The archive took too long to read.", result.failureMessage())
    }

    @Test
    fun `a dead decoder is Unavailable`() = runBlocking {
        val stub = RecordingStub(DeadObjectException())
        val result = ArchiveInspector(directSource(), client(stub), limits).inspect(localArchiveUri())
        assertEquals(ArchiveInspectionResult.Unavailable, result)
        assertEquals("The archive could not be read safely.", result.failureMessage())
    }

    @Test
    fun `too little space to stage is SourceFailed and the decoder is never called or bound`() = runBlocking {
        pipeProvider.declaredSize = null
        val stub = RecordingStub(okInspection)
        val result = ArchiveInspector(stagingSource(availableCacheBytes = { 0L }), client(stub), limits)
            .inspect(PipeDocumentsProvider.documentUri())
        val failed = result as? ArchiveInspectionResult.SourceFailed ?: error("expected SourceFailed, got $result")
        assertTrue(failed.cause is ArchiveSourceException.InsufficientSpace)
        assertEquals(failed.cause.message, result.failureMessage())
        assertEquals(0, stub.calls)
        assertEquals(0, bindCalls)
        assertTrue(workspaces().isEmpty())
    }

    @Test
    fun `a streamed archive is staged for the call and the copy is gone when the result is in hand`() = runBlocking {
        pipeProvider.declaredSize = payload.size.toLong()
        val stub = RecordingStub(okInspection)
        val result = ArchiveInspector(stagingSource(), client(stub), limits).inspect(PipeDocumentsProvider.documentUri())
        val ready = result as? ArchiveInspectionResult.Ready ?: error("expected Ready, got $result")
        assertTrue(ready.staged)
        assertEquals(okInspection, ready.summary)
        assertEquals("the engine read the staged copy", payload.size.toLong(), stub.lastStatSize)
        assertTrue("the staged copy is released as soon as the summary exists", workspaces().isEmpty())
    }
}
