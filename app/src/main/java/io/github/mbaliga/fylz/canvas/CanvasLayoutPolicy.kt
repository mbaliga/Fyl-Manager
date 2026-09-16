package io.github.mbaliga.fylz.canvas

import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry

/**
 * Pure, JVM-testable geometry rules for the freeform canvas and the bento grid -- no Compose, no
 * Context, so the cascade, the clamp and the span rule are all pinnable without Robolectric.
 */
object CanvasLayoutPolicy {

    /** How many tiles the canvas ever places on screen at once, however many a folder holds. */
    const val MAX_TILES = 48

    // Fraction analogs of SpatialShell's 56dp edge bands and its 96dp bottom band. TilePlacement
    // is stored as a fraction of whatever viewport is live, so a pure function has no density to
    // convert an actual dp figure with -- these margins are that conversion done once, against a
    // 360x800dp baseline phone viewport, which is what "avoiding the edge bands" can mean without
    // a live measurement.
    private const val BASELINE_WIDTH_DP = 360f
    private const val BASELINE_HEIGHT_DP = 800f
    private const val EDGE_BAND_DP = 56f
    private const val BOTTOM_BAND_DP = 96f

    private val MIN_X = EDGE_BAND_DP / BASELINE_WIDTH_DP
    private val MAX_X = 1f - MIN_X
    private val MIN_Y = EDGE_BAND_DP / BASELINE_HEIGHT_DP
    private val MAX_Y = 1f - BOTTOM_BAND_DP / BASELINE_HEIGHT_DP

    private const val CASCADE_STEPS = 9
    private const val STEP_START_X = 0.10f
    private const val STEP_START_Y = 0.08f
    private const val STEP_DX = 0.09f
    private const val STEP_DY = 0.075f
    private const val JITTER_SPAN = 0.035f

    // 0x9E3779B1 as a 32-bit signed Int -- Knuth's multiplicative hash constant, a cheap, stable
    // spread for a small integer index.
    private const val HASH_MULTIPLIER = -1640531535

    /** Clamps a placement's fractions into the safe band; [TilePlacement.z] passes through untouched. */
    fun clamp(p: TilePlacement): TilePlacement =
        TilePlacement(x = p.x.coerceIn(MIN_X, MAX_X), y = p.y.coerceIn(MIN_Y, MAX_Y), z = p.z)

    /** Brings [p] to the front of [maxZ], the highest z currently on screen. */
    fun raise(p: TilePlacement, maxZ: Int): TilePlacement = p.copy(z = maxZ + 1)

    /**
     * Where a tile that has never been dragged lands: a loose diagonal cascade, jittered by a
     * hash of [index] so a screenful of fresh tiles reads as scattered rather than gridded, and
     * wrapping every [CASCADE_STEPS] tiles rather than running off the bottom-right once a folder
     * holds more than a few dozen unplaced entries.
     *
     * Position is pure in [index] alone -- [existing] never steers x/y, so a tile the user has
     * not touched never shifts just because some *other* tile got dragged elsewhere. It only
     * advances which cascade step this tile starts from, so a location whose early steps are
     * already thick with explicit placements pushes its still-unplaced tail further down the
     * cascade instead of stacking fresh arrivals on the same crowded corner.
     */
    fun defaultPlacement(index: Int, existing: Collection<TilePlacement> = emptyList()): TilePlacement {
        val hash = index * HASH_MULTIPLIER
        val jitterX = unitFraction(hash) * JITTER_SPAN - JITTER_SPAN / 2f
        val jitterY = unitFraction(hash ushr 12) * JITTER_SPAN - JITTER_SPAN / 2f
        val step = (index + existing.size) % CASCADE_STEPS
        val x = MIN_X + STEP_START_X + step * STEP_DX + jitterX
        val y = MIN_Y + STEP_START_Y + step * STEP_DY + jitterY
        return clamp(TilePlacement(x = x, y = y, z = index))
    }

    /** Directories first (their own already-sorted order), then files by most recent, stable. */
    fun visibleSubset(entries: List<FileEntry>, cap: Int = MAX_TILES): List<FileEntry> {
        val directories = entries.filter { it.isDirectory }
        val files = entries.filterNot { it.isDirectory }
            .sortedByDescending { it.lastModifiedMillis ?: Long.MIN_VALUE }
        return (directories + files).take(cap)
    }

    /**
     * The bento grid's per-entry column span (2 = a 2x2 cell, 1 = 1x1): every directory, plus the
     * two most recently modified media files, get the large treatment; everything else stays
     * small. Aligned index-for-index with [entries] -- ties in "most recent" break by [entries]'
     * own order, same stability contract as [visibleSubset].
     */
    fun bentoSpans(entries: List<FileEntry>): List<Int> {
        val topMedia = entries.withIndex()
            .filter { (_, entry) -> !entry.isDirectory && entry.kind.isMedia() }
            .sortedByDescending { it.value.lastModifiedMillis ?: Long.MIN_VALUE }
            .take(2)
            .mapTo(HashSet()) { it.index }
        return entries.indices.map { index -> if (entries[index].isDirectory || index in topMedia) 2 else 1 }
    }

    private fun EntryKind.isMedia(): Boolean = this == EntryKind.IMAGE || this == EntryKind.VIDEO

    /** The low byte of [value] as a [0,1) fraction. */
    private fun unitFraction(value: Int): Float = (value and 0xFF) / 256f
}
