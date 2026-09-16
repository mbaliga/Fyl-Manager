package io.github.mbaliga.fylz.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTreeTest {

    private val documents = listOf("Documents", "Contracts", "Agreements")

    @Test
    fun `no open location draws no tree`() {
        assertTrue(locationTree(ancestors = emptyList()).isEmpty())
    }

    @Test
    fun `the open path is expanded and indented one step per level`() {
        val rows = locationTree(documents)

        assertEquals(listOf("Documents", "Contracts", "Agreements"), rows.map(TreeRow::label))
        assertEquals(listOf(0, 1, 2), rows.map(TreeRow::depth))
        assertTrue(rows.all(TreeRow::expanded))
        assertTrue(rows.all(TreeRow::directory))
    }

    @Test
    fun `with nothing focused the folder you are standing in is the current row`() {
        val rows = locationTree(documents)

        assertEquals(listOf("Agreements"), rows.filter(TreeRow::current).map(TreeRow::label))
    }

    @Test
    fun `children hang off the current folder collapsed`() {
        val rows = locationTree(
            ancestors = documents,
            children = listOf(TreeChild("Client", true), TreeChild("Vendor", true)),
        )

        val children = rows.filter { it.target is TreeTarget.Child }
        assertEquals(listOf("Client", "Vendor"), children.map(TreeRow::label))
        assertTrue(children.none(TreeRow::expanded))
        assertTrue(children.all { it.depth == documents.size })
    }

    @Test
    fun `a focused child takes the current marker from its folder`() {
        val rows = locationTree(
            ancestors = documents,
            children = listOf(TreeChild("Client", true), TreeChild("NDA.pdf", false)),
            focusedChild = 1,
        )

        assertEquals(listOf("NDA.pdf"), rows.filter(TreeRow::current).map(TreeRow::label))
        assertEquals(TreeTarget.Child(1), rows.first(TreeRow::current).target)
    }

    @Test
    fun `an out-of-range focus is ignored rather than losing the current row`() {
        val rows = locationTree(
            ancestors = documents,
            children = listOf(TreeChild("Client", true)),
            focusedChild = 7,
        )

        assertEquals(listOf("Agreements"), rows.filter(TreeRow::current).map(TreeRow::label))
    }

    @Test
    fun `a big folder is windowed and says how much it left out`() {
        val children = (1..50).map { TreeChild("File$it", false) }

        val rows = locationTree(documents, children, focusedChild = null, maxChildren = 4)

        assertEquals(4, rows.count { it.target is TreeTarget.Child })
        val tail = rows.last()
        assertEquals(TreeTarget.Hidden(46), tail.target)
        assertEquals("46 more in this folder", tail.label)
    }

    @Test
    fun `the window always contains the focused child`() {
        val children = (1..50).map { TreeChild("File$it", false) }

        val rows = locationTree(documents, children, focusedChild = 40, maxChildren = 4)

        assertEquals("File41", rows.first(TreeRow::current).label)
    }

    @Test
    fun `a folder that fits is not truncated`() {
        val children = (1..4).map { TreeChild("File$it", false) }

        val rows = locationTree(documents, children, maxChildren = 4)

        assertEquals(4, rows.count { it.target is TreeTarget.Child })
        assertNull(rows.firstOrNull { it.target is TreeTarget.Hidden })
    }

    @Test
    fun `the window is clamped to the ends rather than running off them`() {
        assertEquals(0..3, childWindow(size = 50, focused = 0, max = 4))
        assertEquals(46..49, childWindow(size = 50, focused = 49, max = 4))
        assertEquals(8..11, childWindow(size = 50, focused = 10, max = 4))
        assertEquals(0..2, childWindow(size = 3, focused = null, max = 4))
    }
}
