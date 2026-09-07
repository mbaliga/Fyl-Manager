package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * A per-pixel travel cost derived from [source]'s own gradient magnitude (a Sobel operator over
 * luminance) -- an edge is CHEAP to cross, a flat region is EXPENSIVE -- so a shortest-path search
 * over this map naturally hugs real edges in the image instead of cutting straight lines through
 * flat regions. This is the "magnetic" half of the magnetic lasso; [MagneticPath.snap] does the
 * actual pathfinding, this only builds the map it searches over.
 */
class EdgeCostMap private constructor(val width: Int, val height: Int, private val cost: FloatArray) {

    /** [Float.MAX_VALUE] outside the map's own bounds, so a search never wanders off the image. */
    fun costAt(x: Int, y: Int): Float {
        if (x !in 0 until width || y !in 0 until height) return Float.MAX_VALUE
        return cost[y * width + x]
    }

    companion object {
        fun from(source: Bitmap): EdgeCostMap {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            val luminance = FloatArray(width * height) { index ->
                val pixel = pixels[index]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                0.299f * r + 0.587f * g + 0.114f * b
            }

            fun sample(x: Int, y: Int): Float {
                val cx = x.coerceIn(0, width - 1)
                val cy = y.coerceIn(0, height - 1)
                return luminance[cy * width + cx]
            }

            val gradient = FloatArray(width * height)
            var maxGradient = 1f
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val gx = -sample(x - 1, y - 1) - 2 * sample(x - 1, y) - sample(x - 1, y + 1) +
                        sample(x + 1, y - 1) + 2 * sample(x + 1, y) + sample(x + 1, y + 1)
                    val gy = -sample(x - 1, y - 1) - 2 * sample(x, y - 1) - sample(x + 1, y - 1) +
                        sample(x - 1, y + 1) + 2 * sample(x, y + 1) + sample(x + 1, y + 1)
                    val magnitude = sqrt(gx * gx + gy * gy)
                    gradient[y * width + x] = magnitude
                    if (magnitude > maxGradient) maxGradient = magnitude
                }
            }

            // Normalized and inverted: the strongest edge in the whole image costs (near) nothing
            // to cross, a perfectly flat region costs 1 per step.
            val cost = FloatArray(width * height) { index -> 1f - (gradient[index] / maxGradient) }
            return EdgeCostMap(width, height, cost)
        }
    }
}
