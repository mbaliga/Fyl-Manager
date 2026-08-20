package io.github.mbaliga.fylz.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.mbaliga.fylz.MainActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.intents.FylzIntents

/**
 * 2x1 home screen widget: four one-tap actions -- Scan, Search, Shelf, Trash -- each its own
 * [PendingIntent] straight to [MainActivity] carrying the matching [FylzIntents] action. No
 * config, no persisted state of its own; every tap target is derivable from nothing but the
 * button that was pressed.
 */
class QuickActionsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, buildViews(context, id)) }
    }

    override fun onEnabled(context: Context) = WidgetRefreshWorker.reconcile(context)

    override fun onDisabled(context: Context) = WidgetRefreshWorker.cancelIfNoWidgetsRemain(context)

    companion object {
        // Distinct per button AND per appWidgetId -- FLAG_UPDATE_CURRENT reuses a PendingIntent
        // whose (requestCode, action, data, categories) all match an existing one, so two
        // buttons sharing a requestCode would collapse onto whichever built its PendingIntent
        // last, and two placed instances of this widget would collide with each other's Scan
        // button the same way.
        private const val REQUEST_SCAN = 0
        private const val REQUEST_SEARCH = 1
        private const val REQUEST_SHELF = 2
        private const val REQUEST_TRASH = 3
        private const val REQUESTS_PER_WIDGET = 4

        fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_actions)
            views.setOnClickPendingIntent(
                R.id.widget_action_scan,
                actionPendingIntent(context, appWidgetId, REQUEST_SCAN, FylzIntents.ACTION_SCAN),
            )
            views.setOnClickPendingIntent(
                R.id.widget_action_search,
                actionPendingIntent(context, appWidgetId, REQUEST_SEARCH, FylzIntents.ACTION_SEARCH),
            )
            views.setOnClickPendingIntent(
                R.id.widget_action_shelf,
                actionPendingIntent(context, appWidgetId, REQUEST_SHELF, FylzIntents.ACTION_OPEN_SHELF),
            )
            views.setOnClickPendingIntent(
                R.id.widget_action_trash,
                actionPendingIntent(context, appWidgetId, REQUEST_TRASH, FylzIntents.ACTION_OPEN_TRASH),
            )
            return views
        }

        private fun actionPendingIntent(
            context: Context,
            appWidgetId: Int,
            requestSlot: Int,
            action: String,
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setAction(action)
            return PendingIntent.getActivity(
                context,
                appWidgetId * REQUESTS_PER_WIDGET + requestSlot,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
