package io.github.mbaliga.fylz.ui.cluster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The organic corner blob: material melting out of the screen's corner, not a card floating over
 * it. The silhouette is a quarter-round belly bridged to the two screen edges by concave
 * shoulders — the shoulders are what sell "the edge itself bulged" instead of "a circle was
 * pasted into the corner".
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
 * How big a resting tab is. Small enough to sit in the corner of a listing without becoming
 * part of it — the reference is a hibernation tab peeling off a panel edge, not a FAB.
 */
internal val RestingBulgeSize: Dp = 58.dp

/**
 * A corner bulge at rest: the collapsed tab that stays on screen while its tray has content.
 *
 * Deliberately tiny and quiet. It is chrome, and chrome that exists only while the user has
 * staged something — an empty tray draws nothing at all, so the browser's edges stay clean the
 * moment the clipboard empties. The count rides a small badge on the outer shoulder rather than
 * being stamped across the belly, which is what let the old tab shrink to a third of its size
 * without the glyph and the number fighting for the same pixels.
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
    val sizeDp = RestingBulgeSize + 8.dp * swell
    Box(
        modifier
            .size(sizeDp)
            .graphicsLayer { clip = true; shape = CornerBulgeShape(corner, 0.96f + 0.04f * swell) }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onTap)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(
            Modifier
                .align(if (corner == BulgeCorner.TOP_LEFT) Alignment.TopStart else Alignment.BottomEnd)
                .offset {
                    val edge = (sizeDp.toPx() * 0.26f).roundToInt()
                    if (corner == BulgeCorner.TOP_LEFT) IntOffset(edge, edge) else IntOffset(-edge, -edge)
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        if (label.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(if (corner == BulgeCorner.TOP_LEFT) Alignment.TopStart else Alignment.BottomEnd)
                    .offset {
                        val edge = (sizeDp.toPx() * 0.06f).roundToInt()
                        if (corner == BulgeCorner.TOP_LEFT) IntOffset(edge, edge) else IntOffset(-edge, -edge)
                    },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Centre of a resting bulge's glyph in the overlay's px space, for flight-path targets. */
internal fun restingBulgeAnchor(
    corner: BulgeCorner,
    overlaySize: Size,
    density: Density,
): Offset {
    val edge = with(density) { (RestingBulgeSize * 0.42f).toPx() }
    return when (corner) {
        BulgeCorner.TOP_LEFT -> Offset(edge, edge)
        BulgeCorner.BOTTOM_RIGHT -> Offset(overlaySize.width - edge, overlaySize.height - edge)
    }
}

/**
 * A slot glyph reacting to the cluster: swells and brightens as the finger nears.
 *
 * No label of its own. Four labelled slots on a corner arc is four captions overlapping each
 * other and the icons they belong to; the arc names only whichever slot the finger is nearest,
 * once, in a fixed place — see [SlotCaption].
 */
@Composable
internal fun ReactiveSlot(
    proximity: Float,
    hit: Boolean,
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
            val scale = 1f + 0.30f * proximity
            scaleX = scale
            scaleY = scale
            alpha = 0.55f + 0.45f * proximity
        },
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

/**
 * The single caption naming whatever slot the finger is nearest, drawn clear of the arc.
 *
 * One caption in one place is what makes a four-slot corner readable: the labels used to be
 * stamped under each glyph, where at any arc small enough to be discreet they overlapped both
 * each other and the icons. Fading rather than swapping instantly keeps the arc from flickering
 * as the finger crosses the midpoint between two slots.
 */
@Composable
internal fun SlotCaption(text: String, strength: Float, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(50),
        modifier = modifier.graphicsLayer { alpha = strength },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
