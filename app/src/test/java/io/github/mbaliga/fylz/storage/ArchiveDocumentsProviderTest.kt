package io.github.mbaliga.fylz.storage

import android.Manifest
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveEntryCache
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.archive.FakeArchive
import io.github.mbaliga.fylz.decoder.ArchiveEntryInfo
import io.github.mbaliga.fylz.decoder.ArchiveInspection
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.operations.DocNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/**
 * `ArchiveDocumentsProvider` (docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md section 2.1) attached with
 * the manifest's own `<provider>` attributes ([ArchiveProviderTestSupport.manifestProviderInfo]),
 * over the hosted file provider and [io.github.mbaliga.fylz.archive.FakeArchiveDecoder]:
 * `queryDocument` root and entries, `queryChildDocuments` cold and with `EXTRA_ERROR`,
 * `openDocument("r")` seekable and `"w"` refused, every write method refused, `isChildDocument`,
 * the id contract through the resolver, and `rootUri` to the depth bound.
 */
class ArchiveDocumentsProviderTest : FylzDocumentsProviderTestBase() {

    private val resolver: ContentResolver get() = RuntimeEnvironment.getApplication().contentResolver
    private lateinit var hosted: ArchiveProviderTestSupport.Hosted

    private val big = ByteArray(3_000) { (it * 7).toByte() }
    private val sample = FakeArchive(
        listOf(
            FakeArchive.Entry("docs/", kind = ArchiveEntryInfo.KIND_DIRECTORY),
            FakeArchive.Entry("docs/readme.md", "# readme\n".toByteArray(), mtime = 1_600_000_000L),
            FakeArchive.Entry("hello.txt", "hello world".toByteArray()),
            FakeArchive.Entry("images/pixel.png", big),
            FakeArchive.Entry("link", kind = ArchiveEntryInfo.KIND_SYMLINK, linkTarget = "hello.txt"),
        ),
    )

    @Before
    fun host() {
        hosted = ArchiveProviderTestSupport.host()
    }

    private fun archiveUri(name: String = "photos.zip", archive: FakeArchive = sample): Uri {
        archive.write(File(rootDir, name))
        return FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, name)
    }

    private fun rootUri(source: Uri) = ArchiveDocumentId.root(source).toUri()

    private fun children(uri: Uri): List<DocNode> = DocNode.load(resolver, uri)!!.children(resolver)

    private fun entry(uri: Uri, name: String = uri.lastPathSegment ?: "x", kind: EntryKind = EntryKind.ARCHIVE) =
        FileEntry(uri, name, "application/zip", 10L, 0L, 0, kind)

    @Test
    fun `the manifest declares the provider exported, granting Uri permissions, behind MANAGE_DOCUMENTS, with no DocumentsUI filter`() {
        val info = ArchiveProviderTestSupport.manifestProviderInfo(RuntimeEnvironment.getApplication())
        assertEquals("io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider", info.name)
        assertEquals("io.github.mbaliga.fylz.archives", info.authority)
        assertEquals(ArchiveDocumentsProvider.AUTHORITY, info.authority)
        assertTrue(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, info.readPermission)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, info.writePermission)
        // attachInfo accepted it (the @Before attached the provider with exactly this info).
        assertNotNull(hosted.provider.context)
    }

    @Test
    fun `queryDocument describes the root as a folder named after the archive file`() {
        val source = archiveUri()
        val node = DocNode.load(resolver, rootUri(source))!!
        assertEquals("photos.zip", node.name)
        assertTrue(node.isDirectory)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, node.mimeType)
        assertEquals(0, node.flags)
        assertEquals(rootUri(source), node.uri)
        assertEquals(1, hosted.stub.listCalls.get())
    }

    @Test
    fun `queryChildDocuments cold lists the root in archive order with unique non-tree Uris, and describes entries`() {
        val source = archiveUri()
        val rows = children(rootUri(source))
        assertEquals(listOf("docs", "hello.txt", "images", "link"), rows.map { it.name })
        assertEquals(rows.size, rows.map { it.uri }.toSet().size)
        rows.forEach { row ->
            assertEquals(ArchiveDocumentsProvider.AUTHORITY, row.uri.authority)
            assertFalse(DocumentsContract.isTreeUri(row.uri))
            assertEquals(0, row.flags)
        }
        val docs = rows[0]
        assertTrue(docs.isDirectory)
        assertEquals(0, ArchiveDocumentId.parse(docs.uri).ordinal)
        val images = rows[2]
        assertTrue(images.isDirectory)
        assertEquals("implicit directories carry the implicit ordinal", ArchiveDocumentId.IMPLICIT_ORDINAL, ArchiveDocumentId.parse(images.uri).ordinal)
        val hello = rows[1]
        assertEquals("text/plain", hello.mimeType)
        assertEquals(11L, hello.size)
        assertEquals(1_577_836_800_000L, hello.lastModified)
        assertEquals(2, ArchiveDocumentId.parse(hello.uri).ordinal)
        // One level down, through the entry's own Uri.
        val inDocs = children(docs.uri)
        assertEquals(listOf("readme.md"), inDocs.map { it.name })
        assertEquals(1_600_000_000_000L, inDocs.single().lastModified)
        assertEquals("docs/readme.md", ArchiveDocumentId.parse(inDocs.single().uri).path)
        assertEquals("application/octet-stream", DocNode.load(resolver, rows[3].uri)!!.mimeType.let { if (it == "application/octet-stream") it else it })
        assertEquals("image/png", DocNode.load(resolver, children(images.uri).single().uri)!!.mimeType)
        assertEquals("one listing served every query", 1, hosted.stub.listCalls.get())
        // And getType goes through the same table.
        assertEquals("text/plain", resolver.getType(hello.uri))
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, resolver.getType(rootUri(source)))
    }

    @Test
    fun `a cold process lists from the disk copy, not the decoder`() {
        val source = archiveUri()
        children(rootUri(source))
        hosted.coldCatalog()
        assertEquals(listOf("docs", "hello.txt", "images", "link"), children(rootUri(source)).map { it.name })
        assertEquals(1, hosted.stub.listCalls.get())
    }

    @Test
    fun `a listing failure is EXTRA_ERROR on a rowless cursor, and the root document is not found`() {
        hosted.stub.listFailure = ArchiveInspection.failed(ArchiveInspection.OUTCOME_CORRUPT, "Truncated input file")
        val source = archiveUri()
        val childrenUri = DocumentsContract.buildChildDocumentsUri(ArchiveDocumentsProvider.AUTHORITY, ArchiveDocumentId.root(source).encode())
        resolver.query(childrenUri, null, null as Bundle?, null)!!.use { cursor ->
            assertEquals(0, cursor.count)
            assertEquals("The archive is damaged or could not be read: Truncated input file", cursor.extras.getString(DocumentsContract.EXTRA_ERROR))
        }
        // queryDocument reports the same failure as "not found" (a null cursor through the resolver).
        assertNull(DocNode.load(resolver, rootUri(source)))
        assertEquals("memoised", 1, hosted.stub.listCalls.get())
    }

    @Test
    fun `openDocument in read mode returns a seekable descriptor of the materialised entry`() {
        val source = archiveUri()
        val png = children(children(rootUri(source))[2].uri).single()
        resolver.openFileDescriptor(png.uri, "r")!!.use { pfd ->
            assertEquals(big.size.toLong(), pfd.statSize)
            val channel = FileInputStream(pfd.fileDescriptor).channel
            channel.position(2_000L)
            val buffer = ByteBuffer.allocate(1_000)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            assertArrayEquals(big.copyOfRange(2_000, 3_000), buffer.array())
        }
        assertArrayEquals("hello world".toByteArray(), resolver.openInputStream(children(rootUri(source))[1].uri)!!.use { it.readBytes() })
        assertEquals(2, hosted.stub.extractCalls.get())
    }

    @Test
    fun `openDocument refuses write modes, roots, links and hostile ids as FileNotFoundException`() {
        val source = archiveUri()
        val rows = children(rootUri(source))
        assertThrows(FileNotFoundException::class.java) { hosted.provider.openDocument(ArchiveDocumentId.parse(rows[1].uri).encode(), "w", null) }
        assertThrows(FileNotFoundException::class.java) { hosted.provider.openDocument(ArchiveDocumentId.parse(rows[1].uri).encode(), "rw", null) }
        assertThrows(FileNotFoundException::class.java) { hosted.provider.openDocument(ArchiveDocumentId.root(source).encode(), "r", null) }
        val links = assertThrows(FileNotFoundException::class.java) { hosted.provider.openDocument(ArchiveDocumentId.parse(rows[3].uri).encode(), "r", null) }
        assertEquals(ArchiveEntryCache.LINKS_REFUSED, links.message)
        assertThrows(FileNotFoundException::class.java) { hosted.provider.openDocument("not-an-id", "r", null) }
        // Through the resolver a hostile id is a document that does not exist.
        assertNull(DocNode.load(resolver, DocumentsContract.buildDocumentUri(ArchiveDocumentsProvider.AUTHORITY, "%%%")))
        // A stale ordinal (the id names a different header than the tree has) is not found either.
        val stale = ArchiveDocumentId.parse(rows[1].uri).let { it.entry(it.ordinal + 5, it.path) }
        assertNull(DocNode.load(resolver, stale.toUri()))
        assertEquals(0, hosted.stub.extractCalls.get())
    }

    @Test
    fun `every write method is refused`() {
        val source = archiveUri()
        val root = ArchiveDocumentId.root(source).encode()
        val hello = ArchiveDocumentId.parse(children(rootUri(source))[1].uri).encode()
        assertThrows(UnsupportedOperationException::class.java) { hosted.provider.createDocument(root, "text/plain", "x") }
        assertThrows(UnsupportedOperationException::class.java) { hosted.provider.deleteDocument(hello) }
        assertThrows(UnsupportedOperationException::class.java) { hosted.provider.renameDocument(hello, "y") }
        assertThrows(UnsupportedOperationException::class.java) { hosted.provider.moveDocument(hello, root, root) }
        assertThrows(UnsupportedOperationException::class.java) { hosted.provider.copyDocument(hello, root) }
        assertThrows(UnsupportedOperationException::class.java) { hosted.provider.removeDocument(hello, root) }
        // And the rows say so: no write flags.
        children(rootUri(source)).forEach { assertFalse(it.canWrite) }
    }

    @Test
    fun `isChildDocument needs the same archive and a path prefix`() {
        val source = archiveUri()
        val root = ArchiveDocumentId.root(source)
        val docs = root.entry(0, "docs")
        val readme = root.entry(1, "docs/readme.md")
        val hello = root.entry(2, "hello.txt")
        val other = ArchiveDocumentId.root(archiveUri("other.zip")).entry(1, "docs/readme.md")
        val provider = hosted.provider
        assertTrue(provider.isChildDocument(root.encode(), docs.encode()))
        assertTrue(provider.isChildDocument(root.encode(), readme.encode()))
        assertTrue(provider.isChildDocument(docs.encode(), readme.encode()))
        assertFalse(provider.isChildDocument(docs.encode(), hello.encode()))
        assertFalse(provider.isChildDocument(hello.encode(), root.encode()))
        assertFalse(provider.isChildDocument(root.encode(), other.encode()))
        assertFalse(provider.isChildDocument(root.encode(), "garbage"))
        assertFalse("`docs/readme.md` is not under `doc`", provider.isChildDocument(root.entry(0, "doc").encode(), readme.encode()))
    }

    @Test
    fun `rootUri builds a top-level root from a file and a nested root from an entry, refusing the fifth level`() {
        val source = archiveUri()
        val root = ArchiveDocumentsProvider.rootUri(entry(source, "photos.zip"))
        assertEquals(ArchiveDocumentId.root(source).toUri(), root)
        val inner = ArchiveDocumentsProvider.rootUri(entry(ArchiveDocumentId.root(source).entry(4, "d/inner.zip").toUri(), "inner.zip"))
        assertEquals(ArchiveDocumentId(source, listOf("d/inner.zip"), ArchiveDocumentId.IMPLICIT_ORDINAL, "").toUri(), inner)
        val depth4 = ArchiveDocumentId(source, listOf("a.zip", "b.zip", "c.zip"), 0, "d.zip").toUri()
        val refused = assertThrows(IllegalArgumentException::class.java) { ArchiveDocumentsProvider.rootUri(entry(depth4, "d.zip")) }
        assertEquals(ArchiveDocumentId.DEPTH_REFUSED, refused.message)
        assertTrue(ArchiveDocumentsProvider.isArchiveUri(root))
        assertFalse(ArchiveDocumentsProvider.isArchiveUri(source))
    }

    @Test
    fun `a nested archive browses through its own root Uri and lists once per level`() {
        val inner = FakeArchive(listOf(FakeArchive.Entry("inner.txt", "inner".toByteArray())))
        val source = archiveUri("outer.zip", FakeArchive(listOf(FakeArchive.Entry("nested.zip", FakeArchive.bytes(inner)))))
        val nestedEntry = children(rootUri(source)).single()
        val nestedRoot = ArchiveDocumentsProvider.rootUri(entry(nestedEntry.uri, "nested.zip"))
        val innerRows = children(nestedRoot)
        assertEquals(listOf("inner.txt"), innerRows.map { it.name })
        assertEquals("inner", resolver.openInputStream(innerRows.single().uri)!!.use { String(it.readBytes()) })
        assertEquals(2, hosted.stub.listCalls.get())
        assertEquals(ArchiveRef(source, listOf("nested.zip")), ArchiveDocumentId.parse(innerRows.single().uri).archive)
    }

    @Test
    fun `queryRoots is empty`() {
        resolver.query(DocumentsContract.buildRootsUri(ArchiveDocumentsProvider.AUTHORITY), null, null as Bundle?, null)!!.use { cursor ->
            assertEquals(0, cursor.count)
        }
    }
}
