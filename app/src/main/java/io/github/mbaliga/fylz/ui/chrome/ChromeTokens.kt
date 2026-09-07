package io.github.mbaliga.fylz.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
 * The smallest dimension anywhere in this chrome at [ChromeScale.DEFAULT]: the add-tab button's
 * width and the selection row's height, both 44dp. [ChromeScale.MAX] is defined as exactly the
 * factor that lifts THIS to the touch floor, so every larger control clears it by construction
 * rather than by a hand-checked table -- see `ChromeScaleTest`, which fails if a new control is
 * ever added below this figure without the constant moving with it.
 */
private const val SMALLEST_CHROME_DP = 44f

/** The floor a thumb-sized control owes, per DESIGN.md and Android's own guidance. */
const val ChromeTouchFloorDp: Float = 48f

/**
 * How large the bottom chrome draws itself, under the user's control.
 *
 * The owner's ask, verbatim: *"Let the UI chrome scale have user control, where default is the
 * current size and max bumps the values up to the touch targets."* So [DEFAULT] is the Figma read
 * to the dp -- nothing moves for anyone who never opens the setting -- and [MAX] is not a taste
 * knob but a derived figure: the exact factor at which the smallest control in this file's chrome
 * reaches [ChromeTouchFloorDp].
 *
 * Two stops rather than a slider on purpose. The whole usable range here is 9%, because the
 * default geometry already sits within a few dp of the floor; a continuous control over that span
 * would offer a dozen indistinguishable settings and one real decision.
 */
enum class ChromeScale(val factor: Float) {
    /** The export's own geometry, untouched. */
    DEFAULT(1f),

    /** Every chrome control clears the 48dp touch floor. */
    MAX(ChromeTouchFloorDp / SMALLEST_CHROME_DP),
}

/**
 * The scale in force for this subtree. A composition local rather than a parameter for the same
 * reason [io.github.mbaliga.fylz.ui.components.LocalIconStyle] is one: the tab band, the selection
 * row and the actions bar are each reached down a different call chain, and threading one display
 * preference through all three to reach a leaf is exactly what locals are for.
 */
val LocalChromeScale: ProvidableCompositionLocal<ChromeScale> = compositionLocalOf { ChromeScale.DEFAULT }

/** One base dimension at the scale in force. */
@Composable
internal fun Dp.scaledForChrome(): Dp = this * LocalChromeScale.current.factor

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
