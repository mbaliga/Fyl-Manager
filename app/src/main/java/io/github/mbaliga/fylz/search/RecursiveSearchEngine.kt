package io.github.mbaliga.fylz.search

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/** Progressive state of a running search, so the UI can show partial results as they arrive. */
data class SearchProgress(
    val hits: List<SearchHit>,
    val foldersScanned: Int,
    val filesScanned: Int,
    val complete: Boolean,
    val limitReached: Boolean = false,
)

/**
 * Real recursive search across a whole tree, replacing the single-folder
 * `entries.filter { it.name.contains(query) }` in `FylzV1App`.
 *
 * Works against any `DocumentsContract` tree, which -- because the `full` flavor exposes broad
 * storage through its own DocumentsProvider -- means it covers SAF grants and whole storage
 * volumes with one implementation.
 *
 * Traversal is breadth-first and bounded: [maxResults], [maxFolders] and a per-file content read
 * cap keep an accidental search of a 200k-file volume from becoming unbounded work. It is
 * cancellable at every step via normal coroutine cancellation, and emits partial results so the
 * user sees hits immediately instead of waiting for the whole walk.
 */
class RecursiveSearchEngine(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * @param treeUri the tree that scopes the search (permission root)
     * @param rootUri the folder to start from; usually the current folder
     */
    fun search(
        treeUri: Uri,
        rootUri: Uri,
        rootName: String,
        query: SearchQuery,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        maxFolders: Int = DEFAULT_MAX_FOLDERS,
    ): Flow<SearchProgress> = flow {
        if (query.isEmpty) {
            emit(SearchProgress(emptyList(), 0, 0, complete = true))
            return@flow
        }

        val hits = mutableListOf<SearchHit>()
        val queue = ArrayDeque(listOf(rootUri to ""))
        val visited = mutableSetOf(rootUri.toString())
        var foldersScanned = 0
        var filesScanned = 0
        var limitReached = false
        var sinceLastEmit = 0

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (hits.size >= maxResults || foldersScanned >= maxFolders) {
                limitReached = true
                break
            }
            val (folderUri, folderPath) = queue.removeFirst()
            foldersScanned += 1

            val children = runCatching { listChildren(treeUri, folderUri) }.getOrDefault(emptyList())
            for (child in children) {
                coroutineContext.ensureActive()
                val childPath = if (folderPath.isEmpty()) child.name else "$folderPath/${child.name}"

                if (child.isDirectory) {
                    // .fylz-trash is the app's own recycle location; searching it would surface
                    // items the user has already deleted as if they were still in place.
                    if (child.name == RECYCLE_DIRECTORY) continue
                    if (visited.add(child.uri.toString())) queue.addLast(child.uri to childPath)
                } else {
                    filesScanned += 1
                }

                if (!query.matchesMetadata(child)) continue

                if (query.matchesName(child.name)) {
                    hits += SearchHit(child, relativePath(rootName, childPath), SearchMatchSource.NAME)
                    sinceLastEmit += 1
                } else if (query.searchContent && !child.isDirectory && isContentSearchable(child)) {
                    contentSnippet(child.uri, query)?.let { snippet ->
                        hits += SearchHit(
                            child,
                            relativePath(rootName, childPath),
                            SearchMatchSource.CONTENT,
                            snippet,
                        )
                        sinceLastEmit += 1
                    }
                }

                if (hits.size >= maxResults) {
                    limitReached = true
                    break
                }
            }

            if (sinceLastEmit > 0 || foldersScanned % FOLDERS_PER_EMIT == 0) {
                emit(SearchProgress(hits.toList(), foldersScanned, filesScanned, complete = false))
                sinceLastEmit = 0
            }
        }

        emit(
            SearchProgress(
                hits = hits.toList(),
                foldersScanned = foldersScanned,
                filesScanned = filesScanned,
                complete = true,
                limitReached = limitReached,
            ),
        )
    }.flowOn(Dispatchers.IO)

    private fun listChildren(treeUri: Uri, folderUri: Uri): List<FileEntry> {
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
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (idIndex < 0 || nameIndex < 0) return emptyList()
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType = if (mimeIndex >= 0) {
                    cursor.getString(mimeIndex) ?: "application/octet-stream"
                } else {
                    "application/octet-stream"
                }
                entries += FileEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                    name = name,
                    mimeType = mimeType,
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                    lastModifiedMillis = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        null
                    },
                    flags = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) cursor.getInt(flagsIndex) else 0,
                    kind = FileType.classify(name, mimeType),
                )
            }
        }
        return entries
    }

    /**
     * Content search is deliberately limited to text-shaped files under [MAX_CONTENT_BYTES].
     * docs/product/preview-and-recycle-bin-contract.md requires bounded reads; a search that
     * streamed a 4 GB video into memory looking for a word would violate that in spirit.
     */
    private fun isContentSearchable(entry: FileEntry): Boolean {
        val size = entry.sizeBytes ?: return false
        if (size > MAX_CONTENT_BYTES) return false
        if (FileType.isTextPreviewable(entry.kind)) return true
        return entry.mimeType.startsWith("text/") ||
            entry.mimeType in EXTRA_TEXT_MIME_TYPES
    }

    private fun contentSnippet(uri: Uri, query: SearchQuery): String? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).buffered().use { reader ->
                var read = 0
                reader.lineSequence().forEach { line ->
                    read += line.length
                    if (read > MAX_CONTENT_BYTES) return@use null
                    if (query.terms.all { line.contains(it, ignoreCase = true) }) {
                        return@use line.trim().take(SNIPPET_LENGTH)
                    }
                }
                null
            }
        }
    }.getOrNull()

    private fun relativePath(rootName: String, path: String): String =
        if (path.contains('/')) "$rootName/${path.substringBeforeLast('/')}" else rootName

    companion object {
        const val DEFAULT_MAX_RESULTS: Int = 500
        const val DEFAULT_MAX_FOLDERS: Int = 5_000
        const val RECYCLE_DIRECTORY: String = ".fylz-trash"

        private const val MAX_CONTENT_BYTES = 2L * 1_024 * 1_024
        private const val SNIPPET_LENGTH = 160
        private const val FOLDERS_PER_EMIT = 25

        private val EXTRA_TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/x-yaml",
            "application/toml",
            "application/x-sh",
            "application/javascript",
        )
    }
}
