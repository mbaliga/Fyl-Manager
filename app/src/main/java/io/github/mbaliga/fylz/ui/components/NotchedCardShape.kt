package io.github.mbaliga.fylz.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/** How wide and tall one action slot is. Pinned to the 48dp touch minimum, not to the mock. */
val QuickLookSlot: Dp = 48.dp

/** The rail never grows past this; beyond it the card is mostly chrome. */
const val QUICK_LOOK_MAX_SLOTS: Int = 5

/** Below two there is no rail worth cutting — one action plus "more" is the floor. */
const val QUICK_LOOK_MIN_SLOTS: Int = 2

/** Clamps a requested rail size into what the shape will actually draw. */
fun quickLookSlots(requested: Int): Int = requested.coerceIn(QUICK_LOOK_MIN_SLOTS, QUICK_LOOK_MAX_SLOTS)

/** Below one there is no notch left to cut — the corner would have nothing to hold. */
const val QUICK_LOOK_CLOSE_MIN_SLOTS: Int = 1

/** Anchor, dock and close is as deep as the corner notch goes; past it it would rival the rail. */
const val QUICK_LOOK_CLOSE_MAX_SLOTS: Int = 3

/** Clamps a requested close-notch size into what the shape will actually draw. */
fun quickLookCloseSlots(requested: Int): Int = requested.coerceIn(QUICK_LOOK_CLOSE_MIN_SLOTS, QUICK_LOOK_CLOSE_MAX_SLOTS)

/**
 * How deep a notch cuts, as a share of one slot.
 *
 * A notch as deep as a whole slot is a STEP in the silhouette — it takes the slot's touch padding
 * out of the card as well as the glyphs. The notch is meant to be a shallow shelf the glyph strip
 * rests in, so it stops at the strip's own height and lets the rest of the touch target hang over
 * the card's edge, where it costs the silhouette nothing.
 */
const val QUICK_LOOK_STRIP_FRACTION: Float = 5f / 6f

/** The glyph strip's height: 40dp against the 48dp slot. Scaled with the slot on a miniature. */
val QuickLookActionStrip: Dp = QuickLookSlot * QUICK_LOOK_STRIP_FRACTION

/**
 * …and never deeper than this share of the card's own height, so a short card gets a shallower
 * shelf instead of losing a third of itself. The strip binds from ~222dp of card height upward;
 * below that this does.
 */
const val QUICK_LOOK_DEPTH_FRACTION: Float = 0.18f

/** The rail's bite may never take more than this much of the top edge… */
const val QUICK_LOOK_MAX_RAIL_FRACTION: Float = 0.45f

/** …and the close notch no more than this much of the bottom edge. */
const val QUICK_LOOK_MAX_CLOSE_FRACTION: Float = 0.30f

/**
 * The narrowest card that still cuts a notch: the width at which [QUICK_LOOK_MAX_RAIL_FRACTION]
 * of the top edge is exactly [QUICK_LOOK_MIN_SLOTS] slots wide. A hair narrower and the rail's
 * share no longer holds one action plus "more", which is the smallest rail that means anything.
 *
 * At the default 48dp slot that is 213dp — the ~220dp floor the design brief asked for, derived
 * from the caps rather than typed in beside them, so changing a cap moves the floor with it.
 */
fun quickLookMinNotchWidth(slotSize: Dp = QuickLookSlot): Dp =
    slotSize * QUICK_LOOK_MIN_SLOTS / QUICK_LOOK_MAX_RAIL_FRACTION

/**
 * The shortest card that still cuts a notch: the height at which [QUICK_LOOK_DEPTH_FRACTION] is
 * half a slot deep. A 22dp glyph in a shelf shallower than 24dp has no margin left at all, and a
 * shelf that shallow reads as a nick in the edge rather than a place anything sits.
 *
 * At the default 48dp slot that is 133dp — the ~140dp floor the brief asked for.
 */
fun quickLookMinNotchHeight(slotSize: Dp = QuickLookSlot): Dp =
    slotSize / 2f / QUICK_LOOK_DEPTH_FRACTION

/**
 * Whether a card this size gets notches at all.
 *
 * Below the floor the two bites eat so much of a small card that the silhouette stops reading as a
 * card — the docked 132×96 mini lost a third of its width *and* a third of its height to them —
 * so [NotchedCardShape] degrades to a plain rounded rectangle instead and whatever chrome would
 * have sat in the notch floats over the card.
 *
 * Exported (rather than an `if` inside [NotchedCardShape.createOutline]) precisely so a caller can
 * ask the question before it lays anything out: the card and its chrome then answer to one
 * predicate and cannot disagree about whether there is a cut to aim at.
 */
fun quickLookNotchesFit(width: Dp, height: Dp, slotSize: Dp = QuickLookSlot): Boolean =
    width >= quickLookMinNotchWidth(slotSize) && height >= quickLookMinNotchHeight(slotSize)

/**
 * How many whole slots fit in [budget].
 *
 * The epsilon is not slop: [quickLookMinNotchWidth] is defined as the width where the rail's share
 * comes to exactly [QUICK_LOOK_MIN_SLOTS] slots, and multiplying back out through two float
 * divisions can land a hair under it. Without this a card sitting exactly on its own documented
 * floor would round down to one fewer slot than the floor exists to guarantee.
 */
private fun slotsWithin(budget: Dp, slotSize: Dp, ceiling: Int): Int =
    floor(budget / slotSize + 1e-3f).toInt().coerceIn(0, ceiling)

/**
 * How many rail slots a card this wide actually cuts, out of [requested].
 *
 * When the requested rail does not fit, the answer is FEWER SLOTS — the actions that drop out
 * belong in the overflow — never a wider bite. That is what keeps the top edge's unbroken run
 * substantial no matter how many actions the user pins: a 5-slot rail on a phone-width card
 * draws 3 slots and sends the other two to "more", rather than spanning the whole top edge and
 * leaving a thin tab at the corner.
 */
fun quickLookRailSlotsFor(width: Dp, requested: Int, slotSize: Dp = QuickLookSlot): Int =
    slotsWithin(width * QUICK_LOOK_MAX_RAIL_FRACTION, slotSize, quickLookSlots(requested))

/** The same rule for the bottom-right notch, against its own smaller share of the edge. */
fun quickLookCloseSlotsFor(width: Dp, requested: Int, slotSize: Dp = QuickLookSlot): Int =
    slotsWithin(width * QUICK_LOOK_MAX_CLOSE_FRACTION, slotSize, quickLookCloseSlots(requested))

/** The bottom-right notch's drawn width on a card [width] across. */
fun quickLookCloseWidth(width: Dp, closeSlots: Int, slotSize: Dp = QuickLookSlot): Dp =
    slotSize * quickLookCloseSlotsFor(width, closeSlots, slotSize)

/**
 * The top-left notch's drawn width on a card [width] across, after the close notch has had its say.
 *
 * The margin rule: the rail is additionally capped so the two notches leave one bare slot of card
 * between them — without it their inner corners meet and the silhouette reads as a ring rather
 * than as two cuts — and the rail is the one that gives way, since it is the notch the caller
 * flexes. The two edge-share caps above already guarantee the margin at any size that passes
 * [quickLookNotchesFit] (0.45w + 0.30w + one slot ≤ w for every w ≥ 4 slots, and the width floor
 * is 4.44 slots), so in practice this clamp never bites; it is kept explicit so a future change to
 * either cap cannot quietly let the two cuts run together.
 */
fun quickLookRailWidth(width: Dp, railSlots: Int, closeSlots: Int, slotSize: Dp = QuickLookSlot): Dp {
    val ideal = slotSize * quickLookRailSlotsFor(width, railSlots, slotSize)
    val margin = width - quickLookCloseWidth(width, closeSlots, slotSize) - slotSize
    return minOf(ideal, margin).coerceAtLeast(0.dp)
}

/**
 * The preview card's silhouette: a rounded rectangle with a rectangular bite taken out of two
 * diagonally opposite corners — a long one at the top-left for the action rail, a shorter one at
 * the bottom-right for anchor/dock/close.
 *
 * The notches are **subtracted**, not drawn over. That matters because the card's content bleeds
 * to its edges: an icon strip painted on top of a plain rectangle would sit on the image, whereas
 * cutting the corner away means the image genuinely stops there and the actions sit in a shape the
 * card no longer occupies.
 *
 * ### What bounds the bite
 *
 * A notch is a shelf, not a step, and it is bounded on every axis by the card rather than by the
 * slot alone:
 * - **Depth** is the glyph strip's height ([QuickLookActionStrip], 40dp at the default slot), or
 *   [QUICK_LOOK_DEPTH_FRACTION] of the card's height on a card too short for that — never a whole
 *   48dp slot, which on anything under 144dp tall used to take a third of the card.
 * - **Width** is capped at [QUICK_LOOK_MAX_RAIL_FRACTION] of the top edge (and
 *   [QUICK_LOOK_MAX_CLOSE_FRACTION] of the bottom), quantised down to whole slots, so the top edge
 *   always keeps a substantial unbroken run. Actions that no longer fit reduce the slot count;
 *   they never widen the bite.
 * - **Existence** is decided by [quickLookNotchesFit]. Below that floor there are no notches and
 *   this is a plain rounded rectangle.
 *
 * Within those bounds the geometry is still driven by [slotSize] rather than by proportion: the
 * reference art puts a slot at about a tenth of the card, which on a phone-sized card lands near
 * 35dp — under the touch minimum. Sizing from the slot keeps every action hittable and lets either
 * notch widen by exactly one slot per action, which is what makes a 3-action and a 5-action card
 * look related rather than merely similar.
 *
 * @param railSlots how many actions the top-left notch holds; clamped by [quickLookSlots] and then
 *   by what the card's own width allows ([quickLookRailSlotsFor]).
 * @param closeSlots how many actions the bottom-right notch holds; clamped the same way.
 * @param innerRadius the rounding where a notch meets the content. Deliberately smaller than
 *   [cornerRadius]: an inner corner rounded as hard as the outer ones reads as a bite out of a
 *   blob rather than a cut into a card.
 */
class NotchedCardShape(
    private val railSlots: Int,
    private val closeSlots: Int = 1,
    private val slotSize: Dp = QuickLookSlot,
    private val cornerRadius: Dp = 28.dp,
    private val innerRadius: Dp = 12.dp,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        with(density) {
            val outer = cornerRadius.toPx()
            val rounded = RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(outer))

            val widthDp = size.width.toDp()
            val heightDp = size.height.toDp()
            if (!quickLookNotchesFit(widthDp, heightDp, slotSize)) return Outline.Rounded(rounded)

            val inner = innerRadius.toPx()
            val railWidth = quickLookRailWidth(widthDp, railSlots, closeSlots, slotSize).toPx()
            val closeWidth = quickLookCloseWidth(widthDp, closeSlots, slotSize).toPx()
            // The strip is read off the slot rather than off QuickLookActionStrip so a miniature
            // drawn at a smaller slot (the settings rail editor) gets a shelf in proportion to the
            // glyphs it is actually showing.
            val notchDepth = minOf(slotSize.toPx() * QUICK_LOOK_STRIP_FRACTION, size.height * QUICK_LOOK_DEPTH_FRACTION)

            val card = Path().apply { addRoundRect(rounded) }
            // Each notch overshoots the card's edges so the difference leaves a clean straight cut
            // rather than a hairline of the outer corner's arc.
            val bleed = outer + inner
            val rail = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(-bleed, -bleed, railWidth, notchDepth),
                        topLeft = CornerRadius.Zero,
                        topRight = CornerRadius.Zero,
                        bottomRight = CornerRadius(inner),
                        bottomLeft = CornerRadius.Zero,
                    ),
                )
            }
            val close = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(
                            size.width - closeWidth,
                            size.height - notchDepth,
                            size.width + bleed,
                            size.height + bleed,
                        ),
                        topLeft = CornerRadius(inner),
                        topRight = CornerRadius.Zero,
                        bottomRight = CornerRadius.Zero,
                        bottomLeft = CornerRadius.Zero,
                    ),
                )
            }

            val cut = Path().apply {
                op(card, rail, PathOperation.Difference)
                op(this, close, PathOperation.Difference)
            }
            return Outline.Generic(cut)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is NotchedCardShape &&
            other.railSlots == railSlots &&
            other.closeSlots == closeSlots &&
            other.slotSize == slotSize &&
            other.cornerRadius == cornerRadius &&
            other.innerRadius == innerRadius

    override fun hashCode(): Int =
        ((((railSlots * 31 + closeSlots) * 31 + slotSize.hashCode()) * 31) + cornerRadius.hashCode()) * 31 + innerRadius.hashCode()
}
