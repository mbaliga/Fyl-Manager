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
 * [MagneticPath.snap]'s whole reason to exist: a path between two anchors should hug a real edge
 * in the image instead of cutting the straight line a plain lasso would draw. These tests build
 * an image with an L-shaped black/white boundary (a black square in the corner of an otherwise
 * white canvas) and anchor both endpoints ON that boundary, so the straight line between them
 * cuts diagonally through the solid black interior while the correct "magnetic" route follows
 * the boundary around the corner instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MagneticPathTest {

    private fun lCornerImage(size: Int, corner: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.WHITE)
        canvas.drawRect(0f, 0f, corner.toFloat(), corner.toFloat(), Paint().apply { color = AndroidColor.BLACK })
        return bitmap
    }

    /** Sum of each step's own arrival cost, diagonal steps weighted the same way [MagneticPath] does. */
    private fun pathCost(costMap: EdgeCostMap, points: List<PixelPoint>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val step = if (a.x != b.x && a.y != b.y) 1.4142135f else 1f
            total += costMap.costAt(b.x, b.y) * step
        }
        return total
    }

    @Test
    fun `snap preserves the requested endpoints`() {
        val costMap = EdgeCostMap.from(lCornerImage(60, 30))

        val path = MagneticPath.snap(costMap, PixelPoint(5, 30), PixelPoint(30, 5))

        assertEquals(PixelPoint(5, 30), path.first())
        assertEquals(PixelPoint(30, 5), path.last())
    }

    @Test
    fun `snap hugs the edge around a corner far more cheaply than the straight line through it`() {
        val costMap = EdgeCostMap.from(lCornerImage(60, 30))
        val from = PixelPoint(5, 30)
        val to = PixelPoint(30, 5)

        val snapped = MagneticPath.snap(costMap, from, to)
        val straightLine = (0..25).map { t -> PixelPoint(from.x + t, from.y - t) }

        val snappedCost = pathCost(costMap, snapped)
        val straightCost = pathCost(costMap, straightLine)

        assertTrue(
            "snapped path (cost $snappedCost) should hug the edge far more cheaply than the " +
                "straight line through the solid interior (cost $straightCost)",
            snappedCost < straightCost / 2,
        )
    }

    @Test
    fun `snapping a point to itself is a single-point path`() {
        val costMap = EdgeCostMap.from(lCornerImage(20, 10))

        val path = MagneticPath.snap(costMap, PixelPoint(4, 4), PixelPoint(4, 4))

        assertEquals(listOf(PixelPoint(4, 4)), path)
    }
}
