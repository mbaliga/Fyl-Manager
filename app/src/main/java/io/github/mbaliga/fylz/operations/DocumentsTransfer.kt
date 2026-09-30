package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * P1.3 / A5: the provider-neutral path for a transfer [LocalFileTransfer] cannot take directly --
 * everything routed through `DocumentsContract`/`ContentResolver`, the way every transfer in this
 * app worked before this task. The universal fallback: [supports] is always true.
 */
class DocumentsTransfer(private val context: Context) : TransferEngine {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun supports(source: DocNode, destinationDirectory: DocNode): Boolean = true

    /**
     * `DocumentsContract.moveDocument` when [source] and [destinationDirectory] share an authority,
     * [source] reports [android.provider.DocumentsContract.Document.FLAG_SUPPORTS_MOVE], and
     * [sourceParent] is known -- letting a provider that can re-parent a document server-side (a
     * cloud provider chief among them) do exactly that instead of a byte-for-byte copy and delete.
     */
    override suspend fun moveFile(
        source: DocNode,
        sourceParent: DocNode?,
        destinationDirectory: DocNode,
        requestedName: String,
    ): DocNode? = withContext(Dispatchers.IO) {
        if (sourceParent == null) return@withContext null
        if (source.uri.authority != destinationDirectory.uri.authority) return@withContext null
        if (source.flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE == 0) return@withContext null
        val movedUri = runCatching {
            DocumentsContract.moveDocument(resolver, source.uri, sourceParent.uri, destinationDirectory.uri)
        }.getOrNull() ?: return@withContext null
        renamedIfNeeded(loadResult(movedUri, destinationDirectory), requestedName)
    }

    /**
     * Tries `DocumentsContract.copyDocument` first, when [source] and [destinationDirectory] share
     * an authority and [source] reports [android.provider.DocumentsContract.Document.FLAG_SUPPORTS_COPY]
     * -- a provider that can duplicate a document itself (again, most valuable for a cloud provider,
     * which can then skip downloading and re-uploading the bytes through this app entirely) does
     * that instead of a stream copy. `copyDocument`'s own contract is to hand back a complete,
     * already-written document or fail outright, never a partial one, so the brief window between
     * that success and the rename below (to [requestedName], if the provider did not already use
     * it) is an ordinary same-authority orphan risk on a process death, not a half-written file --
     * unlike every *stream* copy in this codebase, which is why only those stage under a
     * `.fylz-part-*` name first (P0.6) and this path deliberately does not.
     *
     * Falls back to [streamCopy], 512 KiB at a time (up from the 8 KiB default this used to run
     * at), for every other case: a different authority, no flag, or the provider's own copy
     * failing.
     */
    override suspend fun copyFile(
        source: DocNode,
        destinationDirectory: DocNode,
        requestedName: String,
        onStaged: (DocNode) -> Unit,
        onProgress: (Long) -> Unit,
    ): DocNode = withContext(Dispatchers.IO) {
        // No onStaged call for the provider's own copyDocument: see its own KDoc for why it never
        // has an incomplete intermediate document to record in the first place.
        providerCopy(source, destinationDirectory, requestedName)
            ?: streamCopy(source, destinationDirectory, requestedName, onStaged, onProgress)
    }

    private fun providerCopy(source: DocNode, destinationDirectory: DocNode, requestedName: String): DocNode? {
        if (source.uri.authority != destinationDirectory.uri.authority) return null
        if (source.flags and DocumentsContract.Document.FLAG_SUPPORTS_COPY == 0) return null
        val copiedUri = runCatching {
            DocumentsContract.copyDocument(resolver, source.uri, destinationDirectory.uri)
        }.getOrNull() ?: return null
        return renamedIfNeeded(loadResult(copiedUri, destinationDirectory), requestedName)
    }

    private fun renamedIfNeeded(node: DocNode?, requestedName: String): DocNode? {
        if (node == null) return null
        if (node.name == requestedName) return node
        return runCatching { node.rename(resolver, requestedName) }.getOrNull()
    }

    /**
     * `moveDocument`/`copyDocument`'s returned uri, per the framework's own implementation, keeps
     * [source]'s own tree context rather than [destinationDirectory]'s -- fine when that tree was
     * already rooted broadly enough to cover the new location, but `DocumentsProvider.enforceTree`
     * throws a [SecurityException] querying it otherwise (confirmed against the real provider:
     * [source] and [destinationDirectory] each carrying their own narrowly-scoped tree, rather than
     * sharing one from a common ancestor, is enough to trigger it). Retried once against
     * [destinationDirectory]'s own tree, which is always valid for a document that now lives under
     * it, before giving up.
     */
    private fun loadResult(resultUri: Uri, destinationDirectory: DocNode): DocNode? =
        runCatching { DocNode.load(resolver, resultUri) }.getOrNull()
            ?: runCatching {
                val rebuilt = DocumentsContract.buildDocumentUriUsingTree(
                    destinationDirectory.uri,
                    DocumentsContract.getDocumentId(resultUri),
                )
                DocNode.load(resolver, rebuilt)
            }.getOrNull()

    private suspend fun streamCopy(
        source: DocNode,
        destinationDirectory: DocNode,
        requestedName: String,
        onStaged: (DocNode) -> Unit,
        onProgress: (Long) -> Unit,
    ): DocNode {
        val target = destinationDirectory.createChild(resolver, source.mimeType, requestedName)
        onStaged(target)
        try {
            val input = resolver.openInputStream(source.uri) ?: error("Unable to read ${source.name}.")
            val output = resolver.openOutputStream(target.uri, "w") ?: error("Unable to write ${source.name}.")
            var completed = 0L
            input.use { sourceStream ->
                output.use { targetStream ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        targetStream.write(buffer, 0, count)
                        completed += count
                        onProgress(completed)
                    }
                    targetStream.flush()
                }
            }
            return target.refresh(resolver) ?: error("${source.name} was written but has already vanished.")
        } catch (failure: Throwable) {
            target.delete(resolver)
            throw failure
        }
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 512 * 1024
    }
}
