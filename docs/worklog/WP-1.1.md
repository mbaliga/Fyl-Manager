# WP-1.1 — Opaque item identity

**Status: DONE.**

`core-model`'s `ItemRef(providerId, locationId, opaqueItemId)` replaces `android.net.Uri` as the
identity type everywhere the plan named: the operation journal model
(`core-operations.OperationItem`/`FileOperation`), the retry planner, and the low-risk call
sites the plan called out by name. `opaqueItemId` wraps whatever a provider's own document ID
looks like today (a SAF document ID) but the type promises nothing about its shape — no code
outside the Uri↔`ItemRef` adapter is allowed to parse it.

`VersionStamp` is a sealed interface with one case today, `Composite(sizeBytes, modifiedAtMillis)`
— the same size+mtime pair the app already had — leaving room for a future `Revision(token)` case
once a provider hands back a real ETag/revision string, per the plan's explicit "never infer
equality from pathname + mtime alone" instruction. `ItemIdentity.compareVersions` is built and
tested (`Same`/`Changed`/`Unknown`) but deliberately **not** wired into
`FileOperationService.finishMoveCleanup`'s existing size check — that would change today's
"unreadable size => proceed" behavior into something a caller might legitimately want to be
"unreadable => block" per the plan's acceptance law #2, and that's a real behavior decision for
whoever consumes it, not a mechanical migration. It ships as tested, documented, unwired
scaffolding, same as WP-1.2's capability vocabulary.

`ItemIdentity.isRoot(ref)` (`opaqueItemId == locationId`) replaced
`OperationRetryPolicy`'s old raw `Uri` path-segment inspection for "is this destination the
location's own root, safe to replay a copy into." It is also a genuine bug fix, not just a
rewrite: two different-looking-but-equivalent tree-root URIs (`content://.../tree/x` and
`content://.../tree/x/document/x`) previously compared unequal via `Uri.toString()`, wrongly
blocking a valid batch retry. Pinned by
`a bare-tree and a tree-document-at-root destination are recognized as the same folder` in
`core-operations`'s `OperationRetryPolicyTest`.

## The adapter

`app/src/main/java/io/github/mbaliga/fylz/storage/ItemRefs.kt` is the one place a `Uri` becomes
an `ItemRef` and back. A tree-shaped URI (`tree/<id>` or `tree/<id>/document/<id>`) becomes
`ItemRef(authority, treeDocId, documentId)`; anything else falls back to
`ItemRef(authority, "fylz-fallback-item-ref", uri.toString())` rather than throwing, so a URI
shape this app hasn't seen yet degrades to "opaque, not equal to anything but its exact former
self" instead of crashing a file operation. Root-collapse (`isRoot`) and the fallback path are
both pinned in `ItemRefsTest`.

## Call sites migrated

Journal-facing `OperationItem` construction in `FileOperationService`, `RecycleBinService`,
`FileTools` (batch rename), `ArchiveService` (create/extract ZIP), and
`DuplicateCleanupService` now build `ItemRef`s at construction and convert back to `Uri` only at
the boundary where a `DocumentFile`/`DocumentsContract` call actually needs one. Deliberately
**not** migrated: `RecycleRecord`'s own persisted fields (`originalUri`, `recycledUri`,
`originalParentUri`) and `BatchRenamePlan`/`DuplicateGroup`'s upstream `Uri`-typed working data —
only the point where a `Uri` crosses into the journal's `OperationItem`/`FileOperation` shape
needed to change. Keeping `RecycleRecord` itself Uri-typed for now keeps this phase's blast
radius to what the plan actually asked for; migrating it is a natural WP-2-or-later follow-up,
not a gap discovered late.

Two dormant helpers got wired up rather than rewritten: `FileHistoryStore.migrateSource`
already existed (untested, uncalled) and now runs from `DocumentRepository.rename()` whenever a
provider hands back a different URI after a rename, so file-history versions survive a rename
instead of orphaning under a URI nothing resolves to any more. `LibraryStore.migrateUri` is new
— the same idea for favorites and tags, which were never covered by anything — and merges tag
sets rather than overwriting when the destination URI already carries some, so a stray metadata
collision after two renames land on the same name doesn't silently drop one side. Covered by
five new `LibraryStoreTest` cases (favorite migration, tag migration, tag merge, no-op on an
untracked URI, no-op on a self-rename).

## What did not move

`DuplicateCleanupPolicy`/`Service`, though touched to keep them compiling against the new
`DuplicateGroup.items: List<ItemRef>`, still live in `app` rather than a `core-*` module — they
weren't named in WP-1.3's extraction list, and moving them wasn't necessary to satisfy WP-1.1's
identity migration.
