package io.github.mbaliga.fylz.browse

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.DensityMode

/**
 * The S/M/L icon-size axis. [DensityMode.COMFORTABLE] is today's shipped size in every render
 * branch -- scale 1 -- so adding density is additive: nothing changes until a user picks S or L.
 *
 * The named sizes below are every hardcoded row/card/thumb size the browser's GRID, LIST and
 * DETAILS branches use, re-derived from that COMFORTABLE baseline times [scale]. One table, not
 * three branches each carrying its own literals, is what lets "how dense is dense" be answered
 * once instead of hunted down per branch.
 */
object Density {
    fun scale(mode: DensityMode): Float = when (mode) {
        DensityMode.COMPACT -> 0.8f
        DensityMode.COMFORTABLE -> 1.0f
        DensityMode.DETAILED -> 1.25f
    }

    // COMFORTABLE-baseline literals the render branches ship today -- GRID: FileCard.height and
    // the `Adaptive` min cell width; LIST and DETAILS: row height. Thumb sizes follow each row.
    private const val GRID_CARD_HEIGHT_DP = 164
    private const val GRID_MIN_CELL_WIDTH_DP = 130
    private const val GRID_THUMB_DP = 56
    private const val LIST_ROW_HEIGHT_DP = 62
    private const val LIST_THUMB_DP = 40
    private const val DETAILS_ROW_HEIGHT_DP = 44
    private const val DETAILS_THUMB_DP = 24

    fun gridCardHeight(mode: DensityMode): Dp = GRID_CARD_HEIGHT_DP.dp * scale(mode)
    fun gridMinCellWidth(mode: DensityMode): Dp = GRID_MIN_CELL_WIDTH_DP.dp * scale(mode)
    fun gridThumb(mode: DensityMode): Dp = GRID_THUMB_DP.dp * scale(mode)

    fun listRowHeight(mode: DensityMode): Dp = LIST_ROW_HEIGHT_DP.dp * scale(mode)
    fun listThumb(mode: DensityMode): Dp = LIST_THUMB_DP.dp * scale(mode)

    fun detailsRowHeight(mode: DensityMode): Dp = DETAILS_ROW_HEIGHT_DP.dp * scale(mode)
    fun detailsThumb(mode: DensityMode): Dp = DETAILS_THUMB_DP.dp * scale(mode)
}
