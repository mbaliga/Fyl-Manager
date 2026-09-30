package io.github.mbaliga.fylz.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The three bundled JSON theme presets (owner request: "convert whatever fixed colors exist
 * today into 2-3 bundled preset JSON files") -- `Moss` (today's only default, unchanged in
 * appearance), `Ink` and `Clay`, converted from the `AccentPreset` colours `FylzTheme.kt` used to
 * hardcode. The fourth pre-existing accent, `Electric`, was dropped from the bundled set per the
 * "2-3" guidance -- see docs/agent/REVIEW_QUEUE.md.
 *
 * Each preset is a literal JSON document -- a real, complete, independently valid instance of
 * the exact schema [ThemeJson] parses, readable and editable as such -- embedded as a Kotlin
 * string constant rather than a file under `app/src/main/assets/`. That is a deliberate deviation
 * from "bundled preset JSON files" read the most literal way (a real `assets/` directory): doing
 * it that way was tried first, but `context.assets.open(...)` needs
 * `testOptions.unitTests.isIncludeAndroidResources = true` for Robolectric to see real assets at
 * all, and turning that flag on in this project changes which native SQLite path
 * `FylzDatabase`'s own, wholly unrelated Robolectric tests exercise -- five of them regressed the
 * moment the flag was added, reproducibly, on a clean run. Embedding the JSON removes the
 * `Context`/`AssetManager` dependency (and this loader's need for a `Context` argument at all),
 * so it needs no Robolectric shadow and cannot affect anything else in the app. See
 * docs/agent/REVIEW_QUEUE.md for the fuller account.
 */
object BuiltInThemePresets {
    const val DEFAULT_ID = "moss"
    val ids: List<String> = listOf("moss", "ink", "clay")

    fun titleFor(id: String): String = id.replaceFirstChar { it.uppercaseChar() }

    private val json: Map<String, String> = mapOf(
        "moss" to MOSS_JSON,
        "ink" to INK_JSON,
        "clay" to CLAY_JSON,
    )

    fun load(id: String): Result<ThemePalette> = runCatching {
        val source = json[id] ?: error("No bundled preset named \"$id\"")
        ThemeJson.parse(source).getOrThrow()
    }

    /** Only reached if a bundled id's own JSON somehow fails to parse -- should never happen for
     * a shipped id (each one is exercised by [io.github.mbaliga.fylz.ui.theme.BuiltInThemePresetsTest]),
     * but a theme failing to load must never crash the app (the same "never a crash" contract
     * [ThemeJson] holds for a user's own custom JSON). Moss's own colours, so a fallback is
     * visually identical to the pre-feature default. */
    fun fallback(): ThemePalette = ThemePalette(
        name = "Moss",
        light = PaletteColors(
            primary = Color(0xFF315F49), secondary = Color(0xFF52645A), tertiary = Color(0xFF3B5B7A),
            background = Color(0xFFFDFEFD), surface = Color(0xFFFAFCFA), surfaceVariant = Color(0xFFDDE4DD),
            onBackground = Color(0xFF1A1C1A), onSurface = Color(0xFF1A1C1A),
        ),
        dark = PaletteColors(
            primary = Color(0xFF9BD3B3), secondary = Color(0xFFB9CCBF), tertiary = Color(0xFFA9C7E4),
            background = Color(0xFF0E1110), surface = Color(0xFF111413), surfaceVariant = Color(0xFF3C443F),
            onBackground = Color(0xFFE2E3E1), onSurface = Color(0xFFE2E3E1),
        ),
    )
}

private const val MOSS_JSON = """
{
  "version": 1,
  "name": "Moss",
  "colors": {
    "light": {
      "primary": "#315F49",
      "secondary": "#52645A",
      "tertiary": "#3B5B7A",
      "background": "#FDFEFD",
      "surface": "#FAFCFA",
      "surfaceVariant": "#DDE4DD",
      "onBackground": "#1A1C1A",
      "onSurface": "#1A1C1A"
    },
    "dark": {
      "primary": "#9BD3B3",
      "secondary": "#B9CCBF",
      "tertiary": "#A9C7E4",
      "background": "#0E1110",
      "surface": "#111413",
      "surfaceVariant": "#3C443F",
      "onBackground": "#E2E3E1",
      "onSurface": "#E2E3E1"
    }
  }
}
"""

private const val INK_JSON = """
{
  "version": 1,
  "name": "Ink",
  "colors": {
    "light": {
      "primary": "#3E5268",
      "secondary": "#565E68",
      "tertiary": "#6B4D68",
      "background": "#FDFEFE",
      "surface": "#FAFBFC",
      "surfaceVariant": "#DEE3E9",
      "onBackground": "#1B1C1E",
      "onSurface": "#1B1C1E"
    },
    "dark": {
      "primary": "#A8C9EA",
      "secondary": "#BEC7D2",
      "tertiary": "#D6B8D9",
      "background": "#0E1013",
      "surface": "#121417",
      "surfaceVariant": "#3F444B",
      "onBackground": "#E3E4E6",
      "onSurface": "#E3E4E6"
    }
  }
}
"""

private const val CLAY_JSON = """
{
  "version": 1,
  "name": "Clay",
  "colors": {
    "light": {
      "primary": "#84523D",
      "secondary": "#705A50",
      "tertiary": "#55643A",
      "background": "#FFFBF9",
      "surface": "#FDF7F4",
      "surfaceVariant": "#EBDED6",
      "onBackground": "#201A17",
      "onSurface": "#201A17"
    },
    "dark": {
      "primary": "#FFB69A",
      "secondary": "#E1BFB1",
      "tertiary": "#C3D29A",
      "background": "#140F0D",
      "surface": "#1B1512",
      "surfaceVariant": "#4A3F38",
      "onBackground": "#EDE0DA",
      "onSurface": "#EDE0DA"
    }
  }
}
"""
