package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.ExtractFrameReader
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveExtractResult
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import io.github.mbaliga.fylz.storage.VolumeInfo
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
 * `ArchiveExtractor` (`docs/agent/DESIGN-M34-SELECTIVE-EXTRACT.md` section 2.3) end to end over
 * the hosted file provider ([FaultyDocumentsProvider] for its write-failure seams) and
 * [FakeArchiveDecoder] writing real FZX1 frames: the three layouts byte-identical with the hardlink
 * under both paths and directories on demand; a per-entry `FAIL`, a lying size and an app-side
 * refusal isolating their item; `ABORT` and a transport loss re-issuing once with reduced limits;
 * `REFUSED`/`LIMIT_EXCEEDED` leaving nothing and not retrying; the cancel flag; a system stop and
 * the resumed re-run; verification digests; conflict rules settled at claim; the changed-archive
 * check; progress numbers; a wide `Here`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveExtractorTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var rootDir: File
    private lateinit var destinationDir: File
    private lateinit var destination: Uri
    private lateinit var faulty: FaultyDocumentsProvider
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var catalog: ArchiveCatalog
    private lateinit var journal: OperationJournal
    private var volume: VolumeInfo? = ExtractTestArchives.ext4()
    private val unbinds = AtomicInteger()
    private var now = 0L

    /** Every read advances a second: the cancel poll, the progress report and the journal throttle all fire on every frame. */
    private val ticking: () -> Long = { now += 1_000L; now }

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        destinationDir = File(rootDir, "dest").apply { mkdirs() }
        destination = ExtractTestArchives.treeUri("dest")
        faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(VolumeDescriptor(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Internal storage", rootDir, primary = true, removable = false, readOnly = false))
        stub = FakeArchiveDecoder()
        catalog = ExtractTestArchives.catalog(context, stub)
        journal = OperationJournal(context)
    }

    private fun extractor(clock: () -> Long = ticking) = ArchiveExtractor(
        context = context,
        journal = journal,
        catalog = catalog,
        extractionClient = { FakeArchiveDecoder.client(stub, onUnbind = { unbinds.incrementAndGet() }) },
        volumeFor = { volume },
        workLookup = WorkLookup.NONE,
        streamInactivityMillis = 5_000L,
        cancelWaitMillis = 500L,
        clock = clock,
    )

    private fun archive(name: String = "a.zip", fake: FakeArchive = ExtractTestArchives.sample()): ArchiveRef = ExtractTestArchives.write(rootDir, name, fake)

    private fun planAndStore(ref: ArchiveRef, selection: ExtractSelection = ExtractSelection.All, layout: ExtractLayoutRequest = ExtractLayoutRequest.Here, ui: PlannerUi = HeadlessPlannerUi()): String {
        val result = runBlocking { ExtractPlanner(context.contentResolver, catalog, volumeFor = { volume }).plan(ExtractRequest(ref, selection, destination, layout), ui) }
        check(result is ExtractPlanResult.Planned) { "expected Planned, got $result" }
        journal.putWithExtractPlan(result.operation, result.plan)
        return result.operation.id
    }

    private fun run(id: String, onProgress: (ExtractProgress) -> Unit = {}): ExtractRunOutcome = runBlocking { extractor().run(id, onProgress = onProgress) }

    private fun files(): Map<String, ByteArray> = ExtractTestArchives.filesUnder(destinationDir)

    private fun assertBytes(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys.sorted(), actual.keys.sorted())
        expected.forEach { (path, bytes) -> assertEquals(path, bytes.toList(), actual.getValue(path).toList()) }
    }

    private fun noStagingLeft() {
        val staged = destinationDir.walkTopDown().filter { it.name.startsWith(STAGING_NAME_PREFIX) }.toList()
        assertTrue("staged documents left behind: $staged", staged.isEmpty())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `Here extracts every item byte-identical, directories on demand, the hardlink under both paths, and finalises each item`() {
        val id = planAndStore(archive())
        val outcome = run(id)
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), outcome)
        assertBytes(ExtractTestArchives.expectedFiles, files())
        assertTrue(File(destinationDir, "late").isDirectory)
        assertTrue(File(destinationDir, "images").isDirectory)
        noStagingLeft()
        val operation = journal.find(id)!!
        assertEquals(OperationState.SUCCEEDED, operation.state)
        operation.items.forEach { item ->
            assertEquals(item.displayName, OperationState.SUCCEEDED, item.state)
            assertNull(item.errorCode)
            assertNull(item.stagingUri)
            assertNotNull(item.finalUri)
            assertEquals(item.displayName, item.expectedBytes, item.completedBytes)
        }
        assertEquals(1, stub.rangesCalls.size)
        val call = stub.rangesCalls.single()
        assertEquals(journal.extractPlan(id)!!.ordinals, call.ordinals)
        assertEquals(journal.extractPlan(id)!!.limits, call.limits)
        assertEquals("the extraction instance is unbound when the run ends", 1, unbinds.get())
        assertEquals(1, journal.operations.value.size)
    }

    @Test
    fun `IntoFolder lands everything under the one folder, Entries lands the selected entries`() {
        val ref = archive()
        val folder = planAndStore(ref, layout = ExtractLayoutRequest.IntoFolder("photos"))
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(folder))
        assertBytes(ExtractTestArchives.expectedFiles.mapKeys { "photos/${it.key}" }, files())
        assertEquals(listOf("photos"), destinationDir.list()!!.toList())
        noStagingLeft()

        val entriesDir = File(rootDir, "dest2").apply { mkdirs() }
        destination = ExtractTestArchives.treeUri("dest2")
        val ids = listOf(ArchiveDocumentId(ref.source, ref.chain, ExtractTestArchives.DOCS, "docs"), ArchiveDocumentId(ref.source, ref.chain, ExtractTestArchives.LATE_X, "late/x.txt"))
        val entries = planAndStore(ref, selection = ExtractSelection.Entries(ids))
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(entries))
        assertBytes(
            mapOf("docs/readme.md" to ExtractTestArchives.readme, "docs/guide.md" to ExtractTestArchives.guide, "docs/link-to-hello" to ExtractTestArchives.hello, "x.txt" to ExtractTestArchives.late),
            ExtractTestArchives.filesUnder(entriesDir),
        )
    }

    @Test
    fun `a FAIL frame fails only its item and deletes the partial file, a header-level FAIL likewise, the rest lands -- PARTIAL`() {
        stub.failOrdinals[ExtractTestArchives.GUIDE] = ExtractFrameReader.FAIL_CRC
        stub.headerFailOrdinals += ExtractTestArchives.LATE_X
        val id = planAndStore(archive())
        assertEquals(ExtractRunOutcome.Finished(OperationState.PARTIAL), run(id))
        val expected = ExtractTestArchives.expectedFiles.filterKeys { !it.startsWith("docs/") && !it.startsWith("late/") }
        assertBytes(expected, files())
        assertFalse(File(destinationDir, "docs").exists())
        assertFalse(File(destinationDir, "late").exists())
        noStagingLeft()
        val items = journal.find(id)!!.items.associateBy { it.displayName }
        assertEquals(OperationState.FAILED, items.getValue("docs").state)
        assertEquals(ExtractErrorCodes.ARCHIVE_CRC_MISMATCH, items.getValue("docs").errorCode)
        assertEquals(OperationState.FAILED, items.getValue("late").state)
        assertEquals(ExtractErrorCodes.ARCHIVE_ENTRY_UNREADABLE, items.getValue("late").errorCode)
        assertEquals(OperationState.SUCCEEDED, items.getValue("hello.txt").state)
        assertEquals(1, stub.rangesCalls.size)
    }

    @Test
    fun `an END whose byte count disagrees with what arrived is a size mismatch for its item`() {
        stub.lieAboutSize[ExtractTestArchives.HELLO] = 3L
        val id = planAndStore(archive())
        assertEquals(ExtractRunOutcome.Finished(OperationState.PARTIAL), run(id))
        val items = journal.find(id)!!.items.associateBy { it.displayName }
        assertEquals(ExtractErrorCodes.SIZE_MISMATCH, items.getValue("hello.txt").errorCode)
        // The hardlink copy inside docs shares the ordinal, so docs fails with it; the rest lands.
        assertEquals(ExtractErrorCodes.SIZE_MISMATCH, items.getValue("docs").errorCode)
        assertEquals(OperationState.SUCCEEDED, items.getValue("images").state)
        assertFalse(File(destinationDir, "hello.txt").exists())
        noStagingLeft()
    }

    @Test
    fun `an app-side create refusal fails the items on this side and the pass still completes`() {
        faulty.refuseCreate = true
        val id = planAndStore(archive())
        faulty.refuseCreate = true
        assertEquals(ExtractRunOutcome.Finished(OperationState.FAILED), run(id))
        assertTrue(journal.find(id)!!.items.all { it.state == OperationState.FAILED && it.errorCode == ExtractErrorCodes.WRITE_FAILED })
        assertEquals("the pass ran once; the demuxer discarded the frames", 1, stub.rangesCalls.size)
        assertEquals(emptyList<String>(), destinationDir.list()!!.toList())
        faulty.refuseCreate = false
    }

    @Test
    fun `an ABORT re-issues the pass once from the stop ordinal, minus what completed, with the limits reduced`() {
        stub.abortAtOrdinal = ExtractTestArchives.HELLO
        val id = planAndStore(archive())
        val plan = journal.extractPlan(id)!!
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertBytes(ExtractTestArchives.expectedFiles, files())
        noStagingLeft()
        assertEquals(2, stub.rangesCalls.size)
        val (first, second) = stub.rangesCalls
        assertEquals(plan.ordinals, first.ordinals)
        assertEquals(listOf(3, 4, 7, 8, 9), second.ordinals.ordinals().toList())
        val completedBytes = ExtractTestArchives.readme.size + ExtractTestArchives.guide.size.toLong()
        assertEquals(first.limits.maxTotalUncompressedBytes - completedBytes, second.limits.maxTotalUncompressedBytes)
        assertEquals("docs/, readme.md and guide.md were done", first.limits.maxEntries - 3, second.limits.maxEntries)
        assertEquals(first.limits.maxFileBytes, second.limits.maxFileBytes)
    }

    @Test
    fun `a stream cut mid-entry re-issues once from after the last completed entry and the partial document is replaced`() {
        stub.truncateAtOrdinal = ExtractTestArchives.GUIDE
        val id = planAndStore(archive())
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertBytes(ExtractTestArchives.expectedFiles, files())
        noStagingLeft()
        assertEquals(2, stub.rangesCalls.size)
        assertEquals(ExtractTestArchives.GUIDE, stub.rangesCalls[1].ordinals.ordinals().first())
        assertFalse(ExtractTestArchives.README in stub.rangesCalls[1].ordinals)
    }

    @Test
    fun `a second abort is not retried again and the unfinished items fail as ARCHIVE_FATAL`() {
        stub.abortAtOrdinal = ExtractTestArchives.HELLO
        val id = planAndStore(archive())
        // The fake clears `abortAtOrdinal` after aborting; arm it again during the re-issued pass so that one aborts too.
        val stubRef = stub
        stubRef.beforeData = { ordinal ->
            if (ordinal == ExtractTestArchives.HELLO && stubRef.rangesCalls.size == 2) stubRef.abortAtOrdinal = ExtractTestArchives.PIXEL
            true
        }
        assertEquals(ExtractRunOutcome.Finished(OperationState.PARTIAL), run(id))
        assertEquals(2, stub.rangesCalls.size)
        val items = journal.find(id)!!.items.associateBy { it.displayName }
        assertEquals(OperationState.SUCCEEDED, items.getValue("docs").state)
        assertEquals(OperationState.SUCCEEDED, items.getValue("hello.txt").state)
        listOf("images", "late", "bad?name.txt").forEach { name ->
            assertEquals(name, OperationState.FAILED, items.getValue(name).state)
            assertEquals(name, ExtractErrorCodes.ARCHIVE_FATAL, items.getValue(name).errorCode)
        }
        noStagingLeft()
    }

    @Test
    fun `REFUSED and LIMIT_EXCEEDED fail every pending item, leave nothing behind and are never retried`() {
        stub.rangesFailure = ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_REFUSED, "Archive exceeds the total size limit.")
        val refused = planAndStore(archive())
        assertEquals(ExtractRunOutcome.Finished(OperationState.FAILED), run(refused))
        assertTrue(journal.find(refused)!!.items.all { it.errorCode == ExtractErrorCodes.ARCHIVE_REFUSED })
        assertEquals(emptyList<String>(), destinationDir.list()!!.toList())
        assertEquals(1, stub.rangesCalls.size)

        stub.rangesFailure = ArchiveExtractResult.failed(ArchiveExtractResult.OUTCOME_LIMIT_EXCEEDED, "limit exceeded (file) at entry x")
        val limited = planAndStore(archive("b.zip"))
        assertEquals(ExtractRunOutcome.Finished(OperationState.FAILED), run(limited))
        assertTrue(journal.find(limited)!!.items.all { it.errorCode == ExtractErrorCodes.LIMIT_EXCEEDED })
        assertEquals(2, stub.rangesCalls.size)
        assertEquals(emptyList<String>(), destinationDir.list()!!.toList())
    }

    @Test
    fun `a cancel through the plan's flag mid-pass leaves nothing under any name, writes CANCELLED and unbinds`() {
        val id = planAndStore(archive())
        stub.beforeData = { ordinal -> if (ordinal == ExtractTestArchives.HELLO) journal.setCancelRequested(id); true }
        assertEquals(ExtractRunOutcome.Finished(OperationState.CANCELLED), run(id))
        val operation = journal.find(id)!!
        assertEquals(OperationState.CANCELLED, operation.state)
        assertTrue(operation.items.all { it.state == OperationState.CANCELLED && it.errorCode == ExtractErrorCodes.USER_CANCELLED && it.stagingUri == null })
        assertEquals(emptyList<String>(), destinationDir.list()!!.toList())
        assertEquals(1, unbinds.get())
        // A cancel requested before the run starts ends it before any call.
        val early = planAndStore(archive("c.zip"))
        journal.setCancelRequested(early)
        val callsBefore = stub.rangesCalls.size
        assertEquals(ExtractRunOutcome.Finished(OperationState.CANCELLED), run(early))
        assertEquals(callsBefore, stub.rangesCalls.size)
    }

    @Test
    fun `a system stop pauses the operation with its staging kept, and the re-run claims, cleans and finishes it`() {
        val id = planAndStore(archive())
        lateinit var job: Job
        stub.rangesInterleaveMillis = 5L
        stub.beforeData = { ordinal -> if (ordinal == ExtractTestArchives.PIXEL) job.cancel(); true }
        runBlocking {
            job = launch(Dispatchers.IO) {
                runCatching { extractor().run(id, stopReason = { WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY }) }
            }
            job.join()
        }
        val paused = journal.find(id)!!
        assertEquals(OperationState.PAUSED_BY_SYSTEM, paused.state)
        assertTrue(paused.items.toString(), paused.items.all { it.state == OperationState.PAUSED_BY_SYSTEM || it.state == OperationState.SUCCEEDED })
        paused.items.filter { it.stagingUri != null }.forEach { item ->
            assertNotNull("recorded staging is kept for the re-run", DocNode.load(context.contentResolver, item.stagingUri!!))
        }
        stub.beforeData = { true }
        stub.rangesInterleaveMillis = 0L
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertBytes(ExtractTestArchives.expectedFiles, files())
        noStagingLeft()
        assertEquals(2, unbinds.get())
    }

    @Test
    fun `a re-run after a pause deletes the staged document a previous run recorded before starting the item over`() {
        val id = planAndStore(archive())
        val before = journal.find(id)!!
        val stale = File(destinationDir, ".fylz-part-$id-0-docs").apply { mkdirs(); File(this, "half.bin").writeBytes(ByteArray(10)) }
        val staleUri = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "dest/${stale.name}")
        journal.put(before.copy(state = OperationState.PAUSED_BY_SYSTEM, items = before.items.mapIndexed { i, item -> if (i == 0) item.copy(state = OperationState.PAUSED_BY_SYSTEM, stagingUri = staleUri) else item.copy(state = OperationState.PAUSED_BY_SYSTEM) }))
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertFalse(stale.exists())
        assertBytes(ExtractTestArchives.expectedFiles, files())
        noStagingLeft()
    }

    @Test
    fun `a second run on a finished operation, a legacy row or a missing id is not claimed`() {
        val id = planAndStore(archive())
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        val after = journal.find(id)!!
        assertEquals(ExtractRunOutcome.NotClaimed, run(id))
        assertEquals(after, journal.find(id))
        assertEquals(1, stub.rangesCalls.size)
        journal.put(FileOperation(id = "legacy", type = FileOperationType.EXTRACT, items = listOf(OperationItem(source = Uri.parse("content://x/a.zip"), displayName = "a.zip")), state = OperationState.QUEUED))
        assertEquals(ExtractRunOutcome.NotClaimed, run("legacy"))
        assertEquals(ExtractRunOutcome.NotClaimed, run("missing"))
    }

    @Test
    fun `verification ALWAYS re-reads every file, records every entry digest and the root file's sha256`() {
        VerifySettings(context).setMode(VerifyMode.ALWAYS)
        val id = planAndStore(archive())
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        val digests = journal.entryDigests(id)
        assertEquals(listOf(1, 2, 3, 4, 7, 9), digests.keys.toList())
        assertEquals(sha256(ExtractTestArchives.guide), digests[ExtractTestArchives.GUIDE])
        assertEquals(sha256(ExtractTestArchives.hello), digests[ExtractTestArchives.HELLO])
        val items = journal.find(id)!!.items.associateBy { it.displayName }
        assertEquals(sha256(ExtractTestArchives.hello), items.getValue("hello.txt").sha256)
        assertNull("a folder item has no single hash", items.getValue("docs").sha256)
        VerifySettings(context).setMode(VerifyMode.OFF)
        val second = planAndStore(archive("b.zip"), layout = ExtractLayoutRequest.IntoFolder("again"))
        run(second)
        assertTrue("digests are recorded only when verifying", journal.entryDigests(second).isEmpty())
    }

    @Test
    fun `a SKIP conflict is settled at claim without extracting the item, REPLACE replaces, KEEP_BOTH re-uniquifies a name taken since planning`() {
        File(destinationDir, "late").mkdirs()
        File(destinationDir, "late/old.txt").writeText("old")
        File(destinationDir, "hello.txt").writeText("old hello")
        val ref = archive()
        val skip = planAndStore(ref, ui = HeadlessPlannerUi(conflictPolicy = ConflictPolicy.SKIP))
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(skip))
        val skipped = journal.find(skip)!!.items.associateBy { it.displayName }
        assertEquals(ExtractErrorCodes.SKIPPED_CONFLICT, skipped.getValue("late").errorCode)
        assertEquals(ExtractErrorCodes.SKIPPED_CONFLICT, skipped.getValue("hello.txt").errorCode)
        assertEquals("old", File(destinationDir, "late/old.txt").readText())
        assertEquals("old hello", File(destinationDir, "hello.txt").readText())
        assertFalse("the skipped folder's ordinals were not read", ExtractTestArchives.LATE_X in stub.rangesCalls.last().ordinals)
        assertTrue("the hardlink target is still read for docs", ExtractTestArchives.HELLO in stub.rangesCalls.last().ordinals)
        assertEquals(ExtractTestArchives.hello.toList(), File(destinationDir, "docs/link-to-hello").readBytes().toList())

        File(destinationDir, "docs").deleteRecursively()
        File(destinationDir, "images").deleteRecursively()
        File(destinationDir, "bad?name.txt").delete()
        val replace = planAndStore(ref, ui = HeadlessPlannerUi(conflictPolicy = ConflictPolicy.REPLACE))
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(replace))
        assertEquals(ExtractTestArchives.hello.toList(), File(destinationDir, "hello.txt").readBytes().toList())
        assertEquals(ExtractTestArchives.late.toList(), File(destinationDir, "late/x.txt").readBytes().toList())
        assertFalse(File(destinationDir, "late/old.txt").exists())
        assertEquals(1, destinationDir.listFiles()!!.count { it.name == "hello.txt" })

        val keepBoth = planAndStore(ref, ui = HeadlessPlannerUi(conflictPolicy = ConflictPolicy.KEEP_BOTH))
        assertEquals("hello (2).txt", journal.extractPlan(keepBoth)!!.items.first { it.requestedName == "hello.txt" }.nameOverride)
        File(destinationDir, "hello (2).txt").writeText("taken since planning")
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(keepBoth))
        assertEquals(ExtractTestArchives.hello.toList(), File(destinationDir, "hello (3).txt").readBytes().toList())
        assertEquals("taken since planning", File(destinationDir, "hello (2).txt").readText())
        noStagingLeft()
    }

    @Test
    fun `an archive that changed since planning fails the run with ARCHIVE_CHANGED before any call`() {
        val ref = archive()
        val id = planAndStore(ref)
        // A different archive under the same name: a different size, so a different catalog key.
        ExtractTestArchives.write(rootDir, "a.zip", FakeArchive(listOf(FakeArchive.Entry("other.txt", "changed".toByteArray()))))
        assertEquals(ExtractRunOutcome.Finished(OperationState.FAILED), run(id))
        assertTrue(journal.find(id)!!.items.all { it.errorCode == ExtractErrorCodes.ARCHIVE_CHANGED })
        assertEquals(0, stub.rangesCalls.size)
        assertEquals(emptyList<String>(), destinationDir.list()!!.toList())
        assertEquals("nothing was bound, so nothing is unbound", 0, unbinds.get())
    }

    @Test
    fun `progress goes from reading the archive to the full byte count, with the item count`() {
        val id = planAndStore(archive())
        val reports = ArrayList<ExtractProgress>()
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id) { reports += it })
        assertTrue(reports.first().readingArchive)
        val total = ExtractTestArchives.expectedFiles.values.sumOf { it.size.toLong() }
        assertEquals(total, reports.last().totalBytes)
        assertEquals(total, reports.last().completedBytes)
        assertFalse(reports.last().readingArchive)
        assertEquals(5, reports.last().itemCount)
        assertTrue(reports.zipWithNext().all { (a, b) -> b.completedBytes >= a.completedBytes })
    }

    @Test
    fun `a Here with hundreds of roots runs in one pass with one row per item`() {
        val many = FakeArchive(List(500) { i -> FakeArchive.Entry("f$i.bin", ExtractTestArchives.prngBytes(i, 16 + i % 7)) })
        val id = planAndStore(archive("many.zip", many), ui = HeadlessPlannerUi(largeHereAsFolder = false))
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertEquals(1, stub.rangesCalls.size)
        assertEquals(500, destinationDir.list()!!.size)
        assertEquals(500, journal.find(id)!!.items.size)
        assertTrue(journal.find(id)!!.items.all { it.state == OperationState.SUCCEEDED })
        (0 until 500 step 97).forEach { i -> assertEquals(ExtractTestArchives.prngBytes(i, 16 + i % 7).toList(), File(destinationDir, "f$i.bin").readBytes().toList()) }
    }

    @Test
    fun `on a FAT destination every component is sanitised and nested collisions are uniquified`() {
        volume = ExtractTestArchives.exfat()
        val fake = FakeArchive(
            listOf(
                FakeArchive.Entry("bad?name.txt", "1".toByteArray()),
                FakeArchive.Entry("d/a?b.txt", "2".toByteArray()),
                FakeArchive.Entry("d/a*b.txt", "3".toByteArray()),
                FakeArchive.Entry("d/sub:dir/x.txt", "4".toByteArray()),
            ),
        )
        val id = planAndStore(archive("fat.zip", fake))
        assertEquals(ExtractRunOutcome.Finished(OperationState.SUCCEEDED), run(id))
        assertEquals(setOf("bad_name.txt", "d/a_b.txt", "d/a_b (2).txt", "d/sub_dir/x.txt"), files().keys)
        assertEquals("1", File(destinationDir, "bad_name.txt").readText())
        assertEquals(setOf("2", "3"), setOf(File(destinationDir, "d/a_b.txt").readText(), File(destinationDir, "d/a_b (2).txt").readText()))
    }
}
