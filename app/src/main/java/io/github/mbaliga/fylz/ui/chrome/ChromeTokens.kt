package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.mbaliga.fylz.R

/**
 * The bottom tab band, the selection row and the top actions bar are a literal Figma read: pure
 * black, pure white, one pure red, no alpha on the ink. That is deliberately not
 * [io.github.mbaliga.fylz.ui.cluster.InkSurface]'s translucent family -- this file is additive to
 * that palette, not a replacement for it. Drag-time bulges and preview surfaces keep their own
 * tokens; these four are for the chrome band only.
 */
val ChromeInk: Color = Color(0xFF000000)
val ChromeOn: Color = Color(0xFFFFFFFF)
val ChromeInactive: Color = Color(0xFF424242)
val ChromeDanger: Color = Color(0xFFFF0000)

/** The "SELECTED" label rides on-ink white at this alpha; the count beside it stays full strength. */
const val ChromeSelectedLabelAlpha: Float = 0.5f

/**
 * Hyle Grotesk Classic at the three weights the export actually uses, independent of
 * [io.github.mbaliga.fylz.ui.theme.FylzTheme]'s own typography. This chrome is a fixed,
 * theme-independent read of one Figma export -- the same reasoning that keeps
 * `LandingSplash.kt`'s wordmark on its own bundled Hyle face rather than the theme's chosen
 * family: a one-time flourish, not a font swap the rest of the app inherits. In particular it
 * must NOT fall back to [io.github.mbaliga.fylz.ui.theme.ThemeStyle.monospace]'s CLI/Vintage
 * override -- the brutalist tab band reads correctly in exactly one face.
 *
 * Build 11.5 swaps the underlying font from Space Grotesk (a stock outside import) to Hyle
 * Grotesk Classic (the Hyle-native face built on Space Grotesk, see docs/FONTS.md) -- the owner's
 * "only Hyle fonts" note applies here too. Geometry/colours are the unchanged Figma read; only
 * the font resource changed.
 */
@Composable
fun chromeFontFamily(): FontFamily = remember {
    FontFamily(
        Font(R.font.hyle_grotesk_classic_regular, FontWeight.Normal),
        Font(R.font.hyle_grotesk_classic_medium, FontWeight.Medium),
        Font(R.font.hyle_grotesk_classic_bold, FontWeight.Bold),
    )
}
