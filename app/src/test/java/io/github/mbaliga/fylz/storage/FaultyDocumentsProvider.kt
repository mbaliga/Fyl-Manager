package io.github.mbaliga.fylz.storage

import android.database.Cursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import org.robolectric.Robolectric
import kotlin.concurrent.thread

/**
 * P0.0 test double: wraps a real, temp-directory-backed [FylzFilesDocumentsProvider] and can be
 * configured to simulate the provider failures the Phase 0 recovery paths need proof against —
 * refused rename, delete or create, and a write that throws partway through.
 *
 * Every call this double doesn't specifically fault-inject is forwarded unchanged to the real
 * provider, so a test only needs to describe the one failure it's proving recovery from.
 */
class FaultyDocumentsProvider : DocumentsProvider() {

    /** When true, [renameDocument] throws instead of delegating. */
    var refuseRename: Boolean = false

    /** When true, [deleteDocument] throws instead of delegating. */
    var refuseDelete: Boolean = false

    /** When true, [createDocument] throws instead of delegating. */
    var refuseCreate: Boolean = false

    /**
     * When set, a write-mode [openDocument] returns a pipe that silently stops forwarding bytes
     * to the underlying file after this many bytes, then closes — so the caller's next write
     * fails with a broken-pipe [java.io.IOException], simulating a provider that throws mid-write.
     * Read-mode opens are never affected.
     */
    var throwAfterBytes: Long? = null

    /**
     * The real provider this double wraps. Built directly through Robolectric so it's a fully
     * attached, working [FylzFilesDocumentsProvider] without being registered under any authority
     * of its own — every call reaches it only via this double's forwarding methods below.
     */
    private val real: FylzFilesDocumentsProvider by lazy {
        Robolectric.buildContentProvider(FylzFilesDocumentsProvider::class.java)
            .create(AUTHORITY)
            .get()
    }

    /** Pass-through to the real provider's P0.0 test seam; set this before any operation. */
    var volumeOverride: List<VolumeDescriptor>?
        get() = real.volumeOverride
        set(value) {
            real.volumeOverride = value
        }

    override fun onCreate(): Boolean {
        real // force attachment now, so a misconfigured double fails fast in setup, not mid-test
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = real.queryRoots(projection)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        real.queryDocument(documentId, projection)

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = real.queryChildDocuments(parentDocumentId, projection, sortOrder)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        real.isChildDocument(parentDocumentId, documentId)

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val limit = throwAfterBytes
        val pfd = real.openDocument(documentId, mode, signal)
        return if (limit != null && mode.contains('w')) truncatingPipe(pfd, limit) else pfd
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (refuseCreate) throw FileNotFoundException("simulated: create refused for $displayName")
        return real.createDocument(parentDocumentId, mimeType, displayName)
    }

    override fun deleteDocument(documentId: String) {
        if (refuseDelete) throw FileNotFoundException("simulated: delete refused for $documentId")
        real.deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (refuseRename) throw FileNotFoundException("simulated: rename refused for $documentId")
        return real.renameDocument(documentId, displayName)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String = real.moveDocument(sourceDocumentId, sourceParentDocumentId, targetParentDocumentId)

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ) = real.openDocumentThumbnail(documentId, sizeHint, signal)

    /**
     * Hands the caller the write end of a pipe. A background thread copies bytes from it into
     * [target] until [limit] is reached, then closes both ends — after which the caller's next
     * `write()` fails with a broken pipe, exactly as if the real provider had thrown mid-write.
     */
    private fun truncatingPipe(target: ParcelFileDescriptor, limit: Long): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        thread(isDaemon = true, name = "faulty-provider-write") {
            ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var written = 0L
                    while (written < limit) {
                        val toRead = minOf(buffer.size.toLong(), limit - written).toInt()
                        val n = input.read(buffer, 0, toRead)
                        if (n < 0) return@thread
                        output.write(buffer, 0, n)
                        written += n
                    }
                }
            }
        }
        return writeSide
    }

    companion object {
        const val AUTHORITY: String = FylzFilesDocumentsProvider.AUTHORITY
    }
}
