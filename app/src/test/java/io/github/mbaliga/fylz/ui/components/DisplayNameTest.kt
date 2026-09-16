package io.github.mbaliga.fylz.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure logic, no composition needed -- the interesting cases are all in
 * [FileFormatRegistry.compoundExtension]'s edge cases, not in the stripping itself.
 */
class DisplayNameTest {

    @Test
    fun `extensions show unchanged when the setting is on`() {
        assertEquals("report.pdf", displayName("report.pdf", isDirectory = false, showExtensions = true))
    }

    @Test
    fun `a known extension is hidden when the setting is off`() {
        assertEquals("report", displayName("report.pdf", isDirectory = false, showExtensions = false))
    }

    @Test
    fun `a compound extension is stripped as one unit`() {
        assertEquals("archive", displayName("archive.tar.gz", isDirectory = false, showExtensions = false))
    }

    @Test
    fun `a dotfile that is all extension is never stripped to empty`() {
        assertEquals(".gitignore", displayName(".gitignore", isDirectory = false, showExtensions = false))
    }

    @Test
    fun `a name with no extension is unaffected`() {
        assertEquals("README", displayName("README", isDirectory = false, showExtensions = false))
    }

    @Test
    fun `folders are never affected regardless of the setting`() {
        assertEquals("Photos.old", displayName("Photos.old", isDirectory = true, showExtensions = false))
    }

    @Test
    fun `only the trailing extension is stripped, not every dot in the name`() {
        assertEquals("my.notes", displayName("my.notes.txt", isDirectory = false, showExtensions = false))
    }
}
