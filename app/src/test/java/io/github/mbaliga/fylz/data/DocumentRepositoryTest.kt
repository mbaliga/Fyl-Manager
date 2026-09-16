package io.github.mbaliga.fylz.data

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.staging.ShelfItem
import io.github.mbaliga.fylz.staging.ShelfStore
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.toItemRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * [DocumentRepository.probe] and the Shelf half of the relocation-propagation fix, run against
 * the real [FylzFilesDocumentsProvider] -- beside [io.github.mbaliga.fylz.storage
 * .StorageBackendContractTest]'s own registration idiom. Unlike the fake SAF provider, this
 * backend's document ids encode the relative path, so a rename genuinely mints a new URI and
 * actually exercises `rename`'s `renamed != uri` branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var treeUri: Uri

    @Before
    fun registerProvider() {
        val externalRoot = temporaryFolder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(externalRoot.toPath())
        val providerInfo = ProviderInfo().apply {
            authority = FylzFilesDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            // DocumentsProvider.attachInfo throws SecurityException unless both guards carry
            // the signature-level MANAGE_DOCUMENTS declaration from the manifest.
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        Robolectric.buildContentProvider(FylzFilesDocumentsProvider::class.java).create(providerInfo)
        treeUri = FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID)
    }

    private fun rootDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun createFile(name: String, content: ByteArray = ByteArray(0)): Uri {
        val uri = requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, rootDocumentUri(), "text/plain", name),
        )
        if (content.isNotEmpty()) {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(content) }
        }
        return uri
    }

    private fun createFolder(name: String): Uri = requireNotNull(
        DocumentsContract.createDocument(
            context.contentResolver,
            rootDocumentUri(),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ),
    )

    // ── probe ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe reports name mime type size and kind for a file`() = runBlocking {
        val repository = DocumentRepository(context)
        val uri = createFile("hello.txt", "hello world".toByteArray())

        val entry = repository.probe(uri)

        assertEquals("hello.txt", entry?.name)
        assertEquals("text/plain", entry?.mimeType)
        assertEquals(11L, entry?.sizeBytes)
        assertEquals(EntryKind.TEXT, entry?.kind)
        assertEquals(false, entry?.isDirectory)
    }

    @Test
    fun `probe on a directory normalizes size to null`() = runBlocking {
        val repository = DocumentRepository(context)
        val uri = createFolder("photos")

        val entry = repository.probe(uri)

        assertEquals(true, entry?.isDirectory)
        assertEquals(EntryKind.DIRECTORY, entry?.kind)
        assertNull(entry?.sizeBytes)
    }

    @Test
    fun `probe returns null once the document is gone`() = runBlocking {
        val repository = DocumentRepository(context)
        val uri = createFile("gone.txt")
        DocumentsContract.deleteDocument(context.contentResolver, uri)

        assertNull(repository.probe(uri))
    }

    @Test
    fun `probe returns null for a uri no provider serves`() = runBlocking {
        val repository = DocumentRepository(context)

        assertNull(repository.probe(Uri.parse("content://not.a.real.authority/tree/x/document/y")))
    }

    // ── relocation propagation (Shelf) ────────────────────────────────────────────────

    private fun shelfItem(uri: Uri, name: String) = ShelfItem(
        ref = uri.toItemRef(),
        displayName = name,
        kind = EntryKind.TEXT,
        isDirectory = false,
        sizeBytes = 0L,
        modifiedAtMillis = null,
        addedAtMillis = 0L,
        sourceCrumb = "Test",
    )

    @Test
    fun `rename migrates a shelved member to the provider's new document id`() = runBlocking {
        val shelf = ShelfStore(context)
        val repository = DocumentRepository(context, shelf = shelf)
        val uri = createFile("before.txt")
        shelf.add(listOf(shelfItem(uri, "before.txt")))

        val renamed = repository.rename(uri, "after.txt")

        // This backend encodes the name in the document id, so a real rename must produce a
        // genuinely different uri -- otherwise this test would pass without exercising anything.
        assertNotEquals(uri, renamed)
        assertEquals(listOf(renamed.toItemRef()), shelf.items().map { it.ref })
    }

    @Test
    fun `rename without a shelf handle still succeeds`() = runBlocking {
        val repository = DocumentRepository(context)
        val uri = createFile("before.txt")

        val renamed = repository.rename(uri, "after.txt")

        assertEquals("after.txt", DocumentsContract.getDocumentId(renamed).substringAfterLast(':'))
    }

    @Test
    fun `rename leaves an unrelated shelf member untouched`() = runBlocking {
        val shelf = ShelfStore(context)
        val repository = DocumentRepository(context, shelf = shelf)
        val renaming = createFile("before.txt")
        val bystander = createFile("bystander.txt")
        shelf.add(listOf(shelfItem(bystander, "bystander.txt")))

        repository.rename(renaming, "after.txt")

        assertEquals(listOf(bystander.toItemRef()), shelf.items().map { it.ref })
    }

    // ── relocation propagation (onItemRelocated) ──────────────────────────────────────

    /**
     * Pins the rename fan-out fix: a caller-supplied [io.github.mbaliga.fylz.data
     * .DocumentRepository]'s `onItemRelocated` must fire on a genuine rename exactly the way
     * [io.github.mbaliga.fylz.operations.FileOperationService]'s and [io.github.mbaliga.fylz
     * .operations.FileTools]'s own do for a move -- see `FileOperationServiceRelocationTest`.
     * `FylzV1App` builds this repository with the identical lambda it hands those two services,
     * so canvas placement and the landing subject (which only that lambda's fuller fan-out
     * knows how to migrate) ride along with whatever this test proves fires.
     */
    @Test
    fun `rename fires the supplied onItemRelocated with the old and new uri`() = runBlocking {
        val relocations = mutableListOf<Pair<Uri, Uri>>()
        val repository = DocumentRepository(context, onItemRelocated = { old, new -> relocations += old to new })
        val uri = createFile("before.txt")

        val renamed = repository.rename(uri, "after.txt")

        assertNotEquals(uri, renamed)
        assertEquals(listOf(uri to renamed), relocations)
    }

    @Test
    fun `a supplied onItemRelocated replaces this repository's own store handles rather than joining them`() =
        runBlocking {
            val shelf = ShelfStore(context)
            var fired = false
            val repository = DocumentRepository(context, shelf = shelf, onItemRelocated = { _, _ -> fired = true })
            val uri = createFile("before.txt")
            shelf.add(listOf(shelfItem(uri, "before.txt")))

            repository.rename(uri, "after.txt")

            assertEquals(true, fired)
            // The caller's lambda owns migration entirely once supplied -- this repository's own
            // shelf handle must not also run, or a caller whose lambda already covers the Shelf
            // (as FylzV1App's does) would see it migrated twice.
            assertEquals(listOf(uri.toItemRef()), shelf.items().map { it.ref })
        }
}
