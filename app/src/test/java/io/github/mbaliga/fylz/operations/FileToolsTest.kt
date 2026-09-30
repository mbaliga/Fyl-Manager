package io.github.mbaliga.fylz.operations

import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
 * P0.4: batch rename, against the real, temp-directory-hosted provider (P0.0). Proves the
 * `DocumentFile.fromSingleUri(...).parentFile == null` defect that made every batch rename fail
 * is gone, that a swap cycle works without a spurious collision, and that a mid-batch failure
 * rolls back completely rather than leaving files at their temporary names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileToolsTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var docsDir: File
    private lateinit var faulty: FaultyDocumentsProvider
    private lateinit var tools: FileTools

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        docsDir = File(rootDir, "docs").apply { mkdirs() }
        faulty = FaultyDocumentsProvider.install()
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
        tools = FileTools(RuntimeEnvironment.getApplication())
    }

    // Anchored at the fixed volume-root tree, exactly like DocumentRepository.listChildren's
    // buildDocumentUriUsingTree(treeUri, documentId) -- a stable, unchanging tree segment plus a
    // varying document id. A self-anchored uri (tree segment == the document's own path) is what
    // DocumentsProvider.enforceTree rejects the moment that path changes under a rename, so it
    // must never be what a real FileEntry.uri looks like; this mirrors production exactly.
    private fun rootTreeUri(): Uri = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)

    private fun documentUri(relativePath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            rootTreeUri(),
            "${FylzFilesDocumentsProvider.PRIMARY_ROOT_ID}:$relativePath",
        )

    private fun writeFile(name: String, content: String) {
        File(docsDir, name).writeText(content)
    }

    @Test
    fun `renames ten files by prefix and preserves content`() = runBlocking {
        val names = (1..10).map { "item$it.txt" }
        names.forEach { writeFile(it, "content-$it") }
        val items = names.map { documentUri("docs/$it") to it }

        val plans = tools.planBatchRename(items, prefix = "File-")
        tools.executeBatchRename(documentUri("docs"), plans)

        names.forEachIndexed { index, oldName ->
            val expectedNewName = "File-${(index + 1).toString().padStart(2, '0')}.txt"
            assertTrue(File(docsDir, expectedNewName).exists())
            assertEquals("content-$oldName", File(docsDir, expectedNewName).readText())
        }
        assertEquals(10, docsDir.listFiles()?.size)
    }

    @Test
    fun `a swap cycle renames without a spurious collision`() = runBlocking {
        writeFile("a.txt", "A")
        writeFile("b.txt", "B")
        val plans = listOf(
            BatchRenamePlan(documentUri("docs/a.txt"), "a.txt", "b.txt"),
            BatchRenamePlan(documentUri("docs/b.txt"), "b.txt", "a.txt"),
        )

        tools.executeBatchRename(documentUri("docs"), plans)

        assertEquals("A", File(docsDir, "b.txt").readText())
        assertEquals("B", File(docsDir, "a.txt").readText())
    }

    @Test
    fun `a target colliding with an untouched file is refused before anything is renamed`() = runBlocking {
        writeFile("a.txt", "A")
        writeFile("taken.txt", "already here")
        val plans = listOf(BatchRenamePlan(documentUri("docs/a.txt"), "a.txt", "taken.txt"))

        assertThrows(Exception::class.java) {
            runBlocking { tools.executeBatchRename(documentUri("docs"), plans) }
        }

        assertEquals("A", File(docsDir, "a.txt").readText())
        assertEquals("already here", File(docsDir, "taken.txt").readText())
    }

    @Test
    fun `a refused rename mid-batch rolls back completely`() = runBlocking {
        val names = (1..10).map { "item$it.txt" }
        names.forEach { writeFile(it, "content-$it") }
        val items = names.map { documentUri("docs/$it") to it }
        val plans = tools.planBatchRename(items, prefix = "File-")

        faulty.refuseRenameAtCall = 6
        assertThrows(Exception::class.java) {
            runBlocking { tools.executeBatchRename(documentUri("docs"), plans) }
        }

        // Every original file is back, nothing was left at a ".fylz-rename-<uuid>" temp name,
        // and no "File-NN.txt" target exists either.
        assertEquals(names.toSet(), docsDir.list()?.toSet())
        names.forEach { name ->
            assertEquals("content-$name", File(docsDir, name).readText())
        }
    }
}
