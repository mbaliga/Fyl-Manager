package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ActionsBarWidth: Dp = 209.08.dp
private val ActionsBarHeight: Dp = 50.79.dp

/**
 * The top-left actions bar: a black slab hanging from the very top of the content area, its
 * right edge cut on the same [FolderTabShape] diagonal the tab band uses, top-left corner square
 * against the screen edge. Visible only while a selection is live -- the caller mounts and
 * unmounts it rather than this composable gating on its own selection count, since (unlike
 * [SelectionRow]) it carries no count to gate on itself.
 *
 * The export's `box-shadow 0 4px 8px rgba(0,0,0,0.25)` is a DOWNWARD shadow, unlike the tab
 * band's upward one -- Compose's own `shadowElevation` casts down natively, so this is the one
 * piece of this chrome that draws its shadow the ordinary way instead of as a gradient overlay.
 *
 * Icon choice deliberately mirrors [io.github.mbaliga.fylz.ui.ActionsRoom]'s own picks for the
 * same three operations (zip/move/copy), so a selection sees the same glyph whether it reads it
 * here or scrolls to the full actions room.
 *
 * @param onZip "Add to a ZIP..." -- [io.github.mbaliga.fylz.ui.ActionsRoom]'s `FylzAction.ARCHIVE`.
 * @param onMove "Move to..." -- `FylzAction.MOVE`.
 * @param onCopy "Copy to..." -- `FylzAction.COPY`.
 */
@Composable
fun ActionsBar(
    onZip: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(width = ActionsBarWidth, height = ActionsBarHeight),
        shape = FolderTabShape(mirrored = true),
        color = ChromeInk,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 44.dp, end = 20.dp + FolderTabSlant),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionGlyph(Icons.Outlined.Archive, "Add to a ZIP", onZip)
            ActionGlyph(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to another folder", onMove)
            ActionGlyph(Icons.Outlined.ContentCopy, "Copy to another folder", onCopy)
        }
    }
}

@Composable
private fun ActionGlyph(icon: ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = label,
        tint = ChromeOn,
        modifier = Modifier.size(30.dp).clickable(onClick = onClick, onClickLabel = label),
    )
}
