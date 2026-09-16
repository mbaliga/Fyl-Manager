package io.github.mbaliga.fylz.ui.tags

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure logic, no composition needed. The interesting cases are all about quoting: a hand-rolled
 * `"tag:$tag"` would misparse the moment a tag's name has a space or a paren in it, which is
 * exactly why [tagSearchQuery] goes through `QueryCompiler` instead of string concatenation.
 */
class TagBrowserTest {

    @Test
    fun `a plain tag needs no quoting`() {
        assertEquals("tag:work", tagSearchQuery("work"))
    }

    @Test
    fun `a tag containing a space is quoted so it reparses as one value`() {
        assertEquals("tag:\"road trip\"", tagSearchQuery("road trip"))
    }

    @Test
    fun `a tag containing parens is quoted`() {
        assertEquals("tag:\"(draft)\"", tagSearchQuery("(draft)"))
    }
}
