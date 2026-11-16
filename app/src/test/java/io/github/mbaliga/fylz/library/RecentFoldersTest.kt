package io.github.mbaliga.fylz.library

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFoldersTest {

    private fun folder(name: String) = RecentFolder("tree:$name", "doc:$name", name)

    @Test
    fun `the newest folder goes first`() {
        val list = RecentFolders.record(listOf(folder("a")), folder("b"))
        assertEquals(listOf("b", "a"), list.map { it.name })
    }

    @Test
    fun `reopening a folder moves it to the front without duplicating it`() {
        val list = RecentFolders.record(listOf(folder("a"), folder("b"), folder("c")), folder("c"))
        assertEquals(listOf("c", "a", "b"), list.map { it.name })
    }

    @Test
    fun `the list is capped`() {
        var list = emptyList<RecentFolder>()
        (1..20).forEach { list = RecentFolders.record(list, folder("f$it")) }
        assertEquals(RecentFolders.MAX, list.size)
        assertEquals("f20", list.first().name)
    }

    @Test
    fun `a nameless folder is not recorded`() {
        val start = listOf(folder("a"))
        assertEquals(start, RecentFolders.record(start, RecentFolder("t", "d", " ")))
    }
}
