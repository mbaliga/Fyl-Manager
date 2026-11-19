package io.github.mbaliga.fylz.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.places.PopupCardSpec
import io.github.mbaliga.fylz.ui.places.PopupCardSpec.Seg

/** The picture's outline (see [PopupCardSpec.outline]) scaled to a card of side [sidePx]. */
private fun pictureOutline(sidePx: Float): Path {
    val k = sidePx / PopupCardSpec.SIDE
    val path = Path()
    for (seg in PopupCardSpec.outline()) {
        when (seg) {
            is Seg.Move -> path.moveTo(seg.x * k, seg.y * k)
            is Seg.Line -> path.lineTo(seg.x * k, seg.y * k)
            is Seg.Arc -> path.arcTo(
                Rect((seg.cx - seg.r) * k, (seg.cy - seg.r) * k, (seg.cx + seg.r) * k, (seg.cy + seg.r) * k),
                seg.startDeg,
                seg.sweepDeg,
                false,
            )
        }
    }
    path.close()
    return path
}

private object PictureShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(pictureOutline(size.width))
}

/**
 * The quick-preview popup, built to the reference image: a square card with the picture running
 * edge to edge inside a thin black frame, a black tab notched into the top-left corner holding
 * three actions (open, add to or remove from the selection, share) and one in the bottom-right
 * holding the close mark. [content] is the picture; the tabs are cut out of it, not laid over it.
 *
 * The card sits over a dimmed screen and eases in; tapping the dimmed area closes it. All sizes
 * come from [PopupCardSpec], scaled to the card, so the proportions hold at any size.
 *
 * The reference draws the three actions as the digits "1 2 3", placeholders for whatever the
 * actions are. They sit 96 units apart, which on a phone is about 32dp, narrower than a 48dp
 * touch target, so each button's hit area is taller than the tab and bleeds onto the picture.
 */
@Composable
fun QuickPreviewPopup(
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "popup-appear",
    )

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f * appear.coerceIn(0f, 1f)))
            // Tapping outside the card closes it. No ripple: the scrim is not a button.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .semantics { contentDescription = "Preview. Tap outside to close" },
        contentAlignment = Alignment.Center,
    ) {
        val side: Dp = minOf(maxWidth * 0.92f, maxHeight * 0.8f)
        val unit: Dp = side / PopupCardSpec.SIDE
        val outer = RoundedCornerShape(unit * PopupCardSpec.R_OUT)

        Box(
            Modifier
                .size(side)
                .graphicsLayer {
                    val s = 0.9f + 0.1f * appear.coerceIn(0f, 1.05f)
                    scaleX = s
                    scaleY = s
                    alpha = appear.coerceIn(0f, 1f)
                }
                // clip = false: the action buttons' hit areas are taller than their tab and must
                // not be cut off at the card's edge.
                .shadow(24.dp, outer, clip = false)
                // Taps on the card are the card's: they must not reach the scrim and close it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // The black card, and the picture cut to its outline (tabs notched out) inside it.
            Box(Modifier.fillMaxSize().clip(outer).background(Color.Black)) {
                Box(Modifier.fillMaxSize().clip(PictureShape)) { content() }
            }

            // Top-left tab: three actions in equal cells.
            ActionButton(0, unit, "Open", onOpen) {
                Icon(Icons.Outlined.OpenInNew, null, tint = Color.White, modifier = Modifier.size(unit * PopupCardSpec.ACTION_GLYPH * 1.15f))
            }
            ActionButton(1, unit, if (selected) "Remove from selection" else "Add to selection", onToggleSelect) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(unit * PopupCardSpec.ACTION_GLYPH * 1.15f),
                )
            }
            ActionButton(2, unit, "Share", onShare) {
                Icon(Icons.Outlined.Share, null, tint = Color.White, modifier = Modifier.size(unit * PopupCardSpec.ACTION_GLYPH * 1.15f))
            }

            // Bottom-right tab: close.
            val glyph = unit * PopupCardSpec.CLOSE_GLYPH
            val hit = maxOf(48.dp, unit * 96f)
            Box(
                Modifier
                    .offset(
                        x = unit * PopupCardSpec.CLOSE_CENTER_X - hit / 2,
                        y = unit * PopupCardSpec.CLOSE_CENTER_Y - hit / 2,
                    )
                    .size(hit)
                    .clickable(role = Role.Button, onClickLabel = "Close preview", onClick = onClose)
                    .semantics { contentDescription = "Close preview" },
                contentAlignment = Alignment.Center,
            ) {
                // A cross with flat cut ends, as the reference's glyph has: two butt-capped strokes
                // clipped to the glyph's square.
                Canvas(Modifier.size(glyph).clipToBounds()) {
                    val stroke = PopupCardSpec.CLOSE_STROKE * (size.width / PopupCardSpec.CLOSE_GLYPH)
                    drawLine(Color.White, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = stroke, cap = StrokeCap.Butt)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke, cap = StrokeCap.Butt)
                }
            }
        }
    }
}

/** One cell of the top tab: centred on the reference's cell, with a hit area at least 48dp tall. */
@Composable
private fun BoxScope.ActionButton(
    index: Int,
    unit: Dp,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val width = unit * PopupCardSpec.ACTION_CELL_W
    val height = maxOf(48.dp, unit * PopupCardSpec.TOP_TAB_H)
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(
                x = unit * PopupCardSpec.actionCenterX(index) - width / 2,
                y = unit * PopupCardSpec.ACTION_CENTER_Y - height / 2,
            )
            .size(width, height)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon() }
}
