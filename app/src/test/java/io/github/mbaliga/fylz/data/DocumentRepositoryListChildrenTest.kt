package io.github.mbaliga.fylz.data

import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import kotlinx.coroutines.flow.toList
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
 * P1.11: [DocumentRepository.listChildren] streams a folder's children in batches instead of
 * blocking until the whole cursor is read. Uses the same hosted-provider harness as
 * [DocumentRepositoryExternalEntryTest], over a real, temp-directory-backed
 * [FylzFilesDocumentsProvider].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentRepositoryListChildrenTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var repository: DocumentRepository

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
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
    }

    private val treeUri get() = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
    private val rootUri get() = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)

    @Test
    fun `a folder smaller than the initial batch emits exactly one complete batch`() = runBlocking {
        val names = (1..5).map { "file-$it.txt" }
        names.forEach { File(rootDir, it).writeText("x") }

        val batches = repository.listChildren(treeUri, rootUri).toList()

        assertEquals(1, batches.size)
        assertTrue(batches.single().complete)
        assertEquals(names.toSet(), batches.single().entries.map { it.name }.toSet())
    }

    @Test
    fun `a folder larger than the initial batch size streams a partial batch before completing`() = runBlocking {
        val total = 501
        repeat(total) { index -> File(rootDir, "file-%04d.txt".format(index)).writeText("x") }

        val batches = repository.listChildren(treeUri, rootUri).toList()

        assertEquals(2, batches.size)
        assertEquals(500, batches[0].entries.size)
        assertTrue("first batch must not be marked complete", !batches[0].complete)
        assertEquals(total, batches[1].entries.size)
        assertTrue("final batch must be marked complete", batches[1].complete)
        assertEquals(total, batches[1].entries.distinctBy { it.name }.size)
    }

    @Test
    fun `an empty folder still emits one complete batch with no entries`() = runBlocking {
        val batches = repository.listChildren(treeUri, rootUri).toList()

        assertEquals(1, batches.size)
        assertTrue(batches.single().complete)
        assertTrue(batches.single().entries.isEmpty())
    }
}
