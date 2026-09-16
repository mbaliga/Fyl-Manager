package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ActionsBarWidth: Dp = 209.08.dp
private val ActionsBarHeight: Dp = 50.79.dp

/**
 * The export draws three 30dp glyphs on 16dp gaps, the run inset 44dp from the bar's left edge
 * and 20dp from the diagonal cut. A 30dp glyph is a 30dp TOUCH TARGET, well under the 48dp
 * floor -- and unlike a list row there is no spare height or width to grow into: the bar itself
 * is a fixed 209.08 x 50.79.
 *
 * So the GLYPHS stay 30dp and the CELLS around them become 48dp, laid edge to edge with no gap.
 * Three 48dp cells are [ActionsRunSpread] * 2 wider than the export's own 122dp run, and that
 * excess is taken evenly off both insets, which keeps the run centred exactly where it already
 * was: the middle glyph does not move at all and the outer two move by [ActionsRunSpread] --
 * 11dp, which is (48*3 - (30*3 + 16*2)) / 2. The bar's size, silhouette, shadow and
 * icon size are untouched; only the dead space between the glyphs becomes reachable.
 *
 * Full-height cells finish the job vertically: the bar is 50.79dp tall, so a cell that fills it
 * clears 48dp on that axis with room to spare.
 */
private val ActionsGlyphVisual: Dp = 30.dp
private val ActionsGlyphTouch: Dp = 48.dp
private val ActionsRunGap: Dp = 16.dp
private val ActionsRunSpread: Dp =
    (ActionsGlyphTouch * 3 - (ActionsGlyphVisual * 3 + ActionsRunGap * 2)) / 2
private val ActionsRunInsetStart: Dp = 44.dp - ActionsRunSpread
private val ActionsRunInsetEnd: Dp = 20.dp + FolderTabSlant - ActionsRunSpread

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
 * [onMore] is a second, separate touch target trailing the slab rather than a fourth glyph
 * inside it: the slab's width/shape are pinned to the export referenced above, and everything
 * the full [io.github.mbaliga.fylz.ui.ActionsRoom] carries beyond these three (find duplicates,
 * the AI proposal, recycle-bin recovery) used to be reachable only by dragging the bottom edge
 * up -- a gesture nothing on screen advertises. This button is that room's one visible, tappable
 * door, the same courtesy the folder title in the top bar pays the details room above it.
 *
 * @param onZip "Add to a ZIP..." -- [io.github.mbaliga.fylz.ui.ActionsRoom]'s `FylzAction.ARCHIVE`.
 * @param onMove "Move to..." -- `FylzAction.MOVE`.
 * @param onCopy "Copy to another folder" -- `FylzAction.COPY`.
 * @param onMore Opens the full actions room.
 */
@Composable
fun ActionsBar(
    onZip: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(width = ActionsBarWidth, height = ActionsBarHeight),
            shape = FolderTabShape(mirrored = true),
            color = ChromeInk,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = ActionsRunInsetStart, end = ActionsRunInsetEnd),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionGlyph(Icons.Outlined.Archive, "Add to a ZIP", onZip)
                ActionGlyph(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to another folder", onMove)
                ActionGlyph(Icons.Outlined.ContentCopy, "Copy to another folder", onCopy)
            }
        }
        MoreActionsButton(onMore, Modifier.padding(start = 8.dp))
    }
}

/** A plain 48dp circular touch target, matching the slab's own ink/shadow so it reads as the
 *  same chrome rather than a second, unrelated control bolted on beside it. */
@Composable
private fun MoreActionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(ActionsGlyphTouch),
        shape = CircleShape,
        color = ChromeInk,
        shadowElevation = 8.dp,
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick, onClickLabel = "More actions", role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = "More actions",
                tint = ChromeOn,
                modifier = Modifier.size(ActionsGlyphVisual),
            )
        }
    }
}

/**
 * One 30dp glyph centred in a 48dp-wide, full-bar-height touch cell -- the small visual inside a
 * thumb-sized target, rather than a bigger glyph. The cell carries the click and the label; the
 * [Icon] keeps its own [contentDescription] so nothing is lost if the two are ever separated.
 */
@Composable
private fun ActionGlyph(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(ActionsGlyphTouch)
            .fillMaxHeight()
            .clickable(onClick = onClick, onClickLabel = label, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = ChromeOn,
            modifier = Modifier.size(ActionsGlyphVisual),
        )
    }
}
