package io.github.mbaliga.fylz.staging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

    /** The actions bulge's slots, in reading order around the arc from the corner. */
    val actionSlots: List<DropTarget> =
        listOf(DropTarget.CLIPBOARD, DropTarget.MOVE, DropTarget.NEW_FOLDER, DropTarget.COMPRESS)

    /** Where the first and last slot sit on the quarter arc, in degrees off the top edge. */
    private const val ARC_START_DEGREES = 12f
    private const val ARC_END_DEGREES = 78f

    /**
     * Slot centres for the actions bulge: four points on a quarter arc struck from the
     * top-left corner, returned in [actionSlots] order.
     *
     * An arc rather than the old marching row, because the row was the reason the bulge had to
     * be as wide as four slot spacings plus an inset — over 400dp, most of the top of a phone —
     * to hold slots that were still drifting out of the blob it was supposed to be drawn on.
     * On an arc every slot sits the same distance from the corner, so the blob only has to be
     * as big as [radiusPx] plus a glyph, and the silhouette it needs is the quarter-round one
     * it was already drawing.
     */
    fun actionSlotCentres(radiusPx: Float): List<Pair<Float, Float>> {
        val last = (actionSlots.size - 1).coerceAtLeast(1)
        return actionSlots.mapIndexed { index, _ ->
            val sweep = ARC_START_DEGREES +
                (ARC_END_DEGREES - ARC_START_DEGREES) * (index.toFloat() / last)
            val radians = sweep * PI.toFloat() / 180f
            // Measured off the top edge, so slot 0 sits near the top and the last near the side.
            (radiusPx * cos(radians)) to (radiusPx * sin(radians))
        }
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
