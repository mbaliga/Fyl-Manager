package io.github.mbaliga.fylz.storage

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.0 "done when": a smoke test lists, creates, reads and writes through the hosted provider.
 * Everything below runs against the real [FylzFilesDocumentsProvider], not a fake, over a
 * temp directory supplied through [FylzDocumentsProviderTestBase].
 */
class FylzFilesDocumentsProviderSmokeTest : FylzDocumentsProviderTestBase() {

    @Test
    fun `queryRoots reports the overridden primary volume`() {
        val cursor = provider.queryRoots(null)
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(
            FylzFilesDocumentsProvider.PRIMARY_ROOT_ID,
            cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID)),
        )
    }

    @Test
    fun `an empty root lists zero children`() {
        val cursor = provider.queryChildDocuments(rootDocumentId(), null, null)
        assertEquals(0, cursor.count)
    }

    @Test
    fun `create write read and list a file through the provider`() {
        val createdId = provider.createDocument(rootDocumentId(), "text/plain", "notes.txt")

        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(createdId, "w", null)).use {
            it.write("hello fylz".toByteArray())
        }

        val children = provider.queryChildDocuments(rootDocumentId(), null, null)
        assertEquals(1, children.count)
        assertTrue(children.moveToFirst())
        assertEquals(
            "notes.txt",
            children.getString(children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)),
        )
        assertEquals(
            10L,
            children.getLong(children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)),
        )

        val readBack = ParcelFileDescriptor.AutoCloseInputStream(provider.openDocument(createdId, "r", null))
            .use { it.readBytes() }
        assertEquals("hello fylz", String(readBack))
    }

    @Test
    fun `creating a folder then a child inside it round trips through queryChildDocuments`() {
        val folderId = provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, "sub")
        val fileId = provider.createDocument(folderId, "text/plain", "inside.txt")

        val children = provider.queryChildDocuments(folderId, null, null)
        assertEquals(1, children.count)
        assertTrue(children.moveToFirst())
        assertEquals(
            fileId,
            children.getString(children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
        )
    }
}
