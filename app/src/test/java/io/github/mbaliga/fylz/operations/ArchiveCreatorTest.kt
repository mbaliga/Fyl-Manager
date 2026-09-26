package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `ArchiveCreator` (`docs/agent/DESIGN-M35-CREATE.md` section 2.3) end to end over the hosted file
 * provider and [FakeArchiveDecoder] parsing real FZW1 frames and writing a fake archive back: the
 * feed/drain/writeArchive pass through the isolated write instance, split rotation and last-to-
 * first finalisation, conflicts resolved as one unit, verification (primary SHA-256 and the
 * secondary listing check), a decoder-reported failure and a protocol error each mapped to their
 * own error code, an unreadable source, the cancel flag closing the pass, a system stop's pause and
 * resume bounded by MAX_RESTARTS, and claim rules mirroring [ArchiveExtractor]'s own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveCreatorTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var rootDir: File
    private lateinit var destinationDir: File
    private lateinit var destination: Uri
    private lateinit var faulty: FaultyDocumentsProvider
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var journal: OperationJournal
    private val unbinds = AtomicInteger()
    private var now = 0L

    /** Every check the creator makes advances real time, so the cancel poll and progress throttle
     * never accidentally skip a check because a fast test outran the wall clock (mirrors
     * [ArchiveExtractorTest]'s own `ticking`). */
    private val ticking: () -> Long = { now += 1_000L; now }

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        destinationDir = File(rootDir, "dest").apply { mkdirs() }
        destination = ExtractTestArchives.treeUri("dest")
        faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(VolumeDescriptor(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Internal storage", rootDir, primary = true, removable = false, readOnly = false))
        stub = FakeArchiveDecoder()
        journal = OperationJournal(context)
    }

    private fun creator(verifyMode: VerifyMode = VerifyMode.REMOVABLE_AND_NETWORK, clock: () -> Long = ticking) = ArchiveCreator(
        context = context,
        journal = journal,
        writerClient = { FakeArchiveDecoder.client(stub, onUnbind = { unbinds.incrementAndGet() }) },
        verifySettings = VerifySettings(context).apply { setMode(verifyMode) },
        workLookup = WorkLookup.NONE,
        streamInactivityMillis = 5_000L,
        cancelWaitMillis = 500L,
        clock = clock,
    )

    private fun sourceUri(relative: String): Uri = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relative)

    private fun planAndStore(
        sources: List<Uri>,
        format: CompressFormat = CompressFormat.ZIP,
        split: SplitSize = SplitSize.Off,
        archiveName: String = "out",
        conflictUi: CompressPlannerUi = HeadlessCompressUi(),
    ): String {
        val catalog = ExtractTestArchives.catalog(context, stub)
        val planner = CompressPlanner(context.contentResolver, catalog, volumeFor = { ExtractTestArchives.ext4() })
        val request = CompressRequest(sources, format, 6, split, relativeToSelection = true, archiveName = archiveName, destinationFolder = destination)
        val result = runBlocking { planner.plan(request, conflictUi) }
        check(result is CompressPlanResult.Planned) { "expected Planned, got $result" }
        journal.putWithCreatePlan(result.operation, result.plan, result.manifest)
        return result.operation.id
    }

    /** A plan built by hand, for scenarios [planAndStore]'s real walk can't easily arrange (a
     * source deleted after planning, a specific conflict policy). */
    private fun storeManualPlan(
        entries: List<CompressManifestEntry>,
        conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
        split: SplitSize = SplitSize.Off,
        archiveName: String = "out.zip",
        format: CompressFormat = CompressFormat.ZIP,
    ): String {
        val operation = FileOperation(
            type = FileOperationType.ARCHIVE,
            items = listOf(OperationItem(source = entries.first().sourceUri, destination = destination, displayName = archiveName, state = OperationState.QUEUED)),
            conflictPolicy = ConflictPolicy.SKIP,
            state = OperationState.QUEUED,
            destination = destination,
        )
        val plan = CompressPlan(
            operationId = operation.id,
            format = format,
            level = 6,
            split = split,
            relativeToSelection = true,
            archiveName = archiveName,
            destinationUri = destination,
            totalEstimate = null,
            entryCount = entries.size,
            conflictPolicy = conflictPolicy,
        )
        journal.putWithCreatePlan(operation, plan, entries)
        return operation.id
    }

    private fun run(id: String, verifyMode: VerifyMode = VerifyMode.REMOVABLE_AND_NETWORK, onProgress: (CreateProgress) -> Unit = {}): CreateRunOutcome =
        runBlocking { creator(verifyMode).run(id, onProgress = onProgress) }

    private fun noStagingLeft() {
        val staged = destinationDir.walkTopDown().filter { it.name.startsWith(STAGING_NAME_PREFIX) }.toList()
        assertTrue("staged documents left behind: $staged", staged.isEmpty())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a plain zip compresses a folder and a file, verifies, and finalises with no staging left`() {
        File(rootDir, "docs").mkdirs()
        File(rootDir, "docs/a.txt").writeText("hello")
        File(rootDir, "b.txt").writeText("world")
        val id = planAndStore(listOf(sourceUri("docs"), sourceUri("b.txt")))
        val outcome = run(id, verifyMode = VerifyMode.ALWAYS)
        assertEquals(CreateRunOutcome.Finished(OperationState.SUCCEEDED), outcome)
        val output = File(destinationDir, "out.zip")
        assertTrue(output.isFile)
        noStagingLeft()
        assertEquals(1, stub.writeCalls.size)
        val plan = journal.createPlan(id)!!
        assertEquals(0, plan.restartCount)
        val operation = journal.find(id)!!
        assertEquals(OperationState.SUCCEEDED, operation.state)
        assertTrue(operation.items.all { it.state == OperationState.SUCCEEDED })
        assertEquals("the write instance is unbound when the run ends", 1, unbinds.get())
        val part = journal.createPlanItems(id).single()
        assertEquals(OperationState.SUCCEEDED, part.state)
        assertEquals(sha256(output.readBytes()), part.sha256)
        assertEquals(output.length(), part.bytesWritten)
    }

    @Test
    fun `a split output rotates parts and finalises last-to-first with no gaps`() {
        File(rootDir, "big.bin").writeBytes(ByteArray(6_000) { (it % 250).toByte() })
        val id = planAndStore(listOf(sourceUri("big.bin")), split = SplitSize.At(1_000L))
        val outcome = run(id)
        assertEquals(CreateRunOutcome.Finished(OperationState.SUCCEEDED), outcome)
        noStagingLeft()
        val parts = destinationDir.listFiles { f -> f.name.startsWith("out.zip.") }!!.sortedBy { it.name }
        assertTrue("expected more than one part, got ${parts.map { it.name }}", parts.size > 1)
        assertEquals((1..parts.size).map { "out.zip." + it.toString().padStart(3, '0') }, parts.map { it.name })
        assertFalse(File(destinationDir, "out.zip").exists())
    }

    @Test
    fun `a REPLACE conflict removes every existing base and numbered part before the new set finalises, as one unit`() {
        File(destinationDir, "out.zip").writeText("old base")
        File(destinationDir, "out.zip.001").writeText("old part 1")
        File(destinationDir, "out.zip.002").writeText("old part 2")
        File(rootDir, "a.txt").writeText("new content")
        val entries = listOf(CompressManifestEntry(0, false, "a.txt", sourceUri("a.txt"), 0L, needsSpooling = false))
        val id = storeManualPlan(entries, conflictPolicy = ConflictPolicy.REPLACE)
        assertEquals(CreateRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertFalse(File(destinationDir, "out.zip.001").exists())
        assertFalse(File(destinationDir, "out.zip.002").exists())
        assertTrue(File(destinationDir, "out.zip").isFile)
        assertTrue("the old base's bytes are gone", File(destinationDir, "out.zip").readText() != "old base")
        noStagingLeft()
    }

    @Test
    fun `a decoder-reported failure discards every staged part and fails with ARCHIVE_WRITE_FAILED`() {
        File(rootDir, "a.txt").writeText("x")
        stub.writeFailure = ArchiveWriteResult.failed(ArchiveWriteResult.OUTCOME_CORRUPT, "disk full")
        val id = planAndStore(listOf(sourceUri("a.txt")))
        assertEquals(CreateRunOutcome.Finished(OperationState.FAILED), run(id))
        assertEquals(emptyList<String>(), destinationDir.list()!!.filterNot { it == "a.txt" })
        val items = journal.find(id)!!.items
        assertTrue(items.all { it.state == OperationState.FAILED && it.errorCode == CreateErrorCodes.ARCHIVE_WRITE_FAILED })
    }

    @Test
    fun `a protocol error from the engine maps to PROTOCOL_ERROR, not the generic write-failure code`() {
        File(rootDir, "a.txt").writeText("x")
        File(rootDir, "b.txt").writeText("y")
        stub.protocolErrorAtOrdinal = 1
        val id = planAndStore(listOf(sourceUri("a.txt"), sourceUri("b.txt")))
        assertEquals(CreateRunOutcome.Finished(OperationState.FAILED), run(id))
        val items = journal.find(id)!!.items
        assertTrue(items.all { it.errorCode == CreateErrorCodes.PROTOCOL_ERROR })
        assertFalse(File(destinationDir, "out.zip").exists())
    }

    @Test
    fun `a source that no longer exists fails as SOURCE_UNREADABLE and discards the output`() {
        val missing = File(rootDir, "gone.txt").apply { writeText("x") }
        val entries = listOf(CompressManifestEntry(0, false, "gone.txt", sourceUri("gone.txt"), 0L, needsSpooling = false))
        val id = storeManualPlan(entries)
        assertTrue(missing.delete())
        assertEquals(CreateRunOutcome.Finished(OperationState.FAILED), run(id))
        assertFalse(File(destinationDir, "out.zip").exists())
        val items = journal.find(id)!!.items
        assertTrue(items.all { it.errorCode == CreateErrorCodes.SOURCE_UNREADABLE })
    }

    @Test
    fun `a cancel requested mid-pass stops the next entry, aborts the write, and leaves nothing at the destination`() {
        // Robolectric's own pipes never apply real backpressure (confirmed empirically: a write of
        // tens of megabytes with no reader at all returns immediately), so timing the flag off the
        // fake engine's own read progress would race the feeder non-deterministically. Progress
        // itself is deterministic instead: onProgress fires synchronously on the FEEDER's own
        // thread right after an entry finishes (reportProgress, inline in feedFile), strictly
        // before the outer loop's next-entry cancellation check -- so setting the flag there is
        // guaranteed to land before that check, every time, with no race at all.
        File(rootDir, "a.txt").writeText("first")
        File(rootDir, "b.txt").writeText("never fed")
        val id = planAndStore(listOf(sourceUri("a.txt"), sourceUri("b.txt")))
        var armed = false
        val outcome = run(id) { progress ->
            if (!armed && progress.entriesDone >= 1) {
                armed = true
                journal.setCreateCancelRequested(id)
            }
        }
        assertEquals(CreateRunOutcome.Finished(OperationState.CANCELLED), outcome)
        assertEquals(emptyList<String>(), destinationDir.list()!!.toList())
        val items = journal.find(id)!!.items
        assertTrue(items.all { it.state == OperationState.CANCELLED && it.errorCode == CreateErrorCodes.USER_CANCELLED })
        assertEquals(1, unbinds.get())
    }

    @Test
    fun `a cancel requested before the run starts ends it before any writeArchive call`() {
        File(rootDir, "a.txt").writeText("x")
        val id = planAndStore(listOf(sourceUri("a.txt")))
        journal.setCreateCancelRequested(id)
        assertEquals(CreateRunOutcome.Finished(OperationState.CANCELLED), run(id))
        assertEquals(0, stub.writeCalls.size)
    }

    @Test
    fun `a system stop pauses the operation, bumps the restart count, and a later run resumes and finishes`() {
        File(rootDir, "a.txt").writeText("x")
        File(rootDir, "b.txt").writeText("y")
        val id = planAndStore(listOf(sourceUri("a.txt"), sourceUri("b.txt")))
        lateinit var job: Job
        stub.beforeWriteData = { ordinal -> if (ordinal == 0) job.cancel(); true }
        runBlocking {
            job = launch(Dispatchers.IO) {
                runCatching { creator().run(id, stopReason = { WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY }) }
            }
            job.join()
        }
        val paused = journal.find(id)!!
        assertEquals(OperationState.PAUSED_BY_SYSTEM, paused.state)
        assertEquals(1, journal.createPlan(id)!!.restartCount)
        stub.beforeWriteData = { true }
        assertEquals(CreateRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertTrue(File(destinationDir, "out.zip").isFile)
        noStagingLeft()
    }

    @Test
    fun `restarts beyond MAX_RESTARTS fail the operation as TOO_MANY_INTERRUPTIONS rather than pause again`() {
        File(rootDir, "a.txt").writeText("x")
        val id = planAndStore(listOf(sourceUri("a.txt")))
        repeat(ArchiveCreator.MAX_RESTARTS) { journal.incrementCreateRestartCount(id) }
        assertEquals(CreateRunOutcome.Finished(OperationState.FAILED), run(id))
        val items = journal.find(id)!!.items
        assertTrue(items.all { it.errorCode == CreateErrorCodes.TOO_MANY_INTERRUPTIONS })
    }

    @Test
    fun `a second run on a finished operation, a legacy row, or a missing id is not claimed`() {
        File(rootDir, "a.txt").writeText("x")
        val id = planAndStore(listOf(sourceUri("a.txt")))
        assertEquals(CreateRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        val after = journal.find(id)!!
        assertEquals(CreateRunOutcome.NotClaimed, run(id))
        assertEquals(after, journal.find(id))
        assertEquals(1, stub.writeCalls.size)
        journal.put(FileOperation(id = "legacy", type = FileOperationType.ARCHIVE, items = listOf(OperationItem(source = Uri.parse("content://x/a.txt"), displayName = "a.txt")), state = OperationState.QUEUED))
        assertEquals(CreateRunOutcome.NotClaimed, run("legacy"))
        assertEquals(CreateRunOutcome.NotClaimed, run("missing"))
    }

    @Test
    fun `progress is reported with increasing fed bytes as entries are fed`() {
        File(rootDir, "a.txt").writeText("x".repeat(1_000))
        File(rootDir, "b.txt").writeText("y".repeat(1_000))
        val id = planAndStore(listOf(sourceUri("a.txt"), sourceUri("b.txt")))
        val progress = mutableListOf<CreateProgress>()
        run(id) { progress += it }
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (a, b) -> b.completedBytes >= a.completedBytes })
        assertEquals(2_000L, progress.last().completedBytes)
    }
}
