package io.github.mbaliga.fylz.storage

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import androidx.annotation.VisibleForTesting
import io.github.mbaliga.fylz.BuildConfig
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveEntryCache
import io.github.mbaliga.fylz.archive.ArchiveHandle
import io.github.mbaliga.fylz.archive.ArchiveTreeEntry
import io.github.mbaliga.fylz.model.FileEntry
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Every archive location and entry as a document of this provider
 * (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md` section 2.1), so that every consumer that already
 * takes a `content://` Uri -- `FileEntry.uri`, `DocNode.load`, previews, share, "Open with…", the
 * transfer engine, the session codec, LazyColumn keys -- browses, previews and copies archive
 * entries unchanged. Declared exactly like the File provider (`exported="true"`,
 * `grantUriPermissions="true"`, `permission="android.permission.MANAGE_DOCUMENTS"`) and, unlike it,
 * with **no** `DOCUMENTS_PROVIDER` intent filter, so DocumentsUI never lists it. A `DocumentsProvider`
 * cannot be non-exported (`attachInfo` throws); the signature permission is what keeps other apps
 * out, and a Uri handed out with `FLAG_GRANT_READ_URI_PERMISSION` (share, Open with) works because
 * Uri grants bypass the provider permission. Same-UID callers need no grant.
 *
 * Document ids are [ArchiveDocumentId]s; every Uri is the non-tree form
 * `content://io.github.mbaliga.fylz.archives/document/<id>`, and `DocumentRepository.listChildren`
 * and `DocNode.children` have the one non-tree branch that needs. The tab's `treeUri` stays the
 * outer tree; only the location stack holds archive Uris.
 *
 * **Every method blocks on the [ArchiveCatalog]** (section 2.3): there is no loading protocol, no
 * `EXTRA_LOADING`, no `notifyChange`; a cold process gets the full listing, never an empty folder.
 * A catalog failure is carried as [DocumentsContract.EXTRA_ERROR] on a rowless children cursor
 * (`DocumentRepository.listChildren` turns it into an `IOException`, which the listing effect
 * already toasts) and as a `FileNotFoundException` from `queryDocument`/`openDocument`. Read-only:
 * every write method is `UnsupportedOperationException` (M3.6 decides what becomes writable).
 * In-process `ContentResolver` calls run the provider on the caller's thread through the local
 * transport, and every existing caller is already off the main thread; a debug-only check says so,
 * injectable so Robolectric (main looper) can run it.
 */
class ArchiveDocumentsProvider : DocumentsProvider() {

    /** Test seam (the `volumeOverride` pattern): the catalog to use instead of the application's. */
    @VisibleForTesting
    internal var catalogOverride: ArchiveCatalog? = null

    @VisibleForTesting
    internal var entryCacheOverride: ArchiveEntryCache? = null

    /** Debug-only: a provider call on the main looper would block the UI on a listing. Injectable for Robolectric. */
    @VisibleForTesting
    internal var mainThreadGuard: () -> Unit = {
        if (BuildConfig.DEBUG) check(Looper.myLooper() != Looper.getMainLooper()) { "ArchiveDocumentsProvider called on the main thread" }
    }

    private val catalog: ArchiveCatalog
        get() = catalogOverride ?: (context!!.applicationContext as FylzApplication).archiveCatalog

    private val entryCache: ArchiveEntryCache
        get() = entryCacheOverride ?: (context!!.applicationContext as FylzApplication).archiveEntryCache

    override fun onCreate(): Boolean = true

    /** Archives are never SAF roots. */
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        mainThreadGuard()
        val id = parse(documentId)
        val handle = open(id)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (id.isRoot) {
            addRootRow(cursor, id, handle)
        } else {
            val entry = entryOf(handle, id)
            addEntryRow(cursor, id, entry)
        }
        return cursor
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        mainThreadGuard()
        val parent = parse(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val handle = try {
            open(parent)
        } catch (failure: FileNotFoundException) {
            // Section 2.3: a memoised catalog failure is a message on a rowless cursor, not an empty folder.
            cursor.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, failure.message) }
            return cursor
        }
        if (!parent.isRoot) {
            val entry = entryOf(handle, parent)
            if (!entry.isDirectory) throw FileNotFoundException("${parent.path} is not a folder")
        }
        handle.tree.children(parent.path).forEach { child -> addEntryRow(cursor, parent.entry(child.ordinal, child.path), child) }
        return cursor
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        mainThreadGuard()
        if (mode != "r") throw FileNotFoundException("Archive entries are read-only (mode $mode)")
        val id = parse(documentId)
        if (id.isRoot) throw FileNotFoundException("An archive root is a folder")
        val handle = open(id)
        val entry = entryOf(handle, id)
        return blocking(signal) {
            try {
                entryCache.open(handle, entry)
            } catch (refused: ArchiveEntryCache.Refused) {
                throw refused
            } catch (failure: IOException) {
                throw FileNotFoundException(failure.message ?: "The entry could not be read.")
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { ArchiveDocumentId.parse(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { ArchiveDocumentId.parse(documentId) }.getOrNull() ?: return false
        if (parent.archive != child.archive) return false
        if (child.isRoot) return false
        return parent.isRoot || child.path.startsWith(parent.path + "/")
    }

    override fun getDocumentType(documentId: String): String {
        val id = parse(documentId)
        if (id.isRoot) return DocumentsContract.Document.MIME_TYPE_DIR
        val entry = entryOf(open(id), id)
        return mimeTypeOf(entry)
    }

    // Read-only until M3.6.
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = readOnly()
    override fun deleteDocument(documentId: String): Unit = readOnly()
    override fun renameDocument(documentId: String, displayName: String): String = readOnly()
    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String = readOnly()
    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String = readOnly()
    override fun removeDocument(documentId: String, parentDocumentId: String): Unit = readOnly()

    private fun readOnly(): Nothing = throw UnsupportedOperationException("Archives are read-only (M3.6 decides what becomes writable)")

    // ---------------------------------------------------------------- helpers

    @Throws(FileNotFoundException::class)
    private fun parse(documentId: String): ArchiveDocumentId = try {
        ArchiveDocumentId.parse(documentId)
    } catch (e: IllegalArgumentException) {
        throw FileNotFoundException("Not an archive document: ${e.message}")
    }

    /** Blocks on the catalog; a [ArchiveCatalog.Failure] becomes a `FileNotFoundException` with its message. */
    @Throws(FileNotFoundException::class)
    private fun open(id: ArchiveDocumentId): ArchiveHandle = try {
        runBlocking { catalog.open(id) }
    } catch (failure: ArchiveCatalog.Failure) {
        throw FileNotFoundException(failure.message)
    }

    @Throws(FileNotFoundException::class)
    private fun entryOf(handle: ArchiveHandle, id: ArchiveDocumentId): ArchiveTreeEntry {
        val entry = handle.tree.entry(id.path) ?: throw FileNotFoundException("${id.path} is not in this archive")
        if (entry.ordinal != id.ordinal) throw FileNotFoundException("${id.path} is not the entry this id names any more")
        return entry
    }

    /** Runs [block] on a worker, honouring [signal] (`openFileDescriptor(uri, mode, signal)` callers). */
    private fun <T> blocking(signal: CancellationSignal?, block: suspend () -> T): T = runBlocking {
        val job = async { block() }
        signal?.setOnCancelListener { job.cancel() }
        try {
            job.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw OperationCanceledException("cancelled")
        } finally {
            signal?.setOnCancelListener(null)
        }
    }

    private fun addRootRow(cursor: MatrixCursor, id: ArchiveDocumentId, handle: ArchiveHandle) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id.encode())
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, rootDisplayName(id))
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            add(DocumentsContract.Document.COLUMN_SIZE, handle.summary.archiveBytes.takeIf { it >= 0L })
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, null)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        }
    }

    private fun addEntryRow(cursor: MatrixCursor, id: ArchiveDocumentId, entry: ArchiveTreeEntry) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id.encode())
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, entry.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeTypeOf(entry))
            add(DocumentsContract.Document.COLUMN_SIZE, if (entry.isDirectory || !entry.sizeKnown) null else entry.uncompressedBytes)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, if (entry.mtimeKnown) entry.mtimeEpochSeconds * 1_000L else null)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        }
    }

    /** The archive file's own display name for a top-level root (the provider's, when it says), else the entry's name. */
    private fun rootDisplayName(id: ArchiveDocumentId): String {
        if (id.chain.isNotEmpty()) return id.name
        val resolver: ContentResolver = context?.contentResolver ?: return id.name
        return runCatching {
            resolver.query(id.source, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null as Bundle?, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull() ?: id.name
    }

    companion object {
        /** Hard-coded, never `${applicationId}`; the same constant the ids carry. */
        const val AUTHORITY: String = ArchiveDocumentId.AUTHORITY

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

        /** Common extensions `MimeTypeMap` may not know (Robolectric's is empty); the platform map comes first. */
        private val FALLBACK_MIME = mapOf(
            "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json", "xml" to "application/xml",
            "html" to "text/html", "pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp", "mp4" to "video/mp4",
            "mp3" to "audio/mpeg", "zip" to "application/zip", "7z" to "application/x-7z-compressed",
            "tar" to "application/x-tar", "gz" to "application/gzip", "ttf" to "font/ttf", "otf" to "font/otf",
        )

        /**
         * The Uri of the archive rooted at [entry]: a plain file becomes a top-level archive root;
         * an entry of this provider becomes a nested root (its archive's chain plus its path).
         * Throws [IllegalArgumentException] with [ArchiveDocumentId.DEPTH_REFUSED] at the fifth level.
         */
        @Throws(IllegalArgumentException::class)
        fun rootUri(entry: FileEntry): Uri =
            if (ArchiveDocumentId.isArchiveUri(entry.uri)) {
                ArchiveDocumentId.parse(entry.uri).nestedRoot().toUri()
            } else {
                ArchiveDocumentId.root(entry.uri).toUri()
            }

        fun isArchiveUri(uri: Uri?): Boolean = ArchiveDocumentId.isArchiveUri(uri)

        internal fun mimeTypeOf(entry: ArchiveTreeEntry): String {
            if (entry.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
            val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: FALLBACK_MIME[extension]
                ?: "application/octet-stream"
        }
    }
}
