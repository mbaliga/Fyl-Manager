package io.github.mbaliga.fylz.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * In-memory [DocumentsProvider] standing in for a third-party SAF backend (test sources only).
 *
 * The File backend can only exhibit what the local filesystem exhibits. Real SAF providers
 * misbehave in ways CI's tmpfs never will -- case-insensitive name spaces, omitted
 * `LAST_MODIFIED` columns, backends that cannot move a document between trees -- so those
 * behaviors are dials here ([caseInsensitiveNames], [nullLastModified], [failCrossTreeMove])
 * and the contract suite exercises both sides of each dial without a device.
 *
 * Structure lives entirely in the node tree; file bytes live in opaque per-node blob files under
 * the app cache, reached only by document id. Name semantics (case, unicode, length) therefore
 * never leak in from the host filesystem, which is the point of a fake.
 *
 * Serves two disjoint roots, [ROOT_A_DOC_ID] and [ROOT_B_DOC_ID], so cross-tree operations have
 * a real second tree to fail against.
 */
class FakeSafDocumentsProvider : DocumentsProvider() {

    private class Node(
        val documentId: String,
        var name: String,
        val mimeType: String,
        var lastModified: Long?,
        var writable: Boolean,
        var parentId: String?,
        /** Byte store for files; null marks a directory. */
        val blob: File?,
    )

    private val nodes = LinkedHashMap<String, Node>()
    private var nextNodeNumber = 1

    private val blobDirectory: File by lazy {
        File(requireNotNull(context).cacheDir, "fake-saf-blobs").apply { mkdirs() }
    }

    override fun onCreate(): Boolean {
        nodes[ROOT_A_DOC_ID] = Node(ROOT_A_DOC_ID, "Tree A", Document.MIME_TYPE_DIR, 0L, true, null, null)
        nodes[ROOT_B_DOC_ID] = Node(ROOT_B_DOC_ID, "Tree B", Document.MIME_TYPE_DIR, 0L, true, null, null)
        return true
    }

    // ---------------------------------------------------------------- roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        listOf("a" to ROOT_A_DOC_ID, "b" to ROOT_B_DOC_ID).forEach { (rootId, documentId) ->
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, rootId)
                add(Root.COLUMN_DOCUMENT_ID, documentId)
                add(Root.COLUMN_TITLE, nodes.getValue(documentId).name)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }
        return cursor
    }

    // ------------------------------------------------------------ documents

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(cursor, requireNode(documentId))
        return cursor
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = requireDirectory(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        // A MatrixCursor is a snapshot by construction: later node-tree mutation never
        // reaches a cursor already handed out, matching real provider transport.
        childrenOf(parent.documentId).forEach { addRow(cursor, it) }
        return cursor
    }

    /**
     * Ancestor walk over parent pointers. `DocumentsContract`'s *UsingTree calls all funnel
     * through this before the provider sees them, so containment must hold transitively.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var ancestorId = nodes[documentId]?.parentId
        while (ancestorId != null) {
            if (ancestorId == parentDocumentId) return true
            ancestorId = nodes[ancestorId]?.parentId
        }
        return false
    }

    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String): String = requireNode(documentId).mimeType

    @Throws(FileNotFoundException::class)
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val node = requireNode(documentId)
        val blob = node.blob ?: throw FileNotFoundException("$documentId is a directory")
        return ParcelFileDescriptor.open(blob, ParcelFileDescriptor.parseMode(mode))
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = requireDirectory(parentDocumentId)
        if (!parent.writable) throw FileNotFoundException("$parentDocumentId is read-only")
        val safeName = displayName.take(MAX_NAME_LENGTH)
        // Same posture as the File backend: the framework contract is "create something",
        // never "overwrite the user's file".
        var candidate = safeName
        var attempt = 1
        while (childrenOf(parent.documentId).any { nameEquals(it.name, candidate) }) {
            candidate = disambiguate(safeName, attempt++)
        }
        val documentId = allocateId()
        val directory = mimeType == Document.MIME_TYPE_DIR
        val blob = if (directory) null else File(blobDirectory, documentId).apply { createNewFile() }
        nodes[documentId] = Node(
            documentId = documentId,
            name = candidate,
            mimeType = mimeType,
            lastModified = if (nullLastModified) null else System.currentTimeMillis(),
            writable = true,
            parentId = parent.documentId,
            blob = blob,
        )
        return documentId
    }

    @Throws(FileNotFoundException::class)
    override fun renameDocument(documentId: String, displayName: String): String? {
        val node = requireNode(documentId)
        val parentId = node.parentId ?: throw FileNotFoundException("cannot rename a root")
        if (!node.writable) throw FileNotFoundException("$documentId is read-only")
        val safeName = displayName.take(MAX_NAME_LENGTH)
        val collides = childrenOf(parentId).any {
            it.documentId != node.documentId && nameEquals(it.name, safeName)
        }
        if (collides) throw FileNotFoundException("$safeName already exists")
        node.name = safeName
        // Ids are stable across rename, like most cloud providers; a non-null return keeps the
        // framework issuing a usable result uri either way.
        return node.documentId
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        removeSubtree(requireNode(documentId))
    }

    @Throws(FileNotFoundException::class)
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val node = requireNode(sourceDocumentId)
        val targetParent = requireDirectory(targetParentDocumentId)
        // Every refusal happens before any mutation: a failed move must leave both trees
        // exactly as they were, never a half-moved document.
        if (failCrossTreeMove && treeAnchorOf(sourceDocumentId) != treeAnchorOf(targetParentDocumentId)) {
            throw FileNotFoundException("this backend cannot move documents between trees")
        }
        if (childrenOf(targetParent.documentId).any { nameEquals(it.name, node.name) }) {
            throw FileNotFoundException("${node.name} already exists at the destination")
        }
        node.parentId = targetParent.documentId
        return node.documentId
    }

    // ------------------------------------------------- out-of-band mutation

    /**
     * Inserts a file directly into the node tree, bypassing every provider code path -- no
     * sanitation, no disambiguation. This is the fake's equivalent of another process writing
     * to disk behind the provider's back. [lastModified] null models a backend that omits the
     * column.
     */
    fun seedFile(parentDocumentId: String, displayName: String, content: ByteArray, lastModified: Long?): String {
        val documentId = allocateId()
        val blob = File(blobDirectory, documentId).apply { writeBytes(content) }
        nodes[documentId] = Node(
            documentId = documentId,
            name = displayName,
            mimeType = "application/octet-stream",
            lastModified = lastModified,
            writable = true,
            parentId = parentDocumentId,
            blob = blob,
        )
        return documentId
    }

    /** Directory counterpart of [seedFile]. */
    fun seedDirectory(parentDocumentId: String, displayName: String): String {
        val documentId = allocateId()
        nodes[documentId] = Node(
            documentId = documentId,
            name = displayName,
            mimeType = Document.MIME_TYPE_DIR,
            lastModified = System.currentTimeMillis(),
            writable = true,
            parentId = parentDocumentId,
            blob = null,
        )
        return documentId
    }

    /** Removes a child (recursively) behind the provider's back. Exact-name match on purpose. */
    fun removeOutOfBand(parentDocumentId: String, displayName: String) {
        removeSubtree(requireChild(parentDocumentId, displayName))
    }

    fun setWritableOutOfBand(parentDocumentId: String, displayName: String, writable: Boolean) {
        requireChild(parentDocumentId, displayName).writable = writable
    }

    fun setLastModifiedOutOfBand(parentDocumentId: String, displayName: String, lastModified: Long?) {
        requireChild(parentDocumentId, displayName).lastModified = lastModified
    }

    // -------------------------------------------------------------- helpers

    private fun addRow(cursor: MatrixCursor, node: Node) {
        val directory = node.blob == null
        var flags = 0
        if (node.writable) {
            flags = Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE
            flags = if (directory) {
                flags or Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                flags or Document.FLAG_SUPPORTS_WRITE
            }
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, node.documentId)
            add(Document.COLUMN_DISPLAY_NAME, node.name)
            add(Document.COLUMN_MIME_TYPE, node.mimeType)
            add(Document.COLUMN_SIZE, if (directory) null else node.blob?.length())
            // Served as stored: a null stays null, so consumers meet the column shape real
            // backends actually produce instead of an invented timestamp.
            add(Document.COLUMN_LAST_MODIFIED, node.lastModified)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun allocateId(): String = "node-${nextNodeNumber++}"

    @Throws(FileNotFoundException::class)
    private fun requireNode(documentId: String): Node =
        nodes[documentId] ?: throw FileNotFoundException("$documentId does not exist")

    @Throws(FileNotFoundException::class)
    private fun requireDirectory(documentId: String): Node {
        val node = requireNode(documentId)
        if (node.blob != null) throw FileNotFoundException("$documentId is not a directory")
        return node
    }

    @Throws(FileNotFoundException::class)
    private fun requireChild(parentDocumentId: String, displayName: String): Node =
        childrenOf(parentDocumentId).firstOrNull { it.name == displayName }
            ?: throw FileNotFoundException("$displayName not found under $parentDocumentId")

    private fun childrenOf(parentDocumentId: String): List<Node> =
        nodes.values.filter { it.parentId == parentDocumentId }

    private fun removeSubtree(node: Node) {
        childrenOf(node.documentId).forEach(::removeSubtree)
        node.blob?.delete()
        nodes.remove(node.documentId)
    }

    private fun treeAnchorOf(documentId: String): String {
        var current = requireNode(documentId)
        while (true) {
            val parentId = current.parentId ?: return current.documentId
            current = requireNode(parentId)
        }
    }

    private fun nameEquals(left: String, right: String): Boolean =
        if (caseInsensitiveNames) left.equals(right, ignoreCase = true) else left == right

    private fun disambiguate(name: String, attempt: Int): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        return if (extension.isEmpty()) "$stem ($attempt)" else "$stem ($attempt).$extension"
    }

    companion object {
        const val AUTHORITY: String = "fylz.test.saf"
        const val ROOT_A_DOC_ID: String = "root-a"
        const val ROOT_B_DOC_ID: String = "root-b"

        private const val MAX_NAME_LENGTH = 255

        /**
         * Behavior dials, static because Robolectric instantiates the provider reflectively.
         * Set before registering the provider; [resetKnobs] restores defaults so no test class
         * leaks its configuration into another.
         */
        var caseInsensitiveNames: Boolean = false
        var nullLastModified: Boolean = false
        var failCrossTreeMove: Boolean = false

        fun resetKnobs() {
            caseInsensitiveNames = false
            nullLastModified = false
            failCrossTreeMove = false
        }

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
