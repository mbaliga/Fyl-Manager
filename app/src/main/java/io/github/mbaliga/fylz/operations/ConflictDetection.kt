package io.github.mbaliga.fylz.operations

import android.content.ContentResolver
import android.net.Uri

/** One top-level source item that already has a same-named sibling at the destination -- what a
 * P1.6 `ConflictSheet` needs to show its compare card (size, modified date, thumbnail, hash on
 * demand) and let the user resolve. [hashable] is `false` for a [source] built by
 * [DocNode.descriptor] (M3.4, an archive entry with no real document to open): the sheet's own
 * "Show hash" button is never offered for a side that has nothing a `ContentResolver` can stream. */
data class ConflictedItem(
    val source: DocNode,
    val existing: DocNode,
    val hashable: Boolean = true,
)

/**
 * Checks every one of [sources] against [destinationTreeUri]'s own resolved document (P1.8: a bare
 * tree grant's root, or the exact, possibly nested, document a URI that already names one points
 * to -- see [DocNode.resolveDestinationUri]), before a copy or move ever starts, so
 * [ConflictPolicy.ASK] can be resolved once per item through a real sheet instead of
 * [FileOperationService]'s own `resolveTargetPlan` throwing mid-transfer. [nameOverrides] mirrors
 * what the transfer itself will actually use as each item's target name (a Preflight sheet's own
 * auto-rename choice, P1.5) -- checking a source's own original name here would find (or miss) the
 * wrong conflicts for anything already renamed.
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
    val destination = DocNode.loadDestination(resolver, destinationTreeUri) ?: return emptyList()
    return sources.mapNotNull { uri ->
        val source = DocNode.load(resolver, uri) ?: return@mapNotNull null
        val targetName = nameOverrides[uri] ?: source.name
        val existing = destination.findChild(resolver, targetName) ?: return@mapNotNull null
        ConflictedItem(source, existing)
    }
}
