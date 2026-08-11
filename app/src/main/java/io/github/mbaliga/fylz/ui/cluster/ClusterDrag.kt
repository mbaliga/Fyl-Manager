package io.github.mbaliga.fylz.ui.cluster

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.staging.DropTarget
import io.github.mbaliga.fylz.staging.DropTargetPolicy
import io.github.mbaliga.fylz.staging.StagedItem
import io.github.mbaliga.fylz.staging.TargetReaction
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the cluster is doing right now. */
internal enum class ClusterPhase { IDLE, DRAGGING, RETURNING, GENIE, SNAP_CLIPBOARD, SNAP_MOVE }

/**
 * Drives the press-hold cluster drag: gather, follow, and the three ways it can end.
 *
 * Rows own the gesture (they know which entry was held); this controller owns everything the
 * gesture produces. All positions are **root coordinates** — rows report where they sit in the
 * window, the overlay reports where IT sits, and the difference maps flights into overlay
 * space. That indirection is what lets rows scroll away under a live drag without the cluster
 * caring.
 */
internal class ClusterDragController {

    var phase by mutableStateOf(ClusterPhase.IDLE)
        private set

    /** The files aboard the cluster, top card first. */
    var items: List<StagedItem> = emptyList()
        private set

    /** Where each item's row sat when the hold began — the gather animation's start points. */
    var origins: Map<Uri, Offset> = emptyMap()
        private set

    var dragPosition by mutableStateOf(Offset.Zero)
        private set

    /** 0 = cards on their rows … 1 = clustered under the finger. */
    val gather = Animatable(0f)

    /** Terminal flight progress (genie dive or clipboard/move snap). */
    val flight = Animatable(0f)

    /** The overlay writes the latest slot reactions each frame; release reads them. */
    var reactions: List<TargetReaction> = emptyList()

    fun start(items: List<StagedItem>, origins: Map<Uri, Offset>, at: Offset) {
        if (phase != ClusterPhase.IDLE || items.isEmpty()) return
        this.items = items
        this.origins = origins
        dragPosition = at
        phase = ClusterPhase.DRAGGING
    }

    fun drag(to: Offset) {
        if (phase == ClusterPhase.DRAGGING) dragPosition = to
    }

    /** Where the drag ends decides the ending; [ClusterPhase.RETURNING] is "nowhere". */
    fun release(): DropTarget {
        if (phase != ClusterPhase.DRAGGING) return DropTarget.NONE
        val target = DropTargetPolicy.dropFor(reactions)
        phase = when (target) {
            DropTarget.TRASH -> ClusterPhase.GENIE
            DropTarget.CLIPBOARD -> ClusterPhase.SNAP_CLIPBOARD
            DropTarget.MOVE -> ClusterPhase.SNAP_MOVE
            // New-folder and compress hand off to dialogs immediately; no flight to draw.
            DropTarget.NEW_FOLDER, DropTarget.COMPRESS -> ClusterPhase.IDLE
            DropTarget.NONE -> ClusterPhase.RETURNING
        }
        return target
    }

    fun cancel() {
        if (phase == ClusterPhase.DRAGGING) phase = ClusterPhase.RETURNING
    }

    /** Terminal animation finished (or return flight landed); the cluster is gone. */
    fun settle() {
        phase = ClusterPhase.IDLE
        items = emptyList()
        origins = emptyMap()
        reactions = emptyList()
    }

    val active: Boolean get() = phase != ClusterPhase.IDLE
}

/**
 * The drag layer: cluster cards plus the two corner bulges in their drag-time, swollen form.
 *
 * Composed over the whole workspace and only while the controller is [ClusterDragController.active],
 * so an idle browser pays nothing for any of this. The resting bulges (trays with content, no
 * drag in flight) are separate, lighter composables — see the tray browser.
 *
 * @param onFlightLanded a genie or snap flight finished — commit the drop it animated, for the
 *   cargo it carried. The cargo rides the callback because the controller has already settled
 *   (and emptied) by the time it fires.
 */
@Composable
internal fun ClusterDragLayer(
    controller: ClusterDragController,
    onFlightLanded: (DropTarget, List<StagedItem>) -> Unit,
) {
    if (!controller.active) return
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(Size.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(20f)
            .onGloballyPositioned {
                overlayOrigin = it.positionInRoot()
                overlaySize = Size(it.size.width.toFloat(), it.size.height.toFloat())
            },
    ) {
        val local = controller.dragPosition - overlayOrigin

        // ── Slot geometry, computed in px against this overlay ────────────────────────
        val spacing = with(density) { 86.dp.toPx() }
        val inset = with(density) { 64.dp.toPx() }
        val trashInset = with(density) { 72.dp.toPx() }
        val actionCentres = DropTargetPolicy.actionSlotCentres(spacing, inset)
        val trashCentre = DropTargetPolicy.trashCentre(overlaySize.width, overlaySize.height, trashInset)
        val reactRadius = with(density) { 150.dp.toPx() }
        val hitRadius = with(density) { 56.dp.toPx() }

        val reactions = buildList {
            DropTargetPolicy.actionSlots.forEachIndexed { index, target ->
                val (x, y) = actionCentres[index]
                add(DropTargetPolicy.reactionFor(target, x, y, local.x, local.y, reactRadius, hitRadius))
            }
            add(
                DropTargetPolicy.reactionFor(
                    DropTarget.TRASH,
                    trashCentre.first,
                    trashCentre.second,
                    local.x,
                    local.y,
                    reactRadius,
                    hitRadius,
                ),
            )
        }
        controller.reactions = reactions
        val actionsSwell = reactions.filter { it.target != DropTarget.TRASH }.maxOf { it.proximity }
        val trashReaction = reactions.last()

        // ── The two bulges, swollen for the drag ──────────────────────────────────────
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(with(density) { (spacing * DropTargetPolicy.actionSlots.size + inset).toDp() })
                .graphicsLayer { clip = true; shape = CornerBulgeShape(BulgeCorner.TOP_LEFT, 0.85f + 0.15f * actionsSwell) }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            DropTargetPolicy.actionSlots.forEachIndexed { index, target ->
                val (x, y) = actionCentres[index]
                val reaction = reactions[index]
                ReactiveSlot(
                    proximity = reaction.proximity,
                    hit = reaction.hit,
                    label = target.slotLabel(),
                    modifier = Modifier.offset {
                        IntOffset((x - 20.dp.toPx()).roundToInt(), (y - 20.dp.toPx()).roundToInt())
                    },
                ) { tint ->
                    Icon(target.slotIcon(), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(with(density) { (trashInset * 2.4f).toDp() })
                .graphicsLayer { clip = true; shape = CornerBulgeShape(BulgeCorner.BOTTOM_RIGHT, 0.85f + 0.15f * trashReaction.proximity) }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            TrashGlyph(
                proximity = trashReaction.proximity,
                tint = if (trashReaction.hit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .size(44.dp),
            )
        }

        // ── Flights ───────────────────────────────────────────────────────────────────
        LaunchedEffect(controller.phase) {
            when (controller.phase) {
                ClusterPhase.DRAGGING -> {
                    controller.flight.snapTo(0f)
                    controller.gather.snapTo(0f)
                    controller.gather.animateTo(1f, tween(GATHER_MILLIS, easing = SETTLE))
                }
                ClusterPhase.RETURNING -> {
                    controller.gather.animateTo(0f, tween(GATHER_MILLIS, easing = SETTLE))
                    controller.settle()
                }
                ClusterPhase.GENIE -> {
                    val cargo = controller.items
                    controller.flight.animateTo(1f, tween(GENIE_MILLIS, easing = SETTLE))
                    controller.settle()
                    onFlightLanded(DropTarget.TRASH, cargo)
                }
                ClusterPhase.SNAP_CLIPBOARD, ClusterPhase.SNAP_MOVE -> {
                    val target = if (controller.phase == ClusterPhase.SNAP_CLIPBOARD) DropTarget.CLIPBOARD else DropTarget.MOVE
                    val cargo = controller.items
                    controller.flight.animateTo(1f, tween(SNAP_MILLIS, easing = SETTLE))
                    controller.settle()
                    onFlightLanded(target, cargo)
                }
                ClusterPhase.IDLE -> Unit
            }
        }

        // ── The cluster cards ─────────────────────────────────────────────────────────
        val gather = controller.gather.value
        val flight = controller.flight.value
        val trashAnchor = Offset(trashCentre.first, trashCentre.second)
        val clipboardAnchor = restingBulgeAnchor(BulgeCorner.TOP_LEFT, overlaySize, density)
        val visible = controller.items.take(MAX_CARDS)
        val extra = controller.items.size - visible.size

        visible.forEachIndexed { index, item ->
            val fan = fanOffset(index, density)
            val origin = controller.origins[item.uri]?.minus(overlayOrigin) ?: local
            val clustered = local + fan
            val gathered = lerp(origin, clustered, gather)
            val position = when (controller.phase) {
                ClusterPhase.GENIE -> geniePoint(gathered, trashAnchor, flight, index)
                ClusterPhase.SNAP_CLIPBOARD, ClusterPhase.SNAP_MOVE ->
                    lerp(gathered, clipboardAnchor, flight)
                else -> gathered
            }
            val cardScaleX: Float
            val cardScaleY: Float
            val alpha: Float
            if (controller.phase == ClusterPhase.GENIE) {
                // The genie squeeze: waist narrows almost to nothing while the height first
                // bulges then collapses — enough of the lamp motion to read instantly.
                cardScaleX = 1f - flight * 0.94f
                cardScaleY = (1f + 0.30f * sin(PI * flight).toFloat()) * (1f - flight * 0.85f)
                alpha = 1f - flight * flight
            } else {
                val snap = if (flight > 0f) 1f - flight * 0.8f else 1f
                cardScaleX = snap
                cardScaleY = snap
                alpha = if (flight > 0f) 1f - flight * 0.6f else 1f
            }
            ClusterCard(
                item = item,
                badge = if (index == 0 && extra > 0) "+$extra" else null,
                modifier = Modifier
                    .offset {
                        val half = 36.dp.roundToPx()
                        IntOffset(position.x.roundToInt() - half, position.y.roundToInt() - half)
                    }
                    .graphicsLayer {
                        scaleX = cardScaleX
                        scaleY = cardScaleY
                        this.alpha = alpha
                        rotationZ = (index - visible.size / 2f) * 4.5f * gather
                    }
                    .zIndex((MAX_CARDS - index).toFloat()),
            )
        }
    }
}

@Composable
private fun ClusterCard(item: StagedItem, badge: String?, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.size(72.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.displayName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
            )
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** The stacked fan under the finger: a tight trailing spread, top card centred. */
private fun fanOffset(index: Int, density: androidx.compose.ui.unit.Density): Offset = with(density) {
    Offset(x = (index * 7).dp.toPx(), y = (index * 9).dp.toPx())
}

/** A curved dive into the can: quadratic arc with per-card stagger so the stack files in. */
private fun geniePoint(from: Offset, to: Offset, progress: Float, index: Int): Offset {
    val staggered = (progress * (1f + index * 0.12f)).coerceIn(0f, 1f)
    val control = Offset((from.x + to.x) / 2f, min(from.y, to.y) - 140f)
    val inverse = 1f - staggered
    return Offset(
        inverse * inverse * from.x + 2 * inverse * staggered * control.x + staggered * staggered * to.x,
        inverse * inverse * from.y + 2 * inverse * staggered * control.y + staggered * staggered * to.y,
    )
}

private fun lerp(from: Offset, to: Offset, fraction: Float): Offset =
    Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)

internal fun DropTarget.slotIcon() = when (this) {
    DropTarget.CLIPBOARD -> Icons.Outlined.ContentPaste
    DropTarget.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    DropTarget.NEW_FOLDER -> Icons.Outlined.CreateNewFolder
    DropTarget.COMPRESS -> Icons.Outlined.Archive
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

internal fun DropTarget.slotLabel() = when (this) {
    DropTarget.CLIPBOARD -> "Clipboard"
    DropTarget.MOVE -> "Move"
    DropTarget.NEW_FOLDER -> "New folder"
    DropTarget.COMPRESS -> "Compress"
    DropTarget.TRASH -> "Recycle"
    DropTarget.NONE -> ""
}

/** House motion: the constellation's eased settle; flights never overshoot. */
private val SETTLE = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private const val GATHER_MILLIS = 260
private const val GENIE_MILLIS = 430
private const val SNAP_MILLIS = 320
private const val MAX_CARDS = 5
