package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.decoder.ArchiveLimits
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * P0.6: `OperationRunner.recover()`, run once at app start against whatever
 * `OperationJournal`'s own construction-time recovery already relabelled
 * RUNNING/PREFLIGHT/PAUSED -> NEEDS_ATTENTION/"PROCESS_INTERRUPTED" -- simulating a process that
 * died mid-copy, which a single test process can't literally do, by writing that same journal
 * shape by hand and a real orphaned `.fylz-part-*` document alongside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OperationRunnerRecoverTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var sourceDir: File
    private lateinit var destinationDir: File
    private lateinit var journal: OperationJournal
    private lateinit var service: FileOperationService

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        sourceDir = File(rootDir, "source").apply { mkdirs() }
        destinationDir = File(rootDir, "destination").apply { mkdirs() }
        val faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(
            VolumeDescriptor(
                rootId = FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
                title = "Internal storage",
                directory = rootDir,
                primary = true,
                removable = false,
                readOnly = false,
            ),
        )
        journal = OperationJournal(RuntimeEnvironment.getApplication())
        service = FileOperationService(RuntimeEnvironment.getApplication(), journal = journal)
    }

    private fun documentUri(relativePath: String): Uri =
        FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    private fun treeUriFor(relativePath: String): Uri =
        FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    /** Writes a journal record shaped exactly like the one `OperationJournal`'s own
     * construction-time recovery would have produced for an item a dead process left mid-copy,
     * plus (unless [withStagingFile] is false) a real orphaned staging document on disk. */
    private fun writeInterruptedCopyRecord(
        operationId: String,
        stagingFileName: String,
        withStagingFile: Boolean = true,
    ): Uri {
        val stagingUri = documentUri("destination/$stagingFileName")
        if (withStagingFile) {
            File(destinationDir, stagingFileName).writeText("partial write, process died here")
        }
        journal.put(
            FileOperation(
                id = operationId,
                type = FileOperationType.COPY,
                items = listOf(
                    OperationItem(
                        id = "item-1",
                        source = documentUri("source/photo.jpg"),
                        destination = treeUriFor("destination"),
                        displayName = "photo.jpg",
                        state = OperationState.NEEDS_ATTENTION,
                        errorCode = "PROCESS_INTERRUPTED",
                        stagingUri = stagingUri,
                    ),
                ),
                state = OperationState.NEEDS_ATTENTION,
            ),
        )
        return stagingUri
    }

    @Test
    fun `recover deletes exactly the recorded staging document and marks the item interrupted`() = runBlocking {
        val untouched = File(destinationDir, "untouched.txt").apply { writeText("keep me") }
        writeInterruptedCopyRecord("op-1", stagingFileName = ".fylz-part-op-1-0-photo.jpg")

        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver)

        assertFalse(File(destinationDir, ".fylz-part-op-1-0-photo.jpg").exists())
        assertTrue(untouched.exists())
        val recovered = journal.find("op-1")!!
        assertEquals(OperationState.INTERRUPTED, recovered.state)
        val item = recovered.items.single()
        assertEquals(OperationState.INTERRUPTED, item.state)
        assertNull(item.stagingUri)
    }

    @Test
    fun `recover is a no-op when there is nothing to recover`() = runBlocking {
        val other = File(destinationDir, "photos").apply { mkdirs() }
        buildTree(other, listOf(TreeNode.FileNode("a.bin", 10)))

        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver)

        assertTrue(File(other, "a.bin").exists())
        assertTrue(journal.list().isEmpty())
    }

    @Test
    fun `retry after recovery succeeds`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.FileNode("photo.jpg", 4_096)))
        writeInterruptedCopyRecord("op-2", stagingFileName = ".fylz-part-op-2-0-photo.jpg")

        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver)

        val recovered = journal.find("op-2")!!
        val plan = OperationRetryPolicy.plan(recovered)
        check(plan is OperationRetryPlan.Transfer) { "expected a retryable transfer plan, got $plan" }

        service.copy(plan.sourceUris, plan.destinationTreeUri, plan.conflictPolicy)

        val restored = File(destinationDir, "photo.jpg")
        assertTrue(restored.exists())
        assertEquals(4_096L, restored.length())
    }

    // ------------------------------------------------------------------ M3.4: EXTRACT reconciliation

    private val archiveRoot = Uri.parse("content://io.github.mbaliga.fylz.archives/document/cm9vdA")

    private fun writeExtractRecord(operationId: String, state: OperationState, itemState: OperationState = state, errorCode: String? = null, stagingFileName: String? = null, updatedAtMillis: Long = 1_000L, withPlan: Boolean = true) {
        val stagingUri = stagingFileName?.let { name ->
            File(destinationDir, name).writeText("partial")
            documentUri("destination/$name")
        }
        val operation = FileOperation(
            id = operationId,
            type = FileOperationType.EXTRACT,
            items = listOf(OperationItem(id = "$operationId-0", source = archiveRoot, destination = treeUriFor("destination"), displayName = "docs", state = itemState, errorCode = errorCode, stagingUri = stagingUri)),
            conflictPolicy = ConflictPolicy.SKIP,
            state = state,
            createdAtMillis = updatedAtMillis,
            updatedAtMillis = updatedAtMillis,
            destination = treeUriFor("destination"),
        )
        if (withPlan) {
            journal.putWithExtractPlan(operation, ExtractPlan(operationId, archiveRoot, "key", ExtractLayout.HERE, null, OrdinalBitmap.of(0), ArchiveLimits.forExtraction(null, false), false, false, items = listOf(ExtractPlanItem(0, "docs", "docs", ConflictPolicy.SKIP))))
        } else {
            journal.put(operation)
        }
    }

    private fun alive(vararg ids: String) = WorkLookup { tag -> if (tag.removePrefix("op:") in ids) setOf(UUID.randomUUID()) else emptySet() }

    @Test
    fun `an extraction whose tagged work is still alive is left alone, whatever its state`() = runBlocking {
        writeExtractRecord("ex-run", OperationState.RUNNING)
        writeExtractRecord("ex-paused", OperationState.PAUSED_BY_SYSTEM)
        writeExtractRecord("ex-int", OperationState.NEEDS_ATTENTION, errorCode = "PROCESS_INTERRUPTED", stagingFileName = ".fylz-part-ex-int-0-docs")
        writeExtractRecord("ex-queued", OperationState.QUEUED)

        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver, alive("ex-run", "ex-paused", "ex-int", "ex-queued"), nowMillis = { 10_000_000L })

        assertEquals(OperationState.RUNNING, journal.find("ex-run")!!.state)
        assertEquals(OperationState.PAUSED_BY_SYSTEM, journal.find("ex-paused")!!.state)
        assertEquals(OperationState.NEEDS_ATTENTION, journal.find("ex-int")!!.state)
        assertTrue("its staging is kept for the live worker to clean", File(destinationDir, ".fylz-part-ex-int-0-docs").exists())
        assertEquals(OperationState.QUEUED, journal.find("ex-queued")!!.state)
    }

    @Test
    fun `an extraction whose work is gone becomes INTERRUPTED with its recorded staging deleted`() = runBlocking {
        writeExtractRecord("ex-gone", OperationState.NEEDS_ATTENTION, errorCode = "PROCESS_INTERRUPTED", stagingFileName = ".fylz-part-ex-gone-0-docs")
        writeExtractRecord("ex-paused-gone", OperationState.PAUSED_BY_SYSTEM, stagingFileName = ".fylz-part-ex-paused-gone-0-docs")
        val untouched = File(destinationDir, "keep.txt").apply { writeText("keep") }

        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver, WorkLookup.NONE)

        listOf("ex-gone", "ex-paused-gone").forEach { id ->
            val recovered = journal.find(id)!!
            assertEquals(id, OperationState.INTERRUPTED, recovered.state)
            assertEquals(OperationState.INTERRUPTED, recovered.items.single().state)
            assertEquals("PROCESS_INTERRUPTED", recovered.items.single().errorCode)
            assertNull(recovered.items.single().stagingUri)
            assertFalse(File(destinationDir, ".fylz-part-$id-0-docs").exists())
            assertTrue("retryable as the same operation", OperationRetryPolicy.plan(recovered) is OperationRetryPlan.ReclaimExtract)
        }
        assertTrue(untouched.exists())
    }

    @Test
    fun `a queued extraction with no work is NEVER_RAN once it is old enough, and left alone while young`() = runBlocking {
        writeExtractRecord("ex-old", OperationState.QUEUED, updatedAtMillis = 1_000L)
        writeExtractRecord("ex-young", OperationState.QUEUED, updatedAtMillis = 5_000_000L)

        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver, WorkLookup.NONE, nowMillis = { 5_000_000L + 30_000L })

        val old = journal.find("ex-old")!!
        assertEquals(OperationState.FAILED, old.state)
        assertEquals(ExtractErrorCodes.NEVER_RAN, old.items.single().errorCode)
        assertEquals(OperationState.QUEUED, journal.find("ex-young")!!.state)
    }

    @Test
    fun `a legacy extraction row without a plan is not reconciled`() = runBlocking {
        writeExtractRecord("legacy", OperationState.RUNNING, withPlan = false)
        writeExtractRecord("legacy-queued", OperationState.QUEUED, withPlan = false, updatedAtMillis = 1L)
        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver, WorkLookup.NONE, nowMillis = { 10_000_000L })
        assertEquals(OperationState.RUNNING, journal.find("legacy")!!.state)
        assertEquals(OperationState.QUEUED, journal.find("legacy-queued")!!.state)
    }

    @Test
    fun `the conditional update does not clobber a claim that raced recovery`() = runBlocking {
        writeExtractRecord("ex-race", OperationState.QUEUED, updatedAtMillis = 1_000L)
        // The lookup runs between the read and the write: a worker claims the row right then.
        val racing = WorkLookup { _ ->
            check(journal.claimExtract("ex-race", ArchiveExtractor.CLAIMABLE))
            emptySet()
        }
        OperationRunner.recover(journal, RuntimeEnvironment.getApplication().contentResolver, racing, nowMillis = { 10_000_000L })
        val row = journal.find("ex-race")!!
        assertEquals(OperationState.RUNNING, row.state)
        assertEquals(OperationState.RUNNING, row.items.single().state)
        assertNull(row.items.single().errorCode)
    }
}
