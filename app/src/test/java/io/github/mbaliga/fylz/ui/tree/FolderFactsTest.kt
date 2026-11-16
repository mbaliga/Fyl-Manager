package io.github.mbaliga.fylz.ui.tree

import android.net.Uri
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderFactsTest {

    private fun file(name: String, kind: EntryKind, size: Long?, modified: Long? = null) = FileEntry(
        uri = Uri.parse("content://t/$name"), name = name, mimeType = "x/y",
        sizeBytes = size, lastModifiedMillis = modified, flags = 0, kind = kind,
    )

    private fun dir(name: String, modified: Long? = null) =
        file(name, EntryKind.DIRECTORY, null, modified)

    @Test
    fun `an empty folder has empty facts`() {
        val facts = FolderFacts.from(emptyList())
        assertTrue(facts.isEmpty)
        assertEquals("Empty", FolderFacts.countLine(facts))
    }

    @Test
    fun `counts split files from folders and sum known sizes`() {
        val facts = FolderFacts.from(
            listOf(dir("a"), dir("b"), file("x.png", EntryKind.IMAGE, 100), file("y.pdf", EntryKind.PDF, 50)),
        )
        assertEquals(2, facts.folderCount)
        assertEquals(2, facts.fileCount)
        assertEquals(150L, facts.totalBytes)
        assertEquals("2 files · 2 folders", FolderFacts.countLine(facts))
    }

    @Test
    fun `files with no reported size make the total a lower bound`() {
        val facts = FolderFacts.from(listOf(file("a", EntryKind.OTHER, 10), file("b", EntryKind.OTHER, null)))
        assertEquals(10L, facts.totalBytes)
        assertEquals(1, facts.unknownSizeCount)
    }

    @Test
    fun `the largest file is named`() {
        val facts = FolderFacts.from(listOf(file("small", EntryKind.OTHER, 1), file("big", EntryKind.VIDEO, 999)))
        assertEquals("big", facts.largestName)
        assertEquals(999L, facts.largestBytes)
    }

    @Test
    fun `a folder of only folders has no largest file`() {
        val facts = FolderFacts.from(listOf(dir("a")))
        assertNull(facts.largestName)
        assertEquals("1 folder", FolderFacts.countLine(facts))
    }

    @Test
    fun `newest modified spans folders and files`() {
        val facts = FolderFacts.from(listOf(dir("a", 500), file("x", EntryKind.OTHER, 1, 900), file("y", EntryKind.OTHER, 1, null)))
        assertEquals(900L, facts.newestModifiedMillis)
    }

    @Test
    fun `kinds are most common first and ties are stable`() {
        val facts = FolderFacts.from(
            listOf(
                file("1", EntryKind.IMAGE, 1), file("2", EntryKind.IMAGE, 1), file("3", EntryKind.IMAGE, 1),
                file("4", EntryKind.PDF, 1), file("5", EntryKind.AUDIO, 1),
            ),
        )
        assertEquals(EntryKind.IMAGE, facts.kinds.first().first)
        assertEquals("3 images · 1 PDF · 1 audio file", FolderFacts.kindsLine(facts))
    }

    @Test
    fun `the kinds line leaves out unclassified files and is capped`() {
        val facts = FolderFacts.from(
            listOf(
                file("1", EntryKind.IMAGE, 1), file("2", EntryKind.PDF, 1), file("3", EntryKind.AUDIO, 1),
                file("4", EntryKind.VIDEO, 1), file("5", EntryKind.ARCHIVE, 1), file("6", EntryKind.OTHER, 1),
            ),
        )
        val line = FolderFacts.kindsLine(facts, max = 3)
        assertEquals(3, line.split(" · ").size)
        assertTrue("other" !in line)
    }
}
