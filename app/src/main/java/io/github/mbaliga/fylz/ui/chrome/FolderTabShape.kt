package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * How far the default cut eats into a tab's trailing edge. Sized off the tab's own proportions
 * rather than the export's absolute px, so a phone-sized tab and a tablet-sized one read as the
 * same silhouette instead of the same number of dp.
 */
val FolderTabSlant: Dp = 22.dp

/**
 * Clamps a requested slant into what a tab of [width] can actually cut. Past the width itself the
 * diagonal would have to fold back on itself -- the top and bottom edges would cross -- so the cut
 * gives way first rather than the polygon self-intersecting.
 */
fun folderTabSlant(requested: Float, width: Float): Float = requested.coerceIn(0f, width.coerceAtLeast(0f))

/**
 * One folder tab's silhouette: a rectangle with its trailing (right) edge cut on a diagonal
 * instead of square -- the read that makes overlapping tabs cascade like folder tabs in a binder
 * rather than stack like plain cards. The leading (left) edge is always square in both variants;
 * only one edge is ever slanted, which is the whole of what "mirrorable" means here.
 *
 * The physical reference is a tab that narrows toward the edge it pokes out from and stays full
 * width where it meets the folder body. Bottom-anchored here (the tab band sits on the plinth,
 * not above it), that means the un-mirrored cut shortens the TOP edge and leaves the BOTTOM edge
 * full width: the flat base sits flush against the plinth, the cut corner is what the eye reads.
 * The export's active tab is the same base shape flipped across its own horizontal centre
 * (`matrix(1,0,0,-1,0,0)` in the Figma export) rather than a different path -- [mirrored]
 * reproduces exactly that: the cut moves to the BOTTOM-right corner instead, full width along the
 * top.
 *
 * @param slant how far the diagonal eats into the tab's width from its trailing corner; clamped
 *   by [folderTabSlant] so a tab squeezed narrower than its slant still draws a closed,
 *   non-crossing outline instead of a folded one.
 * @param mirrored flips the cut from the top-right corner to the bottom-right corner -- the
 *   export's active-tab variant.
 */
class FolderTabShape(
    private val slant: Dp = FolderTabSlant,
    private val mirrored: Boolean = false,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val cut = folderTabSlant(with(density) { slant.toPx() }, w)
        val topX = folderTabTopEdgeX(w, cut, mirrored)
        val bottomX = folderTabBottomEdgeX(w, cut, mirrored)
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(topX, 0f)
            lineTo(bottomX, h)
            lineTo(0f, h)
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is FolderTabShape && other.slant == slant && other.mirrored == mirrored

    override fun hashCode(): Int = slant.hashCode() * 31 + mirrored.hashCode()
}

/**
 * The trailing edge's x-coordinate at the tab's top, already clamped through [folderTabSlant] so
 * it never drops below 0 or exceeds [width]. Un-mirrored, the top is where the cut bites; mirrored,
 * the top runs full width instead.
 */
fun folderTabTopEdgeX(width: Float, slant: Float, mirrored: Boolean): Float =
    if (mirrored) width else width - folderTabSlant(slant, width)

/** The trailing edge's x-coordinate at the tab's bottom -- the mirror image of [folderTabTopEdgeX]. */
fun folderTabBottomEdgeX(width: Float, slant: Float, mirrored: Boolean): Float =
    if (mirrored) width - folderTabSlant(slant, width) else width
