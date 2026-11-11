package io.github.mbaliga.fylz.ui.tree

import android.net.Uri
import io.github.mbaliga.fylz.model.FolderLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderMorphTest {

    private val hero = 8f

    @Test
    fun `the pane starts at hero size and lands at the node`() {
        assertEquals(hero, FolderMorph.paneScale(0f, hero), 1e-4f)
        assertEquals(1f, FolderMorph.paneScale(1f, hero), 1e-4f)
    }

    @Test
    fun `the pane only ever shrinks as the room opens`() {
        var previous = Float.MAX_VALUE
        for (i in 0..100) {
            val scale = FolderMorph.paneScale(i / 100f, hero)
            assertTrue(scale <= previous + 1e-4f)
            previous = scale
        }
    }

    @Test
    fun `the pane has landed before the room is fully open`() {
        assertEquals(1f, FolderMorph.paneScale(0.85f, hero), 1e-4f)
    }

    @Test
    fun `the tree resolves after the pane starts moving and the text last`() {
        assertEquals(0f, FolderMorph.treeAlpha(0.1f), 1e-4f)
        assertTrue(FolderMorph.treeAlpha(0.5f) > 0f)
        assertEquals(0f, FolderMorph.textAlpha(0.4f), 1e-4f)
        assertTrue(FolderMorph.textAlpha(0.6f) > 0f)
        assertEquals(1f, FolderMorph.textAlpha(1f), 1e-4f)
    }

    @Test
    fun `text slides up to rest`() {
        assertTrue(FolderMorph.textOffsetDp(0f) > 0f)
        assertEquals(0f, FolderMorph.textOffsetDp(1f), 1e-4f)
    }

    @Test
    fun `corners ease from the card radius to the node radius`() {
        assertEquals(FolderMorph.PANE_CORNER_DP.toFloat(), FolderMorph.cornerDp(0f), 1e-4f)
        assertEquals(FolderMorph.NODE_CORNER_DP.toFloat(), FolderMorph.cornerDp(1f), 1e-4f)
    }

    @Test
    fun `the glyph replaces the file rows only near the end`() {
        assertEquals(0f, FolderMorph.folderGlyphAlpha(0.3f), 1e-4f)
        assertEquals(1f, FolderMorph.folderGlyphAlpha(1f), 1e-4f)
    }

    @Test
    fun `out of range reveal is clamped`() {
        assertEquals(hero, FolderMorph.paneScale(-3f, hero), 1e-4f)
        assertEquals(1f, FolderMorph.paneScale(9f, hero), 1e-4f)
    }

    @Test
    fun `a degenerate room does not scale the pane`() {
        assertEquals(1f, FolderMorph.heroScale(0f), 1e-4f)
    }

    private fun loc(name: String) = FolderLocation(Uri.parse("content://t/$name"), name)

    @Test
    fun `the tree is the trail with the current folder marked, then its children`() {
        val rows = FileTreeModel.rows(
            trail = listOf(loc("Internal"), loc("Documents"), loc("Taxes")),
            childFolders = listOf("k1" to "2024", "k2" to "2025"),
        )
        assertEquals(
            listOf(TreeRowKind.ANCESTOR, TreeRowKind.ANCESTOR, TreeRowKind.CURRENT, TreeRowKind.CHILD, TreeRowKind.CHILD),
            rows.map { it.kind },
        )
        assertEquals(listOf(0, 1, 2, 3, 3), rows.map { it.depth })
        assertEquals(listOf(0, 1, 2), rows.take(3).map { it.locationIndex })
        assertEquals("k1", rows[3].childKey)
    }

    @Test
    fun `no open folder means no tree`() {
        assertTrue(FileTreeModel.rows(emptyList(), listOf("k" to "x")).isEmpty())
    }

    @Test
    fun `children are capped`() {
        val many = (1..500).map { "k$it" to "f$it" }
        val rows = FileTreeModel.rows(listOf(loc("Root")), many)
        assertEquals(1 + FileTreeModel.MAX_CHILDREN, rows.size)
    }

    @Test
    fun `a blank folder name still gets a label`() {
        assertEquals("Folder", FileTreeModel.rows(listOf(loc("")), emptyList()).single().name)
    }
}
