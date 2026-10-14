package io.github.mbaliga.fylz.archive

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import io.github.mbaliga.fylz.storage.registerForContentResolver
import io.github.mbaliga.fylz.storage.testProviderInfo
import org.robolectric.Robolectric

/**
 * M3.2 test double: a `DocumentsProvider` that can only *stream*, the way a third-party cloud
 * provider does for a file it has not downloaded -- every `openDocument` hands back the read end
 * of a fresh pipe carrying [bytes], and `queryDocument` reports [declaredSize] as `COLUMN_SIZE`
 * (or nothing). Adapted from `storage/FaultyDocumentsProvider`'s `createPipe()`-fed `openDocument`
 * for read mode. It is what `ArchiveSource`'s staging path is proven against.
 *
 * Robolectric's `ParcelFileDescriptor.createPipe()` is file-backed, not an OS pipe: a `read()`
 * scheduled before the first `write()` sees EOF instead of blocking, so the bytes are written
 * and the write end closed *before* the read end is returned -- deterministic here, and only
 * possible because a file-backed pipe has no 64 KiB buffer to fill. The same shadow is why the
 * returned descriptor reports a size, so tests inject `isSeekable = { false }` to make
 * `ArchiveSource` treat it as the pipe it stands for.
 */
class PipeDocumentsProvider : DocumentsProvider() {

    /** What every stream carries. */
    var bytes: ByteArray = ByteArray(0)

    /** What `COLUMN_SIZE` says; `null` for a provider that does not say. Independent of [bytes] on purpose. */
    var declaredSize: Long? = null

    /** How many descriptors were handed out: one per `openFileDescriptor`/`openInputStream`. */
    var openCount: Int = 0
        private set

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return MatrixCursor(columns).apply {
            newRow().apply {
                columns.forEach { column ->
                    add(
                        column,
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "$documentId.zip"
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/zip"
                            DocumentsContract.Document.COLUMN_SIZE -> declaredSize
                            else -> null
                        },
                    )
                }
            }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: emptyArray())

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        openCount += 1
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
        return pipe[0]
    }

    companion object {
        const val AUTHORITY: String = "io.github.mbaliga.fylz.test.pipe"

        /** A document `Uri` this provider serves once [install]ed. */
        fun documentUri(id: String = "streamed"): android.net.Uri = DocumentsContract.buildDocumentUri(AUTHORITY, id)

        /** Registers a fresh instance with the shadow resolver, the way `FaultyDocumentsProvider.install` does. */
        fun install(): PipeDocumentsProvider {
            val provider = Robolectric.buildContentProvider(PipeDocumentsProvider::class.java)
                .create(testProviderInfo(PipeDocumentsProvider::class.java, AUTHORITY))
                .get()
            return registerForContentResolver(provider, AUTHORITY)
        }
    }
}
