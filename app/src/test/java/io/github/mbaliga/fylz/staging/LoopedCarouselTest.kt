package io.github.mbaliga.fylz.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopedCarouselTest {

    @Test
    fun `an empty tray renders nothing rather than looping nothing`() {
        assertEquals(0, LoopedCarousel.virtualCount(0))
        assertEquals(0, LoopedCarousel.startIndex(0))
    }

    @Test
    fun `virtual slots wrap forward and backward without a seam`() {
        val count = 3
        val start = LoopedCarousel.startIndex(count)

        assertEquals(0, LoopedCarousel.itemIndex(start, count))
        assertEquals(1, LoopedCarousel.itemIndex(start + 1, count))
        assertEquals(2, LoopedCarousel.itemIndex(start + 2, count))
        // Past the last item is the first again — the loop's whole promise.
        assertEquals(0, LoopedCarousel.itemIndex(start + 3, count))
        // And scrolling left of the first is the last, which is where naive % goes negative.
        assertEquals(2, LoopedCarousel.itemIndex(start - 1, count))
    }

    @Test
    fun `one item loops onto itself`() {
        val start = LoopedCarousel.startIndex(1)
        assertEquals(0, LoopedCarousel.itemIndex(start, 1))
        assertEquals(0, LoopedCarousel.itemIndex(start + 7, 1))
        assertEquals(0, LoopedCarousel.itemIndex(start - 7, 1))
    }

    @Test
    fun `the start slot leaves room to flick far in both directions`() {
        for (count in 1..12) {
            val start = LoopedCarousel.startIndex(count)
            assertEquals("start must land on item 0 for $count items", 0, LoopedCarousel.itemIndex(start, count))
            assertTrue(start > 100_000)
            assertTrue(LoopedCarousel.virtualCount(count) - start > 100_000)
        }
    }
}
