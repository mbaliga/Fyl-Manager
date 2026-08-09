package io.github.mbaliga.fylz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.operations.SelectionActions

/** Everything the bottom room can ask the workspace to do. */
internal enum class FylzAction {
    COPY,
    MOVE,
    RECYCLE,
    RENAME,
    BATCH_RENAME,
    TAGS,
    ARCHIVE,
    EXTRACT,
    PDF_TOOLS,
    SHARE,
    CLEAR_SELECTION,
    NEW_FOLDER,
    NEW_FILE,
    SCAN_PDF,
    FIND_DUPLICATES,
    AI_ORGANIZE,
}

/**
 * The bottom room: everything that changes a file.
 *
 * It replaces two surfaces that were never one thing. The first was a contextual bottom bar with
 * eleven buttons in a horizontal scroller — a control you had to swipe sideways to read, whose
 * right-hand half most people never saw, and which covered the listing it acted on. The second
 * was the top bar's overflow menu, which held "New folder" and "Scan to PDF" next to "AI organize
 * proposal" because there was nowhere else to put them. Both were chrome renting space from the
 * file list; both are now rows on a surface that is only there when asked for.
 *
 * Pairs with [DetailsRoom] above: **up is what you are looking at, down is what to do about it.**
 *
 * ### Actions appear only when they apply
 *
 * Inapplicable actions are absent, not greyed out. A room is read top to bottom, so a shorter
 * list is a faster one, and a disabled row is a question the user has to answer ("why can't I?")
 * for no benefit. [SelectionActions] decides — this file contains no rules, only rows, which is
 * what makes the rules testable without a device.
 *
 * Recovery keeps this edge. It was the bottom room before actions arrived, it is reached by the
 * same drag, and it belongs here on the merits: finishing an interrupted move is an action on
 * files, not a setting. It sits last because it is the least frequent.
 *
 * @param selection what the current selection supports, from `SelectionActionPolicy`.
 * @param folderOpen whether a location is open — with none, only recovery has anything to offer.
 * @param canFindDuplicates whether the folder holds enough files for a duplicate scan to mean
 *   anything.
 * @param canOrganize whether an entry is focused for the AI organiser to propose a home for.
 * @param recovery the storage-and-recovery section, owned and composed by the app shell.
 */
@Composable
internal fun ActionsRoom(
    selection: SelectionActions,
    folderOpen: Boolean,
    canFindDuplicates: Boolean,
    canOrganize: Boolean,
    onAction: (FylzAction) -> Unit,
    recovery: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        if (selection.any) {
            RoomHeading(
                if (selection.count == 1) "Selection · 1 item" else "Selection · ${selection.count} items",
            )
            if (selection.copy) ActionRow(Icons.Outlined.ContentCopy, "Copy to…") { onAction(FylzAction.COPY) }
            if (selection.move) ActionRow(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to…") { onAction(FylzAction.MOVE) }
            if (selection.rename) ActionRow(Icons.Outlined.Edit, "Rename") { onAction(FylzAction.RENAME) }
            if (selection.batchRename) {
                ActionRow(Icons.AutoMirrored.Outlined.TextSnippet, "Batch rename") { onAction(FylzAction.BATCH_RENAME) }
            }
            if (selection.tag) ActionRow(Icons.Outlined.Tag, "Tags") { onAction(FylzAction.TAGS) }
            if (selection.archive) ActionRow(Icons.Outlined.Archive, "Add to a ZIP…") { onAction(FylzAction.ARCHIVE) }
            if (selection.extract) ActionRow(Icons.Outlined.FolderOpen, "Extract to…") { onAction(FylzAction.EXTRACT) }
            if (selection.pdfTools) {
                ActionRow(Icons.Outlined.PictureAsPdf, "PDF tools") { onAction(FylzAction.PDF_TOOLS) }
            }
            if (selection.share) ActionRow(Icons.Outlined.Share, "Share") { onAction(FylzAction.SHARE) }
            if (selection.recycle) {
                ActionRow(Icons.Outlined.Delete, "Move to Recycle Bin") { onAction(FylzAction.RECYCLE) }
            }
            ActionRow(Icons.Outlined.Close, "Clear selection") { onAction(FylzAction.CLEAR_SELECTION) }
            Spacer(Modifier.height(24.dp))
        }

        if (folderOpen) {
            RoomHeading("This folder")
            ActionRow(Icons.Outlined.CreateNewFolder, "New folder") { onAction(FylzAction.NEW_FOLDER) }
            ActionRow(Icons.AutoMirrored.Outlined.TextSnippet, "New text file") { onAction(FylzAction.NEW_FILE) }
            ActionRow(Icons.Outlined.PictureAsPdf, "Scan to PDF") { onAction(FylzAction.SCAN_PDF) }
            if (canFindDuplicates) {
                ActionRow(Icons.Outlined.ContentCopy, "Find duplicates") { onAction(FylzAction.FIND_DUPLICATES) }
            }
            if (canOrganize) {
                ActionRow(Icons.Outlined.AutoAwesome, "AI organize proposal") { onAction(FylzAction.AI_ORGANIZE) }
            }
            Spacer(Modifier.height(24.dp))
        }

        RoomHeading("Storage & recovery")
        recovery()
    }
}

/**
 * One action.
 *
 * Icon plus word, never icon alone: `docs/DESIGN.md` requires a semantic label on every icon-only
 * control, and the cheapest way to satisfy that is to not build icon-only controls. The glyph is
 * decorative here — the text beside it is what a screen reader reads — so it carries no
 * content description of its own rather than repeating the label.
 */
@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
