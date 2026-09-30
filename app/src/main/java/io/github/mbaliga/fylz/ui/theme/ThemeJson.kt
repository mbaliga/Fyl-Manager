package io.github.mbaliga.fylz.ui.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONException
import org.json.JSONObject

/**
 * Owner request, phrased once for every app in this family: "All apps need a JSON-based way to
 * theme them. User should be able to preview the JSON's content as plain text so they are never
 * caught unawares." This is the schema and the strict parser for Fylz's half of that -- the exact
 * shape recorded in docs/agent/REVIEW_QUEUE.md, shared with Fotoz's own JSON theming so the same
 * feature reads the same way in both apps:
 *
 * ```json
 * {
 *   "version": 1,
 *   "name": "My Theme",
 *   "colors": {
 *     "light": { "primary": "#RRGGBB", "secondary": "#RRGGBB", "tertiary": "#RRGGBB", "background": "#RRGGBB", "surface": "#RRGGBB", "surfaceVariant": "#RRGGBB", "onBackground": "#RRGGBB", "onSurface": "#RRGGBB" },
 *     "dark":  { ...same keys... }
 *   }
 * }
 * ```
 *
 * [ThemeMode] (system/light/dark) stays the separate light-vs-dark *selector*; a [ThemePalette]
 * carries both halves so [ThemeMode] just picks which one Compose's `ColorScheme` is built from
 * (see `FylzTheme.kt`).
 */
data class PaletteColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
)

data class ThemePalette(val name: String, val light: PaletteColors, val dark: PaletteColors)

/** Thrown only inside [ThemeJson.parse]'s own `runCatching` -- callers see it as a failed
 * [Result], never as an uncaught exception. Every message names the exact bad field (its JSON
 * path), per the owner's "never caught unawares" -- a vague "invalid theme" would not tell a user
 * pasting their own JSON which line to fix. */
class ThemeJsonException(message: String) : IllegalArgumentException(message)

object ThemeJson {
    const val SUPPORTED_VERSION = 1

    private val COLOR_KEYS = listOf(
        "primary", "secondary", "tertiary", "background", "surface", "surfaceVariant", "onBackground", "onSurface",
    )
    private val HEX6 = Regex("^#[0-9A-Fa-f]{6}$")
    private val HEX8 = Regex("^#[0-9A-Fa-f]{8}$")

    /** Never throws: malformed input (not JSON at all, a missing key, a wrong version, a bad hex
     * value) comes back as a failed [Result] carrying a [ThemeJsonException] whose message names
     * the exact field, per "never a crash, never a partial application" -- nothing is read out of
     * [json] into a caller-visible value unless every field validates. */
    fun parse(json: String): Result<ThemePalette> = runCatching {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ThemeJsonException("Not valid JSON: ${e.message}")
        }
        if (!root.has("version")) throw ThemeJsonException("\"version\" is required")
        val version = root.optInt("version", -1)
        if (version != SUPPORTED_VERSION) {
            throw ThemeJsonException("\"version\" must be $SUPPORTED_VERSION, found ${root.opt("version")}")
        }
        if (!root.has("name")) throw ThemeJsonException("\"name\" is required")
        val name = root.optString("name", "")
        val colors = root.optJSONObject("colors") ?: throw ThemeJsonException("\"colors\" is required")
        val light = parseColorSet(colors, "light")
        val dark = parseColorSet(colors, "dark")
        ThemePalette(name, light, dark)
    }

    private fun parseColorSet(colors: JSONObject, mode: String): PaletteColors {
        val set = colors.optJSONObject(mode) ?: throw ThemeJsonException("\"colors.$mode\" is required")
        val values = COLOR_KEYS.associateWith { key -> parseColor(set, mode, key) }
        return PaletteColors(
            primary = values.getValue("primary"),
            secondary = values.getValue("secondary"),
            tertiary = values.getValue("tertiary"),
            background = values.getValue("background"),
            surface = values.getValue("surface"),
            surfaceVariant = values.getValue("surfaceVariant"),
            onBackground = values.getValue("onBackground"),
            onSurface = values.getValue("onSurface"),
        )
    }

    private fun parseColor(set: JSONObject, mode: String, key: String): Color {
        val path = "colors.$mode.$key"
        if (!set.has(key)) throw ThemeJsonException("\"$path\" is required")
        val raw = set.optString(key, "")
        val hex = when {
            HEX8.matches(raw) -> raw.substring(1)
            HEX6.matches(raw) -> "FF" + raw.substring(1)
            else -> throw ThemeJsonException("\"$path\" is not a valid #RRGGBB or #AARRGGBB colour: \"$raw\"")
        }
        return Color(hex.toLong(16).toInt())
    }
}
