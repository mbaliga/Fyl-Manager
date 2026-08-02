package io.github.mbaliga.fylz.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class DocumentRepository(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

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
                    entries += FileEntry(
                        uri = documentUri,
                        name = name,
                        mimeType = mimeType,
                        sizeBytes = cursor.longOrNull(sizeIndex),
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

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private fun android.database.Cursor.intOrZero(index: Int): Int =
        if (index < 0 || isNull(index)) 0 else getInt(index)
}
