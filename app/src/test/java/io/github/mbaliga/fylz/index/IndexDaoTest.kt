package io.github.mbaliga.fylz.index

import io.github.mbaliga.fylz.data.FylzDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.12: [IndexDao]'s path-scoped listing and FTS content search -- the two pieces
 * `search/RecursiveSearchEngine`'s index fast path depends on -- against the real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IndexDaoTest {

    private fun db() = FylzDatabase(RuntimeEnvironment.getApplication()).writableDatabase

    private fun file(
        uri: String,
        path: String,
        name: String = path.substringAfterLast('/'),
        directory: Boolean = false,
        textSnippet: String? = null,
    ) = IndexedFile(
        uri = uri,
        rootUri = "content://docs/root",
        parentUri = "content://docs/root",
        path = path,
        name = name,
        mimeType = if (directory) "vnd.android.document/directory" else "text/plain",
        extension = if (directory) "" else name.substringAfterLast('.', ""),
        sizeBytes = if (directory) null else 10L,
        modifiedAtMillis = 1_000L,
        directory = directory,
        textSnippet = textSnippet,
    )

    @Test
    fun `filesUnderPath returns direct and nested descendants but not siblings`() {
        val db = db()
        val entries = listOf(
            file("content://docs/root/notes", path = "notes.txt"),
            file("content://docs/root/work", path = "Work", directory = true),
            file("content://docs/root/work-report", path = "Work/report.txt"),
            file("content://docs/root/work-sub-deep", path = "Work/Sub/deep.txt"),
            // "Work2" must not be treated as a descendant of "Work" by a naive prefix match.
            file("content://docs/root/work2-file", path = "Work2/other.txt"),
        )
        IndexDao.replaceFilesForRoot(db, "content://docs/root", entries)

        val underWork = IndexDao.filesUnderPath(db, "content://docs/root", "Work").map { it.path }.toSet()

        assertEquals(setOf("Work", "Work/report.txt", "Work/Sub/deep.txt"), underWork)
    }

    @Test
    fun `filesUnderPath with an empty prefix returns the whole root`() {
        val db = db()
        IndexDao.replaceFilesForRoot(
            db,
            "content://docs/root",
            listOf(file("content://docs/root/a", path = "a.txt"), file("content://docs/root/b", path = "b.txt")),
        )

        assertEquals(2, IndexDao.filesUnderPath(db, "content://docs/root", "").size)
    }

    @Test
    fun `contentMatches finds a captured text snippet by word and returns a native snippet`() {
        val db = db()
        IndexDao.replaceFilesForRoot(
            db,
            "content://docs/root",
            listOf(
                file("content://docs/root/a", path = "a.txt", textSnippet = "Total revenue grew twelve percent."),
                file("content://docs/root/b", path = "b.txt", textSnippet = "Nothing relevant here."),
            ),
        )

        val hits = IndexDao.contentMatches(db, "content://docs/root", "", listOf("revenue"))

        assertEquals(listOf("content://docs/root/a"), hits.map { it.first })
        assertTrue("expected a non-empty native FTS snippet", hits.single().second.isNotBlank())
    }

    @Test
    fun `contentMatches is scoped to the given path prefix`() {
        val db = db()
        IndexDao.replaceFilesForRoot(
            db,
            "content://docs/root",
            listOf(
                file("content://docs/root/work-a", path = "Work/a.txt", textSnippet = "budget budget budget"),
                file("content://docs/root/other-b", path = "Other/b.txt", textSnippet = "budget budget budget"),
            ),
        )

        val hits = IndexDao.contentMatches(db, "content://docs/root", "Work", listOf("budget"))

        assertEquals(listOf("content://docs/root/work-a"), hits.map { it.first })
    }

    @Test
    fun `contentMatches with no usable terms returns no hits`() {
        val db = db()
        IndexDao.replaceFilesForRoot(db, "content://docs/root", listOf(file("content://docs/root/a", path = "a.txt", textSnippet = "text")))

        assertTrue(IndexDao.contentMatches(db, "content://docs/root", "", emptyList()).isEmpty())
    }

    @Test
    fun `a query containing FTS-special characters is treated as literal text, not a syntax error`() {
        val db = db()
        IndexDao.replaceFilesForRoot(
            db,
            "content://docs/root",
            listOf(file("content://docs/root/a", path = "a.txt", textSnippet = "cost: -5% (a decrease)")),
        )

        // A raw '-', ':' or '*' is FTS query syntax; ftsMatchExpression must quote it away rather
        // than let SQLite throw on a malformed MATCH expression.
        val hits = IndexDao.contentMatches(db, "content://docs/root", "", listOf("cost:", "-5%"))

        assertTrue(hits.isEmpty() || hits.single().first == "content://docs/root/a")
    }
}
