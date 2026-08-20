package io.github.mbaliga.fylz.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.RemoteViews
import io.github.mbaliga.fylz.MainActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.storage.StorageUsageStore
import java.text.DateFormat
import java.util.Date

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
                views.setImageViewBitmap(R.id.widget_storage_bar, renderBar(emptyList()))
                views.setTextViewText(
                    R.id.widget_storage_status,
                    context.getString(R.string.widget_storage_no_scan),
                )
            } else {
                views.setImageViewBitmap(R.id.widget_storage_bar, renderBar(WidgetBar.segments(snapshot)))
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
         * Renders [segments] into a small, bounded bitmap -- never scaled to the actual widget
         * size on screen (RemoteViews stretches an `ImageView`'s `scaleType="fitXY"` bitmap to
         * fit, the same trick a launch icon badge or a notification's large icon uses), so this
         * never has to know the placed widget's real pixel dimensions. An empty segment list
         * (never scanned, or a scan that found nothing) draws a flat neutral bar rather than
         * nothing, so the widget never shows a blank rectangle that reads as a rendering bug.
         */
        private fun renderBar(segments: List<BarSegment>): Bitmap {
            val bitmap = Bitmap.createBitmap(BAR_WIDTH_PX, BAR_HEIGHT_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (segments.isEmpty()) {
                canvas.drawColor(EMPTY_BAR_COLOR)
                return bitmap
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var x = 0f
            segments.forEachIndexed { index, segment ->
                val remaining = BAR_WIDTH_PX - x
                val width = if (index == segments.lastIndex) remaining else segment.weight * BAR_WIDTH_PX
                paint.color = segment.colorArgb
                canvas.drawRect(x, 0f, (x + width).coerceAtMost(BAR_WIDTH_PX.toFloat()), BAR_HEIGHT_PX.toFloat(), paint)
                x += width
            }
            return bitmap
        }

        private const val BAR_WIDTH_PX = 600
        private const val BAR_HEIGHT_PX = 24
        private val EMPTY_BAR_COLOR = Color.parseColor("#33808080")
    }
}
