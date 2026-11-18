package io.github.mbaliga.fylz.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.ui.places.CollectionStackSpec
import io.github.mbaliga.fylz.ui.places.RecentFile
import io.github.mbaliga.fylz.ui.places.StorageCategory
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A collection drawn as the handoff's card stack: the newest files fanned either side of the
 * active one, which sits in front with a white border and a shadow. Swiping sideways moves through
 * them (the cards follow the finger, then settle on one), and a tap opens the collection.
 *
 * The whole stack is laid out in the handoff's 280-unit width and scaled to the space given, so
 * the same component fits a half-width tile on the landing screen and a wider header.
 */
@Composable
internal fun CollectionStack(
    items: List<RecentFile>,
    category: StorageCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Open collection",
) {
    val spec = CollectionStackSpec
    BoxWithConstraints(
        modifier.aspectRatio(spec.STACK_W / spec.STACK_H),
    ) {
        val unit: Dp = maxWidth / spec.STACK_W
        val unitPx = with(LocalDensity.current) { unit.toPx() }
        val stepPx = spec.SWIPE_STEP * unitPx
        val scope = rememberCoroutineScope()
        val position = remember { Animatable(0f) }
        var dragStart by remember { mutableFloatStateOf(0f) }
        val lastIndex = (items.size - 1).coerceAtLeast(0)

        // A new set of files (the collection changed) starts back at the newest.
        LaunchedEffect(items.firstOrNull()?.uri) { position.snapTo(0f) }

        val dragState = rememberDraggableState { delta ->
            scope.launch { position.snapTo((position.value - delta / stepPx).coerceIn(0f, lastIndex.toFloat())) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .semantics { this.contentDescription = contentDescription }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { dragStart = position.value },
                    onDragStopped = { velocity ->
                        val start = dragStart.roundToInt()
                        val dragPx = -(position.value - dragStart) * stepPx
                        // Compose reports px/s; the handoff's threshold is px/ms.
                        val target = spec.settle(start, dragPx, velocity / 1000f / unitPx.coerceAtLeast(0.01f), items.size)
                        scope.launch {
                            position.animateTo(
                                target.toFloat(),
                                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                            )
                        }
                    },
                )
                .clickable(onClick = onClick),
        ) {
            items.forEachIndexed { index, file ->
                // Only the cards that can be seen are composed: a collection may hold thousands.
                if (abs(index - position.value) < spec.VISIBLE_DISTANCE) {
                    StackCard(file, category, index, position.value, unit, unitPx)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.StackCard(
    file: RecentFile,
    category: StorageCategory,
    index: Int,
    position: Float,
    unit: Dp,
    unitPx: Float,
) {
    val spec = CollectionStackSpec
    val pose = spec.pose(index - position)
    if (pose.alpha <= 0.001f) return
    val shape = RoundedCornerShape(unit * spec.CARD_RADIUS)
    Box(
        Modifier
            // Centred horizontally, TOP units from the top, as in the handoff.
            .align(Alignment.TopCenter)
            .offset(y = unit * spec.TOP)
            .zIndex(pose.z.toFloat())
            .requiredSize(unit * spec.CARD_W, unit * spec.CARD_H)
            .graphicsLayer {
                translationX = pose.x * unitPx
                translationY = pose.y * unitPx
                rotationZ = pose.rotationDeg
                scaleX = pose.scale
                scaleY = pose.scale
                alpha = pose.alpha
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .shadow(unit * 6f, shape)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(unit * spec.BORDER, Color.White), shape),
    ) {
        Box(Modifier.fillMaxSize().padding(unit * spec.BORDER).clip(RoundedCornerShape(unit * (spec.CARD_RADIUS - spec.BORDER)))) {
            RecentFace(FolderCardContent.Recent(file, category), unit)
        }
    }
}

/** A stack with nothing in it: a dashed-looking empty card, so an empty collection still has a place. */
@Composable
internal fun EmptyStack(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spec = CollectionStackSpec
    BoxWithConstraints(modifier.aspectRatio(spec.STACK_W / spec.STACK_H)) {
        val unit = maxWidth / spec.STACK_W
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .offset(y = unit * spec.TOP)
                    .requiredSize(unit * spec.CARD_W, unit * spec.CARD_H)
                    .border(
                        BorderStroke(unit * 2f, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(unit * spec.CARD_RADIUS),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
