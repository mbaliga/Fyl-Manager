package io.github.mbaliga.fylz.core.model

/**
 * What a provider — or, resolved per [ItemSnapshot.capabilities], one specific item or
 * location within it — can actually do.
 *
 * WP-1.2's expanded vocabulary. The prior `StorageCapability` (eight flat values, all declared
 * but with zero consultation sites outside the `storage` package — commands never actually
 * asked) grows here into the plan's grouped table. Not every named value has a provider that
 * declares it yet; several groups (Observation's `WATCH`/`RECENTS`, History, `QUOTA`,
 * `THUMBNAIL`/`METADATA_WRITE`, `ATOMIC_REPLACE`, `PAGED_LIST`) describe capabilities no
 * current backend actually has — declared now, per the plan's own instruction, because a name
 * to grow into is worth having before the feature exists, so long as nothing claims a value
 * true that isn't backed by real behavior. See `docs/worklog/WP-1.2.md` for the exact mapping
 * each provider declares and why.
 *
 * Two old values — "browsable without a picker" and "exposes whole volumes" — do NOT reappear
 * here. Those describe a provider's *launch-surface* behavior (how a root is reached), not
 * anything about an item's operations once reached; they now live as plain booleans on
 * `StorageProvider` itself rather than being forced into an item-shaped vocabulary they were
 * never really part of.
 *
 * Consultation — deciding what a command needs and gating on it — is `core-vfs`'s
 * `CapabilityPolicy`, not this file. This is the vocabulary; that is the decision.
 */
enum class ItemCapability {
    // ── Content ──────────────────────────────────────────────────────────────────────
    /** Bytes can be read at all. */
    READ,

    /** An arbitrary byte range can be read without reading everything before it. */
    RANGE_READ,

    /** Bytes can be read as a sequential stream (the shape every reader in this app uses). */
    STREAM_READ,

    /** Bytes can be written. */
    WRITE,

    /** A write can replace existing content as one atomic step — never a torn intermediate. */
    ATOMIC_REPLACE,

    // ── Hierarchy ────────────────────────────────────────────────────────────────────
    /** Children of a location can be listed. */
    LIST,

    /** Children can be listed a page at a time rather than as one full snapshot. */
    PAGED_LIST,

    /** A new file can be created under a location. */
    CREATE_FILE,

    /** A new folder can be created under a location. */
    CREATE_DIRECTORY,

    /** An item can be renamed in place. */
    RENAME,

    /** An item can be moved to a different location. */
    MOVE,

    /** An item can be copied to a different location. */
    COPY,

    // ── Destruction ──────────────────────────────────────────────────────────────────
    /** An item can be moved to a recoverable trash location. */
    TRASH,

    /** A trashed item can be restored to where it came from. */
    RESTORE_TRASH,

    /** An item can be permanently, non-recoverably deleted. */
    DELETE_PERMANENT,

    // ── Observation ──────────────────────────────────────────────────────────────────
    /** External changes under a location can be observed without polling. */
    WATCH,

    /** The provider offers its own name/metadata search, distinct from this app walking it. */
    NATIVE_SEARCH,

    /** File contents can be opened for a content (not just name) search. */
    CONTENT_SEARCH,

    /** The provider can report recently used or recently changed items. */
    RECENTS,

    // ── Rich data ────────────────────────────────────────────────────────────────────
    /** The provider can hand back a thumbnail without the app decoding the whole file. */
    THUMBNAIL,

    /** Metadata beyond the basic listing columns can be read. */
    METADATA_READ,

    /** Metadata can be written back. */
    METADATA_WRITE,

    // ── History ──────────────────────────────────────────────────────────────────────
    /** The provider's own prior revisions of an item can be listed. */
    VERSION_LIST,

    /** A specific prior revision's bytes can be read. */
    VERSION_READ,

    /** A prior revision can be restored as the current content. */
    VERSION_RESTORE,

    // ── Availability ─────────────────────────────────────────────────────────────────
    /** The location's total storage allocation can be queried. */
    QUOTA,

    /** The location's currently free storage can be queried. */
    FREE_SPACE,

    // ── Explicitly never promised ────────────────────────────────────────────────────
    /**
     * Deletion is forensically unrecoverable — bytes are actually gone, not just unlinked.
     *
     * Granted by **no provider in this app, and none should ever add it**. Flash translation
     * layers and wear-levelling firmware keep copies no application-layer deletion can reach,
     * on every storage medium this app runs on; claiming otherwise is a promise the hardware
     * makes impossible to keep. Fylz's own "Shred" action says exactly this — "as gone as
     * software can honestly make it," never "securely erased" — and this value exists so that
     * claim stays enforceable in code, not only in copy: a provider test asserting
     * `SECURE_ERASE_CLAIM !in provider.capabilities` is the executable form of the promise.
     */
    SECURE_ERASE_CLAIM,
}
