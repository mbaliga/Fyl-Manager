package io.github.mbaliga.fylz.staging

import kotlin.math.hypot

/** What the finger is currently over, or near, while dragging a cluster. */
enum class DropTarget { NONE, CLIPBOARD, MOVE, NEW_FOLDER, COMPRESS, TRASH }

/**
 * One icon slot's live reaction to the approaching cluster.
 *
 * @param target which action this slot is.
 * @param proximity 0 at [DropTargetPolicy.REACT_RADIUS] px away (or further), 1 with the finger
 *   on the slot centre — the single scalar every micro-animation derives from (trash tilt,
 *   lift and lid angle; clipboard swell), so approach and retreat are continuous and symmetric.
 * @param hit true when releasing here drops onto this slot.
 */
data class TargetReaction(
    val target: DropTarget,
    val proximity: Float,
    val hit: Boolean,
)

/**
 * Pure geometry between the dragged cluster and the corner bulges.
 *
 * The layout convention lives here, not in the composables: **actions bulge in the top-left
 * corner** (clipboard, move, new folder, compress), **trash bulge alone in the bottom-right**.
 * Destructive and non-destructive targets sit in opposite corners of the screen so a sloppy
 * drop can miss within a family but never across one — a file meant for the clipboard cannot
 * land in the trash by a few misjudged pixels.
 *
 * All coordinates are px in the drag layer's own space; nothing here reads a density or a
 * composition local, which is what keeps every threshold and slot-centre testable on the JVM.
 */
object DropTargetPolicy {

    /** Distance at which a slot starts reacting to the approaching cluster, px. */
    const val REACT_RADIUS = 340f

    /** Distance within which release counts as a drop on the slot, px. */
    const val HIT_RADIUS = 132f

    /** The actions bulge's slots, in reading order from the corner outward. */
    val actionSlots: List<DropTarget> =
        listOf(DropTarget.CLIPBOARD, DropTarget.MOVE, DropTarget.NEW_FOLDER, DropTarget.COMPRESS)

    /**
     * Slot centres for the actions bulge, hugging the top-left corner along a shallow arc.
     * Returned in [actionSlots] order.
     */
    fun actionSlotCentres(spacingPx: Float, insetPx: Float): List<Pair<Float, Float>> =
        actionSlots.mapIndexed { index, _ ->
            val along = insetPx + spacingPx * index
            // A gentle arc: slots march right while sagging slightly, tracing the bulge's lip.
            along to (insetPx + spacingPx * 0.42f + index * spacingPx * 0.16f)
        }

    /** The trash slot centre, tucked into the bottom-right corner. */
    fun trashCentre(widthPx: Float, heightPx: Float, insetPx: Float): Pair<Float, Float> =
        (widthPx - insetPx) to (heightPx - insetPx)

    /**
     * Reaction of one slot at [centreX],[centreY] to a finger at [dragX],[dragY].
     *
     * The radii default to this policy's px constants; the UI passes density-scaled values so
     * a reach that feels right on one screen feels the same on another.
     */
    fun reactionFor(
        target: DropTarget,
        centreX: Float,
        centreY: Float,
        dragX: Float,
        dragY: Float,
        reactRadius: Float = REACT_RADIUS,
        hitRadius: Float = HIT_RADIUS,
    ): TargetReaction {
        val distance = hypot(dragX - centreX, dragY - centreY)
        val proximity = (1f - distance / reactRadius).coerceIn(0f, 1f)
        return TargetReaction(target, proximity, hit = distance <= hitRadius)
    }

    /**
     * The drop for a release at [dragX],[dragY], given every slot's reaction: the nearest hit
     * slot wins; no hit means the cluster springs home and nothing happens. Nearest-wins keeps
     * two adjacent action slots unambiguous even where their hit circles overlap.
     */
    fun dropFor(reactions: List<TargetReaction>): DropTarget =
        reactions.filter { it.hit }.maxByOrNull { it.proximity }?.target ?: DropTarget.NONE
}
