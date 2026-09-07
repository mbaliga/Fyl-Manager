package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ImageSelectionRenderer]'s two output actions -- real pixel checks, same rationale as
 * [ImageAnnotationRendererTest]: a wrong crop or a wrong transparency mask is a corrupt or
 * mis-cropped file on disk, not a contained test failure.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageSelectionRendererTest {

    private fun redSquare(size: Int) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.RED) }

    @Test
    fun `crop returns exactly the selection's own bounding rectangle`() {
        val source = redSquare(40)
        val mask = SelectionMask.fromPath(Path().apply { addRect(10f, 10f, 30f, 25f, Path.Direction.CW) }, 40, 40)

        val cropped = ImageSelectionRenderer.cropToBoundingBox(source, mask)

        requireNotNull(cropped)
        assertEquals(20, cropped.width)
        assertEquals(15, cropped.height)
        assertEquals(AndroidColor.RED, cropped.getPixel(0, 0))
    }

    @Test
    fun `crop of an empty selection is null`() {
        val source = redSquare(10)
        val mask = SelectionMask.fromPath(Path(), 10, 10)

        assertNull(ImageSelectionRenderer.cropToBoundingBox(source, mask))
    }

    @Test
    fun `cutout keeps the selected shape opaque and makes the rest of its bounding box transparent`() {
        val source = redSquare(40)
        // A circle inscribed in a 20x20 box (10,10)-(30,30): its own corners are outside the
        // circle despite being inside the box's bounding rectangle -- exactly the pixels this
        // action must turn transparent that a plain bounding-box crop would have kept.
        val circle = Path().apply { addCircle(20f, 20f, 10f, Path.Direction.CW) }
        val mask = SelectionMask.fromPath(circle, 40, 40)

        val cutout = ImageSelectionRenderer.cutoutWithTransparency(source, mask)

        requireNotNull(cutout)
        assertEquals(20, cutout.width)
        assertEquals(20, cutout.height)
        // Centre of the circle, in the cutout's own coordinate space (box top-left is (10,10)).
        assertEquals(AndroidColor.RED, cutout.getPixel(10, 10))
        // A corner of the bounding box, outside the circle.
        assertEquals(AndroidColor.TRANSPARENT, cutout.getPixel(0, 0))
    }

    @Test
    fun `cutout of an empty selection is null`() {
        val source = redSquare(10)
        val mask = SelectionMask.fromPath(Path(), 10, 10)

        assertNull(ImageSelectionRenderer.cutoutWithTransparency(source, mask))
    }
}
