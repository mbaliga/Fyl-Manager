package io.github.mbaliga.fylz.operations

import android.net.Uri
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.storage.ArchiveProviderTestSupport
import io.github.mbaliga.fylz.storage.FylzDocumentsProviderTestBase
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Copy-out (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.5) needs nothing new: the real
 * `FileOperationService.copy` -> `DocNode.load(entryUri)` -> `TransferEngines.forPair` picks
 * `DocumentsTransfer` -> `streamCopy(openInputStream(entryUri))` -> the materialised file; a
 * directory recurses through `DocNode.children`'s non-tree branch; verification re-reads the copy;
 * the journal records Uris as for any copy. Run twice: warm, and after the tree left memory.
 */
class ArchiveCopyOutTest : FylzDocumentsProviderTestBase() {

    private lateinit var hosted: ArchiveProviderTestSupport.Hosted
    private lateinit var service: FileOperationService
    private lateinit var destinationDir: File

    private val readme = "# readme\n".toByteArray()
    private val deep = ByteArray(9_000) { (it * 13 + 5).toByte() }
    private val hello = "hello world".toByteArray()

    private val sample = FakeArchive(
        listOf(
            FakeArchive.Entry("docs/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
            FakeArchive.Entry("docs/readme.md", readme),
            FakeArchive.Entry("docs/deeper/data.bin", deep),
            FakeArchive.Entry("hello.txt", hello),
            FakeArchive.Entry("link", kind = ArchiveEntryInfo.KIND_SYMLINK, linkTarget = "hello.txt"),
        ),
    )

    @Before
    fun setUp() {
        hosted = ArchiveProviderTestSupport.host()
        service = FileOperationService(RuntimeEnvironment.getApplication())
        destinationDir = File(rootDir, "dest").apply { mkdirs() }
    }

    private fun archiveUri(name: String = "photos.zip"): Uri {
        sample.write(File(rootDir, name))
        return FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, name)
    }

    private val destinationTree: Uri get() = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "dest")

    private fun rows(uri: Uri) = DocNode.load(RuntimeEnvironment.getApplication().contentResolver, uri)!!.children(RuntimeEnvironment.getApplication().contentResolver)

    @Test
    fun `an entry and a nested directory copy out byte-identical through the real transfer path, warm and cold`() = runBlocking {
        val root = ArchiveDocumentId.root(archiveUri()).toUri()
        val top = rows(root)
        val docsUri = top.first { it.name == "docs" }.uri
        val helloUri = top.first { it.name == "hello.txt" }.uri

        val written = service.copy(listOf(helloUri, docsUri), destinationTree)
        assertEquals(2, written.size)
        assertArrayEquals(hello, File(destinationDir, "hello.txt").readBytes())
        assertArrayEquals(readme, File(destinationDir, "docs/readme.md").readBytes())
        assertArrayEquals(deep, File(destinationDir, "docs/deeper/data.bin").readBytes())
        assertTrue(destinationDir.walk().none { it.name.startsWith(".fylz-part") })
        // The journal recorded the copy with the archive Uris as sources.
        val operation = service.operations().single()
        assertEquals(FileOperationType.COPY, operation.type)
        assertEquals(OperationState.SUCCEEDED, operation.state)
        assertEquals(setOf(helloUri, docsUri), operation.items.map { it.source }.toSet())
        assertEquals(3, hosted.stub.extractCalls.get())

        // Cold: the tree is gone from memory; the disk listing and the entry cache serve the copy.
        hosted.coldCatalog()
        val again = File(rootDir, "dest2").apply { mkdirs() }
        service.copy(listOf(docsUri), FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "dest2"))
        assertArrayEquals(readme, File(again, "docs/readme.md").readBytes())
        assertArrayEquals(deep, File(again, "docs/deeper/data.bin").readBytes())
        assertEquals("no second listing", 1, hosted.stub.listCalls.get())
        assertEquals("materialised entries were reused", 3, hosted.stub.extractCalls.get())
    }

    @Test
    fun `a directory holding a refused entry fails the directory copy, as for any unreadable child, and leaves nothing behind`() = runBlocking {
        val root = ArchiveDocumentId.root(archiveUri()).toUri()
        val failure = assertThrows(Exception::class.java) { runBlocking { service.copy(listOf(root), destinationTree) } }
        assertTrue(failure.message, failure.message!!.contains("Links and special files cannot be opened"))
        assertTrue("the failed directory copy was rolled back", destinationDir.listFiles().isNullOrEmpty())
        // The readable entries alone copy fine.
        val helloUri = rows(root).first { it.name == "hello.txt" }.uri
        service.copy(listOf(helloUri), destinationTree)
        assertArrayEquals(hello, File(destinationDir, "hello.txt").readBytes())
    }
}
