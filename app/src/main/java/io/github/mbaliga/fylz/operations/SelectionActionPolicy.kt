package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.core.model.ItemCapability
import io.github.mbaliga.fylz.core.vfs.CapabilityPolicy
import io.github.mbaliga.fylz.core.vfs.UserAction

/**
 * Which file actions a given selection actually supports.
 *
 * One flag per action, so the room that draws them contains no rules of its own — see
 * [SelectionActionPolicy] for why that separation exists.
 */
data class SelectionActions(
    val count: Int,
    val copy: Boolean = false,
    val move: Boolean = false,
    val recycle: Boolean = false,
    val rename: Boolean = false,
    val batchRename: Boolean = false,
    val tag: Boolean = false,
    val archive: Boolean = false,
    val extract: Boolean = false,
    val pdfTools: Boolean = false,
    val share: Boolean = false,
) {
    /** True when anything at all is selected. */
    val any: Boolean get() = count > 0
}

/**
 * Decides what a selection may be asked to do.
 *
 * This used to be a row of boolean expressions written inline in the action bar's call site —
 * `selectedEntries.size == 1 && selectedEntries.first().kind == EntryKind.ARCHIVE` and friends —
 * evaluated during composition, testable only by running the app. Extracted here it joins the
 * rest of the app's named policy objects (`RecycleBinPolicy`, `OperationRetryPolicy`,
 * `ArchiveExtractionPolicy`) and, more usefully, it becomes the *single* answer to "does this
 * action apply" for however many surfaces want to ask.
 *
 * The rules, and why:
 *
 * - **Rename** takes exactly one entry, because a rename dialog has one text field.
 * - **Batch rename** takes at least two. Batch-renaming a single file is a rename with extra
 *   steps and a worse dialog, and offering both for one selected file made the menu look like it
 *   had two ways to do one thing — which it did.
 * - **Extract** takes exactly one archive. Two archives extracting into one destination is a
 *   collision the picker cannot express.
 * - **PDF tools** needs every selected entry to be a PDF; the page tools have nothing to say
 *   about a JPEG.
 * - **Share** refuses a selection containing a folder. `ACTION_SEND` carries document URIs, and
 *   a directory URI handed to a receiving app is either ignored or an error over there — the
 *   user sees a share sheet, picks a target, and nothing arrives. Better to not offer it.
 * - Everything else — copy, move, recycle, tag, archive — applies to any non-empty selection,
 *   folders included.
 *
 * Phase 2's consultation runs through here too: the shape rules above are intersected with what
 * the active provider's [ItemCapability] set actually offers, per `core-vfs.CapabilityPolicy` —
 * acceptance law #1's "no action appears unless the selected items and destination can support
 * it", previously tested scaffolding with no caller. Only actions with a provider-capability
 * meaning are gated (copy, move, recycle, rename, batch rename). Tag lives in the app's own
 * LibraryStore, and archive, extract, PDF tools and share write into a destination the user has
 * not chosen yet at evaluation time — their gate would be a guess about a location this policy
 * has not seen, so they stay shape-gated only.
 */
object SelectionActionPolicy {

    /** Nothing selected: every action off. */
    val None = SelectionActions(count = 0)

    fun evaluate(kinds: List<EntryKind>, offered: Set<ItemCapability>): SelectionActions {
        if (kinds.isEmpty()) return None
        fun can(action: UserAction) = CapabilityPolicy.canPerform(action, offered)
        return SelectionActions(
            count = kinds.size,
            copy = can(UserAction.COPY),
            move = can(UserAction.MOVE),
            recycle = can(UserAction.MOVE_TO_TRASH),
            rename = kinds.size == 1 && can(UserAction.RENAME),
            batchRename = kinds.size >= 2 && can(UserAction.RENAME),
            tag = true,
            archive = true,
            extract = kinds.singleOrNull() == EntryKind.ARCHIVE,
            pdfTools = kinds.all { it == EntryKind.PDF },
            share = kinds.none { it == EntryKind.DIRECTORY },
        )
    }
}
