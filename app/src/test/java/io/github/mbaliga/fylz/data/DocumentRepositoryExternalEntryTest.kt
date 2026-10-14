package io.github.mbaliga.fylz.data

import android.net.Uri
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.storage.FaultyDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.VolumeDescriptor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

/**
 * P0.12: [DocumentRepository.resolveExternalEntry] builds a [io.github.mbaliga.fylz.model.FileEntry]
 * for a `content://` uri handed to Fylz from outside (an `ACTION_VIEW` "Open with Fylz" launch) --
 * unlike browsing, that uri isn't necessarily from Fylz's own provider at all, so this reads
 * through `OpenableColumns` rather than any DocumentsContract-specific assumption. This test uses
 * the same hosted-provider harness other tests do, but exercises only the generic-column path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentRepositoryExternalEntryTest {

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

    private fun documentUri(relativePath: String): Uri =
        FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, relativePath)

    @Test
    fun `resolves name, size and a text kind for a real document`() = runBlocking {
        File(rootDir, "notes.txt").writeText("hello")

        val entry = repository.resolveExternalEntry(documentUri("notes.txt"))

        assertEquals("notes.txt", entry.name)
        assertEquals(5L, entry.sizeBytes)
        assertEquals(EntryKind.TEXT, entry.kind)
    }

    @Test
    fun `falls back to the uri's last path segment when the name can't be queried`() = runBlocking {
        // No provider is registered for this authority at all, so the query returns null --
        // exactly what a live ContentResolver does for an authority nothing handles.
        val unresolvable = Uri.parse("content://no.such.provider.registered/nonexistent-path")

        val entry = repository.resolveExternalEntry(unresolvable)

        assertEquals("nonexistent-path", entry.name)
    }
}
