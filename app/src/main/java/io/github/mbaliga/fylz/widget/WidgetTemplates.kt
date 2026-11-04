package io.github.mbaliga.fylz.widget

import android.app.PendingIntent
import android.view.View
import android.widget.RemoteViews
import io.github.mbaliga.fylz.R

/** One row of a list template: an optional glyph, a title, a trailing value, a second line and a bar. */
internal data class WidgetRowSpec(
    val title: CharSequence,
    val glyph: CharSequence? = null,
    val trailing: CharSequence? = null,
    /** Second line; drawn by the LARGE template only. */
    val meta: CharSequence? = null,
    val progressPermille: Int? = null,
    /**
     * A destination more specific than the widget's own. Only the LARGE template attaches it, because
     * only its rows are 48dp touch targets; a MEDIUM row is too short to be a target of its own, so
     * there the whole widget is the one target.
     */
    val click: PendingIntent? = null,
    /** What TalkBack reads for a row that is a tap target. Begins with what the tap opens. */
    val description: CharSequence? = null,
)

/** The single button the LARGE template can carry, beside its footer. */
internal data class WidgetActionSpec(
    val label: CharSequence,
    val description: CharSequence,
    val click: PendingIntent,
)

/** Everything the SMALL, MEDIUM and LARGE panel templates can draw; each draws what fits. */
internal data class WidgetPanelSpec(
    val title: CharSequence,
    val click: PendingIntent,
    val description: CharSequence,
    val trailing: CharSequence? = null,
    val glyph: CharSequence? = null,
    val headline: CharSequence? = null,
    val caption: CharSequence? = null,
    val progressPermille: Int? = null,
    val rows: List<WidgetRowSpec> = emptyList(),
    val action: WidgetActionSpec? = null,
    val footer: CharSequence? = null,
)

/** The COMPACT (about 2x1) template: one line and an optional bar, one tap target. */
internal data class WidgetCompactSpec(
    val text: CharSequence,
    val click: PendingIntent,
    val description: CharSequence,
    val glyph: CharSequence? = null,
    val folderIcon: Boolean = false,
    val progressPermille: Int? = null,
)

/** One pad of the `pads` template. */
internal data class WidgetPadSpec(
    val label: CharSequence,
    val click: PendingIntent,
    val description: CharSequence,
)

/**
 * Fills Fylz's widget template layouts (`res/layout/widget_*.xml`).
 *
 * Widgets never draw their own layouts: each one maps its model to these specs and the templates
 * draw them, so every Fylz widget follows the same size, touch and text rules, and the templates
 * can later move to a shared kit unchanged. Only views that exist in a layout are touched; a
 * RemoteViews action on a missing view would turn the whole widget into the launcher's error tile.
 *
 * The panel layouts pin their header and footer and put everything else in a weighted body that
 * clips at its own bottom: when text is scaled up a row is cut, never the footer with its "as of"
 * time and "and N more". What each size holds is budgeted against its [WidgetSize] in WidgetFitTest.
 */
internal object WidgetTemplates {

    private class RowIds(
        val container: Int,
        val glyph: Int,
        val title: Int,
        val trailing: Int,
        val meta: Int,
        val bar: Int,
    )

    /** Both list layouts hold exactly these two rows ([LIST_ROWS]). */
    private val ROWS = listOf(
        RowIds(R.id.widget_row_1, R.id.widget_row_1_glyph, R.id.widget_row_1_title, R.id.widget_row_1_trailing, R.id.widget_row_1_meta, R.id.widget_row_1_bar),
        RowIds(R.id.widget_row_2, R.id.widget_row_2_glyph, R.id.widget_row_2_title, R.id.widget_row_2_trailing, R.id.widget_row_2_meta, R.id.widget_row_2_bar),
    )

    /** Rows the MEDIUM and LARGE list layouts hold; a renderer never asks for more. */
    const val LIST_ROWS = 2

    private class PadIds(val container: Int, val label: Int)

    private val PADS = listOf(
        PadIds(R.id.widget_pad_1, R.id.widget_pad_1_label),
        PadIds(R.id.widget_pad_2, R.id.widget_pad_2_label),
        PadIds(R.id.widget_pad_3, R.id.widget_pad_3_label),
        PadIds(R.id.widget_pad_4, R.id.widget_pad_4_label),
        PadIds(R.id.widget_pad_5, R.id.widget_pad_5_label),
        PadIds(R.id.widget_pad_6, R.id.widget_pad_6_label),
    )

    fun compact(packageName: String, spec: WidgetCompactSpec): RemoteViews =
        RemoteViews(packageName, R.layout.widget_compact).apply {
            root(spec.click, spec.description)
            setViewVisibility(R.id.widget_compact_icon, if (spec.folderIcon) View.VISIBLE else View.GONE)
            optionalText(R.id.widget_compact_glyph, spec.glyph)
            setTextViewText(R.id.widget_compact_text, spec.text)
            bar(R.id.widget_compact_bar, spec.progressPermille)
        }

    /** SMALL: title, glyph + headline, caption, bar, footer. Rows and action are not drawn. */
    fun stat(packageName: String, spec: WidgetPanelSpec): RemoteViews =
        RemoteViews(packageName, R.layout.widget_stat).apply {
            root(spec.click, spec.description)
            setTextViewText(R.id.widget_title, spec.title)
            optionalText(R.id.widget_headline_glyph, spec.glyph)
            setTextViewText(R.id.widget_headline, spec.headline ?: "")
            optionalText(R.id.widget_caption, spec.caption)
            bar(R.id.widget_bar, spec.progressPermille)
            optionalText(R.id.widget_footer, spec.footer)
        }

    /**
     * MEDIUM: header (title + trailing), up to two one-line rows, footer. Rows are never tap
     * targets of their own here (see [WidgetRowSpec.click]).
     */
    fun listMedium(packageName: String, spec: WidgetPanelSpec): RemoteViews =
        RemoteViews(packageName, R.layout.widget_list_medium).apply {
            root(spec.click, spec.description)
            setTextViewText(R.id.widget_title, spec.title)
            optionalText(R.id.widget_trailing, spec.trailing)
            rows(spec.rows, withMeta = false, tappable = false)
            optionalText(R.id.widget_footer, spec.footer)
        }

    /** LARGE: header, optional headline/caption/bar, up to two two-line 48dp rows, action, footer. */
    fun listLarge(packageName: String, spec: WidgetPanelSpec): RemoteViews =
        RemoteViews(packageName, R.layout.widget_list_large).apply {
            root(spec.click, spec.description)
            setTextViewText(R.id.widget_title, spec.title)
            optionalText(R.id.widget_trailing, spec.trailing)
            if (spec.headline == null) {
                setViewVisibility(R.id.widget_headline_row, View.GONE)
            } else {
                setViewVisibility(R.id.widget_headline_row, View.VISIBLE)
                optionalText(R.id.widget_headline_glyph, spec.glyph)
                setTextViewText(R.id.widget_headline, spec.headline)
            }
            optionalText(R.id.widget_caption, spec.caption)
            bar(R.id.widget_bar, spec.progressPermille)
            rows(spec.rows, withMeta = true, tappable = true)
            val action = spec.action
            if (action == null) {
                setViewVisibility(R.id.widget_action, View.GONE)
            } else {
                setViewVisibility(R.id.widget_action, View.VISIBLE)
                setTextViewText(R.id.widget_action, action.label)
                setContentDescription(R.id.widget_action, action.description)
                setOnClickPendingIntent(R.id.widget_action, action.click)
            }
            optionalText(R.id.widget_footer, spec.footer)
        }

    /**
     * The `pads` template at SMALL (2 pads), MEDIUM (4) or LARGE (6). Pads beyond [pads] stay
     * invisible so the grid keeps its shape. COMPACT is drawn by [compact].
     */
    fun pads(packageName: String, size: WidgetSize, pads: List<WidgetPadSpec>, click: PendingIntent, description: CharSequence): RemoteViews {
        val (layout, capacity) = when (size) {
            WidgetSize.COMPACT, WidgetSize.SMALL -> R.layout.widget_pads_small to 2
            WidgetSize.MEDIUM -> R.layout.widget_pads_medium to 4
            WidgetSize.LARGE -> R.layout.widget_pads_large to 6
        }
        return RemoteViews(packageName, layout).apply {
            root(click, description)
            PADS.take(capacity).forEachIndexed { index, ids ->
                val pad = pads.getOrNull(index)
                if (pad == null) {
                    setViewVisibility(ids.container, View.INVISIBLE)
                    return@forEachIndexed
                }
                setViewVisibility(ids.container, View.VISIBLE)
                setTextViewText(ids.label, pad.label)
                setOnClickPendingIntent(ids.container, pad.click)
                setContentDescription(ids.container, pad.description)
            }
        }
    }

    private fun RemoteViews.root(click: PendingIntent, description: CharSequence) {
        setOnClickPendingIntent(android.R.id.background, click)
        setContentDescription(android.R.id.background, description)
    }

    private fun RemoteViews.optionalText(id: Int, value: CharSequence?) {
        if (value.isNullOrEmpty()) {
            setViewVisibility(id, View.GONE)
        } else {
            setViewVisibility(id, View.VISIBLE)
            setTextViewText(id, value)
        }
    }

    private fun RemoteViews.bar(id: Int, permille: Int?) {
        if (permille == null) {
            setViewVisibility(id, View.GONE)
        } else {
            setViewVisibility(id, View.VISIBLE)
            setProgressBar(id, 1000, permille.coerceIn(0, 1000), false)
        }
    }

    private fun RemoteViews.rows(rows: List<WidgetRowSpec>, withMeta: Boolean, tappable: Boolean) {
        ROWS.forEachIndexed { index, ids ->
            val row = rows.getOrNull(index)
            if (row == null) {
                setViewVisibility(ids.container, View.GONE)
                return@forEachIndexed
            }
            setViewVisibility(ids.container, View.VISIBLE)
            optionalText(ids.glyph, row.glyph)
            setTextViewText(ids.title, row.title)
            optionalText(ids.trailing, row.trailing)
            if (withMeta) optionalText(ids.meta, row.meta)
            bar(ids.bar, row.progressPermille)
            val click = row.click
            if (tappable && click != null) {
                setOnClickPendingIntent(ids.container, click)
                row.description?.let { setContentDescription(ids.container, it) }
            }
        }
    }
}
