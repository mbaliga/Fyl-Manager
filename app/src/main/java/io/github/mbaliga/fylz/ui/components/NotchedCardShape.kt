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

/** How wide and tall one action slot is. Pinned to the 48dp touch minimum, not to the mock. */
val QuickLookSlot: Dp = 48.dp

/** The rail never grows past this; beyond it the card is mostly chrome. */
const val QUICK_LOOK_MAX_SLOTS: Int = 5

/** Below two there is no rail worth cutting — one action plus "more" is the floor. */
const val QUICK_LOOK_MIN_SLOTS: Int = 2

/** Clamps a requested rail size into what the shape will actually draw. */
fun quickLookSlots(requested: Int): Int = requested.coerceIn(QUICK_LOOK_MIN_SLOTS, QUICK_LOOK_MAX_SLOTS)

/**
 * The preview card's silhouette: a rounded rectangle with a rectangular bite taken out of two
 * diagonally opposite corners — a long one at the top-left for the action rail, a single-slot one
 * at the bottom-right for close.
 *
 * The notches are **subtracted**, not drawn over. That matters because the card's content bleeds
 * to its edges: an icon strip painted on top of a plain rectangle would sit on the image, whereas
 * cutting the corner away means the image genuinely stops there and the actions sit in a shape the
 * card no longer occupies.
 *
 * Geometry is driven by [slotSize] rather than by proportion. The reference art puts a slot at
 * about a tenth of the card, which on a phone-sized card lands near 35dp — under the touch
 * minimum. Sizing from the slot instead keeps every action hittable and lets the rail widen by
 * exactly one slot per action, which is what makes a 3-action and a 5-action card look related
 * rather than merely similar.
 *
 * @param railSlots how many actions the top-left notch holds; clamped by [quickLookSlots].
 * @param innerRadius the rounding where a notch meets the content. Deliberately smaller than
 *   [cornerRadius]: an inner corner rounded as hard as the outer ones reads as a bite out of a
 *   blob rather than a cut into a card.
 */
class NotchedCardShape(
    private val railSlots: Int,
    private val slotSize: Dp = QuickLookSlot,
    private val cornerRadius: Dp = 28.dp,
    private val innerRadius: Dp = 12.dp,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        with(density) {
            val slot = slotSize.toPx()
            val outer = cornerRadius.toPx()
            val inner = innerRadius.toPx()
            val slots = quickLookSlots(railSlots)

            // A notch can never eat more than the card has; on a very narrow card the rail is
            // capped so the two notches cannot meet and split the silhouette in two.
            val railWidth = minOf(slot * slots, size.width - slot)
            val notchDepth = minOf(slot, size.height / 3f)

            val card = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(outer)))
            }
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
                            size.width - slot,
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
            other.slotSize == slotSize &&
            other.cornerRadius == cornerRadius &&
            other.innerRadius == innerRadius

    override fun hashCode(): Int =
        (((railSlots * 31 + slotSize.hashCode()) * 31) + cornerRadius.hashCode()) * 31 + innerRadius.hashCode()
}
