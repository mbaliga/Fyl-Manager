package io.github.mbaliga.fylz.browse

import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.storage.ArchiveProviderTestSupport
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

/**
 * The `SessionCodecTest` case docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.5 asks for, as a
 * sibling class because it needs the hosted providers: a restored archive location is listed
 * through the provider with a fake decoder -- not just the string round trip -- and a vanished
 * source behind a restored location fails with a message while the location itself survives the
 * decode (so Up still works).
 */
class SessionRestoreArchiveLocationTest : FylzDocumentsProviderTestBase() {

    private lateinit var hosted: ArchiveProviderTestSupport.Hosted
    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        hosted = ArchiveProviderTestSupport.host()
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
    }

    private val treeUri: Uri get() = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
    private val downloadUri: Uri get() = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Download")

    private fun snapshot(tab: FolderTab) = SessionSnapshot(
        tabs = listOf(tab),
        activeTabId = tab.id,
        sortSpec = SortSpec.Default,
        viewMode = ViewMode.LIST,
        previewMode = PreviewMode.DOCKED,
        query = "",
        searchRecursive = false,
    )

    @Test
    fun `a restored archive location lists through the provider`() = runBlocking<Unit> {
        File(rootDir, "Download").mkdirs()
        FakeArchive(
            listOf(
                FakeArchive.Entry("2024/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
                FakeArchive.Entry("2024/a.jpg", ByteArray(3)),
                FakeArchive.Entry("notes.txt", "n".toByteArray()),
            ),
        ).write(File(rootDir, "Download/photos.zip"))
        val archiveFile = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Download/photos.zip")
        val root = ArchiveDocumentId.root(archiveFile)
        val tab = FolderTab(
            id = "t",
            treeUri = treeUri,
            locations = listOf(
                FolderLocation(downloadUri, "Download"),
                FolderLocation(root.toUri(), "photos.zip"),
                FolderLocation(root.entry(0, "2024").toUri(), "2024"),
            ),
        )
        val decoded = decodeSession(encodeSession(snapshot(tab)))!!
        val restored = decoded.tabs.single()
        assertEquals(tab, restored)
        assertEquals("Download / photos.zip / 2024", restored.locations.joinToString(" / ") { it.name })
        // The restored location lists (the catalog is cold: this is the first listing of the process).
        val entries = repository.listChildren(restored.treeUri, restored.current.uri).toList().single().entries
        assertEquals(listOf("a.jpg"), entries.map { it.name })
        assertEquals(1, hosted.stub.listCalls.get())
        // Up is the location above, an archive root, which lists too.
        val above = repository.listChildren(restored.treeUri, restored.locations[1].uri).toList().single().entries
        assertEquals(listOf("2024", "notes.txt"), above.map { it.name })
        assertEquals(1, hosted.stub.listCalls.get())
    }

    @Test
    fun `a vanished source behind a restored location fails with a message and the location survives the decode`() {
        val gone = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "Download/gone.zip")
        val tab = FolderTab(
            id = "t",
            treeUri = treeUri,
            locations = listOf(FolderLocation(downloadUri, "Download"), FolderLocation(ArchiveDocumentId.root(gone).toUri(), "gone.zip")),
        )
        val restored = decodeSession(encodeSession(snapshot(tab)))!!.tabs.single()
        assertEquals(2, restored.locations.size)
        val failure = assertThrows(IOException::class.java) { runBlocking { repository.listChildren(restored.treeUri, restored.current.uri).toList() } }
        assertTrue(failure.message, failure.message!!.startsWith("Unable to read the archive"))
        assertEquals(0, hosted.stub.listCalls.get())
    }
}
