package io.github.mbaliga.fylz.widgets

import io.github.mbaliga.fylz.storage.STORAGE_KIND_ORDER
import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot

/**
 * One coloured run of [StorageWidgetProvider]'s segmented bar. [colorArgb] is a plain packed
 * ARGB int (`0xFFRRGGBB`), not an `android.graphics.Color` -- this file (and [WidgetBar.segments])
 * stays a pure function of [StorageUsageSnapshot] with no Android framework dependency, so
 * [WidgetBarTest] runs as a fast plain-JVM test, [DesktopPolicyTest]-modelled. Turning a segment
 * into pixels (a `Canvas`/`Paint`, real `android.graphics` types) happens only in
 * [StorageWidgetProvider], which is never unit tested directly -- headless widget rendering needs
 * a real device or Robolectric's graphics shadows, neither of which this pure layer should need.
 */
data class BarSegment(val kind: StorageKind, val colorArgb: Int, val weight: Float)

/**
 * One coloured run already placed on the bar's own pixel axis -- [startPx]..[endPx] is exactly
 * where [StorageWidgetProvider]'s renderer paints [colorArgb], with [WidgetBar.GAP_DP] worth of
 * bare track showing between one run's [endPx] and the next run's [startPx]. Density-scaled
 * pixels, not dp: the caller (a real [android.content.Context]) is the only thing that knows the
 * device's density, so [WidgetBar.layoutSegments] takes an already-converted pixel width in and
 * stays a pure function of primitives, no Android graphics dependency, [WidgetBar.segments]-
 * modelled.
 */
data class RenderedSegment(val kind: StorageKind, val colorArgb: Int, val startPx: Float, val endPx: Float)

object WidgetBar {
    /** The gap Build 11.5's fidelity spec calls for between adjacent segments, in dp -- the
     *  caller multiplies by the live device density to get [layoutSegments]'s `gapPx` input. */
    const val GAP_DP = 2

    /**
     * Reduces a snapshot to the ordered, non-empty runs its segmented bar draws.
     *
     * A `null` snapshot (never scanned) or one with zero bytes everywhere both yield an empty
     * list -- [StorageWidgetProvider] reads that as "draw nothing," not as a divide-by-zero risk.
     * A kind with zero bytes is omitted outright rather than emitted as a zero-width segment, and
     * order always follows [STORAGE_KIND_ORDER] -- never a `Map`'s own iteration order -- so the
     * bar and any future legend agree on which run is which, exactly as
     * [io.github.mbaliga.fylz.ui.overview.OverviewCards]'s own bar does.
     */
    fun segments(snapshot: StorageUsageSnapshot?): List<BarSegment> {
        if (snapshot == null) return emptyList()
        val total = STORAGE_KIND_ORDER.sumOf { snapshot.bytesFor(it) }
        if (total <= 0L) return emptyList()
        return STORAGE_KIND_ORDER.mapNotNull { kind ->
            val bytes = snapshot.bytesFor(kind)
            if (bytes <= 0L) return@mapNotNull null
            BarSegment(kind, colorFor(kind), bytes.toFloat() / total.toFloat())
        }
    }

    // Copied from ui/overview/OverviewCards.kt's private colorFor(StorageKind) -- that function
    // is Compose-typed (returns androidx.compose.ui.graphics.Color) and private to a screen this
    // package must not depend on just to keep a legend's colours in sync with a widget's, so the
    // six hex values are duplicated here as plain ints instead. If OverviewCards's palette ever
    // moves, this copy needs the same edit by hand.
    fun colorFor(kind: StorageKind): Int = when (kind) {
        StorageKind.PHOTOS -> 0xFF4F8EF7.toInt()
        StorageKind.DOCUMENTS -> 0xFFB0479A.toInt()
        StorageKind.VIDEOS -> 0xFFE0524B.toInt()
        StorageKind.SOUNDS -> 0xFF7C5CFC.toInt()
        StorageKind.ARCHIVES -> 0xFFE8A33D.toInt()
        StorageKind.OTHER -> 0xFF8A8F98.toInt()
    }

    /**
     * Places [segments] along a bar [trackWidthPx] wide, leaving [gapPx] of bare track between
     * each adjacent pair -- the fix for the old fixed-bitmap renderer's square-cut, butt-joined
     * segments. The gap budget is reserved out of [trackWidthPx] up front (`usableWidthPx`
     * below), never appended on top of it, so the whole run of segments-plus-gaps always fits
     * exactly inside [trackWidthPx] and the caller never has to re-clip afterwards.
     *
     * Width is tracked on two separate running totals: `widthBudgetUsed` (segment widths only,
     * gap-free -- what decides *how wide* each run is) and `pixelCursor` (the actual x position
     * on the bar, gaps included -- what decides *where* each run's [RenderedSegment.startPx] and
     * [RenderedSegment.endPx] land). Keeping them apart is what lets every segment but the last
     * get `weight * usableWidthPx` while the last absorbs whatever width remains -- the same
     * "last run eats the rounding error" rule the old fixed-bitmap renderer used -- without a
     * trailing gap's width ever leaking into that budget and shorting the last segment.
     *
     * An empty [segments] list or a non-positive [trackWidthPx] yields an empty list; a single
     * segment needs no gap and fills the entire track.
     */
    fun layoutSegments(segments: List<BarSegment>, trackWidthPx: Float, gapPx: Float): List<RenderedSegment> {
        if (segments.isEmpty() || trackWidthPx <= 0f) return emptyList()
        val safeGapPx = gapPx.coerceAtLeast(0f)
        val totalGapPx = safeGapPx * (segments.size - 1)
        val usableWidthPx = (trackWidthPx - totalGapPx).coerceAtLeast(0f)

        var widthBudgetUsed = 0f
        var pixelCursor = 0f
        return segments.mapIndexed { index, segment ->
            val remainingWidthBudget = usableWidthPx - widthBudgetUsed
            val width = if (index == segments.lastIndex) {
                remainingWidthBudget
            } else {
                (segment.weight * usableWidthPx).coerceIn(0f, remainingWidthBudget)
            }
            widthBudgetUsed += width
            val start = pixelCursor
            val end = start + width
            pixelCursor = end + safeGapPx
            RenderedSegment(segment.kind, segment.colorArgb, start, end)
        }
    }

    /** Rounded-outer-ends radius for a bar [trackHeightPx] tall -- half the height, so the bar
     *  reads as a full pill at both its own ends regardless of how wide it is placed. */
    fun outerRadiusPx(trackHeightPx: Float): Float = trackHeightPx / 2f
}
