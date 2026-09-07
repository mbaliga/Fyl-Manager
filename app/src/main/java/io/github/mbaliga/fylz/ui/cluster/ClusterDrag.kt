package io.github.mbaliga.fylz.ui.cluster

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.staging.DropTarget
import io.github.mbaliga.fylz.staging.DropTargetPolicy
import io.github.mbaliga.fylz.staging.StagedItem
import io.github.mbaliga.fylz.staging.TargetReaction
import io.github.mbaliga.fylz.ui.components.StackCard
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the cluster is doing right now. */
internal enum class ClusterPhase { IDLE, DRAGGING, RETURNING, GENIE, SNAP_CLIPBOARD, SNAP_MOVE }

/** How many cards the stack ever draws, however many files are aboard. */
internal const val MAX_CARDS = 5

/**
 * Drives the press-hold cluster drag: gather, follow, and the three ways it can end.
 *
 * Rows own the gesture (they know which entry was held); this controller owns everything the
 * gesture produces. All positions are **root coordinates** — rows report where they sit in the
 * window, the overlay reports where IT sits, and the difference maps flights into overlay
 * space. That indirection is what lets rows scroll away under a live drag without the cluster
 * caring.
 *
 * The follow itself is physical rather than interpolated: each card is a [CardSpring] anchored
 * to the finger, so the gather, the lag, the fan-out through a fast turn and the settle when the
 * finger stops are all one simulation instead of four separate tweens. See [ClusterMotion].
 */
internal class ClusterDragController {

    var phase by mutableStateOf(ClusterPhase.IDLE)
        private set

    /** The files aboard the cluster, top card first. */
    var items: List<StagedItem> = emptyList()
        private set

    /** Where each item's row sat when the hold began — the springs' seed positions. */
    var origins: Map<Uri, Offset> = emptyMap()
        private set

    var dragPosition by mutableStateOf(Offset.Zero)
        private set

    /** One spring per drawn card. Allocated once; a drag re-seeds rather than reallocates. */
    val springs: List<CardSpring> = List(MAX_CARDS) { CardSpring() }

    /** Where each card was when a terminal flight began — flights start from the physics. */
    var flightStart: List<Offset> = emptyList()

    /** Terminal flight progress (genie dive, clipboard/move snap, or the spring home). */
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
            // Shelf, new-folder and compress commit immediately; no flight to draw.
            DropTarget.SHELF, DropTarget.NEW_FOLDER, DropTarget.COMPRESS -> ClusterPhase.IDLE
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
        flightStart = emptyList()
    }

    val active: Boolean get() = phase != ClusterPhase.IDLE
}

/**
 * The drag layer: cluster cards plus the two corner bulges in their drag-time form.
 *
 * Composed over the whole workspace and only while the controller is [ClusterDragController.active],
 * so an idle browser pays nothing for any of this. The resting bulges (trays with content, no
 * drag in flight) are separate, lighter composables — see the tray browser.
 *
 * ### Why the bulges are small
 *
 * They used to be laid out as a marching row of slots, which forced the actions blob to be four
 * slot-spacings wide — over 400dp, most of the top of a phone, dropped on top of the toolbar the
 * moment a drag began. The slots now sit on a quarter arc struck from the corner
 * ([DropTargetPolicy.actionSlotCentres]), so the blob is only as big as that radius plus a
 * glyph, and it reads as the corner swelling rather than as a panel landing on the listing.
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

    // How far the grab has swelled from row-thumbnail size into carry size, and risen out from
    // under the thumb. One Animatable for the whole stack: the cards gather onto the finger
    // together, so they grow and rise together too.
    val gather = remember { Animatable(0f) }

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
        val arcRadius = with(density) { ARC_RADIUS.toPx() }
        val trashInset = with(density) { TRASH_INSET.toPx() }
        val actionCentres = DropTargetPolicy.actionSlotCentres(arcRadius)
        val trashCentre = DropTargetPolicy.trashCentre(overlaySize.width, overlaySize.height, trashInset)
        val reactRadius = with(density) { 150.dp.toPx() }
        val hitRadius = with(density) { 34.dp.toPx() }
        val trashHitRadius = with(density) { 56.dp.toPx() }

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
                    trashHitRadius,
                ),
            )
        }
        controller.reactions = reactions
        val actionReactions = reactions.filter { it.target != DropTarget.TRASH }
        val actionsSwell = actionReactions.maxOf { it.proximity }
        val nearestAction = actionReactions.maxByOrNull { it.proximity }
        val trashReaction = reactions.last()

        // ── The actions bulge: a quarter arc of slots on a small corner blob ──────────
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(ACTIONS_BULGE)
                .graphicsLayer {
                    clip = true
                    shape = CornerBulgeShape(BulgeCorner.TOP_LEFT, 0.88f + 0.12f * actionsSwell)
                }
                .background(InkSurface),
        ) {
            // One caption, in the corner the arc encloses. Four labels under four glyphs is what
            // made the old bulge unreadable at any size small enough to be discreet.
            SlotCaption(
                text = nearestAction?.target?.slotLabel().orEmpty(),
                strength = actionsSwell,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 26.dp),
            )
            DropTargetPolicy.actionSlots.forEachIndexed { index, target ->
                val (x, y) = actionCentres[index]
                val reaction = reactions[index]
                ReactiveSlot(
                    proximity = reaction.proximity,
                    hit = reaction.hit,
                    modifier = Modifier.offset {
                        IntOffset((x - 13.dp.toPx()).roundToInt(), (y - 13.dp.toPx()).roundToInt())
                    },
                ) { tint ->
                    Icon(target.slotIcon(), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                }
            }
        }

        // ── The trash bulge: alone in the opposite corner, and it reacts ──────────────
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(TRASH_BULGE)
                .graphicsLayer {
                    clip = true
                    shape = CornerBulgeShape(BulgeCorner.BOTTOM_RIGHT, 0.88f + 0.12f * trashReaction.proximity)
                }
                .background(InkSurface)
                // The hot wash: invisible at rest, a soft red fill once the drop would commit --
                // the same "release here" cue the swell already gives, doubled in colour.
                .background(InkDanger.copy(alpha = if (trashReaction.hit) 0.24f else 0f)),
        ) {
            TrashGlyph(
                proximity = trashReaction.proximity,
                tint = if (trashReaction.hit) InkDanger else InkContent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(46.dp),
            )
        }

        // ── The physics loop: cards chase the finger while the drag lives ─────────────
        // Keyed on overlaySize as well as phase because seeding needs the overlay's origin, and
        // on the very first composition of a drag that origin is not measured yet. Re-seeding
        // when it arrives is free — the drag is microseconds old.
        LaunchedEffect(controller.phase, overlaySize) {
            if (controller.phase != ClusterPhase.DRAGGING || overlaySize == Size.Zero) return@LaunchedEffect
            controller.flight.snapTo(0f)
            val visible = controller.items.take(MAX_CARDS)
            visible.forEachIndexed { index, item ->
                val seed = controller.origins[item.uri]?.minus(overlayOrigin) ?: local
                controller.springs[index].snap(seed)
            }
            var previousFrame = 0L
            while (isActive) {
                withFrameNanos { now ->
                    val dt = if (previousFrame == 0L) 1f / 60f else (now - previousFrame) / 1_000_000_000f
                    previousFrame = now
                    val anchor = controller.dragPosition - overlayOrigin
                    visible.indices.forEach { index ->
                        controller.springs[index].step(
                            target = anchor + fanOffset(index, density),
                            dtSeconds = dt,
                            stiffness = ClusterMotion.stiffnessFor(index),
                            damping = ClusterMotion.dampingFor(index),
                        )
                    }
                }
            }
        }

        // ── The grab swell: row-sized in the hand, carry-sized a fifth of a second later ─
        LaunchedEffect(controller.phase) {
            if (controller.phase != ClusterPhase.DRAGGING) return@LaunchedEffect
            gather.snapTo(0f)
            gather.animateTo(1f, tween(GATHER_MILLIS, easing = SETTLE))
        }

        // ── Terminal flights, each starting from wherever the physics left the cards ──
        LaunchedEffect(controller.phase) {
            when (controller.phase) {
                ClusterPhase.DRAGGING, ClusterPhase.IDLE -> Unit
                ClusterPhase.RETURNING -> {
                    controller.flightStart = controller.springs.map { it.position }
                    controller.flight.snapTo(0f)
                    controller.flight.animateTo(1f, tween(RETURN_MILLIS, easing = SETTLE))
                    controller.settle()
                }
                ClusterPhase.GENIE -> {
                    controller.flightStart = controller.springs.map { it.position }
                    val cargo = controller.items
                    controller.flight.snapTo(0f)
                    controller.flight.animateTo(1f, tween(GENIE_MILLIS, easing = SETTLE))
                    controller.settle()
                    onFlightLanded(DropTarget.TRASH, cargo)
                }
                ClusterPhase.SNAP_CLIPBOARD, ClusterPhase.SNAP_MOVE -> {
                    controller.flightStart = controller.springs.map { it.position }
                    val target = if (controller.phase == ClusterPhase.SNAP_CLIPBOARD) DropTarget.CLIPBOARD else DropTarget.MOVE
                    val cargo = controller.items
                    controller.flight.snapTo(0f)
                    controller.flight.animateTo(1f, tween(SNAP_MILLIS, easing = ARRIVE))
                    controller.settle()
                    onFlightLanded(target, cargo)
                }
            }
        }

        // ── The cluster cards ─────────────────────────────────────────────────────────
        val flight = controller.flight.value
        val trashAnchor = Offset(trashCentre.first, trashCentre.second)
        val clipboardAnchor = restingBulgeAnchor(BulgeCorner.TOP_LEFT, overlaySize, density)
        val visible = controller.items.take(MAX_CARDS)
        val extra = controller.items.size - visible.size

        visible.forEachIndexed { index, item ->
            val spring = controller.springs[index]
            val from = controller.flightStart.getOrNull(index) ?: spring.position
            val position = when (controller.phase) {
                ClusterPhase.GENIE -> geniePoint(from, trashAnchor, flight, index)
                ClusterPhase.SNAP_CLIPBOARD, ClusterPhase.SNAP_MOVE ->
                    arcPoint(from, clipboardAnchor, staggered(flight, index))
                ClusterPhase.RETURNING -> {
                    val home = controller.origins[item.uri]?.minus(overlayOrigin) ?: from
                    lerp(from, home, staggered(flight, index))
                }
                else -> spring.position
            }

            // Squash, stretch and bank come off the live velocity while the drag is under the
            // finger; during a flight the tween owns the shape instead.
            val dragging = controller.phase == ClusterPhase.DRAGGING
            val (deformX, deformY) = if (dragging) ClusterMotion.deform(spring.velocity) else 1f to 1f
            val bank = if (dragging) ClusterMotion.bankDegrees(spring.velocity) else 0f

            val cardScaleX: Float
            val cardScaleY: Float
            val alpha: Float
            if (controller.phase == ClusterPhase.GENIE) {
                // The genie squeeze: waist narrows almost to nothing while the height first
                // bulges then collapses — enough of the lamp motion to read instantly.
                val reached = staggered(flight, index)
                cardScaleX = 1f - reached * 0.94f
                cardScaleY = (1f + 0.30f * sin(PI * reached).toFloat()) * (1f - reached * 0.85f)
                alpha = 1f - reached * reached
            } else if (controller.phase == ClusterPhase.SNAP_CLIPBOARD || controller.phase == ClusterPhase.SNAP_MOVE) {
                val reached = staggered(flight, index)
                cardScaleX = deformX * (1f - reached * 0.8f)
                cardScaleY = deformY * (1f - reached * 0.8f)
                alpha = 1f - reached * 0.6f
            } else if (controller.phase == ClusterPhase.RETURNING) {
                val reached = staggered(flight, index)
                cardScaleX = 1f - reached * 0.25f
                cardScaleY = 1f - reached * 0.25f
                alpha = 1f - reached * 0.85f
            } else {
                cardScaleX = deformX
                cardScaleY = deformY
                alpha = 1f
            }

            // How much of the carry treatment -- the swell and the lift -- is in effect. It
            // eases IN over the gather and back OUT over whatever flight ends the drag, so a
            // returning card shrinks to the row it came from and a snapping card arrives at the
            // point its flight actually aims at rather than 46dp above it.
            val carry = if (dragging) gather.value else 1f - staggered(flight, index)
            val grow = GRAB_SCALE + (1f - GRAB_SCALE) * carry

            ClusterCard(
                item = item,
                badge = if (index == 0 && extra > 0) "+$extra" else null,
                elevation = CARD_ELEVATION * carry,
                modifier = Modifier
                    .offset {
                        // THE LIFT MOVES THE DRAWING ONLY. Every drop decision -- the action arc
                        // above, the trash bulge, the tab strip's own targets -- hit-tests
                        // controller.dragPosition, the real finger point, and that is untouched.
                        // Raising the hit point with the picture would drop the cargo on whatever
                        // happened to sit a thumb's height above the thing you were pointing at.
                        val half = CARD_SIZE.toPx() / 2f
                        // Clamped against the top of the overlay: near the top edge there is
                        // nothing above the finger to lift into, and a stack half off-screen is
                        // worse than one under a thumb.
                        val lift = min(THUMB_LIFT.toPx() * carry, (position.y - half).coerceAtLeast(0f))
                        IntOffset(
                            (position.x - half).roundToInt(),
                            (position.y - half - lift).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        scaleX = cardScaleX * grow
                        scaleY = cardScaleY * grow
                        this.alpha = alpha
                        rotationZ = bank + (index - visible.size / 2f) * 3.5f
                    }
                    .zIndex((MAX_CARDS - index).toFloat()),
            )
        }
    }
}

@Composable
private fun ClusterCard(item: StagedItem, badge: String?, elevation: Dp, modifier: Modifier) {
    Box(modifier.size(CARD_SIZE), contentAlignment = Alignment.Center) {
        StackCard(
            entry = item.entry,
            fallbackName = item.displayName,
            kind = item.kind,
            size = CARD_SIZE,
            // Depth and an edge, both only here: the deck riffle and the tray bulges use this
            // same card lying FLAT on their own surface, and a card that is not in the air has
            // no business casting.
            //
            // The edge is not decoration. StackCard's face is surfaceBright and a listing is
            // surface -- one step apart in a light scheme -- so rendering the layer over a light
            // page showed a stack of four drawing exactly the same picture as a stack of one,
            // every card behind the leader hidden under its opaque white face. The shadow cannot
            // carry that on its own: the cards overlap, so the only shadow anyone sees is the
            // outermost card's.
            modifier = Modifier
                .shadow(elevation, CARD_SHAPE)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CARD_SHAPE),
        )
        Text(
            item.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // StackCard's face is a real thumbnail now, not a flat surface -- the label needs its
            // own scrim (the badge's own background treatment, just dark instead of tonal) to stay
            // legible over a bright or busy photo, not just sit bare on top of it.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
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

/**
 * The stacked fan: a tight trailing spread up and out, leader nearest the finger.
 *
 * It used to spread down-right, which put every card behind the leader straight back into the
 * occlusion [THUMB_LIFT] exists to escape -- the lift would have raised the top card clear and
 * left the count buried. Up-right keeps the whole stack countable at a glance.
 *
 * The step is deliberately a readable fraction of [CARD_SIZE] rather than the few dp it used to
 * be. At the old 7x9dp the leader's own opaque face covered every card behind it: five files and
 * one file drew the same picture.
 */
private fun fanOffset(index: Int, density: Density): Offset = with(density) {
    Offset(x = index * FAN_STEP_X.toPx(), y = -index * FAN_STEP_Y.toPx())
}

/**
 * Per-card flight progress: overlapping action, so the stack files in one card at a time
 * instead of landing as a single welded block.
 */
private fun staggered(progress: Float, index: Int): Float =
    (progress * (1f + index * 0.12f)).coerceIn(0f, 1f)

/** A curved dive into the can: quadratic arc with per-card stagger so the stack files in. */
private fun geniePoint(from: Offset, to: Offset, progress: Float, index: Int): Offset =
    arcPoint(from, to, staggered(progress, index), lift = 140f)

/**
 * Motion along an arc rather than a straight line — the seventh principle, and the difference
 * between a file being carried somewhere and a file being teleported there.
 */
private fun arcPoint(from: Offset, to: Offset, progress: Float, lift: Float = 90f): Offset {
    val control = Offset((from.x + to.x) / 2f, min(from.y, to.y) - lift)
    val inverse = 1f - progress
    return Offset(
        inverse * inverse * from.x + 2 * inverse * progress * control.x + progress * progress * to.x,
        inverse * inverse * from.y + 2 * inverse * progress * control.y + progress * progress * to.y,
    )
}

private fun lerp(from: Offset, to: Offset, fraction: Float): Offset =
    Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)

internal fun DropTarget.slotIcon() = when (this) {
    DropTarget.CLIPBOARD -> Icons.Outlined.ContentPaste
    DropTarget.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    DropTarget.SHELF -> Icons.Outlined.Inventory2
    DropTarget.NEW_FOLDER -> Icons.Outlined.CreateNewFolder
    DropTarget.COMPRESS -> Icons.Outlined.Archive
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

internal fun DropTarget.slotLabel() = when (this) {
    DropTarget.CLIPBOARD -> "Clipboard"
    DropTarget.MOVE -> "Move"
    DropTarget.SHELF -> "Shelf"
    DropTarget.NEW_FOLDER -> "New folder"
    DropTarget.COMPRESS -> "Compress"
    DropTarget.TRASH -> "Recycle"
    DropTarget.NONE -> ""
}

/**
 * The dragged card, and how far above the contact point the stack is DRAWN.
 *
 * A thumb covers far more than the point it reports: a 72dp card centred on that point was, in
 * practice, a card you could not see while you carried it. Two changes together fix that -- the
 * card in flight is bigger than any thumbnail it can have lifted off (40dp in list, 56dp in
 * grid), and the whole stack is drawn clear of the contact point instead of on top of it.
 *
 * [THUMB_LIFT] is a drawing offset and nothing more; see the offset modifier for why the hit
 * point stays exactly where the finger is.
 */
internal val CARD_SIZE = 96.dp
internal val THUMB_LIFT = 60.dp

/**
 * The shadow the stack casts once it is fully lifted, scaled by the lift itself.
 *
 * Not decoration. A dragged card is a near-white slab, and a listing is a near-white page: with
 * no shadow the cargo is invisible over exactly the surface you drag it across, and a stack of
 * four reads as one card because each card's opaque face hides the one behind it. Rendering the
 * layer over a light page is what made that obvious -- the same stack over the dark corner bulge
 * had always looked fine.
 */
private val CARD_ELEVATION = 13.dp

/** Kept in step with [StackCard]'s own shape by hand -- the shadow and edge have to sit on it. */
private val CARD_SHAPE = RoundedCornerShape(10.dp)

/** One card's step in the resting fan. See [fanOffset] for why it is this big and not smaller. */
internal val FAN_STEP_X = 11.dp
internal val FAN_STEP_Y = 10.dp

/**
 * The disc a thumb actually covers, measured across. Not a rendering constant -- nothing draws it
 * -- but the figure [CARD_SIZE] and [THUMB_LIFT] are answerable to, and the reason this file has
 * a test at all. A fingertip contact patch on a phone runs roughly 10-14mm; at 160dp to the inch
 * that is a bit over 48dp of digitiser, and the flesh around it covers half as much again.
 */
internal val ThumbOcclusionDp = 72.dp

/**
 * Card size at the instant of the grab, as a fraction of [CARD_SIZE] -- about 53dp, between the
 * list and grid thumbnails it lifts off. Snapping straight to carry size pops; growing into it
 * is the same object being picked up.
 */
private const val GRAB_SCALE = 0.55f

/** How long the stack takes to swell and rise out from under the thumb. */
private const val GATHER_MILLIS = 190

/** Radius of the action arc struck from the top-left corner. */
private val ARC_RADIUS = 112.dp

/** The blob behind that arc: the radius plus room for a glyph, and nothing more. */
private val ACTIONS_BULGE = 156.dp

/** The trash blob, and how far its can sits in from the corner. */
private val TRASH_BULGE = 132.dp
private val TRASH_INSET = 52.dp

/** House motion: the constellation's eased settle; flights never overshoot. */
private val SETTLE = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** Arrival with a touch of follow-through, for flights that land somewhere concrete. */
private val ARRIVE = CubicBezierEasing(0.2f, 0f, 0.1f, 1.08f)

private const val GENIE_MILLIS = 430
private const val SNAP_MILLIS = 340
private const val RETURN_MILLIS = 300
