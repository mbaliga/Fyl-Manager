package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [EdgeCostMap] is the "how expensive is it to be here" half of the magnetic lasso; correctness
 * here means real edges (high gradient) read as cheap and flat colour fields read as expensive,
 * inverted from the raw Sobel magnitude on purpose so a shortest-path search naturally prefers
 * edges over open space.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class EdgeCostMapTest {

    private fun splitImage(size: Int, splitAt: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.BLACK)
        canvas.drawRect(
            splitAt.toFloat(), 0f, size.toFloat(), size.toFloat(),
            Paint().apply { color = AndroidColor.WHITE },
        )
        return bitmap
    }

    @Test
    fun `cost is near zero right at a sharp edge and near one deep in a flat region`() {
        val map = EdgeCostMap.from(splitImage(40, 20))

        val edgeCost = map.costAt(20, 20)
        val flatCost = map.costAt(5, 20)

        assertTrue("edge cost $edgeCost should be near zero", edgeCost < 0.1f)
        assertTrue("flat cost $flatCost should be near one", flatCost > 0.9f)
    }

    @Test
    fun `cost outside the map's own bounds is treated as maximally expensive`() {
        val map = EdgeCostMap.from(splitImage(10, 5))

        assertEquals(Float.MAX_VALUE, map.costAt(-1, 0))
        assertEquals(Float.MAX_VALUE, map.costAt(0, -1))
        assertEquals(Float.MAX_VALUE, map.costAt(10, 0))
        assertEquals(Float.MAX_VALUE, map.costAt(0, 10))
    }
}
