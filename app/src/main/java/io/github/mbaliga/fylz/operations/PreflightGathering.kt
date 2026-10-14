package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.net.Uri
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds one [PreflightItem] per uri in [sources] -- the impure counterpart to
 * [PreflightPolicy], which stays pure over the result. A source that no longer resolves is
 * dropped rather than failing the whole gather: [FileOperationService.transfer] itself already
 * fails that specific item with its own clear error when it actually tries to open it, so this
 * has nothing useful to add by refusing the whole preflight check over it.
 */
internal suspend fun gatherPreflightItems(resolver: ContentResolver, sources: List<Uri>): List<PreflightItem> =
    sources.mapNotNull { uri ->
        val node = DocNode.load(resolver, uri) ?: return@mapNotNull null
        PreflightItem(
            sourceUri = uri,
            name = node.name,
            isDirectory = node.isDirectory,
            totalBytes = if (node.isDirectory) recursiveSizeBytes(resolver, node) else node.size,
        )
    }

/** Every nested file's size under [node], summed -- a child whose own size is unknown
 * contributes 0 rather than making the whole sum unknown; see [PreflightPolicy]'s own
 * "known sizes only" note on why an undercount here is an acceptable trade. */
private suspend fun recursiveSizeBytes(resolver: ContentResolver, node: DocNode): Long {
    coroutineContext.ensureActive()
    return node.children(resolver).sumOf { child ->
        if (child.isDirectory) recursiveSizeBytes(resolver, child) else (child.size ?: 0L)
    }
}
