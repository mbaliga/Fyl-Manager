package io.github.mbaliga.fylz.core.model

/**
 * Whatever a provider can offer as evidence that an item has, or hasn't, changed.
 *
 * Never a single universal shape — the plan is explicit that this is "whatever the provider can
 * give," and different providers give fundamentally different things. Two variants exist today:
 *
 * - [Composite] is what every provider this app ships (the local `file` backend and SAF) can
 *   report: size and modification time, both nullable because a provider may decline to report
 *   either (see `EntryStops`/`entryStops` elsewhere in the app, which already buckets
 *   null/zero timestamps separately for the same reason — real `DocumentsProvider`s do this
 *   routinely, it is not corruption).
 * - [Revision] is for a provider that hands back an opaque revision token (an ETag, a change
 *   token) instead of size+time — no such provider exists in this app yet. It is declared now,
 *   not spuriously: the plan names this exact shape as the reason [VersionStamp] is a sealed
 *   type rather than one data class, so a future revision-based provider (cloud connectors are
 *   explicitly out of scope for this phase, but the type must not foreclose them) has somewhere
 *   to put its token without every consumer of [Composite] needing to change.
 */
sealed interface VersionStamp {
    data class Composite(val sizeBytes: Long?, val modifiedAtMillis: Long?) : VersionStamp
    data class Revision(val token: String) : VersionStamp
}
