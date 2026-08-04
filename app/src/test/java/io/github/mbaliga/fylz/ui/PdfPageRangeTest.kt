package io.github.mbaliga.fylz.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-range parser behind the PDF tools dialog. It has to tolerate half-typed input, because
 * it runs on every keystroke while the user is still writing the range.
 */
class PdfPageRangeTest {

    @Test
    fun `blank input selects every page`() {
        assertEquals(listOf(0, 1, 2, 3), parsePageRange("", 4))
    }

    @Test
    fun `single pages are converted to zero based indices`() {
        assertEquals(listOf(0, 6), parsePageRange("1,7", 10))
    }

    @Test
    fun `ranges are inclusive at both ends`() {
        assertEquals(listOf(0, 1, 2), parsePageRange("1-3", 10))
    }

    @Test
    fun `ranges and singles combine and keep input order without duplicates`() {
        assertEquals(listOf(0, 1, 2, 6, 8, 9), parsePageRange("1-3,7,9-10,7", 12))
    }

    @Test
    fun `whitespace around segments is tolerated`() {
        assertEquals(listOf(0, 1, 4), parsePageRange(" 1 - 2 , 5 ", 8))
    }

    @Test
    fun `pages beyond the document are dropped rather than rejected`() {
        assertEquals(listOf(0, 1), parsePageRange("1-99", 2))
        assertEquals(emptyList<Int>(), parsePageRange("40", 3))
    }

    @Test
    fun `a reversed range is ignored instead of producing nothing at all`() {
        assertEquals(listOf(4), parsePageRange("9-3,5", 10))
    }

    @Test
    fun `open ended ranges run to the document boundary`() {
        assertEquals(listOf(3, 4), parsePageRange("4-", 5))
        assertEquals(listOf(0, 1, 2), parsePageRange("-3", 5))
    }

    @Test
    fun `partial input while typing does not throw`() {
        // Each of these is a real intermediate state of typing "1-3,7".
        listOf("1", "1-", "1-3", "1-3,", "1-3,7").forEach { partial ->
            parsePageRange(partial, 10)
        }
        // "1-" is open-ended, so mid-typing the range already selects the whole document rather
        // than briefly selecting nothing.
        assertEquals((0 until 10).toList(), parsePageRange("1-", 10))
    }

    @Test
    fun `non numeric junk is discarded`() {
        assertEquals(listOf(1), parsePageRange("abc,2,,", 5))
    }

    @Test
    fun `an empty document yields no pages regardless of input`() {
        assertEquals(emptyList<Int>(), parsePageRange("1-5", 0))
        assertEquals(emptyList<Int>(), parsePageRange("", 0))
    }
}
