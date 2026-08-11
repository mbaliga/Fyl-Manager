package io.github.mbaliga.fylz.ui.cluster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The organic corner blob the references show: material melting out of the screen's corner,
 * not a card floating over it. The silhouette is a quarter-round belly bridged to the two
 * screen edges by concave shoulders — the shoulders are what sell "the edge itself bulged"
 * instead of "a circle was pasted into the corner".
 *
 * [bulge] scales the belly (0 = flat edge, 1 = fully swollen); the shape recomputes rather
 * than scale-transforms so the shoulders stay tangent to the edges at every size.
 */
internal class CornerBulgeShape(
    private val corner: BulgeCorner,
    private val bulge: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val belly = bulge.coerceIn(0f, 1f)
        val path = Path()
        when (corner) {
            BulgeCorner.TOP_LEFT -> {
                val rx = w * belly
                val ry = h * belly
                path.moveTo(0f, 0f)
                path.lineTo(0f, ry)
                // Shoulder off the left edge, belly across, shoulder back into the top edge.
                path.cubicTo(0f, ry * 0.55f, rx * 0.20f, ry * 0.98f, rx * 0.55f, ry * 0.92f)
                path.cubicTo(rx * 0.82f, ry * 0.87f, rx * 0.95f, ry * 0.55f, rx, 0f)
                path.close()
            }
            BulgeCorner.BOTTOM_RIGHT -> {
                val rx = w * belly
                val ry = h * belly
                path.moveTo(w, h)
                path.lineTo(w, h - ry)
                path.cubicTo(w, h - ry * 0.55f, w - rx * 0.20f, h - ry * 0.98f, w - rx * 0.55f, h - ry * 0.92f)
                path.cubicTo(w - rx * 0.82f, h - ry * 0.87f, w - rx * 0.95f, h - ry * 0.55f, w - rx, h)
                path.close()
            }
        }
        return Outline.Generic(path)
    }
}

internal enum class BulgeCorner { TOP_LEFT, BOTTOM_RIGHT }

/**
 * A corner bulge at rest: the collapsed tab that stays on screen while its tray has content.
 *
 * Kept deliberately small and quiet (the hibernation-tab reference): a count and a glyph on
 * the blob's belly, one tap to expand. It is chrome, but chrome that exists only while the
 * user has staged something — an empty tray draws nothing at all, so the browser's edges stay
 * clean the moment the clipboard empties.
 */
@Composable
internal fun RestingBulge(
    corner: BulgeCorner,
    swell: Float,
    label: String,
    contentDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sizeDp = 92.dp + 40.dp * swell
    Box(
        modifier
            .size(sizeDp)
            .graphicsLayer { clip = true; shape = CornerBulgeShape(corner, 0.94f + 0.06f * swell) }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onTap)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val inward = 0.30f + 0.04f * swell
        Box(
            Modifier
                .align(if (corner == BulgeCorner.TOP_LEFT) Alignment.TopStart else Alignment.BottomEnd)
                .offset {
                    val edge = (sizeDp.toPx() * inward).roundToInt()
                    if (corner == BulgeCorner.TOP_LEFT) IntOffset(edge, edge) else IntOffset(-edge, -edge)
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(if (corner == BulgeCorner.TOP_LEFT) Alignment.TopStart else Alignment.BottomEnd)
                    .offset {
                        val edge = (sizeDp.toPx() * 0.10f).roundToInt()
                        if (corner == BulgeCorner.TOP_LEFT) IntOffset(edge, edge) else IntOffset(-edge, -edge)
                    },
            )
        }
    }
}

/** Centre of a resting bulge's glyph in the overlay's px space, for flight-path targets. */
internal fun restingBulgeAnchor(
    corner: BulgeCorner,
    overlaySize: Size,
    density: Density,
): Offset {
    val edge = with(density) { 34.dp.toPx() }
    return when (corner) {
        BulgeCorner.TOP_LEFT -> Offset(edge, edge)
        BulgeCorner.BOTTOM_RIGHT -> Offset(overlaySize.width - edge, overlaySize.height - edge)
    }
}

/** A slot glyph reacting to the cluster: swells and brightens as the finger nears. */
@Composable
internal fun ReactiveSlot(
    proximity: Float,
    hit: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    val tint = if (hit) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier.graphicsLayer {
            val scale = 1f + 0.35f * proximity
            scaleX = scale
            scaleY = scale
        },
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 18.dp)
                .graphicsLayer { alpha = 0.4f + 0.6f * proximity },
        )
    }
}
