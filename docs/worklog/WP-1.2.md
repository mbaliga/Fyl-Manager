# WP-1.2 — Expanded capability vocabulary

**Status: DONE for the vocabulary and the provider migration. Consultation — a command
declaring required capabilities and the UI deriving visibility from them — is explicitly Phase
2 per the plan and is not part of this work package.**

> **Phase 2 addendum (Aug 2026).** Consultation is now live: `SelectionActionPolicy.evaluate`
> takes the leading provider's capability set and gates copy/move/recycle/rename/batch-rename
> through `CapabilityPolicy`. To keep the gates truthful, both providers additionally declare
> `READ`, `STREAM_READ`, `WRITE`, `COPY` and `MOVE` — capabilities their shipping behavior
> (DocumentRepository streaming, the editor's writes, FileOperationService's provider-neutral
> copy/move) has exercised daily since v1 alpha, previously undeclared only because nothing
> consulted them. The honesty rule below is unchanged: nothing claims a value that real
> behavior does not back.

`core-model.ItemCapability` grows the old eight-value `StorageCapability` into the plan's
grouped table: Content (`READ`, `RANGE_READ`, `STREAM_READ`, `WRITE`, `ATOMIC_REPLACE`),
Hierarchy (`LIST`, `PAGED_LIST`, `CREATE_FILE`, `CREATE_DIRECTORY`, `RENAME`, `MOVE`, `COPY`),
Destruction (`TRASH`, `RESTORE_TRASH`, `DELETE_PERMANENT`), Observation (`WATCH`,
`NATIVE_SEARCH`, `CONTENT_SEARCH`, `RECENTS`), Rich data (`THUMBNAIL`, `METADATA_READ`,
`METADATA_WRITE`), History (`VERSION_LIST`, `VERSION_READ`, `VERSION_RESTORE`), and
Availability (`QUOTA`, `FREE_SPACE`), plus `SECURE_ERASE_CLAIM` — a value that exists
specifically so it can be asserted absent, never as something a provider is expected to grant.
`core-vfs.CapabilityPolicy` (built alongside, in WP-1.3's module pass) maps a `UserAction` to
its required `ItemCapability` set and can decide/explain a gate, but nothing calls it yet — it
is tested scaffolding for Phase 2's consultation, exactly as the plan specifies.

## The mapping

Two of the old eight values described the *provider*, not an item or a location within it — how
a root is reached, not what an operation on a reached item can do — so they don't reappear as
`ItemCapability` values. They're now plain booleans on `StorageProvider` itself, each defaulted
`false` via the interface:

- `BROWSE_WITHOUT_PICKER` → `StorageProvider.browseWithoutPicker`
- `WHOLE_VOLUME` → `StorageProvider.wholeVolume`

The remaining six map onto the new vocabulary:

| Old `StorageCapability` | New `ItemCapability` |
|---|---|
| `CREATE` | `CREATE_FILE`, `CREATE_DIRECTORY` |
| `RENAME` | `RENAME` |
| `DELETE` | `DELETE_PERMANENT` |
| `RECYCLE_BIN` | `TRASH`, `RESTORE_TRASH` |
| `RECURSIVE_SEARCH` | `LIST` |
| `CONTENT_SEARCH` | `CONTENT_SEARCH` |

`RECURSIVE_SEARCH` maps to `LIST` rather than `NATIVE_SEARCH` on purpose: the old value meant
"this app can walk this provider's children recursively," which is just `LIST` applied
repeatedly — Fylz has never used a provider's own search feature. `NATIVE_SEARCH`'s KDoc is
explicit that it means the *provider* offers its own name/metadata search "distinct from this
app walking it," so granting it here for a capability that only ever meant "our own recursive
walk works" would overclaim something no current backend does. It stays ungranted by every
provider today, honestly.

Both `SafStorageProvider` and `FileStorageProvider` end up declaring the identical six-value
`ItemCapability` set (`LIST`, `CREATE_FILE`, `CREATE_DIRECTORY`, `RENAME`, `TRASH`,
`RESTORE_TRASH`, `DELETE_PERMANENT`, `CONTENT_SEARCH`) — they always agreed on all six of the
old values too, so this is a faithful, no-op-in-substance rename plus regrouping, not a
capability change. Only `FileStorageProvider` overrides `browseWithoutPicker`/`wholeVolume` to
`true`, exactly matching which of the two it previously declared.

`StorageCapability` itself is deleted from `StorageModels.kt`; nothing else in the app
referenced it (the unrelated `RemoteCapability` vocabulary for the SFTP/SMB/S3 network backends
was untouched — a distinct, already-scoped system the plan doesn't mention).

## Tests

`StorageModelsTest` keeps its provider-distinction test (rewritten against the two booleans
instead of the deleted enum values) and gains a new
`no provider ever claims the secure-erase capability` case — the executable form of the
"Shred never claims forensic erasure" promise the plan's `SECURE_ERASE_CLAIM` KDoc describes,
now checked against both real providers rather than just documented in prose.
