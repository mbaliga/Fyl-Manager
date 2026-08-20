package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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

/** Resolves [TactilePalette] for the current system dark/light state. */
@Composable
fun tactilePalette(): TactilePalette {
    val dark = isSystemInDarkTheme()
    val accent = Color(HyleTokens.Color.colorPaletteAccentViolet)
    val danger = Color(HyleTokens.Color.colorPaletteSignalDanger)
    val indicator = Color(HyleTokens.Color.controlIndicator)
    return if (dark) {
        TactilePalette(
            isDark = true,
            plate = Color(HyleTokens.Color.controlSurface),
            plateHighlight = Color(HyleTokens.Color.controlRimSoft),
            capHigh = Color(HyleTokens.Color.controlSurfaceHigh),
            capMid = Color(HyleTokens.Color.controlSurfaceRaised),
            capBase = Color(HyleTokens.Color.controlSurface),
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
            fieldFill = Color(0xFFF7F7F8),
            // Light's field is a flat body, not a 3-stop groove -- these three collapse to the
            // same flat fill so tactileFieldGroove() can share one gradient-brush code path
            // instead of branching its whole implementation on isDark.
            fieldGradientTop = Color(0xFFF7F7F8),
            fieldGradientMid = Color(0xFFF7F7F8),
            fieldGradientBase = Color(0xFFF7F7F8),
            edge = Color(0xFFDCDCDF),
            indicatorIdle = indicator,
            accent = accent,
            danger = danger,
            // Material 3's own baseline light-scheme error red (~6:1 on this kit's near-white
            // ground) -- a deliberately different, darker red than the decorative `danger` above,
            // not a duplicate-by-mistake.
            dangerText = Color(0xFFB3261E),
        )
    }
}
