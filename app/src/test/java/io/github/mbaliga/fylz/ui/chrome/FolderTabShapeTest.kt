package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape itself needs a device to rasterise, but the edge math in front of it is pure and is
 * where the mistakes would be: a slant that outran its own tab would fold the polygon back on
 * itself, and a mirror that didn't actually flip would make the active tab indistinguishable from
 * the ones behind it.
 */
class FolderTabShapeTest {

    @Test
    fun `slant clamps to the tab's own width, never past it`() {
        assertEquals(30f, folderTabSlant(30f, 100f), 0f)
        assertEquals(50f, folderTabSlant(80f, 50f), 0f)
        assertEquals(0f, folderTabSlant(-5f, 50f), 0f)
        assertEquals(0f, folderTabSlant(20f, 0f), 0f)
    }

    @Test
    fun `un-mirrored, the cut shortens the top edge and leaves the bottom full width`() {
        val width = 180f
        val slant = 22f
        assertEquals(width - slant, folderTabTopEdgeX(width, slant, mirrored = false), 0f)
        assertEquals(width, folderTabBottomEdgeX(width, slant, mirrored = false), 0f)
    }

    @Test
    fun `mirrored flips which edge the cut shortens`() {
        val width = 180f
        val slant = 22f
        assertEquals(width, folderTabTopEdgeX(width, slant, mirrored = true), 0f)
        assertEquals(width - slant, folderTabBottomEdgeX(width, slant, mirrored = true), 0f)
    }

    @Test
    fun `the slant runs the other way between mirrored and un-mirrored at the same width`() {
        val width = 180f
        val slant = 22f
        // Exactly one of the two edges differs from full width in each variant, and it's the
        // other one each time -- that inversion is the whole of what "mirrored" means.
        assertNotEquals(
            folderTabTopEdgeX(width, slant, mirrored = false),
            folderTabTopEdgeX(width, slant, mirrored = true),
        )
        assertNotEquals(
            folderTabBottomEdgeX(width, slant, mirrored = false),
            folderTabBottomEdgeX(width, slant, mirrored = true),
        )
    }

    @Test
    fun `overlap invariant -- the two trailing-edge x-coordinates never cross, even below the slant width`() {
        // A tab squeezed narrower than its own slant must still draw a closed, simple polygon:
        // both computed x-coordinates stay within [0, width] and never invert past one another,
        // which is what keeps consecutive overlapping tabs from folding into a self-intersecting
        // shape at the narrow end of the cascade.
        val slant = 22f
        listOf(0f, 1f, slant / 2f, slant, slant * 1.5f, slant * 10f).forEach { width ->
            listOf(false, true).forEach { mirrored ->
                val top = folderTabTopEdgeX(width, slant, mirrored)
                val bottom = folderTabBottomEdgeX(width, slant, mirrored)
                assertTrue("top ($top) must stay within [0, $width]", top in 0f..width)
                assertTrue("bottom ($bottom) must stay within [0, $width]", bottom in 0f..width)
            }
        }
    }

    @Test
    fun `shapes compare by geometry so recomposition reuses one clipped outline`() {
        assertEquals(FolderTabShape(slant = 22.dp, mirrored = false), FolderTabShape(slant = 22.dp, mirrored = false))
        assertNotEquals(FolderTabShape(slant = 22.dp, mirrored = false), FolderTabShape(slant = 22.dp, mirrored = true))
        assertNotEquals(FolderTabShape(slant = 22.dp, mirrored = false), FolderTabShape(slant = 18.dp, mirrored = false))
        assertEquals(
            FolderTabShape(slant = 22.dp, mirrored = true).hashCode(),
            FolderTabShape(slant = 22.dp, mirrored = true).hashCode(),
        )
    }
}
