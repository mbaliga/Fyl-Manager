package io.github.mbaliga.fylz.ui

import dev.aarso.cellshell.ScrubberStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScrollbarTest {

    private val stops = listOf(ScrubberStop("A", 0), ScrubberStop("B", 10), ScrubberStop("C", 25), ScrubberStop("D", 40))

    @Test fun anIndexFallsInTheLastStopThatHasStarted() {
        assertEquals(0, stopIndexFor(stops, 0))
        assertEquals(0, stopIndexFor(stops, 9))
        assertEquals(1, stopIndexFor(stops, 10))
        assertEquals(2, stopIndexFor(stops, 39))
        assertEquals(3, stopIndexFor(stops, 500))
    }

    @Test fun noStopsMeansNoHighlight() {
        assertEquals(-1, stopIndexFor(emptyList(), 3))
    }

    @Test fun fewStopsAreAllShown() {
        assertEquals(listOf(0, 1, 2, 3), thinStops(stops, 10, 1))
    }

    @Test fun manyStopsAreThinnedButTheCurrentOneSurvives() {
        val many = (0 until 100).map { ScrubberStop("S$it", it * 3) }
        val shown = thinStops(many, 10, current = 57)
        assertTrue(shown.size <= 11)
        assertTrue(57 in shown)
        assertEquals(shown.sorted(), shown)
    }
}
