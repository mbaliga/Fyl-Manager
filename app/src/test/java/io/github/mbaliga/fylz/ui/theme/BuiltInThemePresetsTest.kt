package io.github.mbaliga.fylz.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every bundled preset is a real, valid [ThemeJson] document, not just a string that happens to
 * sit next to the code that reads it. Pure, no Robolectric needed -- see
 * `BuiltInThemePresets.kt`'s own doc for why these are embedded Kotlin constants rather than
 * `context.assets` files. */
class BuiltInThemePresetsTest {

    @Test
    fun `every bundled id loads and parses into a named palette`() {
        for (id in BuiltInThemePresets.ids) {
            val palette = BuiltInThemePresets.load(id).getOrThrow()
            assertEquals(BuiltInThemePresets.titleFor(id), palette.name)
        }
    }

    @Test
    fun `a preset that does not exist fails rather than crashing`() {
        assertTrue(BuiltInThemePresets.load("does-not-exist").isFailure)
    }

    @Test
    fun `the default id is one of the bundled ids`() {
        assertTrue(BuiltInThemePresets.DEFAULT_ID in BuiltInThemePresets.ids)
    }
}
