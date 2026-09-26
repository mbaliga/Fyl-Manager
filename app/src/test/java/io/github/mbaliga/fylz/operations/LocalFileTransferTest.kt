package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.testing.TreeNode
import io.github.mbaliga.fylz.storage.testing.buildTree
import io.github.mbaliga.fylz.storage.testing.diffTrees
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * P1.3 / A5: [LocalFileTransfer] against the real, temp-directory-hosted [FylzFilesDocumentsProvider]
 * (P0.0) -- not [io.github.mbaliga.fylz.storage.FaultyDocumentsProvider], which wraps the real
 * provider rather than extending it, so [FylzFilesDocumentsProvider.fileFor] (an `as?` cast) never
 * resolves it and [FileOperationServiceTest]'s existing suite exercises [DocumentsTransfer]
 * exclusively. This suite is what actually proves the fast path.
 */
class LocalFileTransferTest : FylzDocumentsProviderTestBase() {

    private lateinit var engine: LocalFileTransfer
    private lateinit var destinationDir: File

    @Before
    fun setUpEngine() {
        engine = LocalFileTransfer(RuntimeEnvironment.getApplication())
        destinationDir = File(rootDir, "destination").apply { mkdirs() }
    }

    private fun node(relativePath: String): DocNode =
        DocNode.load(
            RuntimeEnvironment.getApplication().contentResolver,
            FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath),
        ) ?: error("No document at $relativePath")

    @Test
    fun `supports both ends when they resolve to local files under this provider`() {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 10)))
        assertTrue(engine.supports(node("source.bin"), node("destination")))
    }

    @Test
    fun `copyFile writes byte-identical content, preserves the modified time, and reports final progress`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 200_000)))
        val sourceFile = File(rootDir, "source.bin")
        sourceFile.setLastModified(sourceFile.lastModified() - 60_000)

        val progress = mutableListOf<Long>()
        val copied = engine.copyFile(node("source.bin"), node("destination"), "copy.bin") { progress.add(it) }

        val copiedFile = File(destinationDir, "copy.bin")
        assertEquals(emptyList<String>(), diffTrees(sourceFile, copiedFile))
        assertEquals(sourceFile.lastModified(), copiedFile.lastModified())
        assertEquals(sourceFile.length(), progress.last())
        assertEquals("copy.bin", copied.name)
        assertFalse("the temp sibling must not survive a successful copy", File(destinationDir, "copy.bin.fylz-ltmp").exists())
    }

    @Test
    fun `copyFile resumes from an existing temp file instead of restarting`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 100_000)))
        val sourceFile = File(rootDir, "source.bin")
        val sourceBytes = sourceFile.readBytes()
        val tempFile = File(destinationDir, "copy.bin.fylz-ltmp")
        tempFile.writeBytes(sourceBytes.copyOfRange(0, 40_000))

        val progress = mutableListOf<Long>()
        engine.copyFile(node("source.bin"), node("destination"), "copy.bin") { progress.add(it) }

        val copiedFile = File(destinationDir, "copy.bin")
        assertArrayEquals(sourceBytes, copiedFile.readBytes())
        assertTrue(
            "expected the first reported progress (${progress.firstOrNull()}) to already include the resumed 40,000 bytes",
            progress.first() >= 40_000,
        )
        assertFalse(tempFile.exists())
    }

    @Test
    fun `moveFile renames instead of copying, leaving the source gone`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 5_000)))
        val sourceFile = File(rootDir, "source.bin")
        val expectedBytes = sourceFile.readBytes()

        val moved = engine.moveFile(
            source = node("source.bin"),
            sourceParent = null,
            destinationDirectory = node("destination"),
            requestedName = "moved.bin",
        )

        assertTrue(moved != null)
        assertFalse(sourceFile.exists())
        assertEquals("moved.bin", moved!!.name)
        assertArrayEquals(expectedBytes, File(destinationDir, "moved.bin").readBytes())
    }

    @Test
    fun `moveFile refuses to clobber an existing file at the destination`() = runBlocking {
        buildTree(rootDir, listOf(TreeNode.FileNode("source.bin", 10)))
        File(destinationDir, "existing.bin").writeBytes(ByteArray(10))

        val moved = engine.moveFile(
            source = node("source.bin"),
            sourceParent = null,
            destinationDirectory = node("destination"),
            requestedName = "existing.bin",
        )

        assertNull(moved)
        assertTrue("a refused move must leave the source untouched", File(rootDir, "source.bin").exists())
    }

    @Test
    fun `a whole directory tree moves in one rename`() = runBlocking {
        buildTree(
            rootDir,
            listOf(
                TreeNode.DirNode(
                    "album",
                    listOf(TreeNode.FileNode("a.bin", 100), TreeNode.FileNode("b.bin", 200)),
                ),
            ),
        )
        val originalAlbum = File(rootDir, "album")
        val expectedFiles = originalAlbum.listFiles()!!.map { it.name to it.readBytes() }.toMap()

        val moved = engine.moveFile(
            source = node("album"),
            sourceParent = null,
            destinationDirectory = node("destination"),
            requestedName = "album",
        )

        assertTrue(moved != null)
        assertFalse(originalAlbum.exists())
        val movedAlbum = File(destinationDir, "album")
        assertTrue(movedAlbum.isDirectory)
        expectedFiles.forEach { (name, bytes) -> assertArrayEquals(bytes, File(movedAlbum, name).readBytes()) }
    }
}
