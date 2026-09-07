package io.github.mbaliga.fylz.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.canvas.TilePlacement
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.ClusterGestureHooks
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val TILE_WIDTH = 92.dp
private const val NUDGE_FRACTION = 0.08f
private const val ARRANGE_SCALE = 1.06f
private const val ARRANGE_ELEVATION = 10f

/**
 * One tile on [SubjectCanvas]: a folder or file at its own loose, draggable spot.
 *
 * Selection now reaches the canvas too, under the same hard-swap discipline the browse rows use:
 * with no selection live anywhere, a long press enters arrange (this tile's own freeform drag) --
 * unchanged from before. Once a selection exists, on this tile or another, a long press never
 * arranges again: it either joins the selection (this tile not yet selected) or, if this tile is
 * already selected and [cluster] is non-null, starts the same whole-selection cluster drag the
 * browse rows' `FileRowV1` uses. One finger never carries two meanings at once, so the branch is
 * decided once, at the exact moment the old code decided tap-vs-arrange.
 *
 * One [pointerInput] block owns the tile for its whole lifetime, keyed only on the entry's uri --
 * never swapped for a different modifier based on [arranging] or the selection args, because
 * those are set by this very gesture (or a sibling tile's own): a hard swap mid-touch would tear
 * down whatever node is tracking the pointer and attach a new one that missed the down, and
 * Compose only replays Move/Up to nodes that were already part of the hit-test path recorded at
 * the down. [selected], [selectionActive], [onToggleSelection] and [cluster] are read through
 * [rememberUpdatedState] instead, so the branch below always sees the current frame's values even
 * though the coroutine reading them was launched on a much earlier one. Instead the block races a
 * long-press timeout against the pointer lifting: released early is a tap ([onOpen], or a toggle
 * while a selection is active); still down past the timeout enters arrange, joins the selection,
 * or starts the cluster drag, per the paragraph above. The drag only ever moves a local,
 * unpersisted pixel offset; [onCommit] is the single store write, fired once at the end.
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
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    cluster: ClusterGestureHooks? = null,
) {
    val haptics = LocalHapticFeedback.current
    val showExtensions = LocalShowExtensions.current
    val themeStyle = LocalThemeStyle.current
    val shownName = displayName(entry.name, entry.isDirectory, showExtensions)
    // See KDoc: a bare `selected` inside the semantics block below would resolve to this
    // parameter on both sides of an assignment, so the read gets its own name up front.
    val tileSelected = selected

    // Local and unpersisted: the pixel offset a live drag adds on top of `placement`. Zeroed at
    // grab and at release alike -- once a drag commits, the NEXT `placement` this composable
    // receives already reflects it, so nothing is lost by resetting the local delta to nothing.
    var dragOffsetPx by remember(entry.uri) { mutableStateOf(Offset.Zero) }
    // This tile's own origin in root coordinates, refreshed on every layout pass -- the cluster
    // branch below adds it to a local pointer position to report root-space coordinates, exactly
    // what `FileRowV1`'s own cluster hooks expect.
    var originInRoot by remember(entry.uri) { mutableStateOf(Offset.Zero) }

    // Read fresh inside the long-lived pointerInput coroutine below (see KDoc): plain parameters
    // would freeze at whatever they were the one time this key launched the coroutine.
    val selectedState = rememberUpdatedState(selected)
    val selectionActiveState = rememberUpdatedState(selectionActive)
    val toggleSelectionState = rememberUpdatedState(onToggleSelection)
    val clusterState = rememberUpdatedState(cluster)

    fun nudged(dx: Float, dy: Float): TilePlacement = CanvasLayoutPolicy.clamp(
        placement.copy(x = placement.x + dx * NUDGE_FRACTION, y = placement.y + dy * NUDGE_FRACTION),
    )

    val gestureModifier = Modifier
        .onGloballyPositioned { coordinates ->
            originInRoot = coordinates.positionInRoot()
            if (selected) cluster?.onPositioned(entry.uri, coordinates.boundsInRoot().center)
        }
        .pointerInput(entry.uri) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Races the long-press timeout against the pointer lifting, on the SAME down --
                // `true` = up arrived first (a tap), `false` = the wait was cancelled some other
                // way (e.g. consumed elsewhere), `null` = the timeout won while still down.
                val liftedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation() != null
                }
                when (liftedEarly) {
                    true -> if (selectionActiveState.value) toggleSelectionState.value?.invoke() else onOpen()
                    false -> Unit
                    null -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val liveCluster = clusterState.value
                        when {
                            // Already part of the live selection: hand off to the same
                            // whole-selection drag the browse rows use, never the lone-tile
                            // arrange below -- the two must not both claim this finger.
                            selectedState.value && liveCluster != null -> {
                                liveCluster.onStart(originInRoot + down.position)
                                val completed = drag(down.id) { change ->
                                    change.consume()
                                    liveCluster.onDrag(originInRoot + change.position)
                                }
                                if (completed) liveCluster.onEnd() else liveCluster.onCancel()
                            }
                            // A selection is live elsewhere but not on this tile: the long press
                            // joins it instead of arranging -- arrange is retired for as long as
                            // any selection exists, on any tile. Still waits out the same touch
                            // (no drag to track) so the rest of it doesn't leak to whatever is
                            // drawn underneath as an unrelated gesture.
                            selectionActiveState.value -> {
                                toggleSelectionState.value?.invoke()
                                waitForUpOrCancellation()
                            }
                            else -> {
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
                this.selected = tileSelected
                onClick(label = if (selectionActive) "Toggle selection" else "Open") {
                    if (selectionActive) onToggleSelection?.invoke() else onOpen()
                    true
                }
                customActions = buildList {
                    add(CustomAccessibilityAction("Move left") { onCommit(nudged(-1f, 0f)); true })
                    add(CustomAccessibilityAction("Move right") { onCommit(nudged(1f, 0f)); true })
                    add(CustomAccessibilityAction("Move up") { onCommit(nudged(0f, -1f)); true })
                    add(CustomAccessibilityAction("Move down") { onCommit(nudged(0f, 1f)); true })
                    add(
                        CustomAccessibilityAction("Reset position") {
                            onCommit(CanvasLayoutPolicy.clamp(defaultPlacement))
                            true
                        },
                    )
                    onToggleSelection?.let { toggle ->
                        add(CustomAccessibilityAction("Toggle selection") { toggle(); true })
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            if (entry.isDirectory) {
                EntryThumbnail(entry, size = 56.dp)
            } else {
                EntryThumbnail(entry, size = 72.dp, pixels = 384)
            }
            if (selected && !themeStyle.marksSelectionInline()) {
                SelectionMark(themeStyle, Modifier.align(Alignment.TopStart))
            }
        }
        Text(
            (if (selected && themeStyle.marksSelectionInline()) "> " else "") + shownName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Whether [ThemeStyle] marks a selected item with a leading glyph spliced into its name instead
 * of a mark on the thumbnail -- CLI listings have no separate mark column, so the gutter glyph
 * rides the name text itself; every other style overlays [SelectionMark] on the thumbnail corner.
 */
internal fun ThemeStyle.marksSelectionInline(): Boolean = this == ThemeStyle.CLI

/**
 * The on-item selection mark for every style except CLI (see [marksSelectionInline]): Neo and
 * Fylz get the disc-and-checkmark the browse rows already draw on selected `FileRowV1`s. Vintage
 * and Retro swap it for a flat, single-colour square -- no icon glyph, no curve -- matching the
 * integer-grid pixel art those two styles draw everywhere else.
 */
@Composable
internal fun SelectionMark(themeStyle: ThemeStyle, modifier: Modifier = Modifier) {
    when (themeStyle) {
        ThemeStyle.NEO, ThemeStyle.FYLZ -> Box(
            modifier
                .size(18.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        ThemeStyle.VINTAGE, ThemeStyle.RETRO -> Box(modifier.size(14.dp).background(MaterialTheme.colorScheme.primary)) {}
        ThemeStyle.CLI -> Unit
    }
}
