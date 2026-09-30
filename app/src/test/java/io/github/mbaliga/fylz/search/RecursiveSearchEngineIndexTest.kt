package io.github.mbaliga.fylz.search

import io.github.mbaliga.fylz.data.FylzDatabase
import io.github.mbaliga.fylz.index.IndexDao
import io.github.mbaliga.fylz.index.IndexScope
import io.github.mbaliga.fylz.index.IndexedFile
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1.12: [RecursiveSearchEngine]'s index fast path -- "the main search uses the index when the
 * scope is covered, and the live walk otherwise". Uses the same hosted-provider harness other
 * storage tests do ([FaultyDocumentsProvider]), over a real, temp-directory-backed
 * [FylzFilesDocumentsProvider].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecursiveSearchEngineIndexTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var engine: RecursiveSearchEngine

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        val faulty = FaultyDocumentsProvider.install()
        faulty.volumeOverride = listOf(
            VolumeDescriptor(
                rootId = FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
                title = "Internal storage",
                directory = rootDir,
                primary = true,
                removable = false,
                readOnly = false,
            ),
        )
        engine = RecursiveSearchEngine(RuntimeEnvironment.getApplication())
    }

    private val treeUri get() = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
    private val rootUri get() = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)

    private fun indexedFile(
        uri: String,
        path: String,
        directory: Boolean = false,
        textSnippet: String? = null,
    ) = IndexedFile(
        uri = uri,
        rootUri = treeUri.toString(),
        parentUri = treeUri.toString(),
        path = path,
        name = path.substringAfterLast('/'),
        mimeType = if (directory) "vnd.android.document/directory" else "text/plain",
        extension = if (directory) "" else path.substringAfterLast('.', ""),
        sizeBytes = if (directory) null else 10L,
        modifiedAtMillis = 1_000L,
        directory = directory,
        textSnippet = textSnippet,
    )

    private fun markScopeScanned() {
        val db = FylzDatabase(RuntimeEnvironment.getApplication()).writableDatabase
        IndexDao.putScope(db, IndexScope(treeUri.toString(), "Internal storage", lastScannedAtMillis = 1L))
    }

    @Test
    fun `a covered scope returns indexed name hits without any real files on disk`() = runBlocking {
        markScopeScanned()
        val db = FylzDatabase(RuntimeEnvironment.getApplication()).writableDatabase
        IndexDao.replaceFilesForRoot(
            db,
            treeUri.toString(),
            listOf(indexedFile("content://index/report", "Quarterly report.txt")),
        )
        // Deliberately NO real file on disk -- a hit can only come from the index.

        val progress = engine.search(treeUri, rootUri, "Internal storage", SearchQuery.parse("report")).last()

        assertTrue(progress.complete)
        assertEquals(1, progress.hits.size)
        assertEquals("Quarterly report.txt", progress.hits.single().entry.name)
        assertEquals(SearchMatchSource.NAME, progress.hits.single().source)
    }

    @Test
    fun `an uncovered scope falls back to the live walk over real files`() = runBlocking {
        // No scope row at all -- not covered -- so this must walk the real provider.
        File(rootDir, "notes.txt").writeText("hello")

        val progress = engine.search(treeUri, rootUri, "Internal storage", SearchQuery.parse("notes")).last()

        assertTrue(progress.complete)
        assertEquals(listOf("notes.txt"), progress.hits.map { it.entry.name })
    }

    @Test
    fun `content search matches the captured text snippet via FTS`() = runBlocking {
        markScopeScanned()
        val db = FylzDatabase(RuntimeEnvironment.getApplication()).writableDatabase
        IndexDao.replaceFilesForRoot(
            db,
            treeUri.toString(),
            listOf(indexedFile("content://index/budget", "budget.txt", textSnippet = "Total revenue grew twelve percent.")),
        )

        val progress = engine.search(treeUri, rootUri, "Internal storage", SearchQuery.parse("content: revenue")).last()

        assertEquals(1, progress.hits.size)
        assertEquals(SearchMatchSource.CONTENT, progress.hits.single().source)
    }

    @Test
    fun `an indexed hit inside the recycle bin or a staged write is excluded`() = runBlocking {
        markScopeScanned()
        val db = FylzDatabase(RuntimeEnvironment.getApplication()).writableDatabase
        IndexDao.replaceFilesForRoot(
            db,
            treeUri.toString(),
            listOf(
                indexedFile("content://index/trashed", ".fylz-trash/report.txt"),
                indexedFile("content://index/staged", ".fylz-part-1-report.txt"),
                indexedFile("content://index/real", "report.txt"),
            ),
        )

        val progress = engine.search(treeUri, rootUri, "Internal storage", SearchQuery.parse("report")).last()

        assertEquals(listOf("report.txt"), progress.hits.map { it.entry.name })
    }

    @Test
    fun `searching a nested indexed folder scopes results to that folder`() = runBlocking {
        markScopeScanned()
        val db = FylzDatabase(RuntimeEnvironment.getApplication()).writableDatabase
        IndexDao.replaceFilesForRoot(
            db,
            treeUri.toString(),
            listOf(
                indexedFile("content://index/work", "Work", directory = true),
                indexedFile("content://index/work-report", "Work/report.txt"),
                indexedFile("content://index/other-report", "Other/report.txt"),
            ),
        )
        val workUri = android.net.Uri.parse("content://index/work")

        val progress = engine.search(treeUri, workUri, "Work", SearchQuery.parse("report")).last()

        assertEquals(listOf("report.txt"), progress.hits.map { it.entry.name })
    }
}
