package io.github.mbaliga.fylz.core.operations

/**
 * WP-1.4: the operation journal's wire-format version, named and tested instead of implicit.
 *
 * Before this phase the persisted JSON had no version field at all, and every `source`/
 * `destination` was a plain `Uri` string. This phase's `ItemRef` migration (WP-1.1) changes
 * that shape to a structured object — the first real shape change the wire format has ever
 * had — so this is also the first time a version number earns its place.
 *
 * ### The migration contract this constant is part of
 *
 * `OperationJournal` (in `app`, where the actual `org.json` codec lives — see that module's
 * `build.gradle.kts` for why this module never takes an `org.json` dependency itself) must:
 *
 * - **Write** [CURRENT_VERSION] on every `persist()`.
 * - **Read** the field with an `opt`-style accessor, defaulting to `1` (the original,
 *   unversioned shape) when absent — a real device's already-committed pre-versioning records
 *   have no such field, and reading it with a `get`-style accessor would throw on every one of
 *   them, which `decode()`'s existing all-or-nothing failure mode would turn into silently
 *   losing the user's entire operation history on the first launch after this upgrade. That
 *   failure mode is real and already pinned by `OperationJournalCrashInjectionTest`'s
 *   corrupted-payload test for a different cause; a naive version field must not reproduce it
 *   for this one.
 * - **Decode `source`/`destination` by shape, not by version number**: a JSON *object* is the
 *   new `ItemRef` encoding, a JSON *string* is the legacy `Uri` string, decoded through
 *   `app/storage/ItemRefs.kt`'s adapter into an equivalent ref. Shape-sniffing per field is
 *   deliberately more robust here than branching on the record's own version number — it does
 *   not require every item in one already-partially-written record to agree on version, only
 *   that each field, read on its own, is recognizable.
 * - **Never refuse a record for having an unrecognized version number**, including one *newer*
 *   than [CURRENT_VERSION]: unknown fields are already inert on read (a bare `org.json`
 *   property no code asks for is simply never touched), so a future version differs from this
 *   one only by fields this build doesn't know to look for — harmless to ignore, not a reason
 *   to discard the record.
 */
object JournalSchema {
    // v3: OperationItem gains optional sourceStamp/destinationStamp objects, captured when a
    // MOVE's source delete first fails and consumed by MoveCleanupPolicy on cleanup retry.
    // Reading stays shape-based per the contract above: records without the fields (every v1/v2
    // record) decode with null stamps, which the policy treats as insufficient evidence, i.e.
    // exactly the pre-v3 cleanup behavior.
    //
    // v4: FileOperation gains the Undo fields — sourceParentRoot/sourceParentSegments,
    // destinationRoot/destinationSegments, and the undone flag — consumed by UndoPolicy.
    // Same contract again: pre-v4 records decode with nulls/empties and simply are not
    // undoable, which is the truth about them.
    const val CURRENT_VERSION = 4
}
