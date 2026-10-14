package io.github.mbaliga.fylz.archive

import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Which archive a document belongs to: the outermost *file* [source] (never an archive-provider
 * Uri) and the [chain] of nested archive entry paths from it inward -- empty for a top-level
 * archive. Two ids with the same [ArchiveRef] are documents of one archive listing.
 */
data class ArchiveRef(val source: Uri, val chain: List<String>) {
    /** Archive levels, counting [source] itself. */
    val depth: Int get() = chain.size + 1
}

/**
 * The document id of `ArchiveDocumentsProvider` (`docs/agent/DESIGN-M33-ARCHIVE-BROWSING.md`
 * section 2.1): URL-safe base64 without padding of one flat JSON object,
 *
 * ```
 * {"v":1,"src":"content://…/document/photos.zip","n":["inner1.zip","d/inner2.zip"],"o":1234,"p":"2024/a.jpg"}
 * ```
 *
 * `src` is the outermost file Uri, `n` the chain of nested archive entry paths from it inward,
 * `o` the entry's **ordinal** among the archive's raw headers ([IMPLICIT_ORDINAL] for the archive
 * root and for directories the listing never named -- [ArchiveTree] synthesises those), and `p`
 * the normalised entry path (`""` for the root). Depth is `n.size + 1` archive levels and may be
 * at most [MAX_DEPTH]: the fifth level is refused here, at construction, with the message the UI
 * shows. Growth is linear in depth and nothing parses ids recursively.
 *
 * An id identifies an entry by position, so duplicate paths and case collisions are distinct
 * documents and `extract_entry_at` fetches exactly the header the user clicked. Ids carry no
 * listing key: a clipboard entry pasted after the archive was replaced copies the new archive's
 * entry at that position, as a replaced file would (logged).
 *
 * [parse] treats an id as hostile: bad base64, non-JSON, a wrong version, an unnormalised `p`, a
 * chain entry that is not a normalised path, an `o` below -1 or a `src` on this provider's own
 * authority are refused with an [IllegalArgumentException].
 */
data class ArchiveDocumentId(
    val source: Uri,
    val chain: List<String>,
    val ordinal: Int,
    val path: String,
) {
    init {
        require(chain.size + 1 <= MAX_DEPTH) { DEPTH_REFUSED }
        require(ordinal >= IMPLICIT_ORDINAL) { "ordinal $ordinal is below $IMPLICIT_ORDINAL" }
        require(isNormalizedPath(path)) { "path is not normalised: $path" }
        chain.forEach { require(it.isNotEmpty() && isNormalizedPath(it)) { "chain entry is not a normalised path: $it" } }
        require(source.authority != AUTHORITY) { "source must be a file Uri, not an archive document" }
    }

    /** The archive this document belongs to. */
    val archive: ArchiveRef get() = ArchiveRef(source, chain)

    /** Archive levels, counting [source] itself; at most [MAX_DEPTH]. */
    val depth: Int get() = chain.size + 1

    val isRoot: Boolean get() = path.isEmpty()

    /** The document's display name: the last path segment, or the source's own name for the root. */
    val name: String get() = if (isRoot) (chain.lastOrNull() ?: source.lastPathSegment ?: "archive").substringAfterLast('/') else path.substringAfterLast('/')

    /** The id of the archive rooted at this entry (an archive inside this archive); refuses depth 5. */
    fun nestedRoot(): ArchiveDocumentId {
        check(!isRoot) { "the root is not an entry" }
        return ArchiveDocumentId(source, chain + path, IMPLICIT_ORDINAL, "")
    }

    /** A sibling id in the same archive. */
    fun entry(ordinal: Int, path: String): ArchiveDocumentId = ArchiveDocumentId(source, chain, ordinal, path)

    fun encode(): String {
        val json = JSONObject()
            .put("v", VERSION)
            .put("src", source.toString())
            .put("n", JSONArray(chain))
            .put("o", ordinal)
            .put("p", path)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** The non-tree document Uri of this document. */
    fun toUri(): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, encode())

    companion object {
        /** The archive provider's authority; hard-coded, never `${applicationId}` (as the File provider's is). */
        const val AUTHORITY: String = "io.github.mbaliga.fylz.archives"

        const val VERSION = 1

        /** The most archive levels a document may sit in, counting the outermost file. */
        const val MAX_DEPTH = 4

        /** The ordinal of the archive root and of a directory the listing never named. */
        const val IMPLICIT_ORDINAL = -1

        const val DEPTH_REFUSED = "Archives nested deeper than $MAX_DEPTH levels cannot be browsed"

        /** The root document of the archive [archive]. */
        fun root(archive: ArchiveRef): ArchiveDocumentId = ArchiveDocumentId(archive.source, archive.chain, IMPLICIT_ORDINAL, "")

        /** The root document of the top-level archive file at [source]. */
        fun root(source: Uri): ArchiveDocumentId = root(ArchiveRef(source, emptyList()))

        fun isArchiveUri(uri: Uri?): Boolean = uri != null && uri.authority == AUTHORITY

        @Throws(IllegalArgumentException::class)
        fun parse(uri: Uri): ArchiveDocumentId {
            require(isArchiveUri(uri)) { "not an archive document: $uri" }
            return parse(DocumentsContract.getDocumentId(uri))
        }

        @Throws(IllegalArgumentException::class)
        fun parse(id: String): ArchiveDocumentId {
            val bytes = try {
                Base64.getUrlDecoder().decode(id)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("archive document id is not base64url", e)
            }
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: JSONException) {
                throw IllegalArgumentException("archive document id is not JSON", e)
            }
            try {
                require(json.getInt("v") == VERSION) { "archive document id version ${json.optInt("v")} is not $VERSION" }
                val source = json.getString("src")
                require(source.isNotBlank()) { "archive document id has no source" }
                val chainJson = json.getJSONArray("n")
                val chain = List(chainJson.length()) { index -> chainJson.getString(index) }
                return ArchiveDocumentId(Uri.parse(source), chain, json.getInt("o"), json.getString("p"))
            } catch (e: JSONException) {
                throw IllegalArgumentException("archive document id is missing a field: ${e.message}", e)
            }
        }

        /**
         * Whether [path] is in the tree's normal form: empty (the root), or `/`-separated segments
         * none of which is empty, `.` or `..`. A backslash is an ordinary character here -- only a
         * ZIP's separators are rewritten, and that happens in the tree, before an id exists.
         */
        fun isNormalizedPath(path: String): Boolean =
            path.isEmpty() || path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
