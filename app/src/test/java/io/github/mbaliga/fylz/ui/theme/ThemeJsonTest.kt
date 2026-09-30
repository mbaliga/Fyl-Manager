package io.github.mbaliga.fylz.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThemeJson.parse]: the exact schema from docs/agent/REVIEW_QUEUE.md, and every malformed shape
 * the owner's brief named by name -- missing key, bad hex, wrong version, not JSON at all -- each
 * refused with a specific, field-naming error and never a crash.
 */
class ThemeJsonTest {

    private val validJson = """
        {
          "version": 1,
          "name": "My Theme",
          "colors": {
            "light": {
              "primary": "#112233", "secondary": "#445566", "tertiary": "#778899",
              "background": "#FFFFFF", "surface": "#FAFAFA", "surfaceVariant": "#EEEEEE",
              "onBackground": "#000000", "onSurface": "#111111"
            },
            "dark": {
              "primary": "#AABBCC", "secondary": "#CCDDEE", "tertiary": "#DDEEFF",
              "background": "#000000", "surface": "#0A0A0A", "surfaceVariant": "#111111",
              "onBackground": "#FFFFFF", "onSurface": "#EEEEEE"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `a valid theme round-trips into a real ColorScheme`() {
        val palette = ThemeJson.parse(validJson).getOrThrow()
        assertEquals("My Theme", palette.name)
        assertEquals(Color(0xFF112233), palette.light.primary)
        assertEquals(Color(0xFF445566), palette.light.secondary)
        assertEquals(Color(0xFFAABBCC), palette.dark.primary)
        assertEquals(Color(0xFF000000), palette.dark.background)

        val lightScheme = palette.light.toColorScheme(dark = false)
        assertEquals(Color(0xFF112233), lightScheme.primary)
        assertEquals(Color(0xFFFFFFFF), lightScheme.background)
        assertEquals(Color(0xFF111111), lightScheme.onSurface)

        val darkScheme = palette.dark.toColorScheme(dark = true)
        assertEquals(Color(0xFFAABBCC), darkScheme.primary)
        assertEquals(Color(0xFF000000), darkScheme.background)
    }

    @Test
    fun `an eight-digit AARRGGBB colour is accepted, alpha included`() {
        val json = validJson.replace("\"primary\": \"#112233\"", "\"primary\": \"#80112233\"")
        val palette = ThemeJson.parse(json).getOrThrow()
        assertEquals(Color(0x80112233), palette.light.primary)
    }

    @Test
    fun `not valid JSON at all is refused, never a crash`() {
        val result = ThemeJson.parse("this is not { json")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ThemeJsonException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Not valid JSON"))
    }

    @Test
    fun `an empty string is refused, never a crash`() {
        assertTrue(ThemeJson.parse("").isFailure)
    }

    @Test
    fun `a wrong version is refused and names the field`() {
        val json = validJson.replace("\"version\": 1", "\"version\": 2")
        val result = ThemeJson.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("version"))
    }

    @Test
    fun `a missing version is refused and names the field`() {
        val json = validJson.replaceFirst("\"version\": 1,", "")
        val result = ThemeJson.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("version"))
    }

    @Test
    fun `a missing top-level colors key is refused and names it`() {
        val json = """{"version": 1, "name": "X"}"""
        val result = ThemeJson.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("colors"))
    }

    @Test
    fun `a missing colors_dark key is refused and names it`() {
        // "light" must be fully valid here, or the missing-field check for IT would fire first
        // and this test would never actually reach the "dark" section it means to exercise.
        val json = """
            {
              "version": 1,
              "name": "X",
              "colors": {
                "light": {
                  "primary": "#112233", "secondary": "#445566", "tertiary": "#778899",
                  "background": "#FFFFFF", "surface": "#FAFAFA", "surfaceVariant": "#EEEEEE",
                  "onBackground": "#000000", "onSurface": "#111111"
                }
              }
            }
        """.trimIndent()
        val result = ThemeJson.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("colors.dark"))
    }

    @Test
    fun `a missing color field is refused and names its full path`() {
        val json = validJson.replace("\"primary\": \"#112233\", ", "")
        val result = ThemeJson.parse(json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("colors.light.primary"))
    }

    @Test
    fun `a bad hex value is refused and names its full path`() {
        val json = validJson.replace("\"primary\": \"#112233\"", "\"primary\": \"red\"")
        val result = ThemeJson.parse(json)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("colors.light.primary"))
        assertTrue(message.contains("red"))
    }

    @Test
    fun `a hex value missing the leading hash is refused`() {
        val json = validJson.replace("\"primary\": \"#112233\"", "\"primary\": \"112233\"")
        assertTrue(ThemeJson.parse(json).isFailure)
    }

    @Test
    fun `a hex value with the wrong digit count is refused`() {
        val json = validJson.replace("\"primary\": \"#112233\"", "\"primary\": \"#1122\"")
        assertTrue(ThemeJson.parse(json).isFailure)
    }

    @Test
    fun `a hex value with a non-hex digit is refused`() {
        val json = validJson.replace("\"primary\": \"#112233\"", "\"primary\": \"#11223G\"")
        assertTrue(ThemeJson.parse(json).isFailure)
    }
}
