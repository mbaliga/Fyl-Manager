package io.github.mbaliga.fylz.ui.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class CollectionStackSpecTest {

    private val spec = CollectionStackSpec

    @Test fun theActiveCardIsCentredUprightAndOpaque() {
        val p = spec.pose(0f)
        assertEquals(0f, p.x, 0f); assertEquals(0f, p.y, 0f); assertEquals(0f, p.rotationDeg, 0f)
        assertEquals(1f, p.scale, 0f); assertEquals(1f, p.alpha, 0f); assertEquals(100, p.z)
    }

    @Test fun neighboursMatchTheHandoffOffsets() {
        val right = spec.pose(1f)
        assertEquals(58f, right.x, 0.001f); assertEquals(6.5f, right.rotationDeg, 0.001f)
        assertEquals(0.9f, right.scale, 0.001f); assertEquals(4f, right.y, 0.001f); assertEquals(1f, right.alpha, 0f)
        val left = spec.pose(-1f)
        assertEquals(-58f, left.x, 0.001f); assertEquals(-6.5f, left.rotationDeg, 0.001f)
        // Second neighbour: 58 + 26, rotation clamped at two steps, half transparent.
        val second = spec.pose(2f)
        assertEquals(84f, second.x, 0.001f); assertEquals(13f, second.rotationDeg, 0.001f)
        assertEquals(0.5f, second.alpha, 0.001f); assertEquals(8f, second.y, 0.001f)
        // Third: 94, rotation still clamped, invisible.
        val third = spec.pose(3f)
        assertEquals(94f, third.x, 0.001f); assertEquals(13f, third.rotationDeg, 0.001f)
        assertEquals(0f, third.alpha, 0.001f)
        assertEquals(0f, spec.pose(5f).alpha, 0f)
    }

    @Test fun cardsFurtherAwayStackBehindNearerOnes() {
        assertTrue(spec.pose(0f).z > spec.pose(1f).z)
        assertTrue(spec.pose(1f).z > spec.pose(2f).z)
        assertEquals(spec.pose(-2f).z, spec.pose(2f).z)
    }

    @Test fun midSwipePosesAreContinuous() {
        val a = spec.pose(0.99f); val b = spec.pose(1.01f)
        assertEquals(a.x, b.x, 1.5f)
        assertEquals(a.alpha, b.alpha, 0.02f)
    }

    @Test fun aShortSlowDragSnapsBack() {
        assertEquals(2, spec.settle(startIndex = 2, dragPx = -10f, velocityPxPerMs = -0.1f, itemCount = 6))
    }

    @Test fun aLongDragAdvancesOneCardInTheDirectionOfTravel() {
        assertEquals(3, spec.settle(2, dragPx = -30f, velocityPxPerMs = 0f, itemCount = 6))
        assertEquals(1, spec.settle(2, dragPx = 30f, velocityPxPerMs = 0f, itemCount = 6))
    }

    @Test fun aFastFlickAdvancesEvenWithLittleTravel() {
        assertEquals(3, spec.settle(2, dragPx = -5f, velocityPxPerMs = -0.8f, itemCount = 6))
    }

    @Test fun neverLeavesTheStack() {
        assertEquals(0, spec.settle(0, dragPx = 60f, velocityPxPerMs = 1f, itemCount = 6))
        assertEquals(5, spec.settle(5, dragPx = -60f, velocityPxPerMs = -1f, itemCount = 6))
        assertEquals(0, spec.settle(0, dragPx = -60f, velocityPxPerMs = 0f, itemCount = 0))
    }

    @Test fun updatedWording() {
        val now = 1_000_000_000_000L
        assertEquals("Updated just now", RelativeTime.updated(now, now - 20_000))
        assertEquals("Updated 5m ago", RelativeTime.updated(now, now - 5 * 60_000))
        assertEquals("Updated 3h ago", RelativeTime.updated(now, now - 3 * 3_600_000L))
        assertEquals("Updated 2d ago", RelativeTime.updated(now, now - 2 * 86_400_000L))
        assertEquals("Updated 2mo ago", RelativeTime.updated(now, now - 65 * 86_400_000L))
        assertNull(RelativeTime.updated(now, null))
        assertNull(RelativeTime.updated(now, 0L))
    }

    @Test fun groupsByDayNewestFirstAndKeepsUndated() {
        val zone = ZoneOffset.UTC
        fun ms(y: Int, m: Int, d: Int, h: Int = 12) = LocalDate.of(y, m, d).atTime(h, 0).toInstant(zone).toEpochMilli()
        data class F(val name: String, val at: Long?)
        val files = listOf(F("a", ms(2026, 9, 23, 9)), F("b", ms(2026, 9, 23, 17)), F("c", ms(2026, 9, 13)), F("d", ms(2025, 1, 2)), F("z", null))
        val groups = CollectionGrouping.byDay(files, { it.at }, LocalDate.of(2026, 11, 18), zone, Locale.US)
        assertEquals(listOf("Wednesday, September 23", "Sunday, September 13", "Thursday, January 2, 2025", "Undated"), groups.map { it.title })
        assertEquals(listOf("b", "a"), groups[0].items.map { it.name })
        assertEquals("Sep 23", groups[0].shortTitle)
        assertEquals(listOf("z"), groups.last().items.map { it.name })
    }

    @Test fun countWording() {
        assertEquals("1 document", CollectionGrouping.countLabel(1, "document"))
        assertEquals("3 documents", CollectionGrouping.countLabel(3, "document"))
        assertEquals("0 documents", CollectionGrouping.countLabel(0, "document"))
    }
}
