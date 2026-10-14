package io.github.mbaliga.fylz.search

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.data.FylzDatabase
import io.github.mbaliga.fylz.index.IndexDao
import io.github.mbaliga.fylz.index.IndexedFile
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.operations.isStagingName
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

    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val database = FylzDatabase(appContext)

    /**
     * @param treeUri the tree that scopes the search (permission root)
     * @param rootUri the folder to start from; usually the current folder
     */
    /**
     * @param excludedDirectoryNames extra directory names to skip, in addition to
     * [RECYCLE_DIRECTORY] -- legacy, pre-P0.3 recycle bins (see
     * `RecycleBinService.legacyRecycleFolderNames`) that a caller has already identified for this
     * [treeUri].
     */
    fun search(
        treeUri: Uri,
        rootUri: Uri,
        rootName: String,
        query: SearchQuery,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        maxFolders: Int = DEFAULT_MAX_FOLDERS,
        excludedDirectoryNames: Set<String> = emptySet(),
    ): Flow<SearchProgress> = flow {
        if (query.isEmpty) {
            emit(SearchProgress(emptyList(), 0, 0, complete = true))
            return@flow
        }

        // P1.12: "the main search uses the index when the scope is covered, and the live walk
        // otherwise" -- searchIndexed returns null (not "covered": no scan of this exact scope
        // has ever completed, or rootUri's own within-scope path isn't known) rather than a wrong
        // or partial answer, in which case the walk below runs exactly as it always has.
        val indexed = searchIndexed(treeUri, rootUri, rootName, query, maxResults, excludedDirectoryNames)
        if (indexed != null) {
            emit(indexed)
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
                // A `.fylz-part-*` staged write (P0.6) is not a real file yet -- it may still be
                // mid-write -- so it must never surface as a search hit, file or folder alike.
                if (isStagingName(child.name)) continue
                val childPath = if (folderPath.isEmpty()) child.name else "$folderPath/${child.name}"

                if (child.isDirectory) {
                    // .fylz-trash (and a caller-identified legacy bin) is the app's own recycle
                    // location; searching it would surface items the user has already deleted as
                    // if they were still in place.
                    if (child.name == RECYCLE_DIRECTORY || child.name in excludedDirectoryNames) continue
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

    /**
     * P1.12's index fast path. Returns null -- "not covered, fall back to the live walk" -- when:
     * [treeUri] isn't an enabled, at-least-once-scanned [io.github.mbaliga.fylz.index.IndexScope];
     * or [rootUri] isn't the scope's own root and isn't itself a row the index already knows (so
     * its scope-relative path can't be determined). Otherwise returns a single, already-complete
     * [SearchProgress] built entirely from indexed rows -- no SAF calls at all.
     *
     * Name/metadata matching reuses [SearchQuery.matchesMetadata]/[SearchQuery.matchesName]
     * unchanged, against indexed rows converted to the same [FileEntry] shape the live walk uses,
     * so a covered scope returns EXACTLY the same name/metadata hits the live walk would -- only
     * faster. Content matching (`content:`/a quoted phrase) is the one place this genuinely
     * differs: it runs an FTS `MATCH` against [IndexedFile.textSnippet] instead of the live walk's
     * own per-line substring check, so it is tokenized ("port" will not match inside "report",
     * where the live walk's plain `contains` would) -- a stated trade-off, not an oversight, since
     * content search was never indexed before P1.12 at all.
     */
    private fun searchIndexed(
        treeUri: Uri,
        rootUri: Uri,
        rootName: String,
        query: SearchQuery,
        maxResults: Int,
        excludedDirectoryNames: Set<String>,
    ): SearchProgress? {
        val db = database.readableDatabase
        // IndexScope.rootUri is always a TREE uri -- the same shape OpenDocumentTree() and this
        // search's own treeUri parameter both are -- never a document uri, so this compares like
        // with like; rootUri (the folder actually being searched) is a document uri and is
        // compared separately below.
        val scope = IndexDao.scopes(db).firstOrNull { it.rootUri == treeUri.toString() && it.enabled }
            ?: return null
        if (scope.lastScannedAtMillis == null) return null

        val scopeRootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val pathPrefix = if (rootUri.toString() == scopeRootDocumentUri.toString()) {
            ""
        } else {
            IndexDao.findByUri(db, rootUri.toString())?.path ?: return null
        }

        val candidates = IndexDao.filesUnderPath(db, scope.rootUri, pathPrefix)
            .filterNot { isExcludedPath(it.path, excludedDirectoryNames) }
        val nameHits = mutableListOf<SearchHit>()
        var filesScanned = 0
        var foldersScanned = 0
        for (candidate in candidates) {
            if (candidate.directory) {
                foldersScanned += 1
                continue
            }
            filesScanned += 1
            val entry = candidate.toFileEntry()
            if (!query.matchesMetadata(entry)) continue
            if (query.matchesName(candidate.name)) {
                nameHits += SearchHit(entry, indexedRelativePath(rootName, candidate.path, pathPrefix), SearchMatchSource.NAME)
                if (nameHits.size >= maxResults) break
            }
        }

        val contentHits = if (query.searchContent && nameHits.size < maxResults) {
            val namedUris = nameHits.mapTo(mutableSetOf()) { it.entry.uri.toString() }
            IndexDao.contentMatches(db, scope.rootUri, pathPrefix, query.terms)
                .asSequence()
                .filter { (uri, _) -> uri !in namedUris }
                .mapNotNull { (uri, snippet) -> IndexDao.findByUri(db, uri)?.let { it to snippet } }
                .filterNot { (file, _) -> isExcludedPath(file.path, excludedDirectoryNames) }
                .filter { (file, _) -> query.matchesMetadata(file.toFileEntry()) }
                .take((maxResults - nameHits.size).coerceAtLeast(0))
                .map { (file, snippet) -> SearchHit(file.toFileEntry(), indexedRelativePath(rootName, file.path, pathPrefix), SearchMatchSource.CONTENT, snippet) }
                .toList()
        } else {
            emptyList()
        }

        val hits = (nameHits + contentHits).take(maxResults)
        return SearchProgress(
            hits = hits,
            foldersScanned = foldersScanned,
            filesScanned = filesScanned,
            complete = true,
            limitReached = hits.size >= maxResults,
        )
    }

    private fun IndexedFile.toFileEntry(): FileEntry = FileEntry(
        uri = Uri.parse(uri),
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModifiedMillis = modifiedAtMillis,
        flags = 0,
        kind = FileType.classify(name, mimeType),
    )

    /** True when any segment of [path] -- including the entry's own name -- is a staged write, the
     *  recycle bin, or a caller-identified legacy bin, matching the live walk's own per-child
     *  checks exactly (see the main loop above). */
    private fun isExcludedPath(path: String, excludedDirectoryNames: Set<String>): Boolean =
        path.split('/').any { segment -> isStagingName(segment) || segment == RECYCLE_DIRECTORY || segment in excludedDirectoryNames }

    /** Mirrors the live walk's own [relativePath]: the path shown to the user is relative to the
     *  SEARCH root, not the indexed scope's root, so [pathPrefix] (the search root's own
     *  scope-relative path) is stripped first. */
    private fun indexedRelativePath(rootName: String, path: String, pathPrefix: String): String {
        val withinRoot = if (pathPrefix.isEmpty()) path else path.removePrefix("$pathPrefix/")
        return relativePath(rootName, withinRoot)
    }

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
        // P1.12: the Bundle-based query overload, not the classic (selection, selectionArgs,
        // sortOrder) one -- DocumentsProvider's base class throws UnsupportedOperationException
        // from the classic overload; see DocNode.load's own KDoc and DocumentRepository.listChildren's
        // own P1.11 fix for the same bug. The caller's own runCatching had been silently
        // swallowing this, so the live walk found zero children under Robolectric until this
        // task's own first-ever test for this class actually exercised it.
        resolver.query(childrenUri, projection, null as Bundle?, null)?.use { cursor ->
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
