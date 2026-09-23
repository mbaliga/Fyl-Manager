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
 * P1.6: [findConflicts] against the real P0.0-hosted [FylzFilesDocumentsProvider] -- what a
 * `ConflictSheet` needs to know before a copy or move starts, and specifically that it checks the
 * EFFECTIVE name (after a Preflight `nameOverrides` rename, P1.5), not a source's own original
 * name, since a caller resolving Preflight before Conflicts would otherwise find (or miss) the
 * wrong conflicts for a renamed item.
 */
class ConflictDetectionTest : FylzDocumentsProviderTestBase() {

    private fun node(relativePath: String) = FylzFilesDocumentsProvider.documentUri(
        FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
        relativePath,
    )

    @Test
    fun `no conflicts when nothing at the destination shares a name`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 10), TreeNode.DirNode("destination", emptyList())))

        val conflicts = findConflicts(
            RuntimeEnvironment.getApplication().contentResolver,
            listOf(node("source.bin")),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "destination"),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `a same-named destination item is reported as a conflict`() = runBlocking {
        buildTree(
            rootDir,
            listOf(
                TreeNode.FileNode("source.bin", 10),
                TreeNode.DirNode("destination", listOf(TreeNode.FileNode("source.bin", 20))),
            ),
        )

        val conflicts = findConflicts(
            RuntimeEnvironment.getApplication().contentResolver,
            listOf(node("source.bin")),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "destination"),
        )

        val conflict = conflicts.single()
        assertEquals("source.bin", conflict.source.name)
        assertEquals("source.bin", conflict.existing.name)
        assertEquals(20L, conflict.existing.size)
    }

    @Test
    fun `nameOverrides is what gets checked, not the source's own original name`() = runBlocking {
        buildTree(
            rootDir,
            listOf(
                TreeNode.FileNode("bad:name.txt", 10),
                TreeNode.DirNode("destination", listOf(TreeNode.FileNode("renamed.txt", 5))),
            ),
        )
        val sourceUri = node("bad:name.txt")

        val conflicts = findConflicts(
            RuntimeEnvironment.getApplication().contentResolver,
            listOf(sourceUri),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "destination"),
            nameOverrides = mapOf(sourceUri to "renamed.txt"),
        )

        assertEquals(1, conflicts.size)
        assertEquals("renamed.txt", conflicts.single().existing.name)
    }

    @Test
    fun `a nameOverrides rename can also make an original conflict disappear`() = runBlocking {
        buildTree(
            rootDir,
            listOf(
                TreeNode.FileNode("source.bin", 10),
                TreeNode.DirNode("destination", listOf(TreeNode.FileNode("source.bin", 20))),
            ),
        )
        val sourceUri = node("source.bin")

        val conflicts = findConflicts(
            RuntimeEnvironment.getApplication().contentResolver,
            listOf(sourceUri),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "destination"),
            nameOverrides = mapOf(sourceUri to "source (2).bin"),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `a source that no longer resolves is dropped rather than failing the whole check`() = runBlocking {
        buildTree(
            rootDir,
            listOf(
                TreeNode.FileNode("real.bin", 10),
                TreeNode.DirNode("destination", listOf(TreeNode.FileNode("real.bin", 5), TreeNode.FileNode("ghost.bin", 5))),
            ),
        )
        val ghostSource = node("ghost.bin")

        val conflicts = findConflicts(
            RuntimeEnvironment.getApplication().contentResolver,
            listOf(node("real.bin"), ghostSource),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "destination"),
        )

        assertEquals(listOf("real.bin"), conflicts.map { it.source.name })
    }
}
