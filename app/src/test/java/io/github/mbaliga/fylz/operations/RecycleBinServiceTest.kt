package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import io.github.mbaliga.fylz.storage.testing.diffTrees
import java.io.File
import kotlinx.coroutines.runBlocking
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
 * P0.2: recycle and restore folders safely, against the real, temp-directory-hosted provider
 * (P0.0), reusing DocNode (P0.1). Proves the `DocumentFile.fromSingleUri(...).parentFile == null`
 * defect that leaked every `<uuid>` transaction folder is gone, that Replace never deletes data
 * before its replacement is confirmed, and that a refused rename rolls back cleanly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecycleBinServiceTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var docsDir: File
    private lateinit var faulty: FaultyDocumentsProvider
    private lateinit var service: RecycleBinService

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        docsDir = File(rootDir, "docs").apply { mkdirs() }
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
        service = RecycleBinService(RuntimeEnvironment.getApplication())
    }

    private fun documentUri(relativePath: String): Uri =
        FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    private fun treeUriFor(relativePath: String): Uri =
        FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    private fun photoTreeChildren(): List<TreeNode> = listOf(
        TreeNode.FileNode("a.bin", 100),
        TreeNode.DirNode("nested", listOf(TreeNode.FileNode("b.bin", 4_096))),
    )

    private fun recycle(name: String = "photos"): RecycleRecord = runBlocking {
        val recycleRoot = service.recycleRootFor(treeUriFor("docs"))
        // originalParentUri must be a document uri (as DocumentRepository.rootLocation/
        // listChildren always hand the real caller), not a tree uri.
        service.recycle(documentUri("docs/$name"), documentUri("docs"), recycleRoot.uri)
    }

    @Test
    fun `recycles a folder tree and restores it byte identical`() = runBlocking {
        buildTree(docsDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val snapshot = tempFolder.newFolder("snapshot")
        buildTree(snapshot, listOf(TreeNode.DirNode("photos", photoTreeChildren())))

        val record = recycle()
        assertFalse(File(docsDir, "photos").exists())
        assertTrue(File(docsDir, ".fylz-trash").isDirectory)

        service.restore(record.itemId)

        assertTrue(File(docsDir, "photos").isDirectory)
        assertEquals(emptyList<String>(), diffTrees(File(snapshot, "photos"), File(docsDir, "photos")))
        // The <uuid> container must not leak once its item has been restored (defect 1).
        assertEquals(0, File(docsDir, ".fylz-trash").listFiles()?.size ?: 0)
        assertTrue(service.records().none { it.itemId == record.itemId })
    }

    @Test
    fun `restore into a name conflict under skip does nothing`() = runBlocking {
        buildTree(docsDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val record = recycle()
        buildTree(docsDir, listOf(TreeNode.FileNode("photos", 7))) // a conflicting file, not a folder

        service.restore(record.itemId, conflictPolicy = ConflictPolicy.SKIP)

        // The conflicting item is untouched, and the recycled copy is still there and restorable.
        assertEquals(7L, File(docsDir, "photos").length())
        assertTrue(service.records().any { it.itemId == record.itemId })
    }

    @Test
    fun `restore into a name conflict under keep both creates a second copy`() = runBlocking {
        buildTree(docsDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val record = recycle()
        buildTree(docsDir, listOf(TreeNode.FileNode("photos", 7)))

        service.restore(record.itemId, conflictPolicy = ConflictPolicy.KEEP_BOTH)

        assertEquals(7L, File(docsDir, "photos").length())
        assertTrue(File(docsDir, "photos (2)").isDirectory)
        assertTrue(service.records().none { it.itemId == record.itemId })
    }

    @Test
    fun `restore into a name conflict under replace recycles the replaced item`() = runBlocking {
        buildTree(docsDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val record = recycle()
        buildTree(docsDir, listOf(TreeNode.FileNode("photos", 7)))

        service.restore(record.itemId, conflictPolicy = ConflictPolicy.REPLACE)

        assertTrue(File(docsDir, "photos").isDirectory)
        assertTrue(service.records().none { it.itemId == record.itemId })
        // The replaced 7-byte file was recycled, not deleted outright: a second record exists.
        val remaining = service.records()
        assertEquals(1, remaining.size)
        assertEquals(7L, remaining.single().sizeBytes)
    }

    @Test
    fun `restore with the first rename refused keeps the record and loses nothing`() = runBlocking {
        buildTree(docsDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val record = recycle()
        buildTree(docsDir, listOf(TreeNode.FileNode("photos", 7)))

        faulty.refuseRenameAtCall = 1
        assertThrows(Exception::class.java) {
            runBlocking { service.restore(record.itemId, conflictPolicy = ConflictPolicy.REPLACE) }
        }

        // Nothing was touched: the conflicting file is exactly as it was, the record remains.
        assertEquals(7L, File(docsDir, "photos").length())
        assertTrue(service.records().any { it.itemId == record.itemId })
    }

    @Test
    fun `replace with the second rename refused puts the original back in place`() = runBlocking {
        buildTree(docsDir, listOf(TreeNode.DirNode("photos", photoTreeChildren())))
        val record = recycle()
        val snapshot = tempFolder.newFolder("snapshot")
        buildTree(snapshot, listOf(TreeNode.FileNode("photos", 7)))
        buildTree(docsDir, listOf(TreeNode.FileNode("photos", 7)))

        // The first rename (existing -> aside) succeeds; the second (staged -> requested) fails.
        faulty.refuseRenameAtCall = 2
        assertThrows(Exception::class.java) {
            runBlocking { service.restore(record.itemId, conflictPolicy = ConflictPolicy.REPLACE) }
        }

        assertEquals(
            emptyList<String>(),
            diffTrees(File(snapshot, "photos"), File(docsDir, "photos")),
        )
        assertTrue(service.records().any { it.itemId == record.itemId })
    }
}
