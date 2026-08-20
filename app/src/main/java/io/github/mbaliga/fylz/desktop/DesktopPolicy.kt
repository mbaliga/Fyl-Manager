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
     * The Build-10 landing overview, translated one card at a time into desktop widgets and laid
     * out with [nextFreePlacement] itself -- no randomness, and every placement lands on a distinct
     * grid cell by construction, so nothing here can ever overlap under [snap].
     */
    fun defaultSeed(): List<DesktopItem> {
        val specs = listOf(
            SeedSpec("seed-storage", DesktopWidgetType.STORAGE, DesktopItemSize.LARGE),
            SeedSpec(
                "seed-quick-access",
                DesktopWidgetType.QUICK_ACCESS,
                DesktopItemSize.MEDIUM,
                mapOf("target" to "downloads"),
            ),
            SeedSpec("seed-recycle-bin", DesktopWidgetType.RECYCLE_BIN, DesktopItemSize.MEDIUM),
            SeedSpec("seed-pinned", DesktopWidgetType.PINNED, DesktopItemSize.MEDIUM),
            SeedSpec("seed-tags", DesktopWidgetType.TAGS, DesktopItemSize.SMALL),
            SeedSpec("seed-shelf", DesktopWidgetType.SHELF, DesktopItemSize.SMALL),
            SeedSpec("seed-recents", DesktopWidgetType.RECENTS, DesktopItemSize.MEDIUM),
            SeedSpec("seed-quick-actions", DesktopWidgetType.QUICK_ACTIONS, DesktopItemSize.SMALL),
        )
        val placements = mutableListOf<TilePlacement>()
        return specs.map { spec ->
            val placement = nextFreePlacement(placements)
            placements += placement
            DesktopItem.Widget(
                id = spec.id,
                type = spec.type,
                size = spec.size,
                config = spec.config,
                placement = placement,
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
    )
}
