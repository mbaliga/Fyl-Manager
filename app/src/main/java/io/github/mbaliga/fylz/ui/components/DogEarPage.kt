package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.hairline

/** The fold's side length -- frame 2's "~16dp" dog-ear. */
private val FOLD_SIZE: Dp = 16.dp

/**
 * A page-shaped frame for document thumbnails -- frame 2's flat, hairline-bordered card with a
 * folded top-right corner, the "real paper" read the date-sectioned document grid wants in place
 * of a plain square thumbnail tile.
 *
 * The page is [FylzGeometry.RadiusMd]-rounded everywhere except the top-right corner, which is cut
 * on a straight diagonal [FOLD_SIZE] in from each edge instead of rounded -- [content] (the
 * caller's [EntryThumbnail]) is clipped to that page-minus-fold outline so nothing bleeds under
 * the fold, and the notch left behind is filled with a slightly darker triangle plus a soft crease
 * line to read as paper folded back on itself, not a bite taken out of the page.
 *
 * [content] fills the frame (`aspectRatio(3f / 4f)` is applied here, once, so every caller's grid
 * cell gets the same page proportions); a caller passing the fixed-square [EntryThumbnail] should
 * size it to at least the frame's own bounds and let this composable's clip crop the overflow --
 * [EntryThumbnail] has no aspect-fill mode of its own to ask for instead.
 */
@Composable
fun DogEarPage(
    modifier: Modifier = Modifier,
    foldSize: Dp = FOLD_SIZE,
    content: @Composable () -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.surfaceBright
    val foldColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val creaseColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val borderColor = hairline()
    val shape = remember(foldSize) { DogEarPageShape(foldSize) }

    Box(modifier.aspectRatio(3f / 4f)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(pageColor)
                .border(1.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Canvas(Modifier.fillMaxSize()) {
            val fold = foldSize.toPx()
            val notch = Path().apply {
                moveTo(size.width - fold, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, fold)
                close()
            }
            drawPath(notch, color = foldColor)
            drawLine(
                color = creaseColor,
                start = Offset(size.width - fold, 0f),
                end = Offset(size.width, fold),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

/**
 * [DogEarPage]'s outline: [FylzGeometry.RadiusMd] on three corners, a straight [fold]-sized
 * diagonal cut on the fourth (top-right) instead of a fourth round -- built the same
 * subtract-a-corner way [NotchedCardShape] cuts its own action-rail notches, so a page's content
 * clip and its own painted fold line always agree on exactly where the paper ends.
 */
private class DogEarPageShape(private val fold: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        with(density) {
            val radius = FylzGeometry.RadiusMd.toPx()
            val f = fold.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
            val page = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radius)))
            }
            val corner = Path().apply {
                moveTo(size.width - f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, f)
                close()
            }
            val cut = Path().apply { op(page, corner, PathOperation.Difference) }
            Outline.Generic(cut)
        }
}
