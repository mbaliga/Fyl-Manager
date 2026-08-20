package io.github.mbaliga.fylz.desktop

import io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy
import io.github.mbaliga.fylz.canvas.TilePlacement
import kotlin.math.abs
import kotlin.math.round

/**
 * Pure, JVM-testable geometry rules for the desktop -- no Compose, no Context, mirroring
 * [CanvasLayoutPolicy]'s own split for the freeform canvas.
 *
 * [clamp] and [raise] are not reimplemented here: the desktop shares the exact same safe band and
 * front-of-stack rule as the canvas, so both simply delegate. [snap] and [nextFreePlacement] are
 * new -- the desktop, unlike the freeform canvas, snaps every tile to a fixed grid rather than
 * letting it rest anywhere.
 *
 * WIDGET geometry ([clampWidget]/[snapWidget]/[widgetWidthFraction]) is deliberately NOT built the
 * same way [CanvasLayoutPolicy] builds its own safe band: that band is a fraction baked once
 * against a 360x800dp baseline phone, which is exactly right for 92dp shortcut chips (their own
 * footprint barely varies) but silently drifts for a widget card sized as a fraction of the real
 * viewport -- the Build-11 bug that pushed a 92dp-tall card 28dp off the bottom of a 411dp-wide
 * screen. Every widget function below instead takes the LIVE viewport width (and, for vertical
 * placement, the live world height) in dp and re-derives the two-column grid from those real
 * numbers on every call, so [OUTER_MARGIN_DP]/[COLUMN_GUTTER_DP] hold to their stated dp value on
 * any screen rather than approximating it through one baseline's fraction.
 */
object DesktopPolicy {

    /** How many items the desktop ever holds at once, mirrored by [DesktopStore]'s own cap. */
    const val MAX_ITEMS = 64

    // ── The two-column widget grid, in real dp ──────────────────────────────────────────

    /** Left/right screen margin and the gap between the two columns -- both fixed dp, not
     *  fractions; see this object's own KDoc for why. */
    const val OUTER_MARGIN_DP = 16f
    const val COLUMN_GUTTER_DP = 16f

    /** A drag's y settles to the nearest multiple of this many dp within the scrollable world. */
    const val VERTICAL_QUANTUM_DP = 8f

    /** Top clearance above the first card, and clearance kept below the last one -- together with
     *  [defaultSeed]'s own hand-laid content these fix [WORLD_MIN_HEIGHT_DP] (see that constant's
     *  own KDoc): no extra dead scroll baked in past what the seeded cards actually need. */
    const val TOP_MARGIN_DP = 16f
    const val BOTTOM_MARGIN_DP = 24f

    /**
     * [defaultSeed] runs once, at first launch, with no live viewport to measure -- there is
     * nothing to measure against yet. This is a representative phone width for that one-shot
     * layout only; every placement it produces is re-validated against [clampWidget]/[snapWidget]
     * at the DEVICE'S OWN width the instant it renders (every subsequent drag runs through the
     * exact same two functions), so a seeded placement is never more than one reflow away from the
     * real grid on any actual screen width.
     */
    const val REFERENCE_VIEWPORT_WIDTH_DP = 400f

    /**
     * The vertical WORLD the desktop scrolls over, floored so [defaultSeed]'s own hand-laid
     * eight-widget column layout always fits without the surface needing to grow taller once it
     * renders. Derived, not guessed: the seed's lowest card ([DesktopWidgetType.TAGS] on the left)
     * bottoms out at 1288dp (see [defaultSeed]'s own KDoc for the column math), plus
     * [BOTTOM_MARGIN_DP] of clearance below it -- 1288 + 24 = 1312. Nothing past that is dead
     * scroll the way the old fixed 1600dp (plus a further 260dp added at the render site) used to
     * leave once real card heights replaced the old blanket 152/216/360 table.
     */
    const val WORLD_MIN_HEIGHT_DP = 1312

    /**
     * A widget card's width as a fraction of [viewportWidthDp] -- SMALL/MEDIUM share one compact
     * column width (half the viewport, less the outer margins and the column gutter), LARGE takes
     * the full width less the outer margins. Re-derived from the live viewport on every call --
     * see this object's own KDoc.
     */
    fun widgetWidthFraction(size: DesktopItemSize, viewportWidthDp: Float): Float = when (size) {
        DesktopItemSize.SMALL, DesktopItemSize.MEDIUM -> compactWidthDp(viewportWidthDp) / viewportWidthDp
        DesktopItemSize.LARGE -> fullWidthDp(viewportWidthDp) / viewportWidthDp
    }

    /** Where the left/right column starts, as a fraction of [viewportWidthDp] -- exposed (not
     *  private) so both [defaultSeed] and a test can land exactly on the grid without duplicating
     *  the arithmetic. */
    fun leftColumnX(viewportWidthDp: Float): Float = OUTER_MARGIN_DP / viewportWidthDp

    fun rightColumnX(viewportWidthDp: Float): Float =
        (OUTER_MARGIN_DP + compactWidthDp(viewportWidthDp) + COLUMN_GUTTER_DP) / viewportWidthDp

    private fun compactWidthDp(viewportWidthDp: Float): Float =
        ((viewportWidthDp - 2 * OUTER_MARGIN_DP - COLUMN_GUTTER_DP) / 2f).coerceAtLeast(0f)

    private fun fullWidthDp(viewportWidthDp: Float): Float =
        (viewportWidthDp - 2 * OUTER_MARGIN_DP).coerceAtLeast(0f)

    /**
     * [clamp] for a WIDGET card, against the REAL live [viewportWidthDp]/[worldHeightDp] -- a
     * widget keeps its whole width on screen (x from the left margin to `1 - margin - width`;
     * full-width cards can only move vertically) and stays within [TOP_MARGIN_DP]/[BOTTOM_MARGIN_DP]
     * of the world's own top/bottom edge.
     */
    fun clampWidget(p: TilePlacement, size: DesktopItemSize, viewportWidthDp: Float, worldHeightDp: Float): TilePlacement {
        val marginX = OUTER_MARGIN_DP / viewportWidthDp
        val width = widgetWidthFraction(size, viewportWidthDp)
        val maxX = (1f - marginX - width).coerceAtLeast(marginX)
        val marginYTop = TOP_MARGIN_DP / worldHeightDp
        val marginYBottom = BOTTOM_MARGIN_DP / worldHeightDp
        val maxY = (1f - marginYBottom).coerceAtLeast(marginYTop)
        return TilePlacement(x = p.x.coerceIn(marginX, maxX), y = p.y.coerceIn(marginYTop, maxY), z = p.z)
    }

    /**
     * [snap] for a WIDGET card: x snaps to whichever column start is closest (a full-width card
     * only ever has the left column to snap to), y quantizes to the nearest [VERTICAL_QUANTUM_DP]
     * step of the real [worldHeightDp] -- then both pass through [clampWidget] so a snap can never
     * itself land off-screen.
     */
    fun snapWidget(p: TilePlacement, size: DesktopItemSize, viewportWidthDp: Float, worldHeightDp: Float): TilePlacement {
        val left = leftColumnX(viewportWidthDp)
        val columns = if (size == DesktopItemSize.LARGE) floatArrayOf(left) else floatArrayOf(left, rightColumnX(viewportWidthDp))
        val x = columns.minByOrNull { abs(it - p.x) } ?: left
        val quantum = VERTICAL_QUANTUM_DP / worldHeightDp
        val y = round(p.y.coerceIn(0f, 1f) / quantum) * quantum
        return clampWidget(TilePlacement(x = x, y = y, z = p.z), size, viewportWidthDp, worldHeightDp)
    }

    private const val GRID_COLUMNS = 8
    private const val GRID_ROWS = 14

    fun clamp(p: TilePlacement): TilePlacement = CanvasLayoutPolicy.clamp(p)

    fun raise(p: TilePlacement, maxZ: Int): TilePlacement = CanvasLayoutPolicy.raise(p, maxZ)

    /** Quantizes [p] to the nearest cell center of the 8-column x 14-row grid, over the unit
     *  viewport -- then pulls the result back into the safe band, the same as any other placement.
     *  Shortcut tiles only -- see this object's own KDoc for why widgets use [snapWidget] instead. */
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
     * spaces items one grid CELL apart, and these cards are much wider than one cell, so
     * cell-spacing stacks them into one overlapping mass (caught by the Build-11 render pass).
     *
     * A simple two-column masonry, in dp against [REFERENCE_VIEWPORT_WIDTH_DP] / [WORLD_MIN_HEIGHT_DP],
     * using each widget's own content-fit [io.github.mbaliga.fylz.ui.desktop.WidgetRegistry.height]
     * -- a full-width card resets BOTH columns to its own bottom edge, a compact card only advances
     * its own column, and every gap is exactly [COLUMN_GUTTER_DP]:
     *
     *   y=16    Storage        (full,  440dp) ..456
     *   y=472   Deleted files  (full,  144dp) ..616     (456 + 16dp gutter)
     *   y=632   Quick access   (left,  224dp) ..856      | y=632   Recents        (right, 264dp) ..896
     *   y=872   Pinned         (left,  288dp) ..1160     | y=912   Quick actions  (right, 128dp) ..1040
     *   y=1176  Tags           (left,  112dp) ..1288     | y=1056  Shelf          (right, 216dp) ..1272
     *
     * The left and right columns get their own `y=` label on rows 2/3 above precisely because they
     * diverge once each column accumulates its own running total -- only row 1 (both columns
     * resuming together right after "Deleted files") genuinely shares one y. Storage's own 440dp
     * (not the old 400dp) is [io.github.mbaliga.fylz.ui.desktop.WidgetRegistry]'s own worst-case
     * content-fit height for a fully-scanned device (all six [io.github.mbaliga.fylz.storage.StorageKind]
     * legend rows plus a two-line disclaimer); every y below it in the left column shifts down by
     * that same 40dp so the 16dp gutters stay exactly 16dp instead of silently shrinking into an
     * overlap.
     *
     * Every one of those y/y+height pairs is a multiple of [VERTICAL_QUANTUM_DP] (8dp), so the
     * whole seed already sits exactly on the grid [snapWidget] itself quantizes to -- landing here
     * is not a coincidence to be re-verified by eye, [DesktopPolicyTest] pins it.
     */
    fun defaultSeed(viewportWidthDp: Float = REFERENCE_VIEWPORT_WIDTH_DP): List<DesktopItem> {
        val leftX = leftColumnX(viewportWidthDp)
        val rightX = rightColumnX(viewportWidthDp)
        val world = WORLD_MIN_HEIGHT_DP.toFloat()
        fun y(topDp: Float) = topDp / world
        val specs = listOf(
            SeedSpec("seed-storage", DesktopWidgetType.STORAGE, DesktopItemSize.LARGE, x = leftX, y = y(16f)),
            SeedSpec(
                "seed-quick-access",
                DesktopWidgetType.QUICK_ACCESS,
                DesktopItemSize.MEDIUM,
                config = mapOf("target" to "downloads"),
                x = leftX,
                y = y(632f),
            ),
            SeedSpec("seed-recycle-bin", DesktopWidgetType.RECYCLE_BIN, DesktopItemSize.LARGE, x = leftX, y = y(472f)),
            SeedSpec("seed-pinned", DesktopWidgetType.PINNED, DesktopItemSize.MEDIUM, x = leftX, y = y(872f)),
            SeedSpec("seed-tags", DesktopWidgetType.TAGS, DesktopItemSize.SMALL, x = leftX, y = y(1176f)),
            SeedSpec("seed-shelf", DesktopWidgetType.SHELF, DesktopItemSize.SMALL, x = rightX, y = y(1056f)),
            SeedSpec("seed-recents", DesktopWidgetType.RECENTS, DesktopItemSize.MEDIUM, x = rightX, y = y(632f)),
            SeedSpec("seed-quick-actions", DesktopWidgetType.QUICK_ACTIONS, DesktopItemSize.SMALL, x = rightX, y = y(912f)),
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
