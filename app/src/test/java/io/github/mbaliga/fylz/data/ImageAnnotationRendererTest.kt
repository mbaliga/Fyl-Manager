package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ImageAnnotationRenderer.burnIn] is the one place a drawn stroke becomes actual saved pixels --
 * [io.github.mbaliga.fylz.ui.AnnotateOverlay] calls it for both Save and Save As, so a bug here is
 * a bug in every annotated file this app ever writes. Native graphics mode is load-bearing:
 * Robolectric's default (legacy) `Canvas`/`Paint` shadows record calls without actually painting a
 * pixel, so a test relying on real output has to opt into the Skia-backed native renderer, the same
 * way this codebase's screenshot harnesses already do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageAnnotationRendererTest {

    private fun whiteSquare(size: Int) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.WHITE) }

    @Test
    fun `a stroke paints its own color at the line and leaves the rest of the image untouched`() {
        val source = whiteSquare(100)
        val stroke = AnnotationStroke(
            points = listOf(Offset(10f, 50f), Offset(90f, 50f)),
            color = Color.Red,
            strokeWidthPx = 10f,
        )

        val result = ImageAnnotationRenderer.burnIn(source, listOf(stroke), Size(100f, 100f))

        assertEquals(AndroidColor.RED, result.getPixel(50, 50))
        assertEquals(AndroidColor.WHITE, result.getPixel(5, 5))
        assertEquals(AndroidColor.WHITE, result.getPixel(95, 95))
    }

    @Test
    fun `burning in never mutates the source bitmap`() {
        val source = whiteSquare(100)
        val stroke = AnnotationStroke(listOf(Offset(10f, 50f), Offset(90f, 50f)), Color.Red, 10f)

        ImageAnnotationRenderer.burnIn(source, listOf(stroke), Size(100f, 100f))

        assertEquals(AndroidColor.WHITE, source.getPixel(50, 50))
    }

    @Test
    fun `no strokes means no change`() {
        val source = whiteSquare(20)

        val result = ImageAnnotationRenderer.burnIn(source, emptyList(), Size(20f, 20f))

        for (x in 0 until 20) for (y in 0 until 20) {
            assertEquals(AndroidColor.WHITE, result.getPixel(x, y))
        }
    }

    /**
     * The canvas a stroke is captured on is a screen-sized rendering of the photo, almost never
     * the photo's own pixel dimensions -- a point at the canvas's exact centre has to land at the
     * bitmap's exact centre regardless of how much bigger or smaller the source actually is.
     */
    @Test
    fun `a point scales from canvas space into the source bitmap's own pixel space`() {
        val source = whiteSquare(200)
        // A canvas at half the bitmap's size; a stroke centred on ITS centre (25,25) must land on
        // the bitmap's centre (100,100), not at (25,25).
        val stroke = AnnotationStroke(
            points = listOf(Offset(20f, 25f), Offset(30f, 25f)),
            color = Color.Blue,
            strokeWidthPx = 4f,
        )

        val result = ImageAnnotationRenderer.burnIn(source, listOf(stroke), Size(50f, 50f))

        assertEquals(AndroidColor.BLUE, result.getPixel(100, 100))
        assertNotEquals(AndroidColor.BLUE, result.getPixel(25, 25))
    }
}
