package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import java.io.File
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
}
