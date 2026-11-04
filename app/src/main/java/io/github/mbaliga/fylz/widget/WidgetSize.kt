package io.github.mbaliga.fylz.widget

/**
 * The four layouts every Fylz home-screen widget ships, smallest first.
 *
 * Each widget hands the launcher one `RemoteViews` per entry, keyed by [widthDp] x [heightDp]
 * (`RemoteViews(Map<SizeF, RemoteViews>)`, API 31+, which is Fylz's minSdk). The launcher's own
 * `AppWidgetHostView` then picks, per placement and per resize, the layout whose ideal size fits
 * the cell area and is nearest to it, falling back to the smallest one. The app is not woken to
 * make that choice.
 *
 * The sizes follow the classic launcher-cell formula (70 x cells - 30 dp): 2x1, 2x2, 4x2, 4x3.
 * Because the launcher picks a layout as soon as the widget reaches its ideal size, each layout's
 * content must fit that exact size; WidgetFitTest budgets every widget against these numbers.
 */
enum class WidgetSize(val widthDp: Float, val heightDp: Float) {
    /** About 2x1: one line, one tap target. Below a template's minimum this is the whole widget. */
    COMPACT(110f, 40f),

    /** About 2x2: a title, one value and its caption. */
    SMALL(110f, 110f),

    /** About 4x2: a header, two one-line rows and an "as of" footer. One tap target. */
    MEDIUM(250f, 110f),

    /** About 4x3 and taller: a header, a headline, up to two 48dp rows that can be targets, and a footer. */
    LARGE(250f, 180f),
}
