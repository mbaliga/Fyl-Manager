package io.github.mbaliga.fylz.storage.testing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TreeFixturesTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val shape = listOf(
        TreeNode.FileNode("empty.bin", 0),
        TreeNode.FileNode("tiny.bin", 1),
        TreeNode.DirNode(
            "nested",
            listOf(
                TreeNode.FileNode("a.bin", 5 * 1024 * 1024),
                TreeNode.DirNode("deeper", listOf(TreeNode.FileNode("b.bin", 4096))),
            ),
        ),
    )

    @Test
    fun `identical trees built from the same shape have no diff`() {
        val a = tempFolder.newFolder("a")
        val b = tempFolder.newFolder("b")
        buildTree(a, shape)
        buildTree(b, shape)

        assertEquals(emptyList<String>(), diffTrees(a, b))
    }

    @Test
    fun `a changed byte is detected`() {
        val a = tempFolder.newFolder("a")
        val b = tempFolder.newFolder("b")
        buildTree(a, shape)
        buildTree(b, shape)
        File(b, "nested/deeper/b.bin").writeBytes(ByteArray(4096))

        val diff = diffTrees(a, b)
        assertTrue(diff.any { it.contains("nested/deeper/b.bin") })
    }

    @Test
    fun `a missing file is detected`() {
        val a = tempFolder.newFolder("a")
        val b = tempFolder.newFolder("b")
        buildTree(a, shape)
        buildTree(b, shape)
        File(b, "tiny.bin").delete()

        val diff = diffTrees(a, b)
        assertTrue(diff.any { it.contains("missing: /tiny.bin") })
    }

    @Test
    fun `an extra file is detected`() {
        val a = tempFolder.newFolder("a")
        val b = tempFolder.newFolder("b")
        buildTree(a, shape)
        buildTree(b, shape)
        File(b, "extra.bin").writeBytes(byteArrayOf(1))

        val diff = diffTrees(a, b)
        assertTrue(diff.any { it.contains("unexpected: /extra.bin") })
    }

    @Test
    fun `a zero byte file round trips as zero length`() {
        val a = tempFolder.newFolder("a")
        buildTree(a, shape)
        assertEquals(0L, File(a, "empty.bin").length())
        assertEquals(5L * 1024 * 1024, File(a, "nested/a.bin").length())
    }
}
