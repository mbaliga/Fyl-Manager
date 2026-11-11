package io.github.mbaliga.fylz.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.mbaliga.fylz.IndexManagerActivity
import io.github.mbaliga.fylz.MainActivity
import io.github.mbaliga.fylz.OperationHistoryActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ui.formatStorageBytes
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** Words, sizes and times for one rendering, all from the app's string resources. */
internal class WidgetText(
    private val context: Context,
    val nowMillis: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val locale get() = context.resources.configuration.locales[0]

    fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)

    /** Same units and rounding as the storage home, so the widget and the app agree. */
    fun bytes(value: Long): String = formatStorageBytes(value.coerceAtLeast(0L))

    fun count(value: Int): String = NumberFormat.getIntegerInstance(locale).format(value)

    fun items(count: Int): String = plural(R.plurals.widget_items, count, count(count))

    fun join(first: CharSequence, second: CharSequence): String = s(R.string.widget_join, first, second)

    /** The user's 12/24-hour clock. */
    fun clock(millis: Long): String =
        DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(millis))

    /** A wall-clock time of day, such as a backup plan's daily window. */
    fun timeOfDay(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return clock(calendar.timeInMillis)
    }

    /** "Today 14:02", "Yesterday 09:10", "Mon 08:00", "12 Oct 08:00" (see [DayWord]). */
    fun moment(millis: Long): String = when (WidgetTime.dayWord(millis, nowMillis, zone)) {
        DayWord.TODAY -> s(R.string.widget_day_today, clock(millis))
        DayWord.YESTERDAY -> s(R.string.widget_day_yesterday, clock(millis))
        DayWord.TOMORROW -> s(R.string.widget_day_tomorrow, clock(millis))
        DayWord.WEEKDAY -> s(R.string.widget_day_time, pattern("EEE", millis), clock(millis))
        DayWord.DATE -> s(R.string.widget_day_time, pattern("dMMM", millis), clock(millis))
    }

    /** "as of 14:02": printed wherever data can change without Fylz seeing it. */
    fun asOf(): String = s(R.string.widget_as_of, clock(nowMillis))

    /** Footer text: the "as of" time, plus "and N more" when rows did not fit. */
    fun footer(hidden: Int): String =
        if (hidden > 0) join(asOf(), plural(R.plurals.widget_and_more, hidden, hidden)) else asOf()

    private fun pattern(skeleton: String, millis: Long): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(millis))
    }
}

/**
 * Tap targets. Every intent is explicit (Fylz's own components), every PendingIntent immutable,
 * and request codes are unique per widget id and slot, because PendingIntent identity ignores
 * extras: two slots sharing a code would overwrite each other's destination.
 */
internal object WidgetIntents {
    const val SLOT_MAIN = 0
    const val SLOT_SECONDARY = 1
    const val SLOT_ACTION = 2

    /** First slot for per-row or per-pad targets; up to [SLOTS_PER_WIDGET] - this many of them. */
    const val SLOT_ITEM_BASE = 4
    private const val SLOTS_PER_WIDGET = 16

    const val ACTION_OPEN = "io.github.mbaliga.fylz.widget.action.OPEN"

    fun requestCode(appWidgetId: Int, slot: Int): Int {
        require(slot in 0 until SLOTS_PER_WIDGET)
        return appWidgetId * SLOTS_PER_WIDGET + slot
    }

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** Opens Fylz's main activity at [destination] (see [WidgetLaunch]). */
    fun open(context: Context, appWidgetId: Int, slot: Int, destination: WidgetDestination): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        WidgetLaunch.extras(destination).forEach { (key, value) -> intent.putExtra(key, value) }
        return PendingIntent.getActivity(context, requestCode(appWidgetId, slot), intent, FLAGS)
    }

    /**
     * For the standard-launch-mode screens a widget opens directly: reuse the instance already in
     * Fylz's task (dropping anything above it) instead of stacking another one per tap, so Back
     * never has to unwind a pile of identical screens.
     */
    private const val REUSE_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    /** Opens the file-operation activity screen ("Activity"). */
    fun operationHistory(context: Context, appWidgetId: Int, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(appWidgetId, slot),
            Intent(context, OperationHistoryActivity::class.java).addFlags(REUSE_FLAGS),
            FLAGS,
        )

    /** Opens the index manager. */
    fun indexManager(context: Context, appWidgetId: Int, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(appWidgetId, slot),
            Intent(context, IndexManagerActivity::class.java).addFlags(REUSE_FLAGS),
            FLAGS,
        )

    /** Asks [WidgetActionReceiver] (not exported) to start an index rebuild. */
    fun rebuildIndex(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(appWidgetId, SLOT_ACTION),
            Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_REBUILD_INDEX),
            FLAGS,
        )
}
