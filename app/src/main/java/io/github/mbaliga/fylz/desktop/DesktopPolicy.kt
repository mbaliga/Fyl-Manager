package io.github.mbaliga.fylz.desktop

import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.canvas.TilePlacement
import kotlin.math.abs

/**
 * Pure, JVM-testable geometry rules for the desktop -- no Compose, no Context, mirroring
 * [CanvasLayoutPolicy]'s own split for the freeform canvas.
 *
 * [clamp] and [raise] are not reimplemented here: the desktop shares the exact same safe band and
 * front-of-stack rule as the canvas, so both simply delegate. [snap] and [nextFreePlacement] are
 * new -- the desktop, unlike the freeform canvas, snaps every tile to a fixed grid rather than
 * letting it rest anywhere.
 */
object DesktopPolicy {

    /** How many items the desktop ever holds at once, mirrored by [DesktopStore]'s own cap. */
    const val MAX_ITEMS = 64

    /**
     * Widget card widths as fractions of the viewport width, and the vertical WORLD the desktop
     * scrolls over. Placement x/y are fractions of (viewport width x world height) -- the world is
     * never shorter than [WORLD_MIN_HEIGHT_DP], so the default layout below fits every phone
     * without overlap (card HEIGHTS are fixed dp; if y were a fraction of the raw viewport, the
     * same fractions would collide on a short screen and leave gaps on a tall one).
     */
    const val WIDGET_WIDTH_COMPACT = 0.46f
    const val WIDGET_WIDTH_FULL = 0.94f
    const val WORLD_MIN_HEIGHT_DP = 1600

    fun widgetWidthFraction(size: DesktopItemSize): Float = when (size) {
        DesktopItemSize.SMALL, DesktopItemSize.MEDIUM -> WIDGET_WIDTH_COMPACT
        DesktopItemSize.LARGE -> WIDGET_WIDTH_FULL
    }

    /**
     * [clamp] for a WIDGET card: the canvas safe band was tuned for 92dp shortcut tiles and would
     * shove a 46-94%-wide card so far right it clips off-screen. A widget instead keeps its whole
     * width on screen: x in [0.03, 0.97 - width] (full-width cards can only move vertically), y
     * anywhere in the scrollable world short of its very bottom edge.
     */
    fun clampWidget(p: TilePlacement, widthFraction: Float): TilePlacement {
        val maxX = (0.97f - widthFraction).coerceAtLeast(EDGE_GUTTER_X)
        return TilePlacement(
            x = p.x.coerceIn(EDGE_GUTTER_X, maxX),
            y = p.y.coerceIn(0.01f, 0.92f),
            z = p.z,
        )
    }

    /**
     * [snap] for a WIDGET card. The shortcut grid's cell centers run through the canvas clamp,
     * which shoves anything left of the canvas band to x~0.16 -- useless for cards that live in a
     * two-column layout. A compact widget snaps to the left/right column ([EDGE_GUTTER_X] / 0.51),
     * a full-width one only to the left gutter; rows quantize on the same 14-row grid as [snap].
     */
    fun snapWidget(p: TilePlacement, widthFraction: Float): TilePlacement {
        val columns =
            if (widthFraction >= WIDGET_WIDTH_FULL) floatArrayOf(EDGE_GUTTER_X)
            else floatArrayOf(EDGE_GUTTER_X, WIDGET_RIGHT_COLUMN_X)
        val x = columns.minByOrNull { abs(it - p.x) } ?: EDGE_GUTTER_X
        val y = cellCenter(cellIndex(p.y, GRID_ROWS), GRID_ROWS)
        return clampWidget(TilePlacement(x = x, y = y, z = p.z), widthFraction)
    }

    const val WIDGET_RIGHT_COLUMN_X = 0.51f

    private const val EDGE_GUTTER_X = 0.03f
    private const val GRID_COLUMNS = 8
    private const val GRID_ROWS = 14

    fun clamp(p: TilePlacement): TilePlacement = CanvasLayoutPolicy.clamp(p)

    fun raise(p: TilePlacement, maxZ: Int): TilePlacement = CanvasLayoutPolicy.raise(p, maxZ)

    /** Quantizes [p] to the nearest cell center of the 8-column x 14-row grid, over the unit
     *  viewport -- then pulls the result back into the safe band, the same as any other placement. */
    fun snap(p: TilePlacement): TilePlacement {
        val snappedX = cellCenter(cellIndex(p.x, GRID_COLUMNS), GRID_COLUMNS)
        val snappedY = cellCenter(cellIndex(p.y, GRID_ROWS), GRID_ROWS)
        return clamp(TilePlacement(x = snappedX, y = snappedY, z = p.z))
    }

    /**
     * The first empty grid cell for a freshly created item, scanned row-major from the top-left
     * corner of the safe band. "Empty" means every already-[existing] placement's own cell is more
     * than one cell step away in both directions -- i.e. outside that placement's immediate 3x3
     * neighborhood -- so two items never land edge-to-edge on the same widget's visual footprint.
     * Falls back to [CanvasLayoutPolicy.defaultPlacement]'s cascade on the vanishingly rare desktop
     * packed tightly enough that no such cell exists.
     */
    fun nextFreePlacement(existing: Collection<TilePlacement>): TilePlacement {
        val occupiedCells = existing.map { cellIndex(it.y, GRID_ROWS) to cellIndex(it.x, GRID_COLUMNS) }
        for (row in 0 until GRID_ROWS) {
            val y = cellCenter(row, GRID_ROWS)
            for (col in 0 until GRID_COLUMNS) {
                val x = cellCenter(col, GRID_COLUMNS)
                if (!insideSafeBand(x, y)) continue
                val tooClose = occupiedCells.any { (occRow, occCol) ->
                    abs(occRow - row) <= 1 && abs(occCol - col) <= 1
                }
                if (!tooClose) return TilePlacement(x = x, y = y, z = existing.size)
            }
        }
        return CanvasLayoutPolicy.defaultPlacement(existing.size, existing)
    }

    /**
     * The Build-10 landing overview, translated one card at a time into desktop widgets with
     * EXPLICIT hand-laid placements -- no randomness. [nextFreePlacement] cannot lay this out: it
     * spaces items one grid CELL apart, and these cards are 46-94% of the viewport wide, so
     * cell-spacing stacks them into one overlapping mass (caught by the Build-11 render pass).
     * The y fractions below are of the [WORLD_MIN_HEIGHT_DP]-floored scrollable world, computed
     * against the fixed card heights (360/216/152dp -- see DesktopTile's desktopWidgetSize), each
     * row leaving clear water beneath the one above:
     *
     *   y=0.015  Storage        (full width, 360dp)   ..~408dp
     *   y=0.265  Deleted files  (full width, 360dp)   ..~784dp
     *   y=0.505  Quick access | Recents   (216dp)     ..~1024dp
     *   y=0.665  Pinned       | Quick actions (216/152dp) ..~1280dp
     *   y=0.810  Tags         | Shelf     (152dp)     ..~1448dp
     */
    fun defaultSeed(): List<DesktopItem> {
        val leftX = EDGE_GUTTER_X
        val rightX = 0.51f
        val specs = listOf(
            SeedSpec("seed-storage", DesktopWidgetType.STORAGE, DesktopItemSize.LARGE, x = leftX, y = 0.015f),
            SeedSpec(
                "seed-quick-access",
                DesktopWidgetType.QUICK_ACCESS,
                DesktopItemSize.MEDIUM,
                config = mapOf("target" to "downloads"),
                x = leftX,
                y = 0.505f,
            ),
            SeedSpec("seed-recycle-bin", DesktopWidgetType.RECYCLE_BIN, DesktopItemSize.LARGE, x = leftX, y = 0.265f),
            SeedSpec("seed-pinned", DesktopWidgetType.PINNED, DesktopItemSize.MEDIUM, x = leftX, y = 0.665f),
            SeedSpec("seed-tags", DesktopWidgetType.TAGS, DesktopItemSize.SMALL, x = leftX, y = 0.810f),
            SeedSpec("seed-shelf", DesktopWidgetType.SHELF, DesktopItemSize.SMALL, x = rightX, y = 0.810f),
            SeedSpec("seed-recents", DesktopWidgetType.RECENTS, DesktopItemSize.MEDIUM, x = rightX, y = 0.505f),
            SeedSpec("seed-quick-actions", DesktopWidgetType.QUICK_ACTIONS, DesktopItemSize.SMALL, x = rightX, y = 0.665f),
        )
        return specs.mapIndexed { index, spec ->
            DesktopItem.Widget(
                id = spec.id,
                type = spec.type,
                size = spec.size,
                config = spec.config,
                placement = TilePlacement(x = spec.x, y = spec.y, z = index),
            )
        }
    }

    /** Whether ([x], [y]) is already inside the safe band -- i.e. [clamp] would leave it untouched.
     *  Checked this way, rather than duplicating [CanvasLayoutPolicy]'s private band constants, so
     *  the desktop grid can never quietly drift out of step with the canvas's own edge margins. */
    private fun insideSafeBand(x: Float, y: Float): Boolean {
        val clamped = clamp(TilePlacement(x = x, y = y, z = 0))
        return clamped.x == x && clamped.y == y
    }

    private fun cellIndex(value: Float, cells: Int): Int =
        (value.coerceIn(0f, 1f) * cells).toInt().coerceIn(0, cells - 1)

    private fun cellCenter(index: Int, cells: Int): Float = (index + 0.5f) / cells

    private data class SeedSpec(
        val id: String,
        val type: DesktopWidgetType,
        val size: DesktopItemSize,
        val config: Map<String, String> = emptyMap(),
        val x: Float,
        val y: Float,
    )
}
