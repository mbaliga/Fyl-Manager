package io.github.mbaliga.fylz.core.model

/**
 * Everything the app knows about one item at the moment it was read.
 *
 * A snapshot, not a live handle: every field is what the provider reported at read time, and
 * nothing here re-queries anything. [capabilities] is deliberately per-snapshot rather than
 * only per-provider — the plan's requirement that capabilities resolve "per location and per
 * item (read-only child inside writable root)" means a read-only file inside an otherwise
 * writable folder reports a narrower [capabilities] set than its parent, not the provider's
 * blanket declaration.
 *
 * @param ref this item's identity. See [ItemRef].
 * @param parentRef the containing location's identity, or null for a root with none.
 * @param displayName what to show a person. Never used for identity or comparison — that is
 *   exactly the mistake [ItemIdentity]'s KDoc warns against.
 * @param kind the coarse shape of the item.
 * @param sizeBytes null when the provider did not report one (routine for directories and for
 *   some virtual documents — never assume this means zero).
 * @param modifiedAtMillis null or zero when the provider did not report a real timestamp.
 * @param contentType the provider-reported MIME type, when it has an opinion.
 * @param versionStamp whatever change-evidence the provider could offer; see [VersionStamp].
 * @param capabilities what this specific item, at this specific location, actually supports —
 *   not necessarily everything its provider is capable of elsewhere.
 * @param metadata provider-reported extras with no dedicated field of their own. Free-form on
 *   purpose: this is the escape hatch for the "no consumer yet" values in [ItemCapability] and
 *   whatever a provider-specific adapter wants to surface without a schema change here.
 */
data class ItemSnapshot(
    val ref: ItemRef,
    val parentRef: ItemRef?,
    val displayName: String,
    val kind: EntryKind,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val contentType: String?,
    val versionStamp: VersionStamp?,
    val capabilities: Set<ItemCapability>,
    val metadata: Map<String, String> = emptyMap(),
)
