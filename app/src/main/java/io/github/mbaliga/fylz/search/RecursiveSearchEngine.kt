package io.github.mbaliga.fylz.search

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.aarso.search.EvalContext
import dev.aarso.search.FacetEvaluator
import dev.aarso.search.FieldRegistry
import dev.aarso.search.Matcher
import dev.aarso.search.Normalizer
import dev.aarso.search.ParsedQuery
import dev.aarso.search.QueryCompiler
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/** Where a hit came from, so the results list can say why a file matched. */
enum class SearchMatchSource {
    NAME,
    CONTENT,
}

data class SearchHit(
    val entry: FileEntry,
    val relativePath: String,
    val source: SearchMatchSource,
    /** One line of surrounding text for a content hit; null for name hits. */
    val snippet: String? = null,
    /** The matcher/scorer score -- 0.0 for a content-only hit, which has nothing lexical in its
     *  own name to rank it by (the same "unranked" case `Matcher.score` documents). */
    val score: Double = 0.0,
    /** Ranges into [entry]'s name where a lexical query term matched, in original-text
     *  coordinates (`Normalizer.findMatches`). Empty for a content hit or a facet-only query. */
    val nameHighlights: List<IntRange> = emptyList(),
)

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
     * @param parsed the query -- `FylzSearch.parse`
     * @param registry the facet vocabulary [parsed] was validated against -- `FylzSearch.registry`,
     *   built over the same zone/tag lookup as [matcher]
     * @param matcher `FylzSearch.matcher` built over [registry]
     * @param ctx the clock this walk ranks recency against, held fixed for the whole search so a
     *   long walk doesn't re-date itself mid-emit
     */
    fun search(
        treeUri: Uri,
        rootUri: Uri,
        rootName: String,
        parsed: ParsedQuery,
        registry: FieldRegistry<FileEntry>,
        matcher: Matcher<FileEntry>,
        ctx: EvalContext,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        maxFolders: Int = DEFAULT_MAX_FOLDERS,
    ): Flow<SearchProgress> = flow {
        if (parsed.isEmptyQuery()) {
            emit(SearchProgress(emptyList(), 0, 0, complete = true))
            return@flow
        }

        val terms = QueryCompiler.lexicalTerms(parsed.root)
        val contentPassEnabled = parsed.wantsContent() && terms.isNotEmpty()

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

                // Facets first, independent of the lexical half -- a content candidate still has
                // to satisfy `ext:`/`type:`/`size:` before its bytes are worth reading.
                if (!FacetEvaluator.matches(child, parsed, registry, ctx)) continue

                val doc = FylzSearch.toDoc(child, path = folderPath.takeIf { it.isNotEmpty() })
                val scored = matcher.score(child, doc, parsed, ctx)
                if (scored != null) {
                    hits += SearchHit(
                        entry = child,
                        relativePath = relativePath(rootName, childPath),
                        source = SearchMatchSource.NAME,
                        score = scored.score,
                        nameHighlights = Normalizer.findMatches(child.name, terms),
                    )
                    sinceLastEmit += 1
                } else if (contentPassEnabled && !child.isDirectory && isContentSearchable(child)) {
                    contentSnippet(child.uri, terms)?.let { snippet ->
                        hits += SearchHit(
                            entry = child,
                            relativePath = relativePath(rootName, childPath),
                            source = SearchMatchSource.CONTENT,
                            snippet = snippet,
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
                emit(SearchProgress(hits.sortedWith(HIT_ORDER), foldersScanned, filesScanned, complete = false))
                sinceLastEmit = 0
            }
        }

        emit(
            SearchProgress(
                hits = hits.sortedWith(HIT_ORDER),
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

    /** [terms] are already [Normalizer.normalize]d -- the same lexical terms the matcher scored
     *  the name/path pass with, so a content hit and a name hit agree on what "contains" means. */
    private fun contentSnippet(uri: Uri, terms: List<String>): String? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).buffered().use { reader ->
                var read = 0
                reader.lineSequence().forEach { line ->
                    read += line.length
                    if (read > MAX_CONTENT_BYTES) return@use null
                    val normalizedLine = Normalizer.normalize(line)
                    if (terms.all { normalizedLine.contains(it) }) {
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

        /** Mirrors search-core's `RANKING_ORDER` tiebreak chain (score desc, then recency desc,
         *  then id asc) exactly, typed for [SearchHit] instead of `Scored` so re-sorting the
         *  capped hit list on every emit doesn't need a full doc re-projection. */
        private val HIT_ORDER: Comparator<SearchHit> =
            compareByDescending<SearchHit> { it.score }
                .thenByDescending { it.entry.lastModifiedMillis ?: 0L }
                .thenBy { it.entry.uri.toString() }
    }
}
