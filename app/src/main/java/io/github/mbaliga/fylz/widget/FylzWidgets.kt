package io.github.mbaliga.fylz.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.SizeF
import android.widget.RemoteViews
import androidx.annotation.StringRes
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.index.LocalIndexScheduler
import io.github.mbaliga.fylz.model.StoreTopic
import java.util.EnumSet

/** Fylz's home-screen widgets. Each has one provider class registered in the manifest. */
enum class WidgetKind(
    internal val provider: Class<out AppWidgetProvider>,
    @StringRes internal val label: Int,
    @StringRes internal val errorMessage: Int,
) {
    SPACE(SpaceWidgetProvider::class.java, R.string.widget_space_label, R.string.widget_space_error),
    BACKUPS(BackupsWidgetProvider::class.java, R.string.widget_backups_label, R.string.widget_backups_error),
    RECOVERY(RecoveryWidgetProvider::class.java, R.string.widget_recovery_label, R.string.widget_recovery_error),
    OPERATIONS(OperationsWidgetProvider::class.java, R.string.widget_operations_label, R.string.widget_operations_error),
    FOLDERS(FoldersWidgetProvider::class.java, R.string.widget_folders_label, R.string.widget_folders_error),
    INDEX(IndexWidgetProvider::class.java, R.string.widget_index_label, R.string.widget_index_error),
    ;

    internal fun load(context: Context, text: WidgetText): WidgetRenderer = when (this) {
        SPACE -> SpaceRenderer.load(context, text)
        BACKUPS -> BackupsRenderer.load(context, text)
        RECOVERY -> RecoveryRenderer.load(context, text)
        OPERATIONS -> OperationsRenderer.load(context, text)
        FOLDERS -> FoldersRenderer.load(context, text)
        INDEX -> IndexRenderer.load(context, text)
    }
}

/**
 * Pushes fresh widget content when Fylz's data changes.
 *
 * Widgets are never polled (`updatePeriodMillis="0"`). Every store write reaches [onStoreChanged]
 * (through `StoreChanges`, installed by `FylzApplication`); requests are coalesced for
 * [DEBOUNCE_MILLIS] so a copy that writes its journal on every chunk redraws the home screen about
 * once a second, not once a chunk. Only kinds that are actually on a home screen read any data.
 *
 * Nothing is cached: every redraw reads the stores again, so this adds no file of its own and
 * nothing that Android backup could carry off the device.
 */
object FylzWidgets {
    private const val DEBOUNCE_MILLIS = 1_000L

    private val lock = Any()
    private val pending: EnumSet<WidgetKind> = EnumSet.noneOf(WidgetKind::class.java)
    private var drainScheduled = false

    private val handler: Handler by lazy {
        Handler(HandlerThread("fylz-widgets").apply { start() }.looper)
    }

    /** Which widgets show data from each store. */
    internal fun kindsFor(topic: StoreTopic): Array<WidgetKind> = when (topic) {
        // A journal write can be progress, recovery after process death, space used or freed,
        // or a new standard folder.
        StoreTopic.OPERATION_JOURNAL -> arrayOf(WidgetKind.OPERATIONS, WidgetKind.RECOVERY, WidgetKind.SPACE, WidgetKind.FOLDERS)
        StoreTopic.RECYCLE_BIN -> arrayOf(WidgetKind.RECOVERY, WidgetKind.SPACE)
        StoreTopic.BACKUPS -> arrayOf(WidgetKind.BACKUPS, WidgetKind.OPERATIONS)
        StoreTopic.LOCAL_INDEX -> arrayOf(WidgetKind.INDEX)
    }

    /** The `StoreChanges` listener: redraw the widgets that show [topic]'s data. */
    fun onStoreChanged(context: Context, topic: StoreTopic) = requestRefresh(context, *kindsFor(topic))

    /** Ask for a redraw of [kinds] (all kinds when empty). Safe from any thread; never throws. */
    fun requestRefresh(context: Context, vararg kinds: WidgetKind) {
        runCatching {
            val app = context.applicationContext ?: context
            synchronized(lock) {
                if (kinds.isEmpty()) pending.addAll(WidgetKind.entries) else pending.addAll(kinds)
                if (drainScheduled) return
                drainScheduled = true
            }
            handler.postDelayed({ drain(app) }, DEBOUNCE_MILLIS)
        }
    }

    private fun drain(context: Context) {
        val kinds = synchronized(lock) {
            drainScheduled = false
            EnumSet.copyOf(pending).also { pending.clear() }
        }
        kinds.forEach { kind -> runCatching { renderNow(context, kind, null) } }
    }

    /**
     * Redraws [kind] on the widget thread and calls [done] afterwards, for broadcast receivers
     * holding a `goAsync()` result.
     */
    internal fun renderAsync(context: Context, kind: WidgetKind, ids: IntArray?, done: () -> Unit) {
        val app = context.applicationContext ?: context
        val posted = runCatching {
            handler.post {
                try {
                    runCatching { renderNow(app, kind, ids) }
                } finally {
                    done()
                }
            }
        }.getOrDefault(false)
        if (!posted) done()
    }

    /** Reads [kind]'s data once and pushes one size-mapped RemoteViews per placed widget. */
    internal fun renderNow(context: Context, kind: WidgetKind, ids: IntArray?) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val targets = WidgetIds.owned(ids, manager.getAppWidgetIds(ComponentName(context, kind.provider)))
        if (targets.isEmpty()) return
        val text = WidgetText(context, System.currentTimeMillis())
        val renderer = runCatching { kind.load(context, text) }.getOrElse { ErrorRenderer(context, text, kind) }
        targets.forEach { id ->
            val views = runCatching { sized(renderer, id) }.getOrElse { sized(ErrorRenderer(context, text, kind), id) }
            manager.updateAppWidget(id, views)
        }
        renderer.timeBoundaries?.let { WidgetTick.schedule(context, kind, WidgetTime.nextRefreshAt(text.nowMillis, text.zone, it)) }
    }

    /** API 31+ (Fylz's minSdk): the launcher picks the layout per size without waking the app. */
    private fun sized(renderer: WidgetRenderer, appWidgetId: Int): RemoteViews =
        RemoteViews(WidgetSize.entries.associate { SizeF(it.widthDp, it.heightDp) to renderer.views(appWidgetId, it) })
}

/**
 * Keeps a provider to its own widgets.
 *
 * Providers are exported (launchers must bind them), so any app can send one an
 * `APPWIDGET_UPDATE` naming any widget ids, and `AppWidgetProvider` hands those ids straight to
 * `onUpdate`. Fylz may update every widget of its own, so without this filter a forged broadcast to
 * the Space provider could draw Space content and tap targets into an Index widget.
 */
internal object WidgetIds {
    /**
     * @param requested ids an update asked for, or null for "every placed widget of this kind".
     * @param placed the ids the system has bound to this provider.
     */
    fun owned(requested: IntArray?, placed: IntArray): IntArray {
        if (requested == null) return placed
        val bound = placed.toHashSet()
        return requested.filter { it in bound }.distinct().toIntArray()
    }
}

/**
 * One inexact, non-waking alarm per widget kind for the next time-only boundary: midnight, a
 * backup window, a backup lease lapsing, or the In progress watchdog that retires work whose
 * process died (`OperationsWidgetModel.recheckAtMillis`). No exact-alarm permission: a late redraw
 * is fine because every timed value is printed with its day word or "as of" time, which stays true
 * until the redraw comes.
 */
internal object WidgetTick {
    fun schedule(context: Context, kind: WidgetKind, atMillis: Long) {
        runCatching {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            alarms.set(AlarmManager.RTC, atMillis, pendingIntent(context, kind))
        }
    }

    private fun pendingIntent(context: Context, kind: WidgetKind): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            kind.ordinal,
            Intent(context, WidgetTickReceiver::class.java)
                .setAction(WidgetTickReceiver.ACTION_TICK)
                .putExtra(WidgetTickReceiver.EXTRA_KIND, kind.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Receives [WidgetTick]'s alarms. Not exported: only Fylz's own PendingIntents reach it. */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) return
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { name -> WidgetKind.entries.firstOrNull { it.name == name } }
            ?: return
        val result = goAsync()
        FylzWidgets.renderAsync(context, kind, null) { result.finish() }
    }

    companion object {
        const val ACTION_TICK = "io.github.mbaliga.fylz.widget.action.TICK"
        const val EXTRA_KIND = "io.github.mbaliga.fylz.widget.extra.KIND"
    }
}

/**
 * The one widget control that changes state: the Index widget's "Rebuild". Not exported; the
 * action is checked and nothing else in the intent is read.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REBUILD_INDEX) return
        val result = goAsync()
        runCatching { LocalIndexScheduler(context.applicationContext).rebuild() }
        FylzWidgets.renderAsync(context, WidgetKind.INDEX, null) { result.finish() }
    }

    companion object {
        const val ACTION_REBUILD_INDEX = "io.github.mbaliga.fylz.widget.action.REBUILD_INDEX"
    }
}

/**
 * Base provider: every update, resize or clock/locale change is rendered off the main thread
 * inside `goAsync()`. Resizes need no handling: each update already carries a layout per size.
 */
abstract class FylzWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val result = goAsync()
        FylzWidgets.renderAsync(context, kind, appWidgetIds) { result.finish() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in REDRAW_ACTIONS) {
            val result = goAsync()
            FylzWidgets.renderAsync(context, kind, null) { result.finish() }
            return
        }
        super.onReceive(context, intent)
    }

    private companion object {
        /** System broadcasts the manifest routes here (see the providers' intent filters). */
        val REDRAW_ACTIONS = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
        )
    }
}

class SpaceWidgetProvider : FylzWidgetProvider(WidgetKind.SPACE)
class BackupsWidgetProvider : FylzWidgetProvider(WidgetKind.BACKUPS)
class RecoveryWidgetProvider : FylzWidgetProvider(WidgetKind.RECOVERY)
class OperationsWidgetProvider : FylzWidgetProvider(WidgetKind.OPERATIONS)
class FoldersWidgetProvider : FylzWidgetProvider(WidgetKind.FOLDERS)
class IndexWidgetProvider : FylzWidgetProvider(WidgetKind.INDEX)
