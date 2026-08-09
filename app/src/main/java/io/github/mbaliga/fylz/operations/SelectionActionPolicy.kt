package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.model.EntryKind

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
 */
object SelectionActionPolicy {

    /** Nothing selected: every action off. */
    val None = SelectionActions(count = 0)

    fun evaluate(kinds: List<EntryKind>): SelectionActions {
        if (kinds.isEmpty()) return None
        return SelectionActions(
            count = kinds.size,
            copy = true,
            move = true,
            recycle = true,
            rename = kinds.size == 1,
            batchRename = kinds.size >= 2,
            tag = true,
            archive = true,
            extract = kinds.singleOrNull() == EntryKind.ARCHIVE,
            pdfTools = kinds.all { it == EntryKind.PDF },
            share = kinds.none { it == EntryKind.DIRECTORY },
        )
    }
}
