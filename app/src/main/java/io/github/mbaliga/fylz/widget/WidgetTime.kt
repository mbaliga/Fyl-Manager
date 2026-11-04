package io.github.mbaliga.fylz.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * How a timestamp is worded on a widget.
 *
 * Widgets print a day word plus a clock time ("Today 14:02") rather than a relative age
 * ("3 h ago"). A relative age is false the moment it is drawn and the home screen only redraws
 * when Fylz pushes an update; a day word stays true until midnight, and Fylz schedules one redraw
 * there ([WidgetTime.nextRefreshAt]).
 */
enum class DayWord {
    YESTERDAY,
    TODAY,
    TOMORROW,

    /** Within six days either side: the weekday name is unambiguous. */
    WEEKDAY,

    /** Anything further: a short date. */
    DATE,
}

/** Pure calendar arithmetic for widgets; every function takes the clock and zone explicitly. */
object WidgetTime {

    fun dayWord(atMillis: Long, nowMillis: Long, zone: ZoneId): DayWord {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, at)
        return when {
            days == 0L -> DayWord.TODAY
            days == -1L -> DayWord.YESTERDAY
            days == 1L -> DayWord.TOMORROW
            days in -6L..6L -> DayWord.WEEKDAY
            else -> DayWord.DATE
        }
    }

    /**
     * The next time the wall clock reads [hour]:[minute] strictly after [nowMillis].
     *
     * Mirrors `BackupScheduler.delayUntil`, which is how a daily backup plan's first window is
     * placed. Across a daylight-saving gap `ZonedDateTime` moves the time forward, as the
     * scheduler's own arithmetic does.
     */
    fun nextDailyWindow(hour: Int, minute: Int, nowMillis: Long, zone: ZoneId): Long {
        require(hour in 0..23 && minute in 0..59)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    /** The start of the next calendar day in [zone]. */
    fun nextMidnight(nowMillis: Long, zone: ZoneId): Long {
        val tomorrow: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
        return ZonedDateTime.of(tomorrow.atStartOfDay(), zone).toInstant().toEpochMilli()
    }

    /**
     * When the widgets next need a redraw because time passed, not because data changed.
     *
     * That is the next midnight (day words roll over) or the earliest upcoming boundary a widget
     * prints, such as a backup window, whichever comes first. Boundaries at or before [nowMillis]
     * are ignored.
     */
    fun nextRefreshAt(nowMillis: Long, zone: ZoneId, boundaries: Collection<Long>): Long {
        val midnight = nextMidnight(nowMillis, zone)
        val upcoming = boundaries.filter { it > nowMillis }.minOrNull() ?: return midnight
        return minOf(midnight, upcoming + BOUNDARY_GRACE_MILLIS)
    }

    /** A boundary redraw lands just after the boundary, so "next" has really moved on. */
    const val BOUNDARY_GRACE_MILLIS: Long = 60_000L
}
