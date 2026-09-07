package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect

/**
 * Which pixels of a [width]x[height] image are selected, as a flat per-pixel mask -- the one
 * representation every selection tool (lasso, magic wand; magnetic lasso is a later slice of the
 * same toolkit) converges on, so [ImageSelectionRenderer]'s output actions are pure functions of
 * a mask and never need to know which tool produced it.
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

        /**
         * Magic wand: every pixel reachable from ([seedX], [seedY]) by 4-connected steps (no
         * diagonals) through pixels within [tolerance] of the SEED's own color -- not a running
         * average of the region grown so far, so the result does not drift as it expands. Two
         * patches of near-identical color that never touch are never both selected: this is a
         * connectivity-bounded fill, not a global color threshold over the whole image.
         *
         * Tolerance is a per-channel Chebyshev distance (the largest of the R/G/B differences),
         * 0..255 -- 0 matches the seed's exact color only, 255 matches everything the flood can
         * reach (the whole image, since nothing ever fails that check).
         *
         * Iterative with an explicit `IntArray`-backed stack, not recursion or a boxed
         * `ArrayDeque<Int>`: a permissive tolerance on a real photo can enqueue millions of
         * pixels, and boxing every one of them is a real memory cost this can't afford.
         */
        fun floodFill(source: Bitmap, seedX: Int, seedY: Int, tolerance: Int): SelectionMask {
            val width = source.width
            val height = source.height
            require(seedX in 0 until width && seedY in 0 until height) { "Seed point is outside the image." }
            require(tolerance in 0..255) { "Tolerance must be between 0 and 255." }

            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val seedColor = pixels[seedY * width + seedX]
            val included = BooleanArray(width * height)
            val visited = BooleanArray(width * height)

            var stack = IntArray(1_024)
            var stackSize = 0
            fun push(index: Int) {
                if (stackSize == stack.size) stack = stack.copyOf(stack.size * 2)
                stack[stackSize++] = index
            }
            fun visit(index: Int) {
                if (!visited[index]) {
                    visited[index] = true
                    push(index)
                }
            }

            val seedIndex = seedY * width + seedX
            visited[seedIndex] = true
            push(seedIndex)

            while (stackSize > 0) {
                val index = stack[--stackSize]
                if (!withinTolerance(pixels[index], seedColor, tolerance)) continue
                included[index] = true
                val x = index % width
                val y = index / width
                if (x > 0) visit(index - 1)
                if (x < width - 1) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y < height - 1) visit(index + width)
            }
            return SelectionMask(width, height, included)
        }

        private fun withinTolerance(a: Int, b: Int, tolerance: Int): Boolean {
            val dr = kotlin.math.abs(Color.red(a) - Color.red(b))
            val dg = kotlin.math.abs(Color.green(a) - Color.green(b))
            val db = kotlin.math.abs(Color.blue(a) - Color.blue(b))
            return maxOf(dr, dg, db) <= tolerance
        }
    }
}
