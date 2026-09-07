package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChromeScale.MAX]'s whole promise is a number: at that scale every control in the bottom chrome
 * clears the 48dp touch floor. This is what makes that promise checkable rather than a claim in a
 * KDoc, and what fails if a control smaller than the calibration figure is ever added.
 *
 * The dimensions are restated here rather than imported wholesale on purpose. Several are private
 * to their own files (the add-tab chip's width, the selection close button's), and a test that
 * could only see the public ones would pass while the smallest control in the band went unchecked
 * -- which is exactly the gap that let three sub-floor controls ship in the first place.
 */
class ChromeScaleTest {

    /**
     * Every chrome control's own smallest dimension at [ChromeScale.DEFAULT] -- the side a thumb
     * has least of. Keep this in step with the files named beside each entry.
     */
    private val controls: Map<String, Dp> = mapOf(
        // TabBand.kt
        "tab strip height" to 47.dp,
        "add-tab chip width" to 44.dp,
        "trash chip min width" to 64.dp,
        "folder tab min width" to 64.dp,
        // SelectionRow.kt
        "selection row height" to 44.dp,
        "selection close width" to 66.5.dp,
        // ActionsBar.kt
        "actions bar height" to 50.79.dp,
        "actions glyph cell" to 48.dp,
    )

    @Test
    fun `default scale leaves the export geometry untouched`() {
        assertEquals(1f, ChromeScale.DEFAULT.factor, 0f)
    }

    @Test
    fun `every chrome control clears the touch floor at max scale`() {
        controls.forEach { (name, base) ->
            val scaled = base * ChromeScale.MAX.factor
            assertTrue(
                "$name measures ${scaled.value}dp at MAX, under the ${ChromeTouchFloorDp}dp floor",
                scaled.value >= ChromeTouchFloorDp - TOLERANCE,
            )
        }
    }

    /**
     * MAX is meant to be the SMALLEST factor that clears the floor, not a round number picked
     * above it -- anything larger than needed is chrome eating listing space for nothing. If a
     * control smaller than the calibration figure is added, this fails and the constant behind
     * [ChromeScale.MAX] has to move with it.
     */
    @Test
    fun `max scale is calibrated to the smallest control, not overshot`() {
        val smallest = controls.values.minOf { it.value }
        assertEquals(ChromeTouchFloorDp / smallest, ChromeScale.MAX.factor, 0.0001f)
    }

    @Test
    fun `controls already at or above the floor are not shrunk by any scale`() {
        ChromeScale.entries.forEach { scale ->
            assertTrue("${scale.name} scales below 1x", scale.factor >= 1f)
        }
    }

    private companion object {
        /** Dp arithmetic in float, so a figure landing on 47.999998 is not a failure. */
        const val TOLERANCE = 0.001f
    }
}
