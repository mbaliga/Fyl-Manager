package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SelectionMask.fromPath] is the one place a lasso's freehand drag becomes the per-pixel mask
 * every output action reads -- a wrong rasterization here selects the wrong pixels silently, with
 * nothing else in the pipeline positioned to catch it. Native graphics mode is load-bearing (see
 * [ImageAnnotationRendererTest]'s own note): the default shadow `Canvas`/`Paint` record calls
 * without painting a pixel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SelectionMaskTest {

    @Test
    fun `a filled rectangle selects its own interior and nothing outside it`() {
        val path = Path().apply { addRect(10f, 10f, 30f, 20f, Path.Direction.CW) }

        val mask = SelectionMask.fromPath(path, width = 50, height = 50)

        assertTrue(mask.contains(15, 15))
        assertTrue(mask.contains(29, 19))
        assertFalse(mask.contains(5, 5))
        assertFalse(mask.contains(35, 25))
    }

    @Test
    fun `the bounding box matches the filled rectangle's own bounds`() {
        val path = Path().apply { addRect(10f, 10f, 30f, 20f, Path.Direction.CW) }

        val box = SelectionMask.fromPath(path, width = 50, height = 50).boundingBox()

        requireNotNull(box)
        assertEquals(10, box.left)
        assertEquals(10, box.top)
        assertEquals(30, box.right)
        assertEquals(20, box.bottom)
    }

    @Test
    fun `an empty path selects nothing and has no bounding box`() {
        val mask = SelectionMask.fromPath(Path(), width = 20, height = 20)

        assertNull(mask.boundingBox())
        assertFalse(mask.contains(10, 10))
    }

    @Test
    fun `a point outside the mask's own dimensions is never selected`() {
        val path = Path().apply { addRect(0f, 0f, 20f, 20f, Path.Direction.CW) }

        val mask = SelectionMask.fromPath(path, width = 20, height = 20)

        assertFalse(mask.contains(-1, 5))
        assertFalse(mask.contains(5, -1))
        assertFalse(mask.contains(20, 5))
        assertFalse(mask.contains(5, 20))
    }

    private fun twoColorHalves(width: Int, height: Int, left: Int, right: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = left })
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = right })
        return bitmap
    }

    @Test
    fun `magic wand with zero tolerance selects only its own exact color`() {
        val bitmap = twoColorHalves(20, 20, left = Color.RED, right = Color.BLUE)

        val mask = SelectionMask.floodFill(bitmap, seedX = 2, seedY = 2, tolerance = 0)

        assertTrue(mask.contains(9, 10))
        assertFalse(mask.contains(10, 10))
        assertFalse(mask.contains(19, 10))
    }

    @Test
    fun `magic wand with full tolerance selects the entire image`() {
        val bitmap = twoColorHalves(20, 20, left = Color.RED, right = Color.BLUE)

        val mask = SelectionMask.floodFill(bitmap, seedX = 2, seedY = 2, tolerance = 255)

        assertTrue(mask.contains(0, 0))
        assertTrue(mask.contains(19, 19))
    }

    @Test
    fun `magic wand never crosses to a same-colored region it is not connected to`() {
        // Two red squares that never touch, separated by a white gap -- filling from one must
        // never reach the other, no matter how close their colors are, because nothing links them.
        val bitmap = Bitmap.createBitmap(30, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(bitmap)
        val red = Paint().apply { color = Color.RED }
        canvas.drawRect(0f, 0f, 5f, 10f, red)
        canvas.drawRect(25f, 0f, 30f, 10f, red)

        val mask = SelectionMask.floodFill(bitmap, seedX = 2, seedY = 5, tolerance = 10)

        assertTrue(mask.contains(2, 5))
        assertFalse(mask.contains(27, 5))
    }

    @Test
    fun `magic wand refuses a seed point outside the image`() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        assertThrows(IllegalArgumentException::class.java) {
            SelectionMask.floodFill(bitmap, seedX = 20, seedY = 5, tolerance = 10)
        }
    }
}
