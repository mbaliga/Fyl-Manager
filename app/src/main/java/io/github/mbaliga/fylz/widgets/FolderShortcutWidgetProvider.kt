package io.github.mbaliga.fylz.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.RemoteViews
import io.github.mbaliga.fylz.MainActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.intents.FylzIntents

/**
 * 1x1 home screen widget: one folder shortcut, its icon and label. Unconfigured until
 * [FolderWidgetConfigActivity] (launched automatically by the platform via
 * `APPWIDGET_CONFIGURE`, and again on tap if a config write was somehow lost) writes this
 * widget's [FolderWidgetConfig] into [PREFERENCES_NAME].
 *
 * Config lives in its own preferences file, keyed by `appWidgetId` -- not
 * [io.github.mbaliga.fylz.desktop.DesktopStore] or any other store this workstream does not own
 * -- because a home screen widget instance and a desktop tile are different objects with
 * different lifecycles (a widget's `appWidgetId` is minted and retired by the platform, not by
 * this app).
 */
class FolderShortcutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, buildViews(context, id)) }
    }

    /** The platform never re-delivers a removed widget's config write anywhere else -- clean up
     *  its three keys now, or they sit in [PREFERENCES_NAME] forever under an appWidgetId no
     *  widget will ever reuse. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = preferences(context).edit()
        appWidgetIds.forEach { id ->
            editor.remove(key(id, FIELD_TREE_URI))
            editor.remove(key(id, FIELD_FOLDER_URI))
            editor.remove(key(id, FIELD_LABEL))
        }
        check(editor.commit()) { "Unable to clean up folder widget config." }
    }

    override fun onEnabled(context: Context) = WidgetRefreshWorker.reconcile(context)

    override fun onDisabled(context: Context) = WidgetRefreshWorker.cancelIfNoWidgetsRemain(context)

    companion object {
        private const val PREFERENCES_NAME = "fylz_widget_folders"
        private const val FIELD_TREE_URI = "treeUri"
        private const val FIELD_FOLDER_URI = "folderUri"
        private const val FIELD_LABEL = "label"

        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        private fun key(appWidgetId: Int, field: String) = "$appWidgetId:$field"

        /** Written by [FolderWidgetConfigActivity] once the user picks a folder. */
        fun writeConfig(context: Context, appWidgetId: Int, treeUri: Uri, folderUri: Uri, label: String) {
            val editor = preferences(context).edit()
                .putString(key(appWidgetId, FIELD_TREE_URI), treeUri.toString())
                .putString(key(appWidgetId, FIELD_FOLDER_URI), folderUri.toString())
                .putString(key(appWidgetId, FIELD_LABEL), label)
            check(editor.commit()) { "Unable to persist folder widget config." }
        }

        fun readConfig(context: Context, appWidgetId: Int): FolderWidgetConfig? {
            val preferences = preferences(context)
            val treeUri = preferences.getString(key(appWidgetId, FIELD_TREE_URI), null)?.let(Uri::parse)
            val folderUri = preferences.getString(key(appWidgetId, FIELD_FOLDER_URI), null)?.let(Uri::parse)
            val label = preferences.getString(key(appWidgetId, FIELD_LABEL), null)
            if (treeUri == null || folderUri == null || label == null) return null
            return FolderWidgetConfig(treeUri, folderUri, label)
        }

        /** Shared by [onUpdate], [WidgetRefresher] and [FolderWidgetConfigActivity]'s first push. */
        fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_folder)
            views.setImageViewResource(R.id.widget_folder_icon, R.drawable.ic_widget_folder)

            val config = readConfig(context, appWidgetId)
            val pendingIntent = if (config == null) {
                views.setTextViewText(R.id.widget_folder_label, context.getString(R.string.widget_folder_unconfigured))
                val configureIntent = Intent(context, FolderWidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                PendingIntent.getActivity(
                    context,
                    requestCode(appWidgetId),
                    configureIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                views.setTextViewText(R.id.widget_folder_label, config.label)
                val openIntent = FylzIntents.applyOpenFolderExtras(
                    Intent(context, MainActivity::class.java),
                    config.treeUri,
                    config.folderUri,
                )
                PendingIntent.getActivity(
                    context,
                    requestCode(appWidgetId),
                    openIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            views.setOnClickPendingIntent(R.id.widget_folder_root, pendingIntent)
            return views
        }

        // One request code per appWidgetId keeps each 1x1 instance's PendingIntent distinct --
        // FLAG_UPDATE_CURRENT would otherwise let a second placed instance silently overwrite
        // the first's tap target.
        private fun requestCode(appWidgetId: Int) = 10_000 + appWidgetId
    }
}

data class FolderWidgetConfig(val treeUri: Uri, val folderUri: Uri, val label: String)
