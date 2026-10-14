package io.github.mbaliga.fylz.ui.theme

import androidx.compose.ui.graphics.toArgb
import io.github.mbaliga.fylz.crashStyleFor
import io.github.mbaliga.fylz.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The accent choice reaches the theme: picking each bundled accent yields a different primary (and
 * a different set of derived container/inverse tokens) in both halves, the persisted choice is
 * what a fresh store resolves after a restart, and the crash-recovery screen follows it too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccentThemingTest {

    private fun scheme(id: String, dark: Boolean) = BuiltInThemePresets.load(id).getOrThrow().let { palette ->
        if (dark) palette.dark.toColorScheme(dark = true, inversePrimary = palette.light.primary)
        else palette.light.toColorScheme(dark = false, inversePrimary = palette.dark.primary)
    }

    @Test
    fun `every bundled accent yields a different primary in both halves`() {
        assertTrue("expected at least three bundled accents", BuiltInThemePresets.ids.size >= 3)
        for (dark in listOf(false, true)) {
            val primaries = BuiltInThemePresets.ids.map { scheme(it, dark).primary }
            assertEquals("duplicate primary among ${BuiltInThemePresets.ids} (dark=$dark)", primaries.size, primaries.toSet().size)
        }
    }

    @Test
    fun `every bundled accent drives the derived accent-role tokens, none left at baseline purple`() {
        val baseline = listOf(0xFFEADDFF, 0xFFE8DEF8, 0xFFFFD8E4, 0xFFD0BCFF, 0xFF6750A4, 0xFF4F378B).map { androidx.compose.ui.graphics.Color(it) }
        for (id in BuiltInThemePresets.ids) {
            for (dark in listOf(false, true)) {
                val s = scheme(id, dark)
                for (token in listOf(s.primaryContainer, s.secondaryContainer, s.tertiaryContainer, s.inversePrimary, s.primary)) {
                    assertTrue("$id dark=$dark kept a baseline-purple token", token !in baseline)
                }
            }
            // inversePrimary is the *other* half's primary: the accent as it reads on the inverse bar.
            val palette = BuiltInThemePresets.load(id).getOrThrow()
            assertEquals(palette.dark.primary, scheme(id, dark = false).inversePrimary)
            assertEquals(palette.light.primary, scheme(id, dark = true).inversePrimary)
        }
        assertEquals(
            "containers differ between accents",
            BuiltInThemePresets.ids.size,
            BuiltInThemePresets.ids.map { scheme(it, false).primaryContainer }.toSet().size,
        )
    }

    @Test
    fun `the persisted accent is restored by a fresh store and resolves to that accent's primary`() {
        for (id in BuiltInThemePresets.ids) {
            ThemePaletteStore(RuntimeEnvironment.getApplication()).setBuiltIn(id)
            val restored = ThemePaletteStore(RuntimeEnvironment.getApplication())
            assertEquals(ActiveTheme.BuiltIn(id), restored.selection.value)
            assertEquals(
                scheme(id, dark = false).primary,
                restored.resolved.value.light.toColorScheme(dark = false).primary,
            )
        }
    }

    @Test
    fun `the light-dark-system mode is persisted with the accent`() {
        assertEquals(ThemeMode.SYSTEM, ThemePaletteStore(RuntimeEnvironment.getApplication()).mode.value)
        ThemePaletteStore(RuntimeEnvironment.getApplication()).setMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemePaletteStore(RuntimeEnvironment.getApplication()).mode.value)
    }

    @Test
    fun `the crash-recovery accent follows the chosen palette, not a fixed one`() {
        val styles = BuiltInThemePresets.ids.map { crashStyleFor(BuiltInThemePresets.load(it).getOrThrow()) }
        assertEquals(styles.size, styles.map { it.accentLight }.toSet().size)
        assertEquals(styles.size, styles.map { it.accentDark }.toSet().size)
        val ink = BuiltInThemePresets.load("ink").getOrThrow()
        assertEquals(ink.light.primary.toArgb(), crashStyleFor(ink).accentLight)
        assertNotEquals(crashStyleFor(BuiltInThemePresets.load("moss").getOrThrow()), crashStyleFor(ink))
    }
}
