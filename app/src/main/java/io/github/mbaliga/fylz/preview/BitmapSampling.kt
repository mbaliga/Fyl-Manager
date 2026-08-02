package io.github.mbaliga.fylz.preview

/**
 * Returns a power-of-two decode sample that keeps both decoded dimensions at or below [maxSide].
 * Android's bitmap decoder can use this before allocating the preview bitmap.
 */
internal fun calculateBitmapSampleSize(width: Int, height: Int, maxSide: Int): Int {
    require(maxSide > 0) { "maxSide must be positive" }
    if (width <= 0 || height <= 0) return 1

    var sample = 1
    while (width / sample > maxSide || height / sample > maxSide) {
        if (sample > Int.MAX_VALUE / 2) return sample
        sample *= 2
    }
    return sample
}
