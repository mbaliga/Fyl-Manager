# WP-1.4 — Journal serialization versioning

**Status: DONE.**

`core-operations.JournalSchema.CURRENT_VERSION = 2` is the first real version number the
operation journal's wire format has had — version 1 is the implicit, unversioned shape every
already-committed on-device record is in today. `2` exists because WP-1.1 changed something
that actually needed a version bump: `source`/`destination` went from a plain `Uri` string to a
nested `{providerId, locationId, opaqueItemId}` object.

`JournalSchema`'s KDoc is the migration contract, and `OperationJournal.persist()`/`decode()`
follow it exactly:

- **Write** `schemaVersion = JournalSchema.CURRENT_VERSION` on every `persist()`.
- **Read** defensively — `decode()` never gates behavior on the version number at all; the
  field exists for documentation and future-proofing, not as a branch condition.
- **Decode `source`/`destination` by JSON shape, not by version**: `JSONObject.decodeItemRef`
  inspects `opt(key)` — a `JSONObject` is the current `ItemRef` encoding (read directly), a
  `String` is a pre-migration `Uri` (parsed and run through `ItemRefs.kt`'s
  `Uri.toItemRef()` adapter), and a missing/null key decodes as `null` (valid for the optional
  `destination` field; `source` is required and `decode()` fails that one record via the
  existing `runCatching` rather than inventing a fake ref). Shape-sniffing per field, rather
  than branching once on the record's own `schemaVersion`, means a batch of records written
  across an app upgrade — some old-shaped, some new — decodes correctly without needing every
  item in one record to agree on version.
- **Never refuse a record for an unrecognized or future version number.** Nothing in `decode()`
  compares `schemaVersion` against `CURRENT_VERSION` at all — an unknown future field is simply
  never read, the same way any bare `org.json` property this build doesn't ask for already goes
  untouched.

## What stayed the same on purpose

`decode()`'s existing all-or-nothing `runCatching` around the whole `JSONArray` — genuinely
corrupted JSON still drops every record rather than crashing, the behavior
`OperationJournalCrashInjectionTest`'s "corrupted journal payload yields an empty list not a
crash" case already pinned before this phase. That's an accepted, unchanged contract, not
something WP-1.4 was asked to harden; a per-record fallback (keep every record this build *can*
parse, drop only the ones it can't) is a real improvement but a different, larger change than
"version the format," and is noted as a hardening candidate rather than folded in here.

## Tests

`OperationJournalCrashInjectionTest` — the WP-0.6 crash suite the plan calls out by name as the
regression gate for this exact work — passes unchanged in intent: its `item()`/`operation()`
fixtures now build `ItemRef`s through the same `Uri.toItemRef()` adapter every production call
site uses, and its round-trip test (`the journal round-trips an operation exactly through its
codec`) exercises the new nested-object wire shape end to end without any assertion needing to
change. `OperationRetryPolicy`'s `sourceUris` field rename to `sourceRefs` (an `ItemRef` list
instead of a `Uri` list) is the only shape change the test needed to follow.
