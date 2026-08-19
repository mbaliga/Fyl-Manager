package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The row's own height -- the count pill and the close button both fill it exactly. */
val SelectionRowHeight: Dp = 44.dp

/** The count pill never draws narrower than this even for a single-digit count, matching the
 *  export's "9999 SELECTED" reference width; a live count past four digits is free to push past
 *  it rather than truncate. */
private val SelectionPillMinWidth: Dp = 215.dp

/** The mirrored close button's own width -- fixed, since an X glyph never needs to grow. */
private val SelectionCloseWidth: Dp = 66.5.dp

/**
 * The selection row: a left-rounded black count pill and a mirrored (right-rounded) close button,
 * flush to the row's own edges with the flexible middle left bare -- the export's `x=4` /
 * `x=369.5` insets on a 440dp frame are both a 4dp margin from their respective edge, which is
 * what [Arrangement.SpaceBetween] reproduces at any width instead of just the one frame size.
 *
 * No "Actions" button here -- [ActionsBar] is the new route to the same room, hung from the top
 * instead of riding along in this row. The caller mounts this only while a selection is live; it
 * draws unconditionally once given a positive [count] and draws nothing for zero, matching the
 * rest of the chrome's "empty state is no chrome at all" idiom.
 */
@Composable
fun SelectionRow(count: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val family = chromeFontFamily()
    Row(
        modifier
            .fillMaxWidth()
            .height(SelectionRowHeight)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountPill(count = count, family = family)
        CloseButton(onClick = onClose)
    }
}

@Composable
private fun CountPill(count: Int, family: FontFamily, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxHeight()
            .defaultMinSize(minWidth = SelectionPillMinWidth)
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(ChromeInk)
            .padding(start = 13.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count",
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            color = ChromeOn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "SELECTED",
            fontFamily = family,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            color = ChromeOn.copy(alpha = ChromeSelectedLabelAlpha),
            maxLines = 1,
        )
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = SelectionCloseWidth, height = SelectionRowHeight)
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(ChromeInk)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Clear selection" },
        contentAlignment = Alignment.Center,
    ) {
        CloseGlyph(Modifier.size(24.dp))
    }
}

/** Two 3.11dp strokes crossing at +/-45 degrees, ~19dp long, sharp (butt) ends -- hand-drawn per
 *  the export rather than a Material close icon, which reads noticeably thinner and rounder. */
@Composable
private fun CloseGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val strokeWidth = 3.11.dp.toPx()
        val half = (19.dp.toPx() / 2f) * SQRT_HALF
        val c = Offset(size.width / 2f, size.height / 2f)
        drawLine(
            color = Color.White,
            start = Offset(c.x - half, c.y - half),
            end = Offset(c.x + half, c.y + half),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Butt,
        )
        drawLine(
            color = Color.White,
            start = Offset(c.x - half, c.y + half),
            end = Offset(c.x + half, c.y - half),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Butt,
        )
    }
}

/** `sqrt(2) / 2` -- how far a diagonal stroke of a given length reaches along one axis. */
private const val SQRT_HALF = 0.70710677f
