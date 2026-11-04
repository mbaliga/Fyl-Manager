package io.github.mbaliga.fylz.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WidgetTimeTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = this.zone): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `day words follow the calendar day, not a 24 hour window`() {
        val now = at(2026, 11, 4, 0, 30)
        assertEquals(DayWord.TODAY, WidgetTime.dayWord(at(2026, 11, 4, 0, 5), now, zone))
        assertEquals(DayWord.YESTERDAY, WidgetTime.dayWord(at(2026, 11, 3, 23, 55), now, zone))
        assertEquals(DayWord.TOMORROW, WidgetTime.dayWord(at(2026, 11, 5, 2, 0), now, zone))
        assertEquals(DayWord.WEEKDAY, WidgetTime.dayWord(at(2026, 10, 30, 9, 0), now, zone))
        assertEquals(DayWord.WEEKDAY, WidgetTime.dayWord(at(2026, 11, 10, 9, 0), now, zone))
        assertEquals(DayWord.DATE, WidgetTime.dayWord(at(2026, 10, 28, 9, 0), now, zone))
        assertEquals(DayWord.DATE, WidgetTime.dayWord(at(2026, 11, 11, 9, 0), now, zone))
    }

    @Test
    fun `next daily window is later today until it passes, then tomorrow`() {
        assertEquals(at(2026, 11, 4, 2, 0), WidgetTime.nextDailyWindow(2, 0, at(2026, 11, 4, 1, 59), zone))
        assertEquals(at(2026, 11, 5, 2, 0), WidgetTime.nextDailyWindow(2, 0, at(2026, 11, 4, 2, 0), zone))
        assertEquals(at(2026, 11, 5, 2, 0), WidgetTime.nextDailyWindow(2, 0, at(2026, 11, 4, 14, 0), zone))
    }

    @Test
    fun `next daily window survives a daylight saving gap`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 29 March 2026: 02:00-03:00 does not exist in Berlin.
        val now = at(2026, 3, 29, 1, 0, berlin)
        val next = WidgetTime.nextDailyWindow(2, 30, now, berlin)
        assertTrue(next > now)
        assertEquals(at(2026, 3, 29, 3, 30, berlin), next)
    }

    @Test
    fun `next midnight is the start of the following local day`() {
        assertEquals(at(2026, 11, 5, 0, 0), WidgetTime.nextMidnight(at(2026, 11, 4, 23, 59), zone))
        assertEquals(at(2026, 11, 5, 0, 0), WidgetTime.nextMidnight(at(2026, 11, 4, 0, 0), zone))
    }

    @Test
    fun `refresh lands just after the earliest upcoming boundary, else at midnight`() {
        val now = at(2026, 11, 4, 1, 0)
        val window = at(2026, 11, 4, 2, 0)
        assertEquals(
            window + WidgetTime.BOUNDARY_GRACE_MILLIS,
            WidgetTime.nextRefreshAt(now, zone, listOf(at(2026, 11, 4, 3, 0), window)),
        )
        assertEquals(at(2026, 11, 5, 0, 0), WidgetTime.nextRefreshAt(now, zone, emptyList()))
        // A boundary after midnight still yields the midnight redraw first.
        assertEquals(at(2026, 11, 5, 0, 0), WidgetTime.nextRefreshAt(now, zone, listOf(at(2026, 11, 5, 2, 0))))
        // Boundaries already past are ignored.
        assertEquals(at(2026, 11, 5, 0, 0), WidgetTime.nextRefreshAt(now, zone, listOf(at(2026, 11, 4, 0, 30))))
    }
}
