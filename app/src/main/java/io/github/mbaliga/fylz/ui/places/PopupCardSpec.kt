package io.github.mbaliga.fylz.ui.places

import kotlin.math.cos
import kotlin.math.sin

/**
 * The quick-preview popup, measured off the reference image (a 1004 px square): a square card
 * whose picture fills it edge to edge, a thin black frame, and two black tabs notched into opposite
 * corners — three actions in the top-left tab, a close in the bottom-right one.
 *
 * Every number is in the reference's own 1004-unit space and is scaled by the card's side, so the
 * popup keeps its proportions at any size. The shape was rebuilt from these numbers and diffed
 * against the reference (outer shape IoU 0.9998, picture region 0.997; the remainder is the
 * glyphs drawn on the tabs), so changing one is a design change, not a tidy-up.
 *
 * Pure Kotlin so the outline can be asserted without a device.
 */
object PopupCardSpec {

    /** The reference's side; all other numbers are fractions of it. */
    const val SIDE = 1004f

    /** Corner radius of the card's outer edge (the black frame). */
    const val R_OUT = 22f

    /** Width of the black frame around the picture. */
    const val FRAME = 2f

    /** Corner radius of the picture where it meets a tab. */
    const val R_PICTURE = 22f

    /** Corner radius of a tab's own rounded corner, the one that points into the picture. */
    const val R_TAB = 24f

    /** The top-left tab: [TOP_TAB_W] wide, [TOP_TAB_H] tall, from the corner. */
    const val TOP_TAB_W = 303f
    const val TOP_TAB_H = 103f

    /** The bottom-right tab: its top-left corner. It runs to the card's far corner. */
    const val BOTTOM_TAB_X = 907f
    const val BOTTOM_TAB_Y = 906f

    /** The three actions sit in equal cells of this width, starting at the card's left edge. */
    const val ACTION_CELL_W = 96f
    const val ACTION_COUNT = 3
    const val ACTION_CENTER_Y = 52f

    /** Visual size of an action glyph: the reference's digits are 46 units tall. */
    const val ACTION_GLYPH = 46f

    /** The close mark: a 41-unit square, strokes about 7 units, centred in the bottom tab. */
    const val CLOSE_CENTER_X = 953f
    const val CLOSE_CENTER_Y = 953f
    const val CLOSE_GLYPH = 41f
    const val CLOSE_STROKE = 7f

    /** Centre of action [index] (0-based) in reference units. */
    fun actionCenterX(index: Int): Float = ACTION_CELL_W * index + ACTION_CELL_W / 2f

    /** One piece of the picture's outline. Angles are degrees, clockwise from 3 o'clock, y down. */
    sealed interface Seg {
        data class Move(val x: Float, val y: Float) : Seg
        data class Line(val x: Float, val y: Float) : Seg
        data class Arc(val cx: Float, val cy: Float, val r: Float, val startDeg: Float, val sweepDeg: Float) : Seg {
            val startX: Float get() = cx + r * cos(Math.toRadians(startDeg.toDouble())).toFloat()
            val startY: Float get() = cy + r * sin(Math.toRadians(startDeg.toDouble())).toFloat()
            val endX: Float get() = cx + r * cos(Math.toRadians((startDeg + sweepDeg).toDouble())).toFloat()
            val endY: Float get() = cy + r * sin(Math.toRadians((startDeg + sweepDeg).toDouble())).toFloat()
        }
    }

    /**
     * The picture's outline in reference units, clockwise from where the top edge leaves the top-left
     * tab. It rounds each corner (r = [R_PICTURE]) and goes around each tab, whose own corner (r =
     * [R_TAB]) bulges into the picture.
     */
    fun outline(): List<Seg> {
        val w = SIDE
        val b = FRAME
        val rp = R_PICTURE
        val rt = R_TAB
        return listOf(
            Seg.Move(TOP_TAB_W + rp, b),
            Seg.Line(w - b - rp, b),
            Seg.Arc(w - b - rp, b + rp, rp, -90f, 90f),                       // top-right corner
            Seg.Line(w - b, BOTTOM_TAB_Y - rp),
            Seg.Arc(w - b - rp, BOTTOM_TAB_Y - rp, rp, 0f, 90f),              // picture corner above the bottom tab
            Seg.Line(BOTTOM_TAB_X + rt, BOTTOM_TAB_Y),
            Seg.Arc(BOTTOM_TAB_X + rt, BOTTOM_TAB_Y + rt, rt, -90f, -90f),    // the bottom tab's rounded corner
            Seg.Line(BOTTOM_TAB_X, w - b - rp),
            Seg.Arc(BOTTOM_TAB_X - rp, w - b - rp, rp, 0f, 90f),              // picture corner left of the bottom tab
            Seg.Line(b + rp, w - b),
            Seg.Arc(b + rp, w - b - rp, rp, 90f, 90f),                        // bottom-left corner
            Seg.Line(b, TOP_TAB_H + rp),
            Seg.Arc(b + rp, TOP_TAB_H + rp, rp, 180f, 90f),                   // picture corner below the top tab
            Seg.Line(TOP_TAB_W - rt, TOP_TAB_H),
            Seg.Arc(TOP_TAB_W - rt, TOP_TAB_H - rt, rt, 90f, -90f),           // the top tab's rounded corner
            Seg.Line(TOP_TAB_W, b + rp),
            Seg.Arc(TOP_TAB_W + rp, b + rp, rp, 180f, 90f),                   // picture corner right of the top tab
        )
    }
}
