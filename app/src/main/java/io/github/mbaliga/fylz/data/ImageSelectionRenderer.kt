package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Turns a [SelectionMask] into actual output pixels -- one function per output action
 * [io.github.mbaliga.fylz.ui.SelectImageOverlay] offers, each a pure function of the source
 * bitmap and the mask, so it does not matter which tool (lasso, magic wand, magnetic lasso) built
 * that mask.
 */
object ImageSelectionRenderer {

    /**
     * The selection's own bounding rectangle, cropped out of [source] as a plain rectangular
     * image -- what "Crop" (replacing the current file) and "Copy as new image" (always a new
     * file) both actually produce; the two differ only in where the caller saves the result, not
     * in these pixels. Null when [mask] selects nothing.
     */
    fun cropToBoundingBox(source: Bitmap, mask: SelectionMask): Bitmap? {
        val box = mask.boundingBox() ?: return null
        return Bitmap.createBitmap(source, box.left, box.top, box.width(), box.height())
    }

    /**
     * The same bounding rectangle, but with every pixel outside the selection's own shape made
     * fully transparent -- needs an alpha channel, so the caller always saves this as PNG or
     * WebP regardless of the source's own format. Null when [mask] selects nothing.
     */
    fun cutoutWithTransparency(source: Bitmap, mask: SelectionMask): Bitmap? {
        val box = mask.boundingBox() ?: return null
        val width = box.width()
        val height = box.height()
        val cropped = Bitmap.createBitmap(source, box.left, box.top, width, height)
        val pixels = IntArray(width * height)
        cropped.getPixels(pixels, 0, width, 0, 0, width, height)
        cropped.recycle()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask.contains(box.left + x, box.top + y)) {
                    pixels[y * width + x] = Color.TRANSPARENT
                }
            }
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}
