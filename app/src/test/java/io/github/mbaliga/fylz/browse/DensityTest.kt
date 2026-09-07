package io.github.mbaliga.fylz.browse

import io.github.mbaliga.fylz.model.DensityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Dp math, no emulator needed -- same reason `QuickLookSizingTest` runs plain JUnit against
 * `resolveFullCardSize`.
 */
class DensityTest {

    @Test
    fun `comfortable is scale 1, the size every branch ships today`() {
        assertEquals(1.0f, Density.scale(DensityMode.COMFORTABLE), 0.0001f)
        assertEquals(164f, Density.gridCardHeight(DensityMode.COMFORTABLE).value, 0.001f)
        assertEquals(130f, Density.gridMinCellWidth(DensityMode.COMFORTABLE).value, 0.001f)
        assertEquals(56f, Density.gridThumb(DensityMode.COMFORTABLE).value, 0.001f)
        assertEquals(62f, Density.listRowHeight(DensityMode.COMFORTABLE).value, 0.001f)
        assertEquals(40f, Density.listThumb(DensityMode.COMFORTABLE).value, 0.001f)
        assertEquals(44f, Density.detailsRowHeight(DensityMode.COMFORTABLE).value, 0.001f)
        assertEquals(24f, Density.detailsThumb(DensityMode.COMFORTABLE).value, 0.001f)
    }

    @Test
    fun `compact is smaller, detailed is bigger, both around comfortable`() {
        assertEquals(0.8f, Density.scale(DensityMode.COMPACT), 0.0001f)
        assertEquals(1.25f, Density.scale(DensityMode.DETAILED), 0.0001f)
    }

    @Test
    fun `every named size scales by the same factor as scale()`() {
        // "One table" means every reader multiplies the same baseline by the same factor -- if a
        // size drifted off scale() it would stop tracking the S/M/L a user actually picked.
        DensityMode.entries.forEach { mode ->
            val s = Density.scale(mode)
            assertEquals(164f * s, Density.gridCardHeight(mode).value, 0.01f)
            assertEquals(130f * s, Density.gridMinCellWidth(mode).value, 0.01f)
            assertEquals(56f * s, Density.gridThumb(mode).value, 0.01f)
            assertEquals(62f * s, Density.listRowHeight(mode).value, 0.01f)
            assertEquals(40f * s, Density.listThumb(mode).value, 0.01f)
            assertEquals(44f * s, Density.detailsRowHeight(mode).value, 0.01f)
            assertEquals(24f * s, Density.detailsThumb(mode).value, 0.01f)
        }
    }

    @Test
    fun `detailed sizes exceed compact sizes for every named table entry`() {
        // Not just the scale factor -- the actual dp values a caller reads must order the same
        // way, or "L" would render smaller than "S" for some branch nobody checked.
        val compact = DensityMode.COMPACT
        val detailed = DensityMode.DETAILED
        assertTrue(Density.gridCardHeight(detailed) > Density.gridCardHeight(compact))
        assertTrue(Density.gridMinCellWidth(detailed) > Density.gridMinCellWidth(compact))
        assertTrue(Density.gridThumb(detailed) > Density.gridThumb(compact))
        assertTrue(Density.listRowHeight(detailed) > Density.listRowHeight(compact))
        assertTrue(Density.listThumb(detailed) > Density.listThumb(compact))
        assertTrue(Density.detailsRowHeight(detailed) > Density.detailsRowHeight(compact))
        assertTrue(Density.detailsThumb(detailed) > Density.detailsThumb(compact))
    }
}
