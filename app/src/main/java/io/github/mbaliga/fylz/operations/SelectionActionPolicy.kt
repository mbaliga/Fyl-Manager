package io.github.mbaliga.fylz.operations

import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry

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
    val annotate: Boolean = false,
    val annotatePdf: Boolean = false,
    val annotateDxf: Boolean = false,
    val convertImage: Boolean = false,
    val imagesToPdf: Boolean = false,
    val selectImage: Boolean = false,
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
 * - **Annotate** takes exactly one image, for the same reason as rename: the drawing screen has
 *   one canvas. A raster kind is necessary but not sufficient -- the drawing screen itself further
 *   narrows to the specific image formats it can actually re-encode (JPEG/PNG/WebP), which this
 *   coarse, [EntryKind]-only policy has no way to express.
 * - **Annotate PDF** is the same one-canvas shape, kept as its own flag rather than folded into
 *   annotate: a PDF's ink gets burned into that one page's own content stream via PDFBox, not
 *   rasterized like the image path, and always writes to a new file rather than overwriting the
 *   source -- different enough underneath that one boolean covering two dispatch targets would
 *   hide which is which at every call site.
 * - **Annotate DXF** is checked by [annotatesDxf], a separate function taking [FileEntry] rather
 *   than [EntryKind] -- DXF, OBJ, STL, PLY, OFF and glTF/GLB all collapse to the same coarse
 *   [EntryKind.OTHER], which [evaluate]'s kind-only signature has no way to tell apart; only the
 *   file's own [FileFormatRegistry] descriptor (`rendererId == "dxf"`) can. The call site folds
 *   the result into [SelectionActions] with a `.copy()` after calling [evaluate], rather than
 *   [evaluate] taking entries directly, so its existing kind-only callers and tests are untouched.
 * - **Convert image** takes exactly one image, same shape as annotate and for the same reason --
 *   the format picker is asking about one file's own bytes, and the same JPEG/PNG/WebP-only
 *   round-trip narrowing happens where the dialog would open, not here.
 * - **Images to PDF** needs every selected entry to be an image, same shape as PDF tools -- but
 *   unlike annotate/convert it takes ANY number of them (one page per image), since combining
 *   several photos into one PDF is the entire point.
 * - **Select image** (lasso/wand/magnetic-lasso, crop/cutout/copy) takes exactly one image, same
 *   shape and same reason as annotate and convert image.
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
            annotate = kinds.singleOrNull() == EntryKind.IMAGE,
            annotatePdf = kinds.singleOrNull() == EntryKind.PDF,
            convertImage = kinds.singleOrNull() == EntryKind.IMAGE,
            imagesToPdf = kinds.all { it == EntryKind.IMAGE },
            selectImage = kinds.singleOrNull() == EntryKind.IMAGE,
            share = kinds.none { it == EntryKind.DIRECTORY },
        )
    }

    /**
     * Whether the selection is exactly one DXF drawing, per the file's own [FileFormatRegistry]
     * descriptor rather than [EntryKind] -- see [SelectionActions]'s own note on why this one flag
     * needs a second, separate check instead of living inside [evaluate].
     */
    fun annotatesDxf(entries: List<FileEntry>): Boolean = entries.singleOrNull()?.let {
        FileFormatRegistry.describe(it.name, it.mimeType, it.kind).rendererId == "dxf"
    } == true
}
