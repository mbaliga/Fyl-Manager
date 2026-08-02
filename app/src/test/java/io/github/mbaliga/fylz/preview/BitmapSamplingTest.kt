package io.github.mbaliga.fylz.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BitmapSamplingTest {
    @Test
    fun leavesSmallImagesAtFullResolution() {
        assertEquals(1, calculateBitmapSampleSize(width = 1_600, height = 900, maxSide = 2_048))
    }

    @Test
    fun usesPowerOfTwoSampleForLargeImages() {
        assertEquals(4, calculateBitmapSampleSize(width = 8_000, height = 4_000, maxSide = 2_048))
    }

    @Test
    fun handlesUnknownBoundsAndRejectsInvalidLimit() {
        assertEquals(1, calculateBitmapSampleSize(width = -1, height = -1, maxSide = 2_048))
        assertThrows(IllegalArgumentException::class.java) {
            calculateBitmapSampleSize(width = 100, height = 100, maxSide = 0)
        }
    }
}
