package io.github.mbaliga.fylz.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * One concrete backend under contract test.
 *
 * Seeding and deleting happen OUT-OF-BAND — direct `java.io.File` writes for the File backend,
 * direct node-tree mutation for the fake — because half the contract is how a provider reports a
 * world that changed behind its back. Creating through the provider would test creation twice
 * and external change never.
 *
 * The capability knobs ([caseInsensitiveNames], [canServeNullLastModified], [failsCrossTreeMove])
 * are how a backend states which optional behaviors it promises; the contract tests gate on them
 * with `assumeTrue`, so a skipped test names the missing capability instead of silently passing.
 */
interface BackendFixture {
    val resolver: ContentResolver

    /** Tree URI of a writable directory dedicated to the running test. */
    val treeUri: Uri

    /** A second, disjoint tree on the same backend; null when the backend serves only one. */
    val secondTreeUri: Uri?
        get() = null

    /** True when the backend treats names differing only in case as the same entry. */
    val caseInsensitiveNames: Boolean

    /** True when the backend can serve a row whose `COLUMN_LAST_MODIFIED` is null. */
    val canServeNullLastModified: Boolean

    /** True when a move between [treeUri] and [secondTreeUri] must fail cleanly. */
    val failsCrossTreeMove: Boolean

    /** Creates a file directly under the tree root, bypassing the provider entirely. */
    fun seedFile(name: String, content: ByteArray = ByteArray(0))

    /** Creates a folder directly under the tree root, bypassing the provider entirely. */
    fun seedFolder(name: String)

    /** Removes the named entry (recursively) behind the provider's back. */
    fun deleteOutOfBand(name: String)

    /** Stamps the entry's timestamp out-of-band; false when the environment cannot. */
    fun setLastModifiedOutOfBand(name: String, epochMillis: Long): Boolean

    /** Seeds a file the provider will serve with a null `COLUMN_LAST_MODIFIED`. */
    fun seedFileWithNullLastModified(name: String) {
        throw UnsupportedOperationException(
            "This backend always stamps LAST_MODIFIED; gate with canServeNullLastModified.",
        )
    }

    /**
     * Makes the named entry read-only out-of-band. Returns false when the environment cannot
     * enforce it (e.g. `java.io.File.setWritable(false)` is a no-op for root), so callers gate
     * with `assumeTrue` rather than asserting against a permission bit that never took.
     */
    fun makeReadOnly(name: String): Boolean

    /**
     * Seeds a file that REPORTS [sizeBytes] without materialising the bytes (sparse file or
     * metadata); false when the backend cannot. Exists so the >2 GiB size-reporting contract
     * costs no disk.
     */
    fun seedHugeFile(name: String, sizeBytes: Long): Boolean = false
}

/**
 * The behavioral gate every storage provider must pass (WP-0.7).
 *
 * The plan's acceptance law #8: provider quirks become capabilities, policies or adapters with
 * tests — never `if` branches in the UI. This suite is where that law is enforced. Any future
 * backend (network providers, a cloud adapter) gets a [BackendFixture] and inherits every test
 * here unchanged; a behavior only some backends can honor is a capability knob on the fixture,
 * gated with `assumeTrue`, so the suite records *which* providers promise it.
 *
 * Everything runs through the public `DocumentsContract` client surface — the same calls
 * `DocumentRepository`, `FileOperationService` and the rest of the app make — because that
 * surface is the contract being guarded, not any provider's internals.
 */
abstract class StorageBackendContractTest {

    protected abstract val fixture: BackendFixture

    // ------------------------------------------------------------- plumbing

    protected class DocumentRow(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long?,
        val lastModified: Long?,
        val flags: Int,
    )

    protected fun rootDocumentUri(treeUri: Uri = fixture.treeUri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    /**
     * Every query goes through the Bundle overload. `DocumentsProvider` finals the legacy
     * five-argument `query` into "Pre-Android-O query format unsupported", and the
     * `(selection, selectionArgs, sortOrder)` resolver overloads land exactly there.
     */
    private fun query(uri: Uri): Cursor {
        val queryArgs: Bundle? = null
        val signal: CancellationSignal? = null
        return requireNotNull(fixture.resolver.query(uri, DOCUMENT_PROJECTION, queryArgs, signal)) {
            "null cursor from $uri"
        }
    }

    protected fun childrenCursor(parentDocumentUri: Uri): Cursor =
        query(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDocumentUri,
                DocumentsContract.getDocumentId(parentDocumentUri),
            ),
        )

    protected fun listChildren(parentDocumentUri: Uri): List<DocumentRow> =
        childrenCursor(parentDocumentUri).use { cursor ->
            buildList { while (cursor.moveToNext()) add(readRow(cursor)) }
        }

    protected fun documentRow(documentUri: Uri): DocumentRow =
        query(documentUri).use { cursor ->
            assertTrue("expected a row for $documentUri", cursor.moveToFirst())
            readRow(cursor)
        }

    private fun readRow(cursor: Cursor): DocumentRow {
        val size = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        val modified = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return DocumentRow(
            documentId = cursor.getString(
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            ),
            displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            ),
            mimeType = cursor.getString(
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
            ),
            size = if (cursor.isNull(size)) null else cursor.getLong(size),
            lastModified = if (cursor.isNull(modified)) null else cursor.getLong(modified),
            flags = cursor.getInt(
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS),
            ),
        )
    }

    protected fun createFile(
        parentDocumentUri: Uri,
        displayName: String,
        mimeType: String = "text/plain",
        content: ByteArray? = null,
    ): Uri {
        val uri = DocumentsContract.createDocument(fixture.resolver, parentDocumentUri, mimeType, displayName)
        assertNotNull("createDocument must produce a document for $displayName", uri)
        if (content != null && content.isNotEmpty()) writeBytes(uri!!, content)
        return uri!!
    }

    protected fun createFolder(parentDocumentUri: Uri, displayName: String): Uri {
        val uri = DocumentsContract.createDocument(
            fixture.resolver,
            parentDocumentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName,
        )
        assertNotNull("createDocument must produce a folder for $displayName", uri)
        return uri!!
    }

    protected fun writeBytes(documentUri: Uri, content: ByteArray) {
        requireNotNull(fixture.resolver.openOutputStream(documentUri)) {
            "null output stream for $documentUri"
        }.use { it.write(content) }
    }

    protected fun readBytes(documentUri: Uri): ByteArray =
        requireNotNull(fixture.resolver.openInputStream(documentUri)) {
            "null input stream for $documentUri"
        }.use { it.readBytes() }

    /**
     * Failure normalized to null. The contract allows a provider to signal a refused rename as
     * either an exception or a null result; what it may never do is overwrite the target.
     */
    protected fun renameOrNull(documentUri: Uri, displayName: String): Uri? = try {
        DocumentsContract.renameDocument(fixture.resolver, documentUri, displayName)
    } catch (refused: Exception) {
        null
    }

    /** Failure normalized to null, same reasoning as [renameOrNull]. */
    protected fun moveOrNull(sourceDocumentUri: Uri, sourceParentUri: Uri, targetParentUri: Uri): Uri? = try {
        DocumentsContract.moveDocument(fixture.resolver, sourceDocumentUri, sourceParentUri, targetParentUri)
    } catch (refused: Exception) {
        null
    }

    /**
     * The document URI without its tree segment. Tree containment on a deleted id degenerates
     * into `SecurityException` (`isChildDocument` cannot vouch for a document that no longer
     * exists); the `FileNotFoundException` contract lives on the document itself.
     */
    private fun plainDocumentUri(documentUri: Uri): Uri =
        DocumentsContract.buildDocumentUri(
            requireNotNull(documentUri.authority),
            DocumentsContract.getDocumentId(documentUri),
        )

    protected fun assertNoLongerOpenable(documentUri: Uri) {
        try {
            fixture.resolver.openInputStream(plainDocumentUri(documentUri))?.close()
            fail("expected FileNotFoundException opening deleted document $documentUri")
        } catch (expected: FileNotFoundException) {
            // A deleted document is gone, not an empty stream.
        }
    }

    // ------------------------------------------------------------- contract

    @Test
    fun `a created file appears in the children listing with its name mime and size`() {
        val root = rootDocumentUri()
        createFile(root, "hello.txt", content = "hello world".toByteArray())
        createFolder(root, "docs")

        val children = listChildren(root)
        val file = children.single { it.displayName == "hello.txt" }
        assertEquals("text/plain", file.mimeType)
        assertEquals(11L, file.size)

        val folder = children.single { it.displayName == "docs" }
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, folder.mimeType)
        assertNull("directories have no byte size", folder.size)
    }

    @Test
    fun `creating a duplicate name yields a distinct document and keeps both`() {
        val root = rootDocumentUri()
        val first = createFile(root, "dup.txt")
        val second = createFile(root, "dup.txt")

        // "Create something" is the framework contract; overwriting a user's file is not.
        assertNotEquals(
            DocumentsContract.getDocumentId(first),
            DocumentsContract.getDocumentId(second),
        )
        val names = listChildren(root).map { it.displayName }.filter { it.startsWith("dup") }
        assertEquals(2, names.size)
        assertEquals("both survivors need distinct names", 2, names.distinct().size)
    }

    @Test
    fun `rename succeeds and the old name leaves the listing`() {
        val root = rootDocumentUri()
        val original = createFile(root, "old.txt", content = "body".toByteArray())

        val renamed = DocumentsContract.renameDocument(fixture.resolver, original, "new.txt")

        assertNotNull(renamed)
        assertEquals("new.txt", documentRow(renamed!!).displayName)
        val names = listChildren(root).map { it.displayName }
        assertTrue("new.txt" in names)
        assertFalse("old.txt" in names)
        assertArrayEquals("body".toByteArray(), readBytes(renamed))
    }

    @Test
    fun `rename onto an existing sibling name fails without clobbering either file`() {
        val root = rootDocumentUri()
        val keep = createFile(root, "keep.txt", content = "keep".toByteArray())
        val other = createFile(root, "other.txt", content = "other".toByteArray())

        assertNull(renameOrNull(other, "keep.txt"))

        val names = listChildren(root).map { it.displayName }
        assertTrue("keep.txt" in names)
        assertTrue("other.txt" in names)
        assertArrayEquals("keep".toByteArray(), readBytes(keep))
        assertArrayEquals("other".toByteArray(), readBytes(other))
    }

    @Test
    fun `case-only rename onto a sibling collides when names are case-insensitive`() {
        assumeTrue("backend resolves names case-sensitively", fixture.caseInsensitiveNames)
        val root = rootDocumentUri()
        createFile(root, "shout.txt")
        val quiet = createFile(root, "quiet.txt")

        assertNull(renameOrNull(quiet, "SHOUT.TXT"))

        val names = listChildren(root).map { it.displayName }
        assertTrue("shout.txt" in names)
        assertTrue("quiet.txt" in names)
    }

    @Test
    fun `case-only rename onto a sibling succeeds when names are case-sensitive`() {
        assumeTrue("backend folds name case", !fixture.caseInsensitiveNames)
        val root = rootDocumentUri()
        createFile(root, "shout.txt")
        val quiet = createFile(root, "quiet.txt")

        val renamed = DocumentsContract.renameDocument(fixture.resolver, quiet, "SHOUT.TXT")

        assertNotNull(renamed)
        val names = listChildren(root).map { it.displayName }
        assertTrue("shout.txt" in names)
        assertTrue("SHOUT.TXT" in names)
        assertFalse("quiet.txt" in names)
    }

    @Test
    fun `move within the tree relocates the child`() {
        val root = rootDocumentUri()
        val dest = createFolder(root, "dest")
        val note = createFile(root, "note.txt", content = "note".toByteArray())

        val moved = DocumentsContract.moveDocument(fixture.resolver, note, root, dest)

        assertNotNull(moved)
        assertFalse("note.txt" in listChildren(root).map { it.displayName })
        assertTrue("note.txt" in listChildren(dest).map { it.displayName })
        assertArrayEquals("note".toByteArray(), readBytes(moved!!))
    }

    @Test
    fun `move onto an existing target name fails and leaves both files in place`() {
        val root = rootDocumentUri()
        val dest = createFolder(root, "dest")
        val occupied = createFile(dest, "clash.txt", content = "target".toByteArray())
        val source = createFile(root, "clash.txt", content = "source".toByteArray())

        assertNull(moveOrNull(source, root, dest))

        assertTrue("clash.txt" in listChildren(root).map { it.displayName })
        assertArrayEquals("target".toByteArray(), readBytes(occupied))
        assertArrayEquals("source".toByteArray(), readBytes(source))
    }

    @Test
    fun `a cross-tree move fails cleanly instead of corrupting either tree`() {
        assumeTrue("backend does not promise cross-tree move failure", fixture.failsCrossTreeMove)
        val secondTree = requireNotNull(fixture.secondTreeUri) {
            "failsCrossTreeMove fixtures must expose a second tree"
        }
        val sourceRoot = rootDocumentUri()
        val targetRoot = rootDocumentUri(secondTree)
        val wanderer = createFile(sourceRoot, "wanderer.txt", content = "payload".toByteArray())

        assertNull(moveOrNull(wanderer, sourceRoot, targetRoot))

        // Clean failure: the source is intact where it was, and nothing landed at the target.
        assertTrue("wanderer.txt" in listChildren(sourceRoot).map { it.displayName })
        assertFalse("wanderer.txt" in listChildren(targetRoot).map { it.displayName })
        assertArrayEquals("payload".toByteArray(), readBytes(wanderer))
    }

    @Test
    fun `a deleted document leaves the listing and can no longer be opened`() {
        val root = rootDocumentUri()
        val doomed = createFile(root, "doomed.txt", content = "bytes".toByteArray())

        assertTrue(DocumentsContract.deleteDocument(fixture.resolver, doomed))

        assertFalse("doomed.txt" in listChildren(root).map { it.displayName })
        assertNoLongerOpenable(doomed)
    }

    @Test
    fun `recursive delete of a non-empty directory removes its children`() {
        val root = rootDocumentUri()
        val bundle = createFolder(root, "bundle")
        val inner = createFile(bundle, "inner.txt", content = "inner".toByteArray())
        val nested = createFolder(bundle, "nested")
        createFile(nested, "deep.txt", content = "deep".toByteArray())

        assertTrue(DocumentsContract.deleteDocument(fixture.resolver, bundle))

        assertFalse("bundle" in listChildren(root).map { it.displayName })
        assertNoLongerOpenable(inner)
    }

    @Test
    fun `NFC and NFD spellings of one visible name coexist and round-trip un-normalized`() {
        // Identical on screen, different code points. A provider that normalizes en route
        // would silently merge two real files -- both spellings must come back byte-exact.
        // Escapes rather than literals, so no editor or formatter can ever collapse the two
        // spellings into one invisible string.
        val nfc = "caf\u00E9.txt"
        val nfd = "cafe\u0301.txt"
        val root = rootDocumentUri()
        val nfcUri = createFile(root, nfc, content = "nfc".toByteArray())
        val nfdUri = createFile(root, nfd, content = "nfd".toByteArray())

        assertNotEquals(
            DocumentsContract.getDocumentId(nfcUri),
            DocumentsContract.getDocumentId(nfdUri),
        )
        val names = listChildren(root).map { it.displayName }
        assertTrue(nfc in names)
        assertTrue(nfd in names)
        assertArrayEquals("nfc".toByteArray(), readBytes(nfcUri))
        assertArrayEquals("nfd".toByteArray(), readBytes(nfdUri))
    }

    @Test
    fun `emoji and RTL-marker names survive create list and read`() {
        val name = "\uD83C\uDF89 report \u200F\u2615.txt"
        val root = rootDocumentUri()
        val uri = createFile(root, name, content = "party".toByteArray())

        assertTrue(name in listChildren(root).map { it.displayName })
        assertArrayEquals("party".toByteArray(), readBytes(uri))
    }

    @Test
    fun `a zero-byte file reports size zero and reads back empty`() {
        val root = rootDocumentUri()
        val empty = createFile(root, "empty.bin", mimeType = "application/octet-stream")

        val row = listChildren(root).single { it.displayName == "empty.bin" }
        assertEquals(0L, row.size)
        assertEquals(0, readBytes(empty).size)
    }

    @Test
    fun `a zero-epoch last modified is served without incident`() {
        val root = rootDocumentUri()
        createFile(root, "epoch.txt")
        assumeTrue(
            "environment cannot stamp a zero-epoch timestamp",
            fixture.setLastModifiedOutOfBand("epoch.txt", 0L),
        )

        val row = listChildren(root).single { it.displayName == "epoch.txt" }
        assertEquals(0L, row.lastModified)
    }

    @Test
    fun `a null last modified is surfaced honestly instead of crashing the listing`() {
        assumeTrue("backend always stamps LAST_MODIFIED", fixture.canServeNullLastModified)
        fixture.seedFileWithNullLastModified("nullstamp.txt")

        // Honesty over invention: no crash, and no fabricated timestamp either.
        val row = listChildren(rootDocumentUri()).single { it.displayName == "nullstamp.txt" }
        assertNull(row.lastModified)
    }

    @Test
    fun `an open children cursor is a stable snapshot and a requery converges`() {
        fixture.seedFile("snap-keep.txt")
        fixture.seedFile("snap-victim.txt")
        val root = rootDocumentUri()

        childrenCursor(root).use { snapshot ->
            val countBefore = snapshot.count

            fixture.seedFile("snap-late.txt")
            fixture.deleteOutOfBand("snap-victim.txt")

            // The cursor a screen is iterating must not change shape mid-iteration...
            assertEquals(countBefore, snapshot.count)
        }

        // ...and a fresh query must tell the new truth, not replay the stale one.
        val converged = listChildren(root).map { it.displayName }
        assertTrue("snap-keep.txt" in converged)
        assertTrue("snap-late.txt" in converged)
        assertFalse("snap-victim.txt" in converged)
    }

    @Test
    fun `a 300-character name is truncated to at most 255 UTF-16 units and created`() {
        // ASCII on purpose: the 255-unit boundary with multi-byte names is a known, separately
        // documented provider bug and is deliberately not pinned here as expected behavior.
        val requested = "l".repeat(296) + ".txt"
        val root = rootDocumentUri()
        val created = createFile(root, requested)

        val createdName = documentRow(created).displayName
        assertTrue(
            "created name must fit in 255 UTF-16 units, was ${createdName.length}",
            createdName.length <= 255,
        )
        assertTrue("truncation must keep the leading name", createdName.startsWith("l".repeat(64)))
        assertTrue(createdName in listChildren(root).map { it.displayName })
    }

    @Test
    fun `a display name carrying path separators cannot traverse out of its parent`() {
        // The path-escape shape of the reserved-character problem, which is the shape that
        // matters: a provider may refuse the name or serve it defanged, but the document it
        // returns must be a DIRECT child of the requested parent — never a nested hierarchy,
        // never an entry outside it. The containment check is the membership of the returned
        // document id in the parent's own listing; a provider that quietly created `a/b.txt`
        // as a folder `a` holding `b.txt` would return an id the parent listing does not have.
        val root = rootDocumentUri()
        val requested = "../escape/..\\a/b.txt"
        val created = try {
            DocumentsContract.createDocument(fixture.resolver, root, "text/plain", requested)
        } catch (refused: Exception) {
            null
        }
        assumeTrue("backend refuses separator-bearing names outright, which is also safe", created != null)

        val ids = listChildren(root).map { it.documentId }
        assertTrue(
            "created document must be a direct child of the requested parent",
            DocumentsContract.getDocumentId(created!!) in ids,
        )
        writeBytes(created, "contained".toByteArray())
        assertArrayEquals("contained".toByteArray(), readBytes(created))
    }

    @Test
    fun `provider-level copy is unsupported so copying stays client-side`() {
        // Neither backend overrides copyDocument, and the app's copy path is deliberately a
        // client-side stream copy through the operation journal. Pinning the refusal keeps
        // that arrangement honest: if a provider ever grows native copy, this test is the
        // prompt to add a capability knob and route the operation engine through it —
        // not to let two copy paths exist unannounced.
        val root = rootDocumentUri()
        val source = createFile(root, "copy-src.txt", content = "x".toByteArray())

        val copied = try {
            DocumentsContract.copyDocument(fixture.resolver, source, root)
        } catch (expected: Exception) {
            null
        }

        assertNull("copyDocument must be refused; copying is the operation engine's job", copied)
    }

    @Test
    fun `a file larger than two gigabytes reports its true size`() {
        // COLUMN_SIZE is a long; a backend that funnels it through Int truncates or goes
        // negative right where files stop being toys. Sparse on disk, so this costs no I/O.
        val fiveGiB = 5L * 1024 * 1024 * 1024
        assumeTrue(
            "backend cannot seed a sparse huge file",
            fixture.seedHugeFile("huge.bin", fiveGiB),
        )

        val row = listChildren(rootDocumentUri()).single { it.displayName == "huge.bin" }
        assertEquals(fiveGiB, row.size)
    }

    @Test
    fun `a read-only file drops its write and rename flags`() {
        val root = rootDocumentUri()
        createFile(root, "locked.txt", content = "x".toByteArray())
        createFile(root, "free.txt", content = "y".toByteArray())
        assumeTrue(
            "environment cannot enforce read-only (running as root?)",
            fixture.makeReadOnly("locked.txt"),
        )

        val rows = listChildren(root)
        val locked = rows.single { it.displayName == "locked.txt" }
        val free = rows.single { it.displayName == "free.txt" }

        assertEquals(0, locked.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
        assertEquals(0, locked.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
        // The control row proves the flags are computed, not simply always absent.
        assertNotEquals(0, free.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
        assertNotEquals(0, free.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
    }

    private companion object {
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
