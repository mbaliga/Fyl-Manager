package io.github.mbaliga.fylz.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DEFAULT_TEXT_LIMIT = 400_000

data class TextPayload(
    val text: String,
    val truncated: Boolean,
)

class SafFileRepository(
    private val resolver: ContentResolver,
) {
    suspend fun rootLocation(treeUri: Uri): Result<FolderLocation> = withContext(Dispatchers.IO) {
        runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val metadata = queryFolderMetadata(documentUri)
            FolderLocation(
                treeUri = treeUri.toString(),
                documentId = documentId,
                title = metadata.first ?: "Folder",
                flags = metadata.second,
            )
        }
    }

    suspend fun listChildren(location: FolderLocation): Result<List<FileEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = Uri.parse(location.treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    location.documentId,
                )
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_FLAGS,
                )

                val cursor = resolver.query(childrenUri, projection, null, null, null)
                    ?: error("The document provider did not return a folder listing.")
                cursor.use {
                    val idIndex = it.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    )
                    require(idIndex >= 0) {
                        "The document provider omitted document identifiers."
                    }
                    val nameIndex = it.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                    val mimeIndex = it.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    )
                    val sizeIndex = it.getColumnIndex(
                        DocumentsContract.Document.COLUMN_SIZE,
                    )
                    val modifiedIndex = it.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                    val flagsIndex = it.getColumnIndex(
                        DocumentsContract.Document.COLUMN_FLAGS,
                    )

                    buildList {
                        while (it.moveToNext()) {
                            val documentId = it.stringOrNull(idIndex) ?: continue
                            val mimeType = it.stringOrNull(mimeIndex)
                                ?: "application/octet-stream"
                            add(
                                FileEntry(
                                    treeUri = location.treeUri,
                                    documentId = documentId,
                                    name = it.stringOrNull(nameIndex) ?: "Untitled",
                                    mimeType = mimeType,
                                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                                    size = it.longOrNull(sizeIndex),
                                    modifiedAt = it.longOrNull(modifiedIndex),
                                    flags = it.intOrZero(flagsIndex),
                                ),
                            )
                        }
                    }
                }
            }
        }

    suspend fun readText(
        entry: FileEntry,
        limit: Int = DEFAULT_TEXT_LIMIT,
    ): Result<TextPayload> = withContext(Dispatchers.IO) {
        runCatching {
            require(limit > 0) { "The preview limit must be positive." }
            val uri = documentUri(entry)
            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(8_192)
                val output = StringBuilder(minOf(limit, 64_000))
                var truncated = false
                while (output.length <= limit) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    val remaining = limit - output.length
                    if (read > remaining) {
                        output.append(buffer, 0, remaining.coerceAtLeast(0))
                        truncated = true
                        break
                    }
                    output.append(buffer, 0, read)
                }
                if (!truncated && output.length == limit && reader.read() >= 0) truncated = true
                TextPayload(output.toString(), truncated)
            } ?: error("The document provider did not return a readable stream.")
        }
    }

    suspend fun writeText(entry: FileEntry, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.openOutputStream(documentUri(entry), "wt")?.bufferedWriter(Charsets.UTF_8)
                    ?.use { writer -> writer.write(text) }
                    ?: error("The document provider did not return a writable stream.")
            }
        }

    suspend fun createDirectory(location: FolderLocation, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = DocumentsContract.createDocument(
                    resolver,
                    documentUri(location),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name,
                )
                checkNotNull(uri) { "The document provider rejected the new folder." }
                Unit
            }
        }

    suspend fun createTextFile(
        location: FolderLocation,
        name: String,
        initialText: String = "",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "md", "markdown" -> "text/markdown"
                "json" -> "application/json"
                "yaml", "yml" -> "application/yaml"
                else -> "text/plain"
            }
            val uri = DocumentsContract.createDocument(
                resolver,
                documentUri(location),
                mime,
                name,
            ) ?: error("The document provider rejected the new file.")
            try {
                resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write(initialText)
                } ?: error("The new file could not be opened for writing.")
            } catch (failure: Throwable) {
                // Do not silently leave behind a partial file when initialization fails.
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw failure
            }
        }
    }

    suspend fun rename(entry: FileEntry, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val renamed = DocumentsContract.renameDocument(
                    resolver,
                    documentUri(entry),
                    newName,
                )
                checkNotNull(renamed) { "The document provider rejected the rename." }
                Unit
            }
        }

    suspend fun delete(entry: FileEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(DocumentsContract.deleteDocument(resolver, documentUri(entry))) {
                "The document provider rejected the delete operation."
            }
        }
    }

    fun documentUri(entry: FileEntry): Uri = DocumentsContract.buildDocumentUriUsingTree(
        Uri.parse(entry.treeUri),
        entry.documentId,
    )

    fun documentUri(location: FolderLocation): Uri = DocumentsContract.buildDocumentUriUsingTree(
        Uri.parse(location.treeUri),
        location.documentId,
    )

    private fun queryFolderMetadata(documentUri: Uri): Pair<String?, Int> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val cursor = resolver.query(documentUri, projection, null, null, null)
            ?: error("The document provider did not return folder metadata.")
        return cursor.use {
            if (!it.moveToFirst()) error("The selected folder no longer exists.")
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val flagsIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val name = if (nameIndex < 0 || it.isNull(nameIndex)) null else it.getString(nameIndex)
            val flags = if (flagsIndex < 0 || it.isNull(flagsIndex)) 0 else it.getInt(flagsIndex)
            name to flags
        }
    }
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun Cursor.longOrNull(index: Int): Long? =
    if (index < 0 || isNull(index)) null else getLong(index)

private fun Cursor.intOrZero(index: Int): Int =
    if (index < 0 || isNull(index)) 0 else getInt(index)
