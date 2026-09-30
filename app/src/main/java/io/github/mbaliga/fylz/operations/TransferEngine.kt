package io.github.mbaliga.fylz.operations

import android.content.Context

/**
 * P1.3 / A5: two I/O paths behind one interface. [LocalFileTransfer] handles jobs where both ends
 * resolve to a [java.io.File] through Fylz's own [io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider]
 * (that provider runs in this process, so resolving is a direct call); [DocumentsTransfer] handles
 * everything else, over `DocumentsContract`/`ContentResolver` like every provider-neutral operation
 * always has. [TransferEngines.forPair] picks the engine per item; nothing above it (the UI,
 * [FileOperationService], [RecycleBinService]) ever needs to know which one ran.
 */
interface TransferEngine {

    /** True when this engine can operate on [source] and [destinationDirectory] directly. */
    fun supports(source: DocNode, destinationDirectory: DocNode): Boolean

    /**
     * Copies [source] (a single file, never a directory -- the recursive tree walk over a
     * directory's children stays the caller's job) into [destinationDirectory] under
     * [requestedName]. [onProgress] is called with cumulative bytes written so far.
     *
     * [onStaged], if given, is called once with the destination-side document as soon as it
     * exists on the provider but before it is necessarily complete (P0.6: so a caller can record
     * it for orphan recovery before the first byte is written) -- not called at all when this
     * engine's path never has an incomplete intermediate document in the first place (see
     * [DocumentsTransfer]'s own `copyDocument` fast path).
     */
    suspend fun copyFile(
        source: DocNode,
        destinationDirectory: DocNode,
        requestedName: String,
        onStaged: (DocNode) -> Unit = {},
        onProgress: (Long) -> Unit,
    ): DocNode

    /**
     * Attempts to move [source] (a file or a whole directory tree) into [destinationDirectory] as
     * [requestedName] in one step, without copying its bytes. [sourceParent] is [source]'s own
     * parent folder, needed only by [DocumentsTransfer]'s `DocumentsContract.moveDocument` path
     * (A2: a caller-supplied parent, never derived from the node itself); [LocalFileTransfer]
     * ignores it, since a `File` rename needs no parent context.
     *
     * @return the moved node, or null when this engine cannot move [source] directly -- a
     *   cross-volume [LocalFileTransfer] pair, a [DocumentsTransfer] pair on different
     *   authorities, one whose provider does not report move support, or no [sourceParent] to
     *   move through. The caller falls back to its own copy-then-delete path.
     */
    suspend fun moveFile(
        source: DocNode,
        sourceParent: DocNode?,
        destinationDirectory: DocNode,
        requestedName: String,
    ): DocNode?
}

/** Picks [LocalFileTransfer] when it can handle a pair, [DocumentsTransfer] otherwise -- the one
 * place A5's "the engine picks the path per item" decision is actually made. */
class TransferEngines(context: Context) {
    private val local: TransferEngine = LocalFileTransfer(context)
    private val documents: TransferEngine = DocumentsTransfer(context)

    fun forPair(source: DocNode, destinationDirectory: DocNode): TransferEngine =
        if (local.supports(source, destinationDirectory)) local else documents
}
