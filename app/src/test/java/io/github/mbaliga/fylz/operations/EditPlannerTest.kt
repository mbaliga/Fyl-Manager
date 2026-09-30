package io.github.mbaliga.fylz.operations

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveFormatFamily
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.archive.FakeArchiveDecoder
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult
import io.github.mbaliga.fylz.storage.ArchiveProviderTestSupport
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * M3.6 (edit ZIP archives in place): [EditPlanner]'s own refusals, and the whole edit end to end
 * through [ArchiveCreator] over [FakeArchiveDecoder] -- add, delete and rename in one edit; the
 * previous archive recycled, not deleted; a cancel mid-edit leaving the original byte-for-byte
 * untouched. The source archive is hosted through the real [io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider]
 * ([ArchiveProviderTestSupport]), exactly as a kept entry's `sourceUri` is actually read by
 * [ArchiveCreator.feedFile] in production -- proving the "no native copy-through needed" choice
 * `docs/agent/REVIEW_QUEUE.md`'s M3.6 entry records, not merely asserting it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditPlannerTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var rootDir: File
    private lateinit var destinationDir: File
    private lateinit var destination: Uri
    private lateinit var stub: FakeArchiveDecoder
    private lateinit var hosted: ArchiveProviderTestSupport.Hosted
    private lateinit var journal: OperationJournal
    private lateinit var planner: EditPlanner
    private var now = 0L

    /** As [io.github.mbaliga.fylz.operations.ArchiveCreatorTest]'s own `ticking`: every check the
     * creator makes advances real time, so the cancel poll never skips a check because a fast test
     * outran the wall clock. */
    private val ticking: () -> Long = { now += 1_000L; now }

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        destinationDir = File(rootDir, "dest").apply { mkdirs() }
        destination = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "dest")
        val faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(VolumeDescriptor(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Internal storage", rootDir, primary = true, removable = false, readOnly = false))
        stub = FakeArchiveDecoder()
        hosted = ArchiveProviderTestSupport.host(stub)
        journal = OperationJournal(context)
        planner = EditPlanner(context.contentResolver, hosted.catalog)
    }

    private fun dir(path: String) = FakeArchive.Entry(path, kind = ArchiveEntryInfo.KIND_DIRECTORY)
    private fun file(path: String, body: ByteArray = "x".toByteArray()) = FakeArchive.Entry(path, body)

    /**
     * A top-level archive's own document, rooted at the *volume's* tree -- like [FileEntry.uri]
     * really is for a file a folder listing found, [FylzFilesDocumentsProvider.children] building
     * every child from the parent's own (correctly tree-rooted) Uri. [FylzFilesDocumentsProvider.documentUri]
     * is the wrong helper here: it roots the tree at the document itself, which
     * `DocumentsProvider.enforceTree` then refuses once [RecycleBinService.replaceWithRecycleFallback]
     * renames that very document to a different name and tries to read it back.
     */
    private fun writeArchive(name: String, entries: List<FakeArchive.Entry>, formatCode: Int = ArchiveFormatFamily.ZIP): ArchiveRef {
        FakeArchive(entries, formatCode = formatCode).write(File(rootDir, name))
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID),
            "${FylzFilesDocumentsProvider.PRIMARY_ROOT_ID}:$name",
        )
        return ArchiveRef(fileUri, emptyList())
    }

    private fun entryId(archive: ArchiveRef, path: String): ArchiveDocumentId = runBlocking {
        val handle = hosted.catalog.open(archive)
        val entry = handle.tree.entry(path)!!
        ArchiveDocumentId(archive.source, archive.chain, entry.ordinal, entry.path)
    }

    private fun run(id: String, onProgress: (CreateProgress) -> Unit = {}): CreateRunOutcome = runBlocking {
        ArchiveCreator(
            context = context,
            journal = journal,
            writerClient = { FakeArchiveDecoder.client(stub) },
            workLookup = WorkLookup.NONE,
            streamInactivityMillis = 5_000L,
            cancelWaitMillis = 500L,
            clock = ticking,
        ).run(id, onProgress = onProgress)
    }

    @Test
    fun `a non-ZIP archive is refused outright`() {
        val archive = writeArchive("photos.7z", listOf(file("a.txt")), formatCode = ArchiveFormatFamily.SEVEN_ZIP)
        val result = runBlocking { planner.plan(ArchiveEditRequest(archive = archive, deletions = listOf(entryId(archive, "a.txt"))), destination) }
        assertEquals(EditPlanResult.Refused(EditPlanner.NON_ZIP_REFUSED), result)
    }

    @Test
    fun `a nested archive is refused before it is even opened`() {
        val archive = ArchiveRef(Uri.parse("content://x/outer.zip"), listOf("inner.zip"))
        val id = ArchiveDocumentId(archive.source, archive.chain, 0, "a.txt")
        val result = runBlocking { planner.plan(ArchiveEditRequest(archive = archive, deletions = listOf(id)), destination) }
        assertEquals(EditPlanResult.Refused(EditPlanner.NESTED_REFUSED), result)
    }

    @Test
    fun `a stale ordinal is refused rather than silently touching the wrong entry`() {
        val archive = writeArchive("photos.zip", listOf(file("a.txt")))
        val stale = ArchiveDocumentId(archive.source, archive.chain, 99, "a.txt")
        val result = runBlocking { planner.plan(ArchiveEditRequest(archive = archive, deletions = listOf(stale)), destination) }
        assertEquals(EditPlanResult.Refused(EditPlanner.STALE_SELECTION), result)
    }

    @Test
    fun `add delete and rename in one edit round-trips and the old archive is recycled, not deleted`() {
        val keep = "keep bytes unchanged".toByteArray()
        val toDelete = "will be removed".toByteArray()
        val toRename = "will be renamed".toByteArray()
        val archive = writeArchive(
            "photos.zip",
            listOf(dir("docs/"), file("docs/keep.txt", keep), file("docs/delete.txt", toDelete), file("rename-me.txt", toRename)),
        )
        val newFile = File(rootDir, "new.txt").apply { writeText("brand new") }
        val newFileUri = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "new.txt")

        val request = ArchiveEditRequest(
            archive = archive,
            deletions = listOf(entryId(archive, "docs/delete.txt")),
            rename = entryId(archive, "rename-me.txt") to "renamed.txt",
            addSources = listOf(newFileUri),
        )
        val planned = runBlocking { planner.plan(request, destination) }
        check(planned is EditPlanResult.Planned) { "expected Planned, got $planned" }
        assertEquals(4, planned.manifest.size) // docs/, docs/keep.txt, renamed.txt, new.txt
        journal.putWithCreatePlan(planned.operation, planned.plan, planned.manifest)

        assertEquals(CreateRunOutcome.Finished(OperationState.SUCCEEDED), run(planned.operation.id))

        val output = File(destinationDir, "photos.zip")
        assertTrue("the replacement landed under the original name", output.isFile)
        val outputRef = ArchiveRef(FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "dest/photos.zip"), emptyList())
        val handle = runBlocking { hosted.catalog.open(outputRef) }
        val paths = handle.tree.children("docs").map { it.path }.toSet() + handle.tree.children("").map { it.path }
        assertTrue("docs/keep.txt survives unchanged", "docs/keep.txt" in paths)
        assertTrue("renamed.txt exists under its new name", "renamed.txt" in paths)
        assertTrue("new.txt was added", "new.txt" in paths)
        assertFalse("docs/delete.txt is gone", "docs/delete.txt" in paths)
        assertFalse("rename-me.txt no longer exists under its old name", "rename-me.txt" in paths)

        val keptBytes = runBlocking { hosted.entryCache.materialise(handle, handle.tree.entry("docs/keep.txt")!!).readBytes() }
        assertEquals("an unchanged entry is byte-for-byte identical", String(keep), String(keptBytes))
        val renamedBytes = runBlocking { hosted.entryCache.materialise(handle, handle.tree.entry("renamed.txt")!!).readBytes() }
        assertEquals(String(toRename), String(renamedBytes))
        val addedBytes = runBlocking { hosted.entryCache.materialise(handle, handle.tree.entry("new.txt")!!).readBytes() }
        assertEquals("brand new", String(addedBytes))

        // The old archive is not simply gone: it was recycled, into the destination's own trash.
        val trash = File(destinationDir, RecycleBinService.RECYCLE_DIRECTORY)
        assertTrue("a .fylz-trash exists at the destination", trash.isDirectory)
        val recycledBytes = trash.walkTopDown().filter { it.isFile }.map { it.readBytes() }.toList()
        assertTrue(
            "the previous archive's own bytes are recoverable from the recycle bin",
            recycledBytes.any { bytes -> String(bytes).contains("will be removed") && String(bytes).contains("will be renamed") },
        )
    }

    @Test
    fun `a cancel requested mid-edit leaves the original archive completely untouched`() {
        val original = "original bytes, never to be touched".toByteArray()
        val archive = writeArchive("photos.zip", listOf(file("a.txt", original), file("b.txt", "second".toByteArray())))
        val request = ArchiveEditRequest(archive = archive, rename = entryId(archive, "a.txt") to "renamed.txt")
        val planned = runBlocking { planner.plan(request, destination) }
        check(planned is EditPlanResult.Planned)
        journal.putWithCreatePlan(planned.operation, planned.plan, planned.manifest)

        val originalFile = File(rootDir, "photos.zip")
        val originalBytesBefore = originalFile.readBytes()

        var armed = false
        val outcome = run(planned.operation.id) { progress ->
            if (!armed && progress.entriesDone >= 1) {
                armed = true
                journal.setCreateCancelRequested(planned.operation.id)
            }
        }
        assertEquals(CreateRunOutcome.Finished(OperationState.CANCELLED), outcome)
        assertTrue("the original document is exactly where it was", originalFile.isFile)
        assertEquals("not a single byte of the original archive changed", originalBytesBefore.toList(), originalFile.readBytes().toList())
        assertFalse("no .fylz-trash was created -- nothing was ever recycled", File(destinationDir, RecycleBinService.RECYCLE_DIRECTORY).isDirectory)
        assertEquals(emptyList<String>(), destinationDir.list()!!.toList())
    }

    @Test
    fun `a decoder failure mid-write also leaves the original untouched`() {
        val archive = writeArchive("photos.zip", listOf(file("a.txt")))
        val request = ArchiveEditRequest(archive = archive, rename = entryId(archive, "a.txt") to "renamed.txt")
        val planned = runBlocking { planner.plan(request, destination) }
        check(planned is EditPlanResult.Planned)
        journal.putWithCreatePlan(planned.operation, planned.plan, planned.manifest)
        stub.writeFailure = ArchiveWriteResult.failed(ArchiveWriteResult.OUTCOME_CORRUPT, "disk full")
        val originalBytesBefore = File(rootDir, "photos.zip").readBytes()

        assertEquals(CreateRunOutcome.Finished(OperationState.FAILED), run(planned.operation.id))
        assertEquals(originalBytesBefore.toList(), File(rootDir, "photos.zip").readBytes().toList())
        assertEquals("no staged replacement was left behind", emptyList<String>(), destinationDir.list()!!.toList())
    }
}
