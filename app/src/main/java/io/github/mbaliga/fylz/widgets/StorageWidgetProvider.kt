package io.github.mbaliga.fylz.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.RemoteViews
import io.github.mbaliga.fylz.MainActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.storage.StorageUsageStore
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * 2x2 home screen widget showing the Storage card's segmented bar.
 *
 * Reads [StorageUsageStore] ONLY -- no scan is ever kicked off from here. A widget's `onUpdate`
 * runs on the main thread with a short execution budget and no guarantee the process even has a
 * foreground UI to show progress in, so it must be headless-safe: read whatever the last
 * completed scan already wrote, render it, and if there has never been one, say so honestly
 * rather than showing a stale zero or a spinner nothing ever resolves.
 */
class StorageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, buildViews(context, id)) }
    }

    override fun onEnabled(context: Context) = WidgetRefreshWorker.reconcile(context)

    override fun onDisabled(context: Context) = WidgetRefreshWorker.cancelIfNoWidgetsRemain(context)

    companion object {
        private const val REQUEST_CODE_OPEN = 9_301

        /** Shared by [onUpdate], [WidgetRefresher] and a fresh placement -- the one path that
         *  decides what this widget looks like, so all three always agree. */
        fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_storage)
            views.setTextViewText(R.id.widget_storage_title, context.getString(R.string.widget_storage_title))

            val snapshot = StorageUsageStore(context).snapshot()
            if (snapshot == null) {
                views.setImageViewBitmap(R.id.widget_storage_bar, renderBar(context, appWidgetId, emptyList()))
                views.setTextViewText(
                    R.id.widget_storage_status,
                    context.getString(R.string.widget_storage_no_scan),
                )
            } else {
                views.setImageViewBitmap(
                    R.id.widget_storage_bar,
                    renderBar(context, appWidgetId, WidgetBar.segments(snapshot)),
                )
                val asOf = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(snapshot.scannedAtMillis))
                views.setTextViewText(
                    R.id.widget_storage_status,
                    context.getString(R.string.widget_storage_as_of, asOf),
                )
            }

            val openIntent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_storage_root, pendingIntent)
            return views
        }

        /**
         * Renders [segments] into a bitmap sized to *this placement's* own content width, at the
         * device's real density -- Build 11.5's fix for the old fixed 600x24 raw-pixel raster,
         * which ignored density entirely and relied on the `ImageView`'s `fitXY` to stretch a
         * blurry, square-cut bar up to whatever size the host actually gave it.
         * [targetWidthPx] reads the host's own placed width back via
         * [AppWidgetManager.getAppWidgetOptions] rather than guessing, so the bitmap is painted
         * close to its true on-screen size instead of relying on `fitXY` to hide a resolution
         * mismatch.
         *
         * The whole track is clipped to a [WidgetBar.outerRadiusPx] round rect -- the "rounded
         * outer ends" the fidelity spec calls for, a pill shape regardless of width -- and the
         * neutral [R.color.widget_bar_track] fill is painted FIRST, across the entire track, so
         * [WidgetBar.GAP_DP]-wide gaps between segments (and the whole bar, when [segments] is
         * empty -- never scanned, or a scan that found nothing) read as an honest empty track
         * rather than a blank rectangle that looks like a rendering bug.
         */
        private fun renderBar(context: Context, appWidgetId: Int, segments: List<BarSegment>): Bitmap {
            val density = context.resources.displayMetrics.density
            val widthPx = targetWidthPx(context, appWidgetId, density)
            val heightPx = (BAR_HEIGHT_DP * density).roundToInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val radiusPx = WidgetBar.outerRadiusPx(heightPx.toFloat())
            canvas.clipPath(
                Path().apply {
                    addRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), radiusPx, radiusPx, Path.Direction.CW)
                },
            )

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = context.getColor(R.color.widget_bar_track)
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)

            val gapPx = WidgetBar.GAP_DP * density
            WidgetBar.layoutSegments(segments, widthPx.toFloat(), gapPx).forEach { run ->
                paint.color = run.colorArgb
                canvas.drawRect(run.startPx, 0f, run.endPx, heightPx.toFloat(), paint)
            }
            return bitmap
        }

        /**
         * The [R.id.widget_storage_bar] `ImageView`'s real content width for this placement, in
         * density-scaled pixels: the host's own reported placed width
         * ([AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH]), minus [WIDGET_INSET_DP] on both sides
         * -- widget_storage.xml's own root padding, its half of the shared "Build 11.5 inset
         * rule" that layout documents. Falls back to [FALLBACK_CELL_WIDTH_DP]
         * (widget_storage_info.xml's own declared `minWidth`) before the host has populated real
         * options yet, so the very first render is never a 0px bitmap.
         */
        private fun targetWidthPx(context: Context, appWidgetId: Int, density: Float): Int {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            val cellWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                .takeIf { it > 0 } ?: FALLBACK_CELL_WIDTH_DP
            val contentWidthDp = (cellWidthDp - 2 * WIDGET_INSET_DP).coerceAtLeast(MIN_BAR_WIDTH_DP)
            return (contentWidthDp * density).roundToInt().coerceAtLeast(1)
        }

        private const val BAR_HEIGHT_DP = 20
        private const val WIDGET_INSET_DP = 14
        private const val FALLBACK_CELL_WIDTH_DP = 110
        private const val MIN_BAR_WIDTH_DP = 48
    }
}
