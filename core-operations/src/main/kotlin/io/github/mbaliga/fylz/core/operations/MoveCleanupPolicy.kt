package io.github.mbaliga.fylz.core.operations

import io.github.mbaliga.fylz.core.model.ItemIdentity
import io.github.mbaliga.fylz.core.model.VersionStamp

/**
 * The identity decision behind "Finish move": whether the pending source delete may proceed on
 * the evidence at hand. Pure, so the rule is testable without a provider, and named, so the
 * Phase 1 exit note's deferred decision ("wiring [ItemIdentity.compareVersions] into
 * finishMoveCleanup changes today's unreadable-size-implies-proceed behavior") is now made in
 * exactly one place instead of implied by inline conditionals.
 *
 * The decision made, explicitly:
 *
 * - **Concrete contrary evidence blocks.** If either the committed destination or the pending
 *   source *provably* changed since the copy was verified — both observations reported full
 *   version stamps and they disagree — cleanup does not delete. A changed destination may no
 *   longer be the verified copy; a changed source holds edits the destination does not.
 * - **Absence of evidence falls back, it does not block.** Providers that decline to report
 *   sizes or timestamps (routine for real `DocumentsProvider`s, and every directory move,
 *   since directories have no meaningful size) yield [ItemIdentity.VersionComparison.Unknown].
 *   Treating that as a block would make "Finish move" permanently impossible on exactly the
 *   providers that most need the journal, so Unknown routes to [Decision.InsufficientEvidence]
 *   and the caller keeps its pre-existing direct cross-checks. This is deliberately weaker
 *   than the acceptance law's treat-Unknown-as-Changed ceiling — the destination here was
 *   already verified byte-for-byte at copy time, so the cleanup is not acting on *no*
 *   evidence, only on evidence that has no version stamp to restate.
 */
object MoveCleanupPolicy {

    sealed interface Decision {
        /** Both stamps restate exactly what the journal recorded: delete the source. */
        data object Proceed : Decision

        /** The committed destination provably changed since the copy was verified. */
        data object DestinationChanged : Decision

        /** The source provably changed after its content was copied; deleting it loses edits. */
        data object SourceModified : Decision

        /** No provable change, but not full confirmation either — apply the legacy checks. */
        data object InsufficientEvidence : Decision
    }

    fun decide(
        journaledSource: VersionStamp?,
        currentSource: VersionStamp?,
        journaledDestination: VersionStamp?,
        currentDestination: VersionStamp?,
    ): Decision {
        val destination = ItemIdentity.compareVersions(journaledDestination, currentDestination)
        if (destination == ItemIdentity.VersionComparison.Changed) return Decision.DestinationChanged
        val source = ItemIdentity.compareVersions(journaledSource, currentSource)
        if (source == ItemIdentity.VersionComparison.Changed) return Decision.SourceModified
        return if (
            destination == ItemIdentity.VersionComparison.Same &&
            source == ItemIdentity.VersionComparison.Same
        ) {
            Decision.Proceed
        } else {
            Decision.InsufficientEvidence
        }
    }
}
