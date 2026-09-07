package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect

/**
 * Which pixels of a [width]x[height] image are selected, as a flat per-pixel mask -- the one
 * representation every selection tool (lasso now; magic wand and magnetic lasso are later slices
 * of the same toolkit) converges on, so [ImageSelectionRenderer]'s output actions are pure
 * functions of a mask and never need to know which tool produced it.
 */
class SelectionMask private constructor(val width: Int, val height: Int, private val included: BooleanArray) {

    fun contains(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return included[y * width + x]
    }

    /** The smallest rectangle containing every selected pixel, or null if nothing is selected. */
    fun boundingBox(): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (included[y * width + x]) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < left || bottom < top) null else Rect(left, top, right + 1, bottom + 1)
    }

    companion object {
        /**
         * Rasterizes a closed path -- the lasso tool's own captured drag, already in the target
         * bitmap's pixel space -- into a mask via its fill region. Filled through a real
         * ARGB_8888 canvas rather than an ALPHA_8 one so the alpha-per-pixel readback has no
         * config-specific ambiguity; a fully-opaque fill against a fully-transparent background
         * makes "which pixels did the path cover" just a per-pixel alpha threshold.
         */
        fun fromPath(path: Path, width: Int, height: Int): SelectionMask {
            require(width > 0 && height > 0) { "A mask needs a positive size." }
            val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(maskBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawPath(path, paint)
            val pixels = IntArray(width * height)
            maskBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            maskBitmap.recycle()
            val included = BooleanArray(width * height) { index -> (pixels[index] ushr 24) >= 128 }
            return SelectionMask(width, height, included)
        }
    }
}
