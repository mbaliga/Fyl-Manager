package io.github.mbaliga.fylz.operations

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.mbaliga.fylz.core.operations.ConflictPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/**
 * Pins the relocation-propagation fix from the caller's side: `onItemRelocated` is the only
 * hook `FylzV1App` has for keeping favorites, tags, history and the Shelf pointed at a moved
 * item's real URI, so it must fire exactly for succeeded moves -- with the correct source and
 * destination pair -- and never for a copy or a skipped conflict.
 *
 * Runs against [RelocationFixtureProvider], a hand-rolled fixture rather than the shared
 * `FakeSafDocumentsProvider`/`FylzFilesDocumentsProvider`: both extend `DocumentsProvider`,
 * whose own deprecated five-argument `query()` override unconditionally throws
 * ("Pre-Android-O query format not supported"), and Robolectric's `ShadowContentResolver`
 * forwards that exact overload straight to the provider instead of upgrading it the way a real
 * cross-process call would (see `FileHistoryStore.kt:330-333`, `docs/worklog/WP-0.7.md`) --
 * fatal here, since androidx `DocumentFile.exists()/length()/canWrite()/findFile()` all still
 * query through that overload. Implementing the same `DocumentsContract` surface directly on a
 * plain `ContentProvider` sidesteps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileOperationServiceRelocationTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var provider: RelocationFixtureProvider

    @Before
    fun registerProvider() {
        val providerInfo = ProviderInfo().apply {
            authority = RelocationFixtureProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
        }
        provider = Robolectric.buildContentProvider(RelocationFixtureProvider::class.java)
            .create(providerInfo)
            .get()
    }

    private fun seed(name: String, content: String): Uri =
        provider.seed(RelocationFixtureProvider.ROOT_A, name, content.toByteArray())

    private val destinationTree: Uri =
        DocumentsContract.buildTreeDocumentUri(RelocationFixtureProvider.AUTHORITY, RelocationFixtureProvider.ROOT_B)

    private fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test
    fun `a succeeded move fires the relocation callback with the source and its new uri`() = runBlocking {
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val service = FileOperationService(context, onItemRelocated = { old, new -> relocations += old to new })
        val source = seed("note.txt", "body")

        val moved = service.move(listOf(source), destinationTree)

        assertEquals(listOf(source to moved.single()), relocations)
        assertArrayEquals("body".toByteArray(), readBytes(moved.single()))
    }

    @Test
    fun `a copy never fires the relocation callback`() = runBlocking {
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val service = FileOperationService(context, onItemRelocated = { old, new -> relocations += old to new })
        val source = seed("keep.txt", "body")

        service.copy(listOf(source), destinationTree)

        assertTrue("copy must never relocate the source's identity", relocations.isEmpty())
    }

    @Test
    fun `multiple moved items each fire their own correctly paired callback`() = runBlocking {
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val service = FileOperationService(context, onItemRelocated = { old, new -> relocations += old to new })
        val first = seed("a.txt", "a")
        val second = seed("b.txt", "b")

        val moved = service.move(listOf(first, second), destinationTree)

        assertEquals(setOf(first to moved[0], second to moved[1]), relocations.toSet())
    }

    @Test
    fun `a folder with a nested file moves its whole subtree instead of throwing`() = runBlocking {
        // Pins the copyDocument directory-recursion fix: source.listFiles() -- unconditionally
        // UnsupportedOperationException on the SingleDocumentFile every source here opens as --
        // must never be reached for a folder source, files or folders alike.
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val service = FileOperationService(context, onItemRelocated = { old, new -> relocations += old to new })
        val folder = provider.seedFolder(RelocationFixtureProvider.ROOT_A, "notes")
        provider.seed(DocumentsContract.getDocumentId(folder), "note.txt", "body".toByteArray())

        val moved = service.move(listOf(folder), destinationTree)

        assertEquals(listOf(folder to moved.single()), relocations)
        val movedChild = requireNotNull(
            provider.childOf(DocumentsContract.getDocumentId(moved.single()), "note.txt"),
        ) { "note.txt did not land inside the moved folder" }
        assertArrayEquals("body".toByteArray(), readBytes(movedChild))
    }

    @Test
    fun `a conflict-skipped item is never relocated`() = runBlocking {
        // A same-named file already at the destination, with SKIP as the policy, routes this
        // item through the "SKIPPED_CONFLICT" branch instead of ever copying or deleting it.
        provider.seed(RelocationFixtureProvider.ROOT_B, "clash.txt", "target".toByteArray())
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val service = FileOperationService(context, onItemRelocated = { old, new -> relocations += old to new })
        val source = seed("clash.txt", "source")

        service.move(listOf(source), destinationTree, conflictPolicy = ConflictPolicy.SKIP)

        assertTrue("a skipped conflict never relocated anything", relocations.isEmpty())
    }
}

/**
 * A flat, in-memory `DocumentsContract`-shaped fixture standing in for a real SAF backend --
 * see the class doc on [FileOperationServiceRelocationTest] for why this exists instead of the
 * shared `DocumentsProvider`-based fakes. Supports exactly what `FileOperationService` /
 * `androidx.documentfile.provider.DocumentFile` (v1.0.1) exercise: single-level roots, document
 * and children queries, create/rename/delete via `call()`, and streamed reads/writes.
 */
class RelocationFixtureProvider : ContentProvider() {

    private class Node(val id: String, var name: String, val parentId: String?, val blob: File?)

    private val nodes = LinkedHashMap<String, Node>()
    private var nextId = 1

    override fun onCreate(): Boolean {
        nodes[ROOT_A] = Node(ROOT_A, "A", null, null)
        nodes[ROOT_B] = Node(ROOT_B, "B", null, null)
        return true
    }

    fun seed(parentId: String, name: String, content: ByteArray): Uri {
        val id = "node-${nextId++}"
        val blob = File(blobDir(), id).apply { writeBytes(content) }
        nodes[id] = Node(id, name, parentId, blob)
        return documentUri(id)
    }

    /**
     * Tree-shaped, unlike [seed]'s bare document uri -- a directory source needs a tree-rooted
     * uri to list its own children (`DocumentsContract.buildChildDocumentsUriUsingTree` throws
     * on a non-tree uri), matching how every real folder this app hands to copy/move already
     * arrives (`DocumentRepository.listChildren` mints exactly this shape).
     */
    fun seedFolder(rootId: String, name: String): Uri {
        val id = "node-${nextId++}"
        nodes[id] = Node(id, name, rootId, null)
        return DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootId), id)
    }

    /** The bare document uri of [parentId]'s child named [name], or null if there is none. */
    fun childOf(parentId: String, name: String): Uri? =
        nodes.values.firstOrNull { it.parentId == parentId && it.name == name }?.let { documentUri(it.id) }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_PROJECTION)
        val documentId = DocumentsContract.getDocumentId(uri)
        if (uri.pathSegments.lastOrNull() == "children") {
            nodes.values.filter { it.parentId == documentId }.forEach { addRow(cursor, it) }
        } else {
            nodes[documentId]?.let { addRow(cursor, it) }
        }
        return cursor
    }

    override fun getType(uri: Uri): String {
        val node = nodes[DocumentsContract.getDocumentId(uri)] ?: return "application/octet-stream"
        return if (node.blob == null) Document.MIME_TYPE_DIR else "text/plain"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val node = nodes[DocumentsContract.getDocumentId(uri)] ?: throw FileNotFoundException("$uri")
        val blob = node.blob ?: throw FileNotFoundException("$uri is a directory")
        return ParcelFileDescriptor.open(blob, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val documentUri = extras?.getParcelable<Uri>(EXTRA_URI)
        val documentId = documentUri?.let(DocumentsContract::getDocumentId)
        return when (method) {
            METHOD_CREATE_DOCUMENT -> {
                val parentId = documentId ?: return null
                val mimeType = extras.getString(Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
                val displayName = extras.getString(Document.COLUMN_DISPLAY_NAME) ?: "untitled"
                val id = "node-${nextId++}"
                val directory = mimeType == Document.MIME_TYPE_DIR
                val blob = if (directory) null else File(blobDir(), id).apply { createNewFile() }
                nodes[id] = Node(id, displayName, parentId, blob)
                Bundle().apply { putParcelable(EXTRA_URI, documentUri(id)) }
            }
            METHOD_RENAME_DOCUMENT -> {
                val id = documentId ?: return null
                val node = nodes[id] ?: return null
                node.name = extras.getString(Document.COLUMN_DISPLAY_NAME) ?: node.name
                Bundle().apply { putParcelable(EXTRA_URI, documentUri(id)) }
            }
            METHOD_DELETE_DOCUMENT -> {
                val id = documentId ?: return null
                nodes.remove(id)?.blob?.delete()
                Bundle()
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, id)

    private fun blobDir(): File = File(requireNotNull(context).cacheDir, "reloc-fixture-blobs").apply { mkdirs() }

    private fun addRow(cursor: MatrixCursor, node: Node) {
        val directory = node.blob == null
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, node.id)
            add(Document.COLUMN_DISPLAY_NAME, node.name)
            add(Document.COLUMN_MIME_TYPE, if (directory) Document.MIME_TYPE_DIR else "text/plain")
            add(Document.COLUMN_SIZE, if (directory) null else node.blob?.length())
            add(Document.COLUMN_LAST_MODIFIED, 0L)
            add(
                Document.COLUMN_FLAGS,
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE or
                    Document.FLAG_SUPPORTS_RENAME or Document.FLAG_DIR_SUPPORTS_CREATE,
            )
        }
    }

    companion object {
        const val AUTHORITY = "fylz.test.relocation"
        const val ROOT_A = "root-a"
        const val ROOT_B = "root-b"

        // DocumentsContract.{EXTRA_URI, METHOD_CREATE_DOCUMENT, METHOD_RENAME_DOCUMENT,
        // METHOD_DELETE_DOCUMENT} are @hide -- present (and public) on the real framework class
        // ContentResolver.call() dispatches through at runtime, but stripped from the public SDK
        // stub this module compiles against. Pinned here as the literal values the framework's
        // own DocumentsContract.createDocument/renameDocument/deleteDocument pass, confirmed by
        // disassembling the Robolectric android-all jar's ContentResolver.call() invocations.
        private const val EXTRA_URI = "uri"
        private const val METHOD_CREATE_DOCUMENT = "android:createDocument"
        private const val METHOD_RENAME_DOCUMENT = "android:renameDocument"
        private const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"

        private val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
