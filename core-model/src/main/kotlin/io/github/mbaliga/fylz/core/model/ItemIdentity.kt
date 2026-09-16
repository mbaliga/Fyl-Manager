package io.github.mbaliga.fylz.core.model

/**
 * The rules for "is this the same item," named and pure so nowhere else has to reinvent them.
 *
 * The plan is explicit about the failure mode this exists to close: **never infer equality
 * from pathname + mtime alone.** A display name is provider-chosen and can collide, be
 * reused, or simply not survive a rename; two items reading the same size and timestamp are
 * not thereby the same item. The only thing this object will call "the same" is a matching
 * [ItemRef] — full stop, no fallback to anything softer.
 */
object ItemIdentity {

    /** Same item, full stop — [ItemRef] equality and nothing else. */
    fun sameItem(a: ItemRef, b: ItemRef): Boolean = a == b

    /**
     * Whether [ref] denotes its own [ItemRef.locationId]'s root.
     *
     * The `Uri` adapter (`app/storage/ItemRefs.kt`) establishes the convention this reads:
     * a location's root ref has `opaqueItemId == locationId`, whether the source `Uri` was a
     * bare tree URI or a tree-document URI that happened to name the tree's own root document
     * — both collapse to the SAME ref here. That is a real fix, not just a rename: today's
     * `OperationRetryPolicy.isReplayableDestination` (before this phase) parsed `Uri`
     * path-segment shape to answer this question, and the two URI forms above were NOT
     * recognized as equal by raw `Uri.toString()` comparison — a destination reached one way
     * could fail a retry that an equivalent destination reached the other way would have
     * passed. Comparing two plain strings here is both simpler and strictly more correct.
     */
    fun isRoot(ref: ItemRef): Boolean = ref.opaqueItemId == ref.locationId

    /** The outcome of comparing two version stamps for the same item. */
    sealed interface VersionComparison {
        /** Every field either side reported agrees. */
        data object Same : VersionComparison

        /** At least one reported field disagrees. */
        data object Changed : VersionComparison

        /**
         * Either side is missing evidence — one or both stamps are null, or a [VersionStamp]
         * reported some fields but not others. This is not "probably same": a caller enforcing
         * the plan's acceptance law that no destructive step proceeds on a stale identity
         * without reconfirmation should treat [Unknown] as it would treat [Changed], not as it
         * would treat [Same]. This object does not make that call itself — see the KDoc above
         * on why softening an unknown into an assumed-same is exactly the mistake this type
         * exists to prevent callers from making by accident.
         */
        data object Unknown : VersionComparison
    }

    /**
     * Compares two version stamps. [before] and [after] are expected to describe the *same*
     * item ([sameItem] already true) — this function does not check that, it only compares
     * the evidence handed to it.
     */
    fun compareVersions(before: VersionStamp?, after: VersionStamp?): VersionComparison {
        if (before == null || after == null) return VersionComparison.Unknown
        return when {
            before is VersionStamp.Composite && after is VersionStamp.Composite ->
                compareComposite(before, after)
            before is VersionStamp.Revision && after is VersionStamp.Revision ->
                if (before.token == after.token) VersionComparison.Same else VersionComparison.Changed
            // A provider that changes which VersionStamp shape it reports mid-comparison has
            // given us nothing comparable — that is unknown, not a detected change.
            else -> VersionComparison.Unknown
        }
    }

    private fun compareComposite(before: VersionStamp.Composite, after: VersionStamp.Composite): VersionComparison {
        if (before.sizeBytes == null || before.modifiedAtMillis == null ||
            after.sizeBytes == null || after.modifiedAtMillis == null
        ) {
            return VersionComparison.Unknown
        }
        return if (before.sizeBytes == after.sizeBytes && before.modifiedAtMillis == after.modifiedAtMillis) {
            VersionComparison.Same
        } else {
            VersionComparison.Changed
        }
    }
}
