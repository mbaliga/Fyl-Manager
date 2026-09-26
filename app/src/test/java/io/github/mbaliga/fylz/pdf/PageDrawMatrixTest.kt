package io.github.mbaliga.fylz.pdf

import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1.13: [pageDrawMatrix] is the one piece of genuinely new correctness-sensitive logic this
 * task's PdfToolService-into-PdfPageTools consolidation introduced -- it has to place an OCR text
 * line back at the same spot [Canvas.drawBitmap]'s own bitmap-to-page transform draws the pixel it
 * sits on, for every rotation, and a wrong sign or a copy-pasted branch would silently misplace
 * (or mirror) invisible text without ever failing a build. A real bitmap/canvas render is not
 * exercisable in this sandbox (no [android.graphics.Bitmap] native backend here), but the matrix
 * math itself is real under Robolectric's shadow, so this is tested directly rather than skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageDrawMatrixTest {

    private val bitmapWidth = 200
    private val bitmapHeight = 100

    @Test
    fun `no rotation leaves every corner exactly where it was`() {
        val matrix = pageDrawMatrix(0, bitmapWidth, bitmapHeight, bitmapWidth, bitmapHeight)
        assertMapsTo(matrix, 0f, 0f, 0f, 0f)
        assertMapsTo(matrix, bitmapWidth.toFloat(), bitmapHeight.toFloat(), bitmapWidth.toFloat(), bitmapHeight.toFloat())
    }

    @Test
    fun `a quarter turn moves the bitmap's top-left corner to the page's top-right`() {
        // The caller already swaps width/height for a quarter turn (PdfPageTools.export's own
        // outputWidthPoints/outputHeightPoints), so the page here is bitmapHeight x bitmapWidth.
        val pageWidth = bitmapHeight
        val pageHeight = bitmapWidth
        val matrix = pageDrawMatrix(1, bitmapWidth, bitmapHeight, pageWidth, pageHeight)

        assertMapsTo(matrix, 0f, 0f, pageWidth.toFloat(), 0f)
        assertMapsTo(matrix, 0f, bitmapHeight.toFloat(), 0f, 0f)
        assertMapsTo(matrix, bitmapWidth.toFloat(), bitmapHeight.toFloat(), 0f, pageHeight.toFloat())
    }

    @Test
    fun `a half turn moves the bitmap's top-left corner to the page's bottom-right`() {
        val matrix = pageDrawMatrix(2, bitmapWidth, bitmapHeight, bitmapWidth, bitmapHeight)

        assertMapsTo(matrix, 0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
        assertMapsTo(matrix, bitmapWidth.toFloat(), bitmapHeight.toFloat(), 0f, 0f)
    }

    @Test
    fun `a three-quarter turn moves the bitmap's top-left corner to the page's bottom-left`() {
        val pageWidth = bitmapHeight
        val pageHeight = bitmapWidth
        val matrix = pageDrawMatrix(3, bitmapWidth, bitmapHeight, pageWidth, pageHeight)

        assertMapsTo(matrix, 0f, 0f, 0f, pageHeight.toFloat())
        assertMapsTo(matrix, bitmapWidth.toFloat(), 0f, 0f, 0f)
    }

    @Test
    fun `a three-quarter turn is the mirror image of a quarter turn, not the same transform`() {
        val quarter = pageDrawMatrix(1, bitmapWidth, bitmapHeight, bitmapHeight, bitmapWidth)
        val threeQuarter = pageDrawMatrix(3, bitmapWidth, bitmapHeight, bitmapHeight, bitmapWidth)

        val quarterPoint = floatArrayOf(0f, 0f)
        val threeQuarterPoint = floatArrayOf(0f, 0f)
        quarter.mapPoints(quarterPoint)
        threeQuarter.mapPoints(threeQuarterPoint)

        assert(quarterPoint.toList() != threeQuarterPoint.toList()) {
            "a quarter turn and a three-quarter turn must not place the same corner in the same place"
        }
    }

    @Test
    fun `a negative rotation normalizes to the same transform as its positive equivalent`() {
        val pageWidth = bitmapHeight
        val pageHeight = bitmapWidth
        val viaNegative = pageDrawMatrix(-1, bitmapWidth, bitmapHeight, pageWidth, pageHeight)
        val viaPositive = pageDrawMatrix(3, bitmapWidth, bitmapHeight, pageWidth, pageHeight)

        assertMapsTo(viaNegative, 0f, 0f, 0f, pageHeight.toFloat())
        assertMapsTo(viaPositive, 0f, 0f, 0f, pageHeight.toFloat())
    }

    @Test
    fun `every rotation fits the whole bitmap onto the page with no clipping or gap`() {
        listOf(
            0 to (bitmapWidth to bitmapHeight),
            1 to (bitmapHeight to bitmapWidth),
            2 to (bitmapWidth to bitmapHeight),
            3 to (bitmapHeight to bitmapWidth),
        ).forEach { (turns, page) ->
            val (pageWidth, pageHeight) = page
            val matrix = pageDrawMatrix(turns, bitmapWidth, bitmapHeight, pageWidth, pageHeight)
            val mapped = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
            matrix.mapRect(mapped)
            assertEquals("turns=$turns", RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), mapped)
        }
    }

    private fun assertMapsTo(matrix: Matrix, x: Float, y: Float, expectedX: Float, expectedY: Float) {
        val point = floatArrayOf(x, y)
        matrix.mapPoints(point)
        assertEquals(expectedX, point[0], 0.01f)
        assertEquals(expectedY, point[1], 0.01f)
    }
}
