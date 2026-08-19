package io.github.mbaliga.fylz.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.history.FileHistoryReason
import io.github.mbaliga.fylz.history.FileHistoryStore
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.staging.ShelfStore
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

class DocumentRepository(
    context: Context,
    private val shelf: ShelfStore? = null,
    // Fired once rename() actually mints a new document uri -- the exact same hook
    // FileOperationService/FileTools take for a move, so the caller supplies one lambda that
    // updates every identity-keyed store (favorites, tags, history, canvas placement, the
    // landing subject, the Shelf) and a rename can no longer notify a narrower set of them than
    // a move does. Left null, rename() falls back to this repository's own three-store handles
    // below -- the pre-existing behavior for callers that never wire the fuller fan-out.
    onItemRelocated: ((Uri, Uri) -> Unit)? = null,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val history = FileHistoryStore(context.applicationContext)
    private val library = LibraryStore(context.applicationContext)
    private val relocated: (Uri, Uri) -> Unit = onItemRelocated ?: { old, new ->
        history.migrateSource(old, new)
        library.migrateUri(old, new)
        shelf?.migrateRef(old, new)
    }

    data class TextContent(
        val value: String,
        val truncated: Boolean,
    )

    fun persistTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(treeUri, flags) }
    }

    suspend fun rootLocation(treeUri: Uri): FolderLocation = withContext(Dispatchers.IO) {
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        FolderLocation(
            uri = rootDocumentUri,
            name = resolveDisplayName(rootDocumentUri)
                ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast('/'),
        )
    }

    suspend fun listChildren(treeUri: Uri, folderUri: Uri): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getDocumentId(folderUri),
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            )

            val entries = mutableListOf<FileEntry>()
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val flagsIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex) ?: "Untitled"
                    val mimeType = cursor.getString(mimeIndex) ?: "application/octet-stream"
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    entries += FileEntry(
                        uri = documentUri,
                        name = name,
                        mimeType = mimeType,
                        // COLUMN_SIZE is meaningless for a directory, but some OEM documents
                        // providers report the raw filesystem entry size anyway (a few KiB of
                        // junk — observed as "3.4 KiB" on every folder on a RedMagic). Our own
                        // FylzFilesDocumentsProvider correctly reports null; normalize foreign
                        // providers to the same contract so the UI never renders nonsense.
                        sizeBytes = cursor.longOrNull(sizeIndex).takeUnless { isDirectory },
                        lastModifiedMillis = cursor.longOrNull(modifiedIndex),
                        flags = cursor.intOrZero(flagsIndex),
                        kind = FileType.classify(name, mimeType),
                    )
                }
            }

            entries.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        }

    suspend fun createDirectory(parentUri: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Folder name is required." }
        DocumentsContract.createDocument(
            resolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name.trim(),
        ) ?: error("The provider could not create the folder.")
    }

    suspend fun createFile(parentUri: Uri, name: String, mimeType: String): Uri =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { "File name is required." }
            DocumentsContract.createDocument(resolver, parentUri, mimeType, name.trim())
                ?: error("The provider could not create the file.")
        }

    suspend fun rename(uri: Uri, newName: String): Uri = withContext(Dispatchers.IO) {
        require(newName.isNotBlank()) { "A new name is required." }
        val renamed = DocumentsContract.renameDocument(resolver, uri, newName.trim())
            ?: error("The provider could not rename the item.")
        // Some providers keep the document ID (and therefore the URI) stable across a rename;
        // only a genuinely new URI needs its identity-keyed metadata carried over.
        if (renamed != uri) relocated(uri, renamed)
        renamed
    }

    suspend fun copyStream(sourceUri: Uri, destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        history.capture(destinationUri, FileHistoryReason.BEFORE_WRITE)
        val input = resolver.openInputStream(sourceUri) ?: error("Unable to read the source.")
        val output = resolver.openOutputStream(destinationUri, "w")
            ?: error("Unable to write the destination.")
        var total = 0L
        input.use { source ->
            output.use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                    total += count
                }
                destination.flush()
            }
        }
        total
    }

    suspend fun readSignature(uri: Uri, maxBytes: Int = 64): ByteArray =
        withContext(Dispatchers.IO) {
            require(maxBytes in 1..4_096)
            val input = resolver.openInputStream(uri) ?: return@withContext byteArrayOf()
            input.use { stream ->
                val buffer = ByteArray(maxBytes)
                val count = stream.read(buffer)
                if (count <= 0) byteArrayOf() else buffer.copyOf(count)
            }
        }

    suspend fun readText(uri: Uri, maxChars: Int = 524_288): TextContent =
        withContext(Dispatchers.IO) {
            val input = resolver.openInputStream(uri)
                ?: error("The selected provider did not return a readable stream.")
            input.use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val output = StringBuilder(minOf(maxChars, 16_384))
                    val buffer = CharArray(8_192)
                    var truncated = false
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        val remaining = maxChars - output.length
                        if (remaining <= 0) {
                            truncated = true
                            break
                        }
                        val accepted = minOf(read, remaining)
                        output.append(buffer, 0, accepted)
                        if (accepted < read) {
                            truncated = true
                            break
                        }
                    }
                    TextContent(output.toString(), truncated)
                }
            }
        }

    suspend fun writeText(uri: Uri, value: String) = withContext(Dispatchers.IO) {
        history.capture(uri, FileHistoryReason.BEFORE_WRITE)
        val output = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("The selected provider did not return a writable stream.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(value) }
    }

    suspend fun resolveDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /**
     * Single-document metadata fetch, for probing one URI (a Shelf member, say) without a
     * parent listing. Null on any failure -- an unreadable, deleted, or permission-revoked
     * document is indistinguishable to a caller from "nothing to show," never an exception.
     */
    suspend fun probe(uri: Uri): FileEntry? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        // The Bundle-args overload, not the deprecated 4-String one: DocumentsProvider hard-
        // refuses the legacy query shape once queried in-process rather than marshalled through
        // the framework's own Binder round-trip that upgrades it (FileHistoryStore.kt:330-333).
        val queryArgs: Bundle? = null
        val signal: CancellationSignal? = null
        runCatching {
            resolver.query(uri, projection, queryArgs, signal)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (nameIndex < 0 || mimeIndex < 0) return@use null
                val name = cursor.getString(nameIndex) ?: "Untitled"
                val mimeType = cursor.getString(mimeIndex) ?: "application/octet-stream"
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                FileEntry(
                    uri = uri,
                    name = name,
                    mimeType = mimeType,
                    // Directories that report a junk size are normalized the same way
                    // listChildren does -- see the COLUMN_SIZE comment there.
                    sizeBytes = cursor.longOrNull(
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE),
                    ).takeUnless { isDirectory },
                    lastModifiedMillis = cursor.longOrNull(
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    ),
                    flags = cursor.intOrZero(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)),
                    kind = FileType.classify(name, mimeType),
                )
            }
        }.getOrNull()
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private fun android.database.Cursor.intOrZero(index: Int): Int =
        if (index < 0 || isNull(index)) 0 else getInt(index)
}
