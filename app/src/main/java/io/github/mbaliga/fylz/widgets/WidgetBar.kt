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

object WidgetBar {
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
}
