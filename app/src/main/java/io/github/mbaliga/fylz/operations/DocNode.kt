package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

/**
 * An immutable snapshot of one document, built from a single [ContentResolver] query.
 *
 * Replaces `DocumentFile` in `operations/` and `FileTools`: `DocumentFile.fromSingleUri(...)`
 * cannot list children or rename (both throw), and its `parentFile` is always null, because a
 * single-URI `DocumentFile` carries no tree context. Every [android.net.Uri] this app hands to
 * `operations/` is already a tree-based document URI (see `FylzFilesDocumentsProvider`'s and
 * `DocumentRepository`'s use of `DocumentsContract.buildDocumentUriUsingTree`), so every
 * `DocumentsContract` call below -- which needs that tree context -- works everywhere a `DocNode`
 * is used.
 *
 * A `DocNode` never derives a parent for itself; callers pass the parent explicitly (A2), because
 * `DocumentFile.parentFile` is exactly the null-returning trap this type exists to avoid.
 */
data class DocNode(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long?,
    val flags: Int,
    val isDirectory: Boolean,
) {
    /** Lists every child in one cursor pass. Child URIs are built from this node's own [uri], so
     * they carry the same tree as this node -- never a bare document URI. */
    fun children(resolver: ContentResolver): List<DocNode> {
        require(isDirectory) { "$name is not a directory" }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val result = mutableListOf<DocNode>()
        // See the note on load(): the classic (selection, selectionArgs, sortOrder) query
        // overload throws from DocumentsProvider's base class; this goes through the modern one.
        resolver.query(childrenUri, PROJECTION, null as android.os.Bundle?, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(cursor.columnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                result += fromCursor(childUri, cursor)
            }
        }
        return result
    }

    /** Creates a child document and reads it back as a [DocNode]. */
    fun createChild(resolver: ContentResolver, mimeType: String, name: String): DocNode {
        require(isDirectory) { "$name's parent (${this.name}) is not a directory" }
        val childUri = DocumentsContract.createDocument(resolver, uri, mimeType, name)
            ?: error("Unable to create $name in ${this.name}.")
        return load(resolver, childUri) ?: error("Created $name but could not read it back.")
    }

    /** The one child named [name], or null. One [children] query per call, same cost as the
     * `DocumentFile.findFile` calls this replaces. */
    fun findChild(resolver: ContentResolver, name: String): DocNode? =
        children(resolver).firstOrNull { it.name == name }

    /** Mirrors `DocumentFile.canWrite()`'s own flag check; [flags] is otherwise exposed raw. */
    val canWrite: Boolean
        get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0 ||
            flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0 ||
            (isDirectory && flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)

    /**
     * Renames this document and returns the resulting node. The document id -- and therefore the
     * uri -- may change as a result; the caller must use the returned node, not this one, for any
     * further operation.
     */
    fun rename(resolver: ContentResolver, name: String): DocNode {
        val newUri = DocumentsContract.renameDocument(resolver, uri, name) ?: uri
        return load(resolver, newUri) ?: error("Renamed to $name but could not read it back.")
    }

    /**
     * `DocumentsContract.deleteDocument`'s documented contract is "true if successfully deleted",
     * not "throws on failure" -- every caller in this codebase (the move source-delete fallback
     * to `MOVE_SOURCE_DELETE_PENDING` chief among them) depends on that. A refusing provider's
     * exception is caught here and reported as `false`, rather than left to crash the caller.
     */
    fun delete(resolver: ContentResolver): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Exception) {
        false
    }

    /** Re-reads this document. Null means it is gone. */
    fun refresh(resolver: ContentResolver): DocNode? = load(resolver, uri)

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

        /**
         * Loads the document at [uri]. Null means it does not exist.
         *
         * Goes through the `(Bundle?, CancellationSignal?)` query overload, not the classic
         * `(selection, selectionArgs, sortOrder)` one: `DocumentsProvider`'s base class throws
         * `UnsupportedOperationException("Pre-Android-O query format not supported.")` from the
         * classic overload -- confirmed under this test suite's Robolectric-hosted real provider,
         * for both a single-document uri (here) and a children uri ([children]). Whether the
         * classic overload happens to still work against a real on-device DocumentsProvider (some
         * other code in this app, e.g. `DocumentRepository`, still uses it) is untested; this
         * method uses the one overload confirmed to work everywhere.
         */
        fun load(resolver: ContentResolver, uri: Uri): DocNode? =
            resolver.query(uri, PROJECTION, null as android.os.Bundle?, null)?.use { cursor ->
                if (cursor.moveToFirst()) fromCursor(uri, cursor) else null
            }

        private fun fromCursor(uri: Uri, cursor: Cursor): DocNode {
            val documentId = cursor.getString(cursor.columnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val name = cursor.getString(cursor.columnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: "Untitled"
            val mimeType = cursor.getString(cursor.columnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE))
                ?: "application/octet-stream"
            val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            return DocNode(
                uri = uri,
                documentId = documentId,
                name = name,
                mimeType = mimeType,
                size = cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE).takeUnless { isDirectory },
                lastModified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                flags = cursor.intOrZero(DocumentsContract.Document.COLUMN_FLAGS),
                isDirectory = isDirectory,
            )
        }

        private fun Cursor.columnIndex(column: String): Int = getColumnIndexOrThrow(column)

        private fun Cursor.longOrNull(column: String): Long? {
            val index = getColumnIndex(column)
            return if (index < 0 || isNull(index)) null else getLong(index)
        }

        private fun Cursor.intOrZero(column: String): Int {
            val index = getColumnIndex(column)
            return if (index < 0 || isNull(index)) 0 else getInt(index)
        }
    }
}
