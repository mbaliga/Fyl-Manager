package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/**
 * [buildCliRows]' box-drawing bookkeeping and the line it prints -- the part of the CLI theme
 * worth locking in away from a real [io.github.mbaliga.fylz.data.DocumentRepository].
 *
 * Robolectric only because `FileEntry` is keyed by a real `android.net.Uri`, same reason
 * `SortSpecTest` opts in.
 */
@RunWith(RobolectricTestRunner::class)
class CliListingTest {

    private val utc = ZoneOffset.UTC

    private fun entry(
        name: String,
        directory: Boolean = false,
        size: Long? = 12_697L,
        modified: Long? = 1_755_302_400_000L, // 2025-08-16T00:00:00Z
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
    fun `folders sort first, then files, both alphabetically`() {
        val rows = buildCliRows(
            entries = listOf(entry("zeta.txt"), entry("beta", directory = true), entry("alpha.txt")),
            expanded = emptySet(),
            children = emptyMap(),
        )
        assertEquals(listOf("beta", "alpha.txt", "zeta.txt"), rows.map { it.entry.name })
    }

    @Test
    fun `the last row at each level draws the corner connector`() {
        val rows = buildCliRows(
            entries = listOf(entry("a.txt"), entry("b.txt")),
            expanded = emptySet(),
            children = emptyMap(),
        )
        assertEquals("├─ ", rows[0].connector())
        assertEquals("└─ ", rows[1].connector())
    }

    @Test
    fun `an expanded folder's children are indented and inherit its guide`() {
        val childUri = Uri.parse("content://test/docs")
        val rows = buildCliRows(
            entries = listOf(entry("docs", directory = true), entry("zeta.txt")),
            expanded = setOf(childUri),
            children = mapOf(childUri to listOf(entry("inside.txt"))),
        )
        // docs (not last), inside.txt (docs' only child, itself last), zeta.txt (last at depth 0).
        assertEquals(listOf("docs", "inside.txt", "zeta.txt"), rows.map { it.entry.name })
        val inside = rows[1]
        assertEquals(1, inside.depth)
        assertTrue(inside.isLastChild)
        // docs still has a sibling (zeta.txt) after it, so its subtree's guide column stays open.
        assertEquals(listOf(true), inside.guides)
        assertEquals("│  └─ ", inside.connector())
    }

    @Test
    fun `a collapsed folder's children never appear even if cached`() {
        val childUri = Uri.parse("content://test/docs")
        val rows = buildCliRows(
            entries = listOf(entry("docs", directory = true)),
            expanded = emptySet(),
            children = mapOf(childUri to listOf(entry("inside.txt"))),
        )
        assertEquals(listOf("docs"), rows.map { it.entry.name })
    }

    @Test
    fun `a directory's trailing columns are blank -- nobody has paid to sum its size`() {
        // The lone entry in its list, so it is its own last child -- the corner connector.
        val row = buildCliRows(listOf(entry("docs", directory = true)), emptySet(), emptyMap()).single()
        assertEquals("", row.trailingColumns(utc))
        assertEquals("└─ docs/", row.line(utc))
    }

    @Test
    fun `a file's line always carries its extension and formatted size and date`() {
        val row = buildCliRows(listOf(entry("report.pdf", size = 12_697L)), emptySet(), emptyMap()).single()
        assertTrue(row.line(utc).contains("report.pdf"))
        assertTrue(row.line(utc).contains("12.4 KiB"))
        assertTrue(row.line(utc).contains("2025-08-16"))
    }
}
