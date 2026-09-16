package io.github.mbaliga.fylz.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps every placed Fylz widget current every six hours, [io.github.mbaliga.fylz.operations.RecycleBinRetentionScheduler]
 * -modelled: a unique periodic work request under a stable name, `UPDATE`d rather than replaced
 * on every [reconcile] call so an in-flight refresh is never orphaned.
 *
 * A widget's own `android:updatePeriodMillis` is left at 0 in every info XML this package ships
 * -- this worker is the only scheduled refresh path, so there is exactly one cadence to reason
 * about, not two racing ones. [WidgetRefresher.refreshAll] is also called directly by each
 * provider's `onUpdate` (a widget just placed, or the platform's own periodic call if a future
 * build ever sets one) and is safe to call as often as any caller likes -- it is a plain read of
 * already-persisted state, never a scan or a network call itself.
 */
class WidgetRefreshWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        WidgetRefresher.refreshAll(applicationContext)
        Result.success()
    }

    companion object {
        const val WORK_NAME = "fylz-widget-refresh"

        /** Enqueues (or confirms) the periodic refresh. Call from each provider's `onEnabled`. */
        fun reconcile(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

        /**
         * Cancels the periodic refresh only once no widget of ANY of the three families remains.
         * `AppWidgetProvider.onDisabled` fires once the last instance of that one provider is
         * removed, not once every Fylz widget on the device reaches zero -- a Storage widget
         * being removed while a Quick actions widget is still placed must not stop refreshing
         * the one still on the user's home screen. Called from each provider's `onDisabled`.
         */
        fun cancelIfNoWidgetsRemain(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val anyRemain = listOf(
                StorageWidgetProvider::class.java,
                FolderShortcutWidgetProvider::class.java,
                QuickActionsWidgetProvider::class.java,
            ).any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
            if (!anyRemain) cancel(context)
        }
    }
}
