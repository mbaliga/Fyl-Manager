package io.github.mbaliga.fylz.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/**
 * Pushes fresh [RemoteViews] to every placed instance of every Fylz widget.
 *
 * The one entry point other workstreams call after something a widget shows might have changed
 * -- a storage scan finishing, a folder being renamed, the Shelf or Trash changing. Each provider
 * exposes its own `companion buildViews(context, appWidgetId)`, the exact function its own
 * `onUpdate` calls, so this and a fresh placement always render identically; refreshing a family
 * with zero placed widgets is a normal, cheap no-op, not a caller's problem to avoid.
 */
object WidgetRefresher {
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        refresh(context, manager, StorageWidgetProvider::class.java, StorageWidgetProvider::buildViews)
        refresh(context, manager, FolderShortcutWidgetProvider::class.java, FolderShortcutWidgetProvider::buildViews)
        refresh(context, manager, QuickActionsWidgetProvider::class.java, QuickActionsWidgetProvider::buildViews)
    }

    private fun refresh(
        context: Context,
        manager: AppWidgetManager,
        providerClass: Class<*>,
        buildViews: (Context, Int) -> RemoteViews,
    ) {
        val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
        if (ids.isEmpty()) return
        ids.forEach { id -> manager.updateAppWidget(id, buildViews(context, id)) }
    }
}
