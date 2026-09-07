package io.github.mbaliga.fylz.browse

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sorting used to be hardcoded in `DocumentRepository` with no UI and no test. These lock in the
 * ordering rules the browser's sort menu now exposes.
 *
 * Robolectric only because `FileEntry` is keyed by a real `android.net.Uri`, which the plain
 * android.jar stub cannot construct (same reason `PdfPagePlanPolicyTest` opts in).
 */
@RunWith(RobolectricTestRunner::class)
class SortSpecTest {

    private fun entry(
        name: String,
        directory: Boolean = false,
        size: Long? = 100,
        modified: Long? = 1_000,
    ) = FileEntry(
        uri = Uri.parse("content://test/${name.replace(' ', '_')}"),
        name = name,
        mimeType = if (directory) "vnd.android.document/directory" else "text/plain",
        sizeBytes = if (directory) null else size,
        lastModifiedMillis = modified,
        flags = 0,
        kind = if (directory) EntryKind.DIRECTORY else EntryKind.TEXT,
    )

    @Test
    fun `folders come first by default regardless of name`() {
        val entries = listOf(entry("zeta.txt"), entry("alpha", directory = true))
        val sorted = sortEntries(entries, SortSpec.Default)
        assertEquals(listOf("alpha", "zeta.txt"), sorted.map { it.name })
    }

    @Test
    fun `descending name does not shuffle folders into the file list`() {
        val entries = listOf(
            entry("b.txt"),
            entry("a-folder", directory = true),
            entry("a.txt"),
            entry("z-folder", directory = true),
        )
        val sorted = sortEntries(entries, SortSpec(SortField.NAME, SortDirection.DESCENDING))
        // Folders still lead; only the order within each partition reverses.
        assertEquals(listOf("z-folder", "a-folder", "b.txt", "a.txt"), sorted.map { it.name })
    }

    @Test
    fun `name sort is case insensitive`() {
        val entries = listOf(entry("Banana.txt"), entry("apple.txt"), entry("Cherry.txt"))
        val sorted = sortEntries(entries, SortSpec.Default)
        assertEquals(listOf("apple.txt", "Banana.txt", "Cherry.txt"), sorted.map { it.name })
    }

    @Test
    fun `size sort orders ascending and treats folders as smallest`() {
        val entries = listOf(
            entry("big.txt", size = 9_000),
            entry("small.txt", size = 12),
            entry("mid.txt", size = 400),
        )
        val sorted = sortEntries(entries, SortSpec(SortField.SIZE))
        assertEquals(listOf("small.txt", "mid.txt", "big.txt"), sorted.map { it.name })
    }

    @Test
    fun `modified sort respects direction`() {
        val entries = listOf(
            entry("old.txt", modified = 100),
            entry("new.txt", modified = 900),
            entry("mid.txt", modified = 500),
        )
        assertEquals(
            listOf("old.txt", "mid.txt", "new.txt"),
            sortEntries(entries, SortSpec(SortField.MODIFIED)).map { it.name },
        )
        assertEquals(
            listOf("new.txt", "mid.txt", "old.txt"),
            sortEntries(entries, SortSpec(SortField.MODIFIED, SortDirection.DESCENDING)).map { it.name },
        )
    }

    @Test
    fun `type sort groups by extension then name`() {
        val entries = listOf(
            entry("b.txt"),
            entry("a.zip"),
            entry("a.txt"),
        )
        val sorted = sortEntries(entries, SortSpec(SortField.TYPE))
        assertEquals(listOf("a.txt", "b.txt", "a.zip"), sorted.map { it.name })
    }

    @Test
    fun `disabling folders first mixes directories into the ordering`() {
        val entries = listOf(entry("zeta", directory = true), entry("alpha.txt"))
        val sorted = sortEntries(entries, SortSpec(foldersFirst = false))
        assertEquals(listOf("alpha.txt", "zeta"), sorted.map { it.name })
    }

    @Test
    fun `ties fall back to name so the order is stable`() {
        val entries = listOf(
            entry("c.txt", size = 10),
            entry("a.txt", size = 10),
            entry("b.txt", size = 10),
        )
        val sorted = sortEntries(entries, SortSpec(SortField.SIZE))
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), sorted.map { it.name })
    }

    @Test
    fun `withField toggles direction when re-selecting the same field`() {
        val initial = SortSpec(SortField.NAME, SortDirection.ASCENDING)
        assertEquals(SortDirection.DESCENDING, initial.withField(SortField.NAME).direction)
        // Switching to a different field starts ascending again rather than inheriting.
        assertEquals(SortDirection.ASCENDING, initial.withField(SortField.SIZE).direction)
        assertEquals(SortField.SIZE, initial.withField(SortField.SIZE).field)
    }
}
