package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.aarso.hyle.tokens.HyleTokens

/**
 * The three bevel shades the kit's own recipe (below) needs that `HyleTokens` has no field for
 * yet -- checked against `hyle-design-system/hyle/src/main/java/dev/aarso/hyle/tokens/
 * HyleTokens.kt` at Build 11.5: `controlSurface`/`Raised`/`High`/`Groove`/`Edge`/`Rim`/`RimSoft`/
 * `Indicator` all exist, a dedicated "inner cavity" dark and "bottom lip" light do not. They are
 * plain alpha-black/white rather than a tint borrowed from any one skin because both the RAISED
 * CAP and RECESSED GROOVE recipes use them as pure shading over whatever base colour the skin
 * already supplies -- a shadow doesn't have a hue of its own.
 *
 * Upstream TODO: once this kit is owner-verified on a device (the module's own stated graduation
 * path into `:hyle`), promote these into a `*.json` token file under `hyle-design-system/tokens/`
 * as `controlBevelDark` / `controlBevelDark2` / `controlBevelLip` (or similar) so `:hyle` itself
 * stops needing three ad-hoc constants a Fylz-local file invented, and any other Hyle consumer
 * gets the same bevel vocabulary for free.
 */
internal val TactileBevelDark: Color = Color.Black.copy(alpha = 0.88f)
internal val TactileBevelDark2: Color = Color.Black.copy(alpha = 0.55f)
internal val TactileBevelLip: Color = Color.White.copy(alpha = 0.12f)

/** The RAISED CAP recipe's 1dp outer rim ring -- same value both skins; see [tactilePalette]. */
internal val TactileCapRim: Color = Color(HyleTokens.Color.controlRim)

/** The RAISED CAP recipe's ~1.5dp top inner lip -- same value both skins; see [tactilePalette]. */
internal val TactileCapRimSoft: Color = Color(HyleTokens.Color.controlRimSoft)

/**
 * One resolved palette for both tactile skins, so every composable in this package reads through
 * [tactilePalette] instead of branching on `isSystemInDarkTheme()` (or `HyleTokens`) itself.
 *
 * LIGHT is the owner's reference frames verbatim (this app is light-first): a light-gray plate,
 * near-black keycaps, a near-white field body. DARK maps the same three recipes -- RAISED CAP,
 * RECESSED GROOVE, PRESS -- onto `HyleTokens`' jet `control*` family, per the spec's "Dark theme:
 * invert sensibly using HyleTokens control* (jet) palette" note. [onCap]/[onPlate] are
 * deliberately equal in dark (both full-strength ink): dimming the inactive side's *text* below a
 * `4.5:1`-safe alpha would trade one hard rule (never colour-alone) for another (text contrast);
 * cap position + [TactileCapRim]/[TactileCapRimSoft]/glint is already the state's second channel,
 * so the ink itself doesn't need to carry it too.
 */
data class TactilePalette(
    val isDark: Boolean,
    /** Toggle/switch/slider/button-secondary container. */
    val plate: Color,
    /** Plate bevel channel: a thin inner highlight along the plate's own top/left edge. */
    val plateHighlight: Color,
    /** RAISED CAP gradient, 158deg: [capHigh] (top-left) -> [capMid] (~53%) -> [capBase]. */
    val capHigh: Color,
    val capMid: Color,
    val capBase: Color,
    /** Ink drawn on a raised cap (PRIMARY/DESTRUCTIVE button label, active toggle segment). */
    val onCap: Color,
    /** Ink drawn directly on the plate (SECONDARY button label, inactive toggle segment). */
    val onPlate: Color,
    /** The glint's stroke + dot colour -- white in both skins. */
    val glint: Color,
    /** RECESSED GROOVE flat fill -- the LIGHT skin's field body (see [fieldGradientTop] for dark). */
    val fieldFill: Color,
    /** RECESSED GROOVE deep-variant gradient, dark skin only: [fieldGradientTop] (black) ->
     *  [fieldGradientMid] (groove, ~46%) -> [fieldGradientBase] (surface-raised). */
    val fieldGradientTop: Color,
    val fieldGradientMid: Color,
    val fieldGradientBase: Color,
    /** Neutral hairline/edge tint for an idle border, independent of
     *  [io.github.mbaliga.fylz.ui.theme.hairline]'s `MaterialTheme.colorScheme` read so this kit
     *  does not require a `FylzTheme` composition it might be previewed outside of. */
    val edge: Color,
    /** Idle-state slash/tick/dimple tint -- `HyleTokens.controlIndicator`, both skins. */
    val indicatorIdle: Color,
    /** Selected/focus accent -- `HyleTokens` violet family, both skins. */
    val accent: Color,
    /** Error accent for DECORATIVE marks only (border, slash-tick, dot, asterisk) -- `HyleTokens`
     *  signal danger, NOT the chrome's pure-red trash tab. */
    val danger: Color,
    /** Error accent for actual TEXT (an error message, a DESTRUCTIVE button's own cap gradient
     *  wherever the label sits over it) -- deliberately a step darker than [danger]. `#E5564B`
     *  itself measures ~3.3:1 against this kit's near-white field/plate, short of the hard-rule
     *  4.5:1 floor for body text; the plain marks above are exempt (a border or a dot is not
     *  text), but a message the user has to read is not. */
    val dangerText: Color,
)

/**
 * True when [surface] is dark enough that the kit should paint its jet skin. Split out as a pure
 * function so the choice is unit-testable without a Compose harness.
 */
internal fun tactileIsDarkSkin(surface: Color): Boolean = surface.luminance() < 0.5f

/**
 * Resolves [TactilePalette] against the APP's own resolved theme, not the system's.
 *
 * This deliberately does NOT read `isSystemInDarkTheme()`. Fylz has its own `ThemeMode`
 * (LIGHT/DARK/SYSTEM) and its own per-`ThemeStyle` colour schemes, so a user running the app in
 * Dark on a Light phone -- or in Light on a Dark phone -- got a kit skinned for the wrong one:
 * light-gray plates and WHITE field bodies sitting on the app's dark surfaces, with the near-white
 * body text of a dark scheme printed on top of them. That is an illegibility bug, not a cosmetic
 * one, and the Build-11.5 kit shipped with it. Reading the luminance of the scheme `FylzTheme`
 * actually resolved follows every one of those inputs at once -- theme mode, theme style, dynamic
 * colour -- with no new plumbing to keep in sync.
 */
@Composable
fun tactilePalette(): TactilePalette {
    val dark = tactileIsDarkSkin(MaterialTheme.colorScheme.surface)
    val accent = Color(HyleTokens.Color.colorPaletteAccentViolet)
    val danger = Color(HyleTokens.Color.colorPaletteSignalDanger)
    val indicator = Color(HyleTokens.Color.controlIndicator)
    return if (dark) {
        TactilePalette(
            isDark = true,
            plate = Color(HyleTokens.Color.controlSurface),
            plateHighlight = Color(HyleTokens.Color.controlRimSoft),
            // The cap's DARKEST stop is controlSurfaceRaised, not controlSurface: bottoming the
            // gradient out on the plate's own colour made the lower half of every dark keycap
            // melt into the plate behind it, so a selected segment read as a smudge rather than a
            // key. A raised thing stays lighter than the well it sits in, all the way down.
            capHigh = Color(HyleTokens.Color.controlSurfaceHigh),
            capMid = Color(0xFF26262D),
            capBase = Color(HyleTokens.Color.controlSurfaceRaised),
            onCap = Color(HyleTokens.Color.colorTextPrimary),
            onPlate = Color(HyleTokens.Color.colorTextPrimary),
            glint = Color.White,
            fieldFill = Color(HyleTokens.Color.controlGroove),
            fieldGradientTop = Color.Black,
            fieldGradientMid = Color(HyleTokens.Color.controlGroove),
            fieldGradientBase = Color(HyleTokens.Color.controlSurfaceRaised),
            edge = Color(HyleTokens.Color.controlEdge),
            indicatorIdle = indicator,
            accent = accent,
            danger = danger,
            // The dark skin's own near-black grooves/plates already push #E5564B's contrast well
            // past 4.5:1 (its lightness is what fails against a near-WHITE ground, not a dark
            // one), so text can safely share the same value as the decorative marks here.
            dangerText = danger,
        )
    } else {
        TactilePalette(
            isDark = false,
            plate = Color(0xFFDEDEDE),
            plateHighlight = Color(0xFFFFFFFF),
            capHigh = Color(0xFF242428),
            capMid = Color(0xFF19191C),
            capBase = Color(0xFF0E0E10),
            onCap = Color(0xFFFFFFFF),
            onPlate = Color(0xFF1B1B1F),
            glint = Color.White,
            // WHITE, not the near-white #F7F7F8 the first pass used: this app's own light ground
            // is already #F9F9FA, so a #F7F7F8 field body differed from the page behind it by two
            // levels of luminance and the fields read as bare floating outlines with no body at
            // all. The field has to be the LIGHTER figure against the near-white ground -- which
            // is also how the owner's state-sheet frame reads, white cards on a light sheet.
            fieldFill = Color(0xFFFFFFFF),
            // Light's field is a flat body, not a 3-stop groove -- these three collapse to the
            // same flat fill so tactileFieldGroove() can share one gradient-brush code path
            // instead of branching its whole implementation on isDark.
            fieldGradientTop = Color(0xFFFFFFFF),
            fieldGradientMid = Color(0xFFFFFFFF),
            fieldGradientBase = Color(0xFFFFFFFF),
            // Dark enough to actually register as a hairline against BOTH the white field body
            // and the #DEDEDE plate; the previous #DCDCDF vanished against the plate entirely.
            edge = Color(0xFFC9C9D1),
            // HyleTokens' own controlIndicator is tuned for the jet skin, where it sits on
            // near-black; dropped straight onto a white field body it reads as a heavy black stub
            // rather than the mid-gray idle tick the owner's state sheet shows.
            indicatorIdle = Color(0xFF8E8E97),
            accent = accent,
            danger = danger,
            // Material 3's own baseline light-scheme error red (~6:1 on this kit's near-white
            // ground) -- a deliberately different, darker red than the decorative `danger` above,
            // not a duplicate-by-mistake.
            dangerText = Color(0xFFB3261E),
        )
    }
}
