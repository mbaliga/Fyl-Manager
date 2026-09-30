package io.github.mbaliga.fylz.data

import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider
import io.github.mbaliga.fylz.storage.ArchiveProviderTestSupport
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

/**
 * `DocumentRepository.listChildren` over an archive location (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md
 * section 2.5): the non-tree branch builds document Uris of the archive authority, rows come in
 * archive order with `kind` classified, and `EXTRA_ERROR` becomes the `IOException` the listing
 * effect already toasts. The tab's `treeUri` stays the outer tree throughout.
 */
class DocumentRepositoryArchiveListingTest : FylzDocumentsProviderTestBase() {

    private lateinit var hosted: ArchiveProviderTestSupport.Hosted
    private lateinit var repository: DocumentRepository

    private val sample = FakeArchive(
        listOf(
            FakeArchive.Entry("docs/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
            FakeArchive.Entry("docs/readme.md", "# readme\n".toByteArray()),
            FakeArchive.Entry("notes.txt", "notes".toByteArray()),
            FakeArchive.Entry("images/pixel.png", ByteArray(64)),
            FakeArchive.Entry("inner.zip", ByteArray(8)),
            FakeArchive.Entry("clip.mp4", ByteArray(8)),
        ),
    )

    @Before
    fun setUp() {
        hosted = ArchiveProviderTestSupport.host()
        repository = DocumentRepository(RuntimeEnvironment.getApplication())
    }

    private val treeUri: Uri get() = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)

    private fun archiveUri(name: String = "photos.zip"): Uri {
        sample.write(File(rootDir, name))
        return FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, name)
    }

    @Test
    fun `an archive root lists non-tree rows in archive order with kinds classified`() = runBlocking {
        val root = ArchiveDocumentId.root(archiveUri()).toUri()
        val batches = repository.listChildren(treeUri, root).toList()
        assertEquals(1, batches.size)
        assertTrue(batches.single().complete)
        val entries = batches.single().entries
        assertEquals(listOf("docs", "notes.txt", "images", "inner.zip", "clip.mp4"), entries.map { it.name })
        assertEquals(
            listOf(EntryKind.DIRECTORY, EntryKind.TEXT, EntryKind.DIRECTORY, EntryKind.ARCHIVE, EntryKind.VIDEO),
            entries.map { it.kind },
        )
        entries.forEach { entry ->
            assertEquals(ArchiveDocumentsProvider.AUTHORITY, entry.uri.authority)
            assertFalse("rows of an archive are non-tree documents", DocumentsContract.isTreeUri(entry.uri))
            assertEquals(0, entry.flags)
        }
        assertNull("directories carry no size", entries[0].sizeBytes)
        assertEquals(5L, entries[1].sizeBytes)
        assertEquals(1_577_836_800_000L, entries[1].lastModifiedMillis)
        assertEquals("text/plain", entries[1].mimeType)
        // A folder inside the archive lists through its own (non-tree) Uri the same way.
        val inDocs = repository.listChildren(treeUri, entries[0].uri).toList().single().entries
        assertEquals(listOf("readme.md"), inDocs.map { it.name })
        assertEquals(EntryKind.MARKDOWN, inDocs.single().kind)
        assertEquals("docs/readme.md", ArchiveDocumentId.parse(inDocs.single().uri).path)
        assertEquals(1, hosted.stub.listCalls.get())
    }

    @Test
    fun `a tree location still lists through the tree form, untouched`() = runBlocking {
        archiveUri("photos.zip")
        File(rootDir, "plain.txt").writeText("x")
        val rootDocument = FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
        val entries = repository.listChildren(treeUri, rootDocument).toList().single().entries
        assertEquals(setOf("photos.zip", "plain.txt"), entries.map { it.name }.toSet())
        entries.forEach { assertTrue(DocumentsContract.isTreeUri(it.uri)) }
    }

    @Test
    fun `EXTRA_ERROR on the archive cursor is thrown as an IOException with the message`() {
        hosted.stub.listFailure = ArchiveInspection.failed(ArchiveInspection.OUTCOME_UNSUPPORTED, "Unrecognized archive format")
        val root = ArchiveDocumentId.root(archiveUri()).toUri()
        val failure = assertThrows(IOException::class.java) { runBlocking { repository.listChildren(treeUri, root).toList() } }
        assertEquals("This file is not an archive Fylz can open: Unrecognized archive format", failure.message)
        // Memoised: a second listing asks nothing new of the decoder and fails the same way.
        assertThrows(IOException::class.java) { runBlocking { repository.listChildren(treeUri, root).toList() } }
        assertEquals(1, hosted.stub.listCalls.get())
        // Until refresh forgets it.
        hosted.stub.listFailure = null
        hosted.catalog.forgetFailures()
        assertEquals(5, runBlocking { repository.listChildren(treeUri, root).toList() }.single().entries.size)
    }

    @Test
    fun `a vanished archive behind a restored location fails with the source's message, not an empty folder`() {
        val gone = ArchiveDocumentId.root(FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "gone.zip")).toUri()
        val failure = assertThrows(IOException::class.java) { runBlocking { repository.listChildren(treeUri, gone).toList() } }
        assertTrue(failure.message, failure.message!!.startsWith("Unable to read the archive"))
    }
}
