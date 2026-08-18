package io.github.mbaliga.fylz.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.canvas.TilePlacement
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.displayName
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val TILE_WIDTH = 92.dp
private const val NUDGE_FRACTION = 0.08f
private const val ARRANGE_SCALE = 1.06f
private const val ARRANGE_ELEVATION = 10f

/**
 * One tile on [SubjectCanvas]: a folder or file at its own loose, draggable spot.
 *
 * No cluster, no selection here -- this build's canvas is the one surface that never joins the
 * cluster contract (entries stay forced-empty reaching it from home). Its own gesture table
 * instead:
 *
 * One [pointerInput] block owns the tile for its whole lifetime, keyed only on the entry's uri --
 * never swapped for a different modifier based on [arranging], because that state is set by
 * this very gesture: a hard swap mid-touch would tear down whatever node is tracking the
 * pointer and attach a new one that missed the down, and Compose only replays Move/Up to nodes
 * that were already part of the hit-test path recorded at the down. Instead the block races a
 * long-press timeout against the pointer lifting: released early is a tap ([onOpen]); still down
 * past the timeout enters arrange mode (haptic + [onEnterArrange]) and keeps tracking the SAME
 * pointer into a drag. The drag only ever moves a local, unpersisted pixel offset; [onCommit] is
 * the single store write, fired once at the end.
 *
 * Every drag has a non-gesture twin: [customActions] nudge the tile 8% of the viewport per
 * activation and persist immediately, exactly as available whether or not the tile is currently
 * being arranged -- gestures are never the only path (`FileDeck`'s a11y pair is the same shape).
 */
@Composable
internal fun CanvasTile(
    entry: FileEntry,
    placement: TilePlacement,
    defaultPlacement: TilePlacement,
    arranging: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    onOpen: () -> Unit,
    onEnterArrange: () -> Unit,
    onExitArrange: () -> Unit,
    onCommit: (TilePlacement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val showExtensions = LocalShowExtensions.current
    val shownName = displayName(entry.name, entry.isDirectory, showExtensions)

    // Local and unpersisted: the pixel offset a live drag adds on top of `placement`. Zeroed at
    // grab and at release alike -- once a drag commits, the NEXT `placement` this composable
    // receives already reflects it, so nothing is lost by resetting the local delta to nothing.
    var dragOffsetPx by remember(entry.uri) { mutableStateOf(Offset.Zero) }

    fun nudged(dx: Float, dy: Float): TilePlacement = CanvasLayoutPolicy.clamp(
        placement.copy(x = placement.x + dx * NUDGE_FRACTION, y = placement.y + dy * NUDGE_FRACTION),
    )

    val gestureModifier = Modifier.pointerInput(entry.uri) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Races the long-press timeout against the pointer lifting, on the SAME down --
            // `true` = up arrived first (a tap), `false` = the wait was cancelled some other
            // way (e.g. consumed elsewhere), `null` = the timeout won while still down.
            val liftedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation() != null
            }
            when (liftedEarly) {
                true -> onOpen()
                false -> Unit
                null -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEnterArrange()
                    val completed = drag(down.id) { change ->
                        change.consume()
                        dragOffsetPx += change.positionChange()
                    }
                    if (completed) {
                        val next = CanvasLayoutPolicy.clamp(
                            TilePlacement(
                                x = placement.x + dragOffsetPx.x / viewportWidthPx,
                                y = placement.y + dragOffsetPx.y / viewportHeightPx,
                                z = placement.z,
                            ),
                        )
                        dragOffsetPx = Offset.Zero
                        onCommit(next)
                    } else {
                        dragOffsetPx = Offset.Zero
                    }
                    onExitArrange()
                }
            }
        }
    }

    Column(
        modifier
            .width(TILE_WIDTH)
            .offset {
                IntOffset(
                    x = (placement.x * viewportWidthPx + dragOffsetPx.x).roundToInt(),
                    y = (placement.y * viewportHeightPx + dragOffsetPx.y).roundToInt(),
                )
            }
            .zIndex(placement.z.toFloat())
            .graphicsLayer {
                scaleX = if (arranging) ARRANGE_SCALE else 1f
                scaleY = if (arranging) ARRANGE_SCALE else 1f
                shadowElevation = if (arranging) ARRANGE_ELEVATION else 0f
                shape = RoundedCornerShape(12.dp)
                clip = false
            }
            .background(
                if (arranging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(12.dp),
            )
            .then(gestureModifier)
            .padding(8.dp)
            .semantics {
                contentDescription = if (entry.isDirectory) "Folder $shownName" else shownName
                onClick(label = "Open") { onOpen(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Move left") { onCommit(nudged(-1f, 0f)); true },
                    CustomAccessibilityAction("Move right") { onCommit(nudged(1f, 0f)); true },
                    CustomAccessibilityAction("Move up") { onCommit(nudged(0f, -1f)); true },
                    CustomAccessibilityAction("Move down") { onCommit(nudged(0f, 1f)); true },
                    CustomAccessibilityAction("Reset position") {
                        onCommit(CanvasLayoutPolicy.clamp(defaultPlacement))
                        true
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (entry.isDirectory) {
            EntryThumbnail(entry, size = 56.dp)
        } else {
            EntryThumbnail(entry, size = 72.dp, pixels = 384)
        }
        Text(
            shownName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
