package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * P1.5: [gatherPreflightItems] against the real P0.0-hosted [FylzFilesDocumentsProvider] -- in
 * particular, that a directory's [PreflightItem.totalBytes] is a real recursive sum over its own
 * tree, not just what one directory entry reports (which a provider never reports for a
 * directory at all -- [DocNode.size] is null for one, by contract).
 */
class PreflightGatheringTest : FylzDocumentsProviderTestBase() {

    private fun node(relativePath: String) = FylzFilesDocumentsProvider.documentUri(
        FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
        relativePath,
    )

    @Test
    fun `a plain file's totalBytes is its own size`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("photo.jpg", 12_345)))

        val items = gatherPreflightItems(RuntimeEnvironment.getApplication().contentResolver, listOf(node("photo.jpg")))

        val item = items.single()
        assertEquals("photo.jpg", item.name)
        assertTrue(!item.isDirectory)
        assertEquals(12_345L, item.totalBytes)
    }

    @Test
    fun `a directory's totalBytes is every nested file's size, summed across every depth`() = runBlocking {
        buildTree(
            rootDir,
            listOf(
                TreeNode.DirNode(
                    "album",
                    listOf(
                        TreeNode.FileNode("a.jpg", 1_000),
                        TreeNode.FileNode("b.jpg", 2_000),
                        TreeNode.DirNode("nested", listOf(TreeNode.FileNode("c.jpg", 3_000))),
                    ),
                ),
            ),
        )

        val items = gatherPreflightItems(RuntimeEnvironment.getApplication().contentResolver, listOf(node("album")))

        val item = items.single()
        assertEquals("album", item.name)
        assertTrue(item.isDirectory)
        assertEquals(6_000L, item.totalBytes)
    }

    @Test
    fun `an empty directory has zero totalBytes, not null or an error`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.DirNode("empty", emptyList())))

        val items = gatherPreflightItems(RuntimeEnvironment.getApplication().contentResolver, listOf(node("empty")))

        assertEquals(0L, items.single().totalBytes)
    }

    @Test
    fun `several top-level items are each gathered, in order`() = runBlocking {
        buildTree(
            rootDir,
            listOf(
                TreeNode.FileNode("a.jpg", 100),
                TreeNode.DirNode("folder", listOf(TreeNode.FileNode("b.jpg", 200))),
            ),
        )

        val items = gatherPreflightItems(
            RuntimeEnvironment.getApplication().contentResolver,
            listOf(node("a.jpg"), node("folder")),
        )

        assertEquals(listOf("a.jpg", "folder"), items.map { it.name })
        assertEquals(100L, items[0].totalBytes)
        assertEquals(200L, items[1].totalBytes)
    }

    @Test
    fun `a source that no longer resolves is dropped rather than failing the whole gather`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("real.jpg", 500)))
        val ghost = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "ghost.jpg")

        val items = gatherPreflightItems(
            RuntimeEnvironment.getApplication().contentResolver,
            listOf(node("real.jpg"), ghost),
        )

        assertEquals(listOf("real.jpg"), items.map { it.name })
    }
}
