package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import io.github.mbaliga.fylz.storage.testing.diffTrees
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
 * P0.1: folder copy and move, against the real, temp-directory-hosted provider (P0.0). Proves the
 * `DocumentFile.fromSingleUri(...).listFiles()` defect is gone (that call always threw, so every
 * folder copy/move failed) and that verification covers every nested file, not just the top item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileOperationServiceTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var sourceDir: File
    private lateinit var destinationDir: File
    private lateinit var faulty: FaultyDocumentsProvider
    private lateinit var service: FileOperationService

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        sourceDir = File(rootDir, "source").apply { mkdirs() }
        destinationDir = File(rootDir, "destination").apply { mkdirs() }
        faulty = FaultyDocumentsProvider.install()
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
        service = FileOperationService(RuntimeEnvironment.getApplication())
    }

    private fun documentUri(relativePath: String): Uri =
        FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    private fun treeUriFor(relativePath: String): Uri =
        FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    /** 20 files across 3 levels (root -> level2 -> level3), including 0-byte, 1-byte and 5 MiB. */
    private fun photoTreeChildren(): List<TreeNode> = listOf(
        TreeNode.FileNode("empty.bin", 0),
        TreeNode.FileNode("tiny.bin", 1),
        TreeNode.DirNode(
            "level2",
            (1..9).map { TreeNode.FileNode("f$it.bin", 4_096) } +
                TreeNode.DirNode(
                    "level3",
                    (1..8).map { TreeNode.FileNode("g$it.bin", 8_192) } +
                        TreeNode.FileNode("big.bin", 5 * 1024 * 1024),
                ),
        ),
    )

    @Test
    fun `copies a three level folder with 20 files byte identical`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))

        service.copy(listOf(documentUri("source/photos")), treeUriFor("destination"))

        assertTrue(File(destinationDir, "photos").isDirectory)
        assertEquals(
            emptyList<String>(),
            diffTrees(File(sourceDir, "photos"), File(destinationDir, "photos")),
        )
    }

    @Test
    fun `a nameOverrides entry renames the copy without touching the source`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.FileNode("bad:name.txt", 100)))
        val sourceUri = documentUri("source/bad:name.txt")

        service.copy(listOf(sourceUri), treeUriFor("destination"), nameOverrides = mapOf(sourceUri to "bad_name.txt"))

        assertTrue("the source keeps its own original name", File(sourceDir, "bad:name.txt").exists())
        assertTrue("the copy lands under the override name", File(destinationDir, "bad_name.txt").exists())
        assertFalse(File(destinationDir, "bad:name.txt").exists())
    }

    @Test
    fun `REPLACE_IF_NEWER replaces the destination only when the source is actually newer`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.FileNode("photo.jpg", 10)))
        val sourceFile = File(sourceDir, "photo.jpg")
        val sourceUri = documentUri("source/photo.jpg")
        File(destinationDir, "photo.jpg").apply {
            writeBytes(ByteArray(5))
            setLastModified(sourceFile.lastModified() - 60_000)
        }

        service.copy(
            listOf(sourceUri),
            treeUriFor("destination"),
            conflictResolutions = mapOf(sourceUri to ConflictPolicy.REPLACE_IF_NEWER),
        )

        assertArrayEquals(sourceFile.readBytes(), File(destinationDir, "photo.jpg").readBytes())
    }

    @Test
    fun `REPLACE_IF_NEWER leaves a newer destination untouched`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.FileNode("photo.jpg", 10)))
        val sourceFile = File(sourceDir, "photo.jpg")
        sourceFile.setLastModified(sourceFile.lastModified() - 60_000)
        val sourceUri = documentUri("source/photo.jpg")
        val existingBytes = ByteArray(5) { 9 }
        File(destinationDir, "photo.jpg").writeBytes(existingBytes)

        service.copy(
            listOf(sourceUri),
            treeUriFor("destination"),
            conflictResolutions = mapOf(sourceUri to ConflictPolicy.REPLACE_IF_NEWER),
        )

        assertArrayEquals(
            "the existing, newer file must survive untouched",
            existingBytes,
            File(destinationDir, "photo.jpg").readBytes(),
        )
    }

    @Test
    fun `conflictResolutions gives a per-item policy overriding the batch default`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.FileNode("a.txt", 5), TreeNode.FileNode("b.txt", 5)))
        File(destinationDir, "a.txt").writeBytes(ByteArray(1))
        File(destinationDir, "b.txt").writeBytes(ByteArray(1))
        val aUri = documentUri("source/a.txt")
        val bUri = documentUri("source/b.txt")

        service.copy(
            listOf(aUri, bUri),
            treeUriFor("destination"),
            conflictPolicy = ConflictPolicy.SKIP,
            conflictResolutions = mapOf(aUri to ConflictPolicy.REPLACE),
        )

        assertArrayEquals(
            "a.txt has an explicit per-item override to replace",
            File(sourceDir, "a.txt").readBytes(),
            File(destinationDir, "a.txt").readBytes(),
        )
        assertArrayEquals(
            "b.txt falls back to the batch policy (skip) and stays untouched",
            ByteArray(1),
            File(destinationDir, "b.txt").readBytes(),
        )
    }

    @Test
    fun `moves a three level folder leaving the source gone`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val snapshot = tempFolder.newFolder("snapshot")
        buildTree(snapshot, listOf(TreeNode.DirNode("photos", photoTreeChildren())))

        service.move(listOf(documentUri("source/photos")), treeUriFor("destination"))

        assertFalse(File(sourceDir, "photos").exists())
        assertEquals(
            emptyList<String>(),
            diffTrees(File(snapshot, "photos"), File(destinationDir, "photos")),
        )
    }

    @Test
    fun `move with delete refused stays pending then finish move cleans up without recopying`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val snapshot = tempFolder.newFolder("snapshot")
        buildTree(snapshot, listOf(TreeNode.DirNode("photos", photoTreeChildren())))

        faulty.refuseDelete = true
        service.move(listOf(documentUri("source/photos")), treeUriFor("destination"))

        val pending = service.operations().first()
        assertEquals(OperationState.NEEDS_ATTENTION, pending.state)
        assertEquals(OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING, pending.items.single().errorCode)
        // The copy already ran and verified clean; only the source removal was refused.
        assertTrue(File(sourceDir, "photos").exists())
        assertEquals(
            emptyList<String>(),
            diffTrees(File(snapshot, "photos"), File(destinationDir, "photos")),
        )
        val destinationBefore = destinationFingerprint()

        faulty.refuseDelete = false
        service.finishMoveCleanup(pending.id)

        assertFalse(File(sourceDir, "photos").exists())
        // Finish move must never copy again: the destination tree is byte-for-byte unchanged.
        assertEquals(destinationBefore, destinationFingerprint())
        val finished = service.operations().first { it.id == pending.id }
        assertEquals(OperationState.SUCCEEDED, finished.state)
    }

    @Test
    fun `one item's failure does not abort the rest of the batch, and the operation ends PARTIAL`() = runBlocking {
        buildTree(
            sourceDir,
            listOf(TreeNode.FileNode("a.txt", 5), TreeNode.FileNode("b.txt", 5), TreeNode.FileNode("c.txt", 5)),
        )
        // Each item's copy ends with exactly one rename (staged name -> final name, no conflict),
        // so the 2nd rename across this 3-item batch is item b's own -- failing only it.
        faulty.refuseRenameAtCall = 2

        val thrown = assertThrows(java.io.FileNotFoundException::class.java) {
            runBlocking {
                service.copy(
                    listOf(documentUri("source/a.txt"), documentUri("source/b.txt"), documentUri("source/c.txt")),
                    treeUriFor("destination"),
                )
            }
        }
        assertTrue(thrown.message.orEmpty().contains("simulated"))

        assertTrue("item a must still have been copied", File(destinationDir, "a.txt").exists())
        assertTrue("item c must still have been copied, despite running after the failed item b", File(destinationDir, "c.txt").exists())
        assertFalse("item b's rename was refused, so it must never reach its final name", File(destinationDir, "b.txt").exists())

        val operation = service.operations().single()
        assertEquals(OperationState.PARTIAL, operation.state)
        val itemsByName = operation.items.associateBy { it.displayName }
        assertEquals(OperationState.SUCCEEDED, itemsByName.getValue("a.txt").state)
        assertEquals(OperationState.FAILED, itemsByName.getValue("b.txt").state)
        assertEquals("FileNotFoundException", itemsByName.getValue("b.txt").errorCode)
        assertEquals(OperationState.SUCCEEDED, itemsByName.getValue("c.txt").state)
    }

    @Test
    fun `every item failing ends the operation FAILED, not PARTIAL`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.FileNode("a.txt", 5), TreeNode.FileNode("b.txt", 5)))
        faulty.refuseRename = true

        // Two different items fail (not one), so this is the generic summary-message path, not
        // the single-failure type-preserving rethrow -- see the "one item's failure" test above
        // for that case.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                service.copy(
                    listOf(documentUri("source/a.txt"), documentUri("source/b.txt")),
                    treeUriFor("destination"),
                )
            }
        }
        assertTrue(thrown.message.orEmpty().contains("All 2 items failed"))

        val operation = service.operations().single()
        assertEquals(OperationState.FAILED, operation.state)
        assertTrue(operation.items.all { it.state == OperationState.FAILED })
    }

    @Test
    fun `a PARTIAL operation's failed item can be retried through OperationRetryPolicy and lands correctly`() = runBlocking {
        buildTree(sourceDir, listOf(TreeNode.FileNode("a.txt", 5), TreeNode.FileNode("b.txt", 5)))
        faulty.refuseRenameAtCall = 2

        assertThrows(java.io.FileNotFoundException::class.java) {
            runBlocking {
                service.copy(listOf(documentUri("source/a.txt"), documentUri("source/b.txt")), treeUriFor("destination"))
            }
        }
        val partial = service.operations().single()
        assertEquals(OperationState.PARTIAL, partial.state)

        val plan = OperationRetryPolicy.plan(partial)
        assertTrue("a PARTIAL operation must be retryable", plan is OperationRetryPlan.Transfer)
        val transferPlan = plan as OperationRetryPlan.Transfer
        assertEquals(
            "only the failed item (b.txt) should be replayed, not the already-succeeded a.txt",
            listOf(documentUri("source/b.txt")),
            transferPlan.sourceUris,
        )

        faulty.refuseRenameAtCall = null
        service.copy(transferPlan.sourceUris, transferPlan.destinationTreeUri, transferPlan.conflictPolicy)

        assertTrue(File(destinationDir, "b.txt").exists())
    }

    @Test
    fun `cancelling mid-copy leaves nothing under the final name`() = runBlocking {
        // P1.2 throttled the journal write that used to happen on every buffer read (the exact
        // defect it fixes), so a copy this test relies on being interruptible now runs much
        // faster with no synchronous per-buffer DB write to give the cancelling thread scheduling
        // room; sized up from 5 MiB so there's still real work in flight when cancelAndJoin runs.
        buildTree(sourceDir, listOf(TreeNode.FileNode("big.bin", 64 * 1024 * 1024)))
        val progressed = CompletableDeferred<Unit>()

        val job = launch {
            service.copy(listOf(documentUri("source/big.bin")), treeUriFor("destination")) { progress ->
                if (!progressed.isCompleted && progress.completedBytes > 0) progressed.complete(Unit)
            }
        }
        progressed.await()
        job.cancelAndJoin()

        // Not just absent under the final name (P0.6's own bar): copyDocument's existing
        // failure-cleanup already deletes the staging document too on an in-process cancel, so
        // nothing at all should be left behind here -- unlike a real process death, which is what
        // OperationRunner.recover (tested separately) exists to clean up afterward.
        assertFalse(File(destinationDir, "big.bin").exists())
        assertTrue(destinationDir.listFiles()?.isEmpty() ?: true)
    }

    /** Relative path + size for every file under the destination, as a stand-in for "unchanged
     * since the last snapshot" -- cheaper than a full content diff and enough to prove
     * [FileOperationService.finishMoveCleanup] didn't touch anything it had already written. */
    private fun destinationFingerprint(): List<String> = destinationDir.walkTopDown()
        .map { "${it.relativeTo(destinationDir).path}:${it.length()}" }
        .toList()
}
