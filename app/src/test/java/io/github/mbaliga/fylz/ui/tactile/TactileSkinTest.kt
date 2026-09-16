package io.github.mbaliga.fylz.ui.tactile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which skin the tactile kit paints must follow the APP's resolved theme, not the phone's.
 *
 * Build 11.5 shipped [tactilePalette] branching on `isSystemInDarkTheme()`. Robolectric's own
 * system configuration is light, which is exactly the condition a user creates by running Fylz in
 * Dark on a Light phone -- and under it the kit drew its light skin (gray plates, WHITE field
 * bodies) underneath a dark scheme's near-white body text. Unreadable, and invisible to any test
 * that only ever composed one theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TactileSkinTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun paletteUnder(mode: ThemeMode, style: ThemeStyle = ThemeStyle.NEO): TactilePalette {
        lateinit var captured: TactilePalette
        compose.setContent {
            FylzTheme(themeMode = mode, accentPreset = AccentPreset.MOSS, dynamicColor = false, themeStyle = style) {
                CapturePalette { captured = it }
            }
        }
        compose.waitForIdle()
        return captured
    }

    @Composable
    private fun CapturePalette(onPalette: (TactilePalette) -> Unit) {
        onPalette(tactilePalette())
    }

    @Test
    fun `the kit paints its jet skin when the APP is dark, whatever the system is set to`() {
        // Robolectric's system config is LIGHT here, so this fails the moment anyone reaches for
        // isSystemInDarkTheme() again.
        val palette = paletteUnder(ThemeMode.DARK)
        assertTrue("a dark app theme must resolve the dark skin", palette.isDark)
        assertTrue(
            "a dark skin's field body must not be a near-white card under near-white text",
            palette.fieldFill.luminanceish() < 0.5f,
        )
    }

    @Test
    fun `the kit paints its light skin when the app is light`() {
        val palette = paletteUnder(ThemeMode.LIGHT)
        assertFalse(palette.isDark)
        assertTrue("the light skin's field body is the lighter figure", palette.fieldFill.luminanceish() > 0.5f)
    }

    @Test
    fun `the dark skin keeps its keycaps lighter than the plate they sit in`() {
        val palette = paletteUnder(ThemeMode.DARK)
        // A raised key must stay above its own well all the way down its gradient, or the bottom
        // of every selected segment melts into the plate behind it.
        listOf(palette.capHigh, palette.capMid, palette.capBase).forEach { stop ->
            assertTrue(
                "cap stop $stop must be lighter than the plate ${palette.plate}",
                stop.luminanceish() > palette.plate.luminanceish(),
            )
        }
    }

    /** Plain average-channel brightness -- enough to assert "lighter than", without pulling in a
     *  colour-science dependency for a test that only needs an ordering. */
    private fun Color.luminanceish(): Float = (red + green + blue) / 3f

    @Test
    fun `the skin decision itself is a pure function of the surface it is given`() {
        assertTrue(tactileIsDarkSkin(Color(0xFF16161A)))
        assertTrue(tactileIsDarkSkin(Color.Black))
        assertFalse(tactileIsDarkSkin(Color(0xFFF9F9FA)))
        assertFalse(tactileIsDarkSkin(Color.White))
    }
}
