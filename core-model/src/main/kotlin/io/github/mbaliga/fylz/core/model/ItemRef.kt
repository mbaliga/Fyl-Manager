package io.github.mbaliga.fylz.core.model

/**
 * A provider-neutral, opaque handle to one item.
 *
 * This is the WP-1.1 identity contract: everywhere the app used to compare or persist an
 * `android.net.Uri` to mean "this specific item," it now carries an `ItemRef` instead. The
 * shape is deliberately three flat, opaque strings — never a parsed path, never something a
 * caller should split, format, or pattern-match on beyond the equality [ItemIdentity] defines.
 * "Paths are display data" (the plan's own words): a [displayName][ItemSnapshot.displayName]
 * or a breadcrumb trail is for humans to read, not for code to key on.
 *
 * @param providerId which backend minted this ref — this app derives it directly from the
 *   originating `Uri`'s authority (see `app/storage/ItemRefs.kt`), so it is stable across
 *   otherwise-identical items served by different providers and distinguishes them without a
 *   provider registry existing anywhere.
 * @param locationId the root/tree this item lives under, in whatever opaque form the provider
 *   uses to identify it (a SAF tree document id, today). Two items sharing a [locationId] are
 *   in the same tree; nothing about this field's shape may be assumed beyond that.
 * @param opaqueItemId the item itself within [locationId]. By convention (established by the
 *   `Uri` adapter, not enforced by this type) `opaqueItemId == locationId` denotes the tree's
 *   own root — see [ItemIdentity.isRoot] — which replaces the old, provider-shape-sniffing
 *   `Uri.pathSegments` inspection `OperationRetryPolicy` used to do.
 */
data class ItemRef(
    val providerId: String,
    val locationId: String,
    val opaqueItemId: String,
)
