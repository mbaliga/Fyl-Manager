package io.github.mbaliga.fylz.storage

import android.net.Uri
import android.provider.DocumentsContract
import io.github.mbaliga.fylz.core.model.ItemRef

/**
 * The WP-1.1 adapter layer: `Uri` in, [ItemRef] out, and back again.
 *
 * This is the ONLY place in the app that is allowed to know what a SAF `Uri`'s segments mean.
 * Everywhere else — `core-operations`, `OperationRetryPolicy`, the journal — works in
 * [ItemRef] and never inspects a `Uri`'s shape again. That boundary is the point: SAF's
 * `tree`/`document` path convention is one provider family's addressing scheme, not a fact
 * about identity in general, and acceptance law #8 ("no provider-specific exception leaks into
 * the UI as an unexamined `if`") means that scheme gets exactly one place to leak into.
 *
 * Both providers this app ships — the local `file` backend
 * ([io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider]) and any real SAF provider —
 * mint URIs through `DocumentsContract`, so the same parsing covers both: `providerId` is
 * simply the URI's authority, which is already a stable, provider-unique identifier with no
 * registry required.
 *
 * ### The two shapes
 *
 * A tree-shaped `content://authority/tree/{treeDocId}[/document/{docId}]` URI — everything
 * this app's own code ever mints as a source or destination — maps to
 * `ItemRef(authority, treeDocId, docId)`, with `docId` defaulting to `treeDocId` when the URI
 * has no explicit document segment. **Both spellings of "this is the tree's own root" collapse
 * to the identical ref**: a bare tree URI and a tree-document URI naming that same root produce
 * `opaqueItemId == locationId` either way (see [io.github.mbaliga.fylz.core.model.ItemIdentity.isRoot]).
 * That is a real correctness fix, not just a rename — see that function's KDoc for what it
 * replaces.
 *
 * Anything else — a plain (non-tree) document URI, a different scheme entirely, a URI this
 * parsing doesn't recognize — is never rejected. It becomes a **fallback ref** carrying the
 * complete original URI string verbatim as its [ItemRef.opaqueItemId], round-tripping exactly
 * through [ItemRef.toUri] with no loss, so this function never throws and every `Uri` this app
 * has ever produced converts to *something* usable.
 */
// A word no real provider's document id scheme produces (FylzFilesDocumentsProvider's own ids,
// and every SAF authority observed, follow a `root:relative/path` shape) -- distinctive enough
// that a genuine collision is a theoretical, never-triggered risk rather than a live one, and
// plain text so this source file stays plain text (an embedded NUL byte would make git/grep
// treat the whole file as binary). If a tree document id ever DID equal this word, toUri()
// cannot tell that ref apart from a genuine fallback ref -- ItemRefsTest pins that exact,
// accepted degradation rather than asserting a round-trip guarantee this design cannot make.
private const val FALLBACK_LOCATION = "fylz-fallback-item-ref"

fun Uri.toItemRef(): ItemRef {
    val providerId = authority.orEmpty()
    val segments = pathSegments
    if (segments.size >= 2 && segments[0] == "tree") {
        val treeDocId = segments[1]
        val documentId = if (segments.size >= 4 && segments[2] == "document") segments[3] else treeDocId
        return ItemRef(providerId, treeDocId, documentId)
    }
    return ItemRef(providerId, FALLBACK_LOCATION, toString())
}

fun ItemRef.toUri(): Uri {
    if (locationId == FALLBACK_LOCATION) return Uri.parse(opaqueItemId)
    val treeUri = Uri.Builder()
        .scheme("content")
        .authority(providerId)
        .appendPath("tree")
        .appendPath(locationId)
        .build()
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, opaqueItemId)
}
