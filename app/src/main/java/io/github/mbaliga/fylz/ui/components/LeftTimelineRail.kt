package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.theme.hairline
import kotlin.math.abs

/** Vertical rhythm between ticks, and the two tick lengths -- the long tick reads as "double" the short one. */
private val TICK_SPACING = 8.dp
private val TICK_LENGTH_SHORT = 4.dp
private val TICK_LENGTH_LONG = 8.dp
private val TICK_STROKE_WIDTH = 1.dp

/**
 * Frame 2's left-edge timeline: a column of short, light dashed ticks running the content's full
 * height, with a longer tick wherever a date section actually starts -- a quiet "you are here"
 * rail, not the functional right-edge scrubber ([dev.aarso.cellshell.EdgeTimelineScrubber]) it
 * sits beside. Purely decorative: no drag, no tap, no state of its own.
 *
 * @param sectionAnchors each [DateSection]'s start, as a 0..1 fraction of the content's total
 *   height -- the same fraction a caller would compute from a `LazyGridState`'s
 *   `layoutInfo`/scroll-progress bookkeeping, kept out of this composable so it never has to know
 *   about grid internals to draw a tick.
 */
@Composable
fun LeftTimelineRail(sectionAnchors: List<Float>, modifier: Modifier = Modifier) {
    val tickColor = hairline()
    Canvas(modifier.fillMaxHeight().width(TICK_LENGTH_LONG)) {
        val spacing = TICK_SPACING.toPx()
        if (spacing <= 0f) return@Canvas
        val shortLen = TICK_LENGTH_SHORT.toPx()
        val longLen = TICK_LENGTH_LONG.toPx()
        val stroke = TICK_STROKE_WIDTH.toPx()
        val anchorYs = sectionAnchors.map { it.coerceIn(0f, 1f) * size.height }

        var y = 0f
        while (y <= size.height) {
            val isAnchor = anchorYs.any { abs(it - y) < spacing / 2f }
            val length = if (isAnchor) longLen else shortLen
            drawLine(
                color = tickColor,
                start = Offset(0f, y),
                end = Offset(length, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            y += spacing
        }
    }
}
