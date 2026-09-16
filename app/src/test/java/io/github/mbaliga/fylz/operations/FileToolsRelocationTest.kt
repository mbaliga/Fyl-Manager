package io.github.mbaliga.fylz.operations

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `FileTools.executeBatchRename`'s half of the relocation-propagation fix: `onItemRelocated`
 * fires per item, from the final-name loop, once that item's rename has actually landed --
 * never for a rename set that never committed.
 *
 * Runs against [RenamingFixtureProvider], a hand-rolled fixture rather than the shared
 * `FakeSafDocumentsProvider`/`FylzFilesDocumentsProvider`: both extend `DocumentsProvider`,
 * whose own deprecated five-argument `query()` override unconditionally throws
 * ("Pre-Android-O query format not supported"), and Robolectric's `ShadowContentResolver`
 * forwards that exact overload straight to the provider instead of upgrading it the way a real
 * cross-process call would (see `FileHistoryStore.kt:330-333`) -- fatal here, since androidx
 * `DocumentFile.exists()/length()/canWrite()/findFile()` all still query through that overload.
 * Implementing the same `DocumentsContract` surface directly on a plain `ContentProvider`
 * sidesteps it -- the same trick `FileOperationServiceRelocationTest`'s
 * `RelocationFixtureProvider` uses, kept separate here because this fixture's rename reassigns
 * a document's ID (this app's own `FylzFilesDocumentsProvider` does exactly that -- its document
 * IDs are path-derived), which is the only case that needs a relocation reported at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileToolsRelocationTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var provider: RenamingFixtureProvider

    @Before
    fun registerProvider() {
        val providerInfo = ProviderInfo().apply {
            authority = RenamingFixtureProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
        }
        provider = Robolectric.buildContentProvider(RenamingFixtureProvider::class.java)
            .create(providerInfo)
            .get()
    }

    private val parentUri: Uri =
        DocumentsContract.buildTreeDocumentUri(RenamingFixtureProvider.AUTHORITY, RenamingFixtureProvider.ROOT)

    @Test
    fun `a batch rename that never commits never fires the relocation callback`() = runBlocking {
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val tools = FileTools(context, onItemRelocated = { old, new -> relocations += old to new })
        val plans = listOf(
            BatchRenamePlan(Uri.parse("content://fylz.test.nowhere/document/a"), "a.txt", "renamed-a.txt"),
        )

        runCatching { tools.executeBatchRename(plans) }

        assertTrue("a rename that never landed must never relocate anything", relocations.isEmpty())
    }

    @Test
    fun `a batch rename that commits fires the relocation callback for every renamed item`() = runBlocking {
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val tools = FileTools(context, onItemRelocated = { old, new -> relocations += old to new })
        val first = provider.seed("a.txt")
        val second = provider.seed("b.txt")
        val plans = tools.planBatchRename(listOf(first to "a.txt", second to "b.txt"), prefix = "renamed-")

        val result = tools.executeBatchRename(plans, parentUri = parentUri)

        assertEquals(listOf(first to result[0], second to result[1]), relocations)
    }

    @Test
    fun `a copy-free single rename lands the new display name at the provider`() = runBlocking {
        val tools = FileTools(context)
        val source = provider.seed("draft.txt")
        val plans = tools.planBatchRename(listOf(source to "draft.txt"), prefix = "final-")

        val result = tools.executeBatchRename(plans, parentUri = parentUri)

        assertEquals("final-01.txt", provider.nameOf(result.single()))
    }
}

/**
 * A flat, in-memory `DocumentsContract`-shaped fixture modeling a provider whose document ID is
 * derived from the document's own name (this app's own `FylzFilesDocumentsProvider` is exactly
 * this shape -- its IDs are `root:relative/path`) -- renaming a document reassigns its ID, and
 * therefore its URI, which is the only case `FileTools.executeBatchRename` needs to report
 * through `onItemRelocated` at all.
 */
class RenamingFixtureProvider : ContentProvider() {

    private class Node(var id: String, var name: String, val parentId: String?)

    private val nodes = LinkedHashMap<String, Node>()
    private var nextId = 1
    private val treeUri: Uri by lazy { DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT) }

    override fun onCreate(): Boolean {
        nodes[ROOT] = Node(ROOT, "root", null)
        return true
    }

    // Tree-shaped, matching every uri this app's own callers actually hand to
    // executeBatchRename (DocumentRepository.listChildren mints entries the same way) -- a bare
    // uri here would still open fine via fromSingleUri, but wouldn't string-match what
    // parent.listFiles() (a TreeDocumentFile) reports for the same node during the collision
    // check.
    fun seed(name: String): Uri {
        val id = "node-${nextId++}"
        nodes[id] = Node(id, name, ROOT)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
    }

    fun nameOf(uri: Uri): String? = nodes[DocumentsContract.getDocumentId(uri)]?.name

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

    override fun getType(uri: Uri): String = "text/plain"

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val documentUri = extras?.getParcelable<Uri>(EXTRA_URI)
        val documentId = documentUri?.let(DocumentsContract::getDocumentId)
        return when (method) {
            METHOD_RENAME_DOCUMENT -> {
                val node = documentId?.let { nodes[it] } ?: return null
                val newName = extras.getString(Document.COLUMN_DISPLAY_NAME) ?: return null
                nodes.remove(node.id)
                val newId = "node-${nextId++}"
                node.id = newId
                node.name = newName
                nodes[newId] = node
                Bundle().apply { putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(treeUri, newId)) }
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun addRow(cursor: MatrixCursor, node: Node) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, node.id)
            add(Document.COLUMN_DISPLAY_NAME, node.name)
            add(Document.COLUMN_MIME_TYPE, "text/plain")
            add(Document.COLUMN_SIZE, 0L)
            add(Document.COLUMN_LAST_MODIFIED, 0L)
            add(
                Document.COLUMN_FLAGS,
                Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME,
            )
        }
    }

    companion object {
        const val AUTHORITY = "fylz.test.renaming"
        const val ROOT = "root"

        // See RelocationFixtureProvider's identical companion comment: these @hide constants are
        // pinned as the literal values ContentResolver.call() dispatches through at runtime.
        private const val EXTRA_URI = "uri"
        private const val METHOD_RENAME_DOCUMENT = "android:renameDocument"

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
