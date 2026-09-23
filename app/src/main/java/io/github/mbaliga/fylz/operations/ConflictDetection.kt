package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** One top-level source item that already has a same-named sibling at the destination -- what a
 * P1.6 `ConflictSheet` needs to show its compare card (size, modified date, thumbnail, hash on
 * demand) and let the user resolve. */
data class ConflictedItem(
    val source: DocNode,
    val existing: DocNode,
)

/**
 * Checks every one of [sources] against [destinationTreeUri]'s own root, before a copy or move
 * ever starts, so [ConflictPolicy.ASK] can be resolved once per item through a real sheet instead
 * of [FileOperationService]'s own `resolveTargetPlan` throwing mid-transfer. [nameOverrides]
 * mirrors what the transfer itself will actually use as each item's target name (a Preflight
 * sheet's own auto-rename choice, P1.5) -- checking a source's own original name here would find
 * (or miss) the wrong conflicts for anything already renamed.
 *
 * A source that no longer resolves is silently skipped, same reasoning as
 * [gatherPreflightItems]: [FileOperationService] itself will report that failure clearly when it
 * actually tries to open the item, so this has nothing useful to add by refusing the whole check.
 */
internal suspend fun findConflicts(
    resolver: ContentResolver,
    sources: List<Uri>,
    destinationTreeUri: Uri,
    nameOverrides: Map<Uri, String> = emptyMap(),
): List<ConflictedItem> {
    val destinationRootUri = DocumentsContract.buildDocumentUriUsingTree(
        destinationTreeUri,
        DocumentsContract.getTreeDocumentId(destinationTreeUri),
    )
    val destination = DocNode.load(resolver, destinationRootUri) ?: return emptyList()
    return sources.mapNotNull { uri ->
        val source = DocNode.load(resolver, uri) ?: return@mapNotNull null
        val targetName = nameOverrides[uri] ?: source.name
        val existing = destination.findChild(resolver, targetName) ?: return@mapNotNull null
        ConflictedItem(source, existing)
    }
}
