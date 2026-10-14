package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `pickFolderPreviewFiles` (folder-card redesign) picks which of a folder's children a card's
 * glass panel previews. Robolectric only because `FileEntry` is keyed by a real `android.net.Uri`,
 * which the plain android.jar stub cannot construct -- same reason `SortSpecTest` opts in.
 */
@RunWith(RobolectricTestRunner::class)
class FolderPreviewPickerTest {

    private fun entry(
        name: String,
        directory: Boolean = false,
        modified: Long? = 1_000,
    ) = FileEntry(
        uri = Uri.parse("content://test/${name.replace(' ', '_')}"),
        name = name,
        mimeType = if (directory) "vnd.android.document/directory" else "text/plain",
        sizeBytes = if (directory) null else 100L,
        lastModifiedMillis = modified,
        flags = 0,
        kind = if (directory) EntryKind.DIRECTORY else EntryKind.TEXT,
    )

    @Test
    fun `empty folder previews nothing`() {
        assertEquals(emptyList<FileEntry>(), pickFolderPreviewFiles(emptyList()))
    }

    @Test
    fun `fewer files than the limit returns all of them, most recent first`() {
        val entries = listOf(
            entry("a.txt", modified = 100),
            entry("b.txt", modified = 300),
            entry("c.txt", modified = 200),
        )
        assertEquals(
            listOf("b.txt", "c.txt", "a.txt"),
            pickFolderPreviewFiles(entries).map { it.name },
        )
    }

    @Test
    fun `more files than the limit keeps only the most recent ones`() {
        val entries = (1..6).map { entry("file$it.txt", modified = it.toLong()) }
        val picked = pickFolderPreviewFiles(entries, limit = 4)
        assertEquals(listOf("file6.txt", "file5.txt", "file4.txt", "file3.txt"), picked.map { it.name })
    }

    @Test
    fun `directories are skipped even when more recent than the files`() {
        val entries = listOf(
            entry("file.txt", modified = 100),
            entry("subfolder", directory = true, modified = 999),
        )
        assertEquals(listOf("file.txt"), pickFolderPreviewFiles(entries).map { it.name })
    }

    @Test
    fun `null lastModifiedMillis sorts after every known timestamp`() {
        val entries = listOf(
            entry("unknown-time.txt", modified = null),
            entry("old.txt", modified = 10),
            entry("new.txt", modified = 20),
        )
        assertEquals(
            listOf("new.txt", "old.txt", "unknown-time.txt"),
            pickFolderPreviewFiles(entries).map { it.name },
        )
    }

    @Test
    fun `ties (equal timestamps, or all-null) keep their original relative order`() {
        val entries = listOf(
            entry("first.txt", modified = null),
            entry("second.txt", modified = null),
            entry("third.txt", modified = 500),
            entry("fourth.txt", modified = 500),
        )
        assertEquals(
            listOf("third.txt", "fourth.txt", "first.txt", "second.txt"),
            pickFolderPreviewFiles(entries).map { it.name },
        )
    }

    @Test
    fun `limit of zero returns nothing`() {
        val entries = listOf(entry("a.txt"), entry("b.txt"))
        assertEquals(emptyList<FileEntry>(), pickFolderPreviewFiles(entries, limit = 0))
    }
}
