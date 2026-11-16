package io.github.mbaliga.fylz.operations

import android.net.Uri

/** Whether pasting copies the items or moves them. */
enum class ClipboardMode { COPY, CUT }

/**
 * One item on the clipboard.
 *
 * [documentId] and [parentDocumentId] are provider document ids rather than URIs because the
 * "is this paste a no-op / a loop" questions are about *where things are*, and ids are what a
 * provider uses for that. [parentDocumentId] is the folder the item was copied from; it is null
 * when that is not known (a recursive-search hit lives in a folder other than the one on screen),
 * in which case the paste is simply not treated as a same-folder no-op.
 */
data class ClipItem(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val documentId: String,
    val parentDocumentId: String?,
)

/** What was copied or cut, held until it is pasted or cleared. */
data class FileClipboard(
    val mode: ClipboardMode,
    val items: List<ClipItem>,
) {
    init {
        require(items.isNotEmpty()) { "The clipboard cannot be empty." }
    }

    /** "report.pdf" for one item, "3 items" for several. */
    val label: String get() = items.singleOrNull()?.name ?: "${items.size} items"
}

/** What pasting a [FileClipboard] into a folder will do. */
data class PastePlan(
    /** Items that will be transferred. */
    val transfer: List<ClipItem>,
    /** Cut items already in the destination: nothing to do for them. */
    val alreadyHere: List<ClipItem>,
    /** Folders that cannot go into themselves or anything inside them, with the reason. */
    val blocked: List<Pair<ClipItem, String>>,
) {
    val isEmpty: Boolean get() = transfer.isEmpty()
}

/**
 * The rules for pasting, kept pure so every case is asserted in a test.
 *
 * - **Copy into the folder you copied from is allowed.** The transfer uses keep-both naming, so
 *   it produces "name (2)": copy-then-paste is how you duplicate a file.
 * - **Cut into the folder it is already in does nothing.** Reported, not treated as an error.
 * - **A folder can never be pasted into itself or a descendant.** That is infinite recursion
 *   for a copy and data loss for a move, so it is refused up front rather than left to the
 *   provider. Provider ids that are not path-shaped cannot be checked this way; for those the
 *   provider's own refusal is the backstop.
 */
object ClipboardPolicy {

    fun plan(clipboard: FileClipboard, destinationDocumentId: String): PastePlan {
        val transfer = mutableListOf<ClipItem>()
        val alreadyHere = mutableListOf<ClipItem>()
        val blocked = mutableListOf<Pair<ClipItem, String>>()
        for (item in clipboard.items) {
            when {
                item.isDirectory && isInside(destinationDocumentId, item.documentId) ->
                    blocked += item to "“${item.name}” cannot be pasted into itself."
                clipboard.mode == ClipboardMode.CUT && item.parentDocumentId == destinationDocumentId ->
                    alreadyHere += item
                else -> transfer += item
            }
        }
        return PastePlan(transfer, alreadyHere, blocked)
    }

    /** True when [candidateId] is [folderId] or lies somewhere beneath it. */
    fun isInside(candidateId: String, folderId: String): Boolean {
        if (candidateId == folderId) return true
        val prefix = folderId.trimEnd('/') + "/"
        return candidateId.startsWith(prefix)
    }

    /** The toast for a finished paste. */
    fun resultMessage(mode: ClipboardMode, count: Int, skipped: Int): String {
        val noun = if (count == 1) "1 item" else "$count items"
        val base = if (mode == ClipboardMode.CUT) "Moved $noun" else "Pasted $noun"
        return if (skipped > 0) "$base ($skipped already here)" else base
    }
}

/** File names for PDFs Fylz writes. Pure so a stray string-template escape cannot come back. */
object PdfOutputNames {
    fun pages(nowMillis: Long): String = "Fylz-pages-$nowMillis.pdf"
    fun merged(nowMillis: Long): String = "Fylz-merged-$nowMillis.pdf"
}
