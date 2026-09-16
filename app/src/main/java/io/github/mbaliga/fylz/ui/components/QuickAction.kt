package io.github.mbaliga.fylz.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Something the preview card can do to the one file it is showing.
 *
 * A vocabulary of its own rather than a reuse of `FylzAction`: that enum is the *selection's*
 * verb list and includes things a single previewed file has no meaning for (batch rename, clear
 * selection, find duplicates). The workspace maps these onto the existing machinery — see
 * `FylzV1App`'s quick-action handler — so nothing here reimplements an operation.
 *
 * [id] is what gets persisted, so it must stay stable across releases even if the enum is
 * reordered or a label is rewritten.
 */
enum class QuickAction(val id: String, val label: String, val icon: ImageVector) {
    OPEN_WITH("open_with", "Open with", Icons.Outlined.OpenInNew),
    SHARE("share", "Share", Icons.Outlined.Share),
    COPY("copy", "Copy to", Icons.Outlined.ContentCopy),
    MOVE("move", "Move to", Icons.AutoMirrored.Outlined.DriveFileMove),
    RENAME("rename", "Rename", Icons.Outlined.DriveFileRenameOutline),
    TAGS("tags", "Tags", Icons.Outlined.Sell),
    ADD_TO_SHELF("add_to_shelf", "Add to Shelf", Icons.Outlined.Inventory2),
    RECYCLE("recycle", "Recycle", Icons.Outlined.DeleteOutline),
    ;

    companion object {
        /**
         * What the rail holds out of the box: the two actions the owner named, which with the
         * always-present "more" slot fills the reference's three.
         */
        val DEFAULT_RAIL: List<QuickAction> = listOf(OPEN_WITH, SHARE)

        /**
         * How many actions the rail can pin. One slot is always spent on "more", so this is one
         * less than the shape's ceiling — a rail with no way into the rest of the list would
         * strand every action the user did not pin.
         */
        val MAX_PINNED: Int = QUICK_LOOK_MAX_SLOTS - 1

        fun fromId(id: String): QuickAction? = entries.firstOrNull { it.id == id }

        /**
         * Reads a persisted rail, dropping ids this build no longer knows and trimming to
         * [MAX_PINNED]. An empty or wholly unrecognised list falls back to [DEFAULT_RAIL] rather
         * than to nothing: a card with only a "more" button is a worse default than the one the
         * user never customised.
         */
        fun railFrom(ids: List<String>): List<QuickAction> {
            val known = ids.mapNotNull(::fromId).distinct().take(MAX_PINNED)
            return known.ifEmpty { DEFAULT_RAIL }
        }

        /** Everything not pinned, in declaration order — the contents of the "more" sheet. */
        fun overflowFor(rail: List<QuickAction>): List<QuickAction> = entries.filterNot { it in rail }
    }
}
