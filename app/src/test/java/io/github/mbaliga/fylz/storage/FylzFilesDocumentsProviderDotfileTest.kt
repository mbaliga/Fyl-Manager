package io.github.mbaliga.fylz.storage

import android.database.Cursor
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.operations.FileOperationService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * P0.3 (defect 3): `sanitizeDisplayName` used to strip every leading dot, so `.gitignore` became
 * `gitignore` and the recycle bin became a visible, ever-multiplying `fylz-trash`, `fylz-trash
 * (1)`, ... Proves dot-prefixed names now survive creation, copy and move untouched, and that the
 * only names still rejected are the ones that can never be a valid document name at all.
 */
class FylzFilesDocumentsProviderDotfileTest : FylzDocumentsProviderTestBase() {

    private fun queryChildren(parentDocumentId: String): Cursor =
        provider.queryChildDocuments(parentDocumentId, null, null as String?)

    private fun childNames(parentDocumentId: String): List<String> {
        val cursor = queryChildren(parentDocumentId)
        val names = mutableListOf<String>()
        while (cursor.moveToNext()) {
            names += cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        }
        return names
    }

    @Test
    fun `dot-prefixed names survive creation untouched`() {
        provider.createDocument(rootDocumentId(), "text/plain", ".gitignore")
        provider.createDocument(rootDocumentId(), "text/plain", ".nomedia")
        provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, ".git")

        assertEquals(
            setOf(".gitignore", ".nomedia", ".git"),
            childNames(rootDocumentId()).toSet(),
        )
    }

    @Test
    fun `dot-prefixed names survive copy and move`() = runBlocking {
        provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, "source")
        provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, "destination")
        provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, "elsewhere")
        provider.createDocument(documentId("source"), "text/plain", ".gitignore")

        val service = FileOperationService(RuntimeEnvironment.getApplication())
        service.copy(
            listOf(FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "source/.gitignore")),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "destination"),
        )
        assertEquals(listOf(".gitignore"), childNames(documentId("destination")))

        service.move(
            listOf(FylzFilesDocumentsProvider.documentUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "destination/.gitignore")),
            FylzFilesDocumentsProvider.treeUri(FylzFilesDocumentsProvider.PRIMARY_ROOT_ID, "elsewhere"),
        )
        assertTrue(".gitignore" !in childNames(documentId("destination")))
        assertEquals(listOf(".gitignore"), childNames(documentId("elsewhere")))
    }

    @Test
    fun `blank dot and dot-dot names are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.createDocument(rootDocumentId(), "text/plain", "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.createDocument(rootDocumentId(), "text/plain", ".")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.createDocument(rootDocumentId(), "text/plain", "..")
        }
    }

    @Test
    fun `names containing a slash or a null character are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.createDocument(rootDocumentId(), "text/plain", "a/b")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.createDocument(rootDocumentId(), "text/plain", "a\u0000b")
        }
    }

    @Test
    fun `other control characters are still replaced rather than rejected`() {
        val id = provider.createDocument(rootDocumentId(), "text/plain", "a\tb")
        assertEquals(documentId("a_b"), id)
    }
}
