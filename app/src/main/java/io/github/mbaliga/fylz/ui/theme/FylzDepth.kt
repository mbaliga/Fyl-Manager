package io.github.mbaliga.fylz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.aarso.hyle.tokens.HyleTokens

/**
 * Hyle's flat radius vocabulary (Build 11.5): 4 / 8 / 12 / 16dp, capping at 16 -- no 20dp cards,
 * no 14, no 6. Read straight off [HyleTokens.Dimension] (`radiusSm`/`radiusMd`/`radiusLg`/
 * `radiusXl`, all plain Int dp) rather than re-declaring the numbers here, so this object is a
 * thin, genuinely-consuming wrapper around the shared token module (`dev.aarso:hyle:0.2.0`) and
 * not a second source of truth that can drift from it. There is deliberately no `RadiusFull` --
 * fully-round shapes (the count-chip pill, the selection pill) use `CircleShape` /
 * `RoundedCornerShape(50)` directly at the call site, the same way `HyleTokens.Dimension
 * .radiusFull` (9999) is a sentinel rather than a real corner radius.
 */
object FylzGeometry {
    val RadiusSm = HyleTokens.Dimension.radiusSm.dp
    val RadiusMd = HyleTokens.Dimension.radiusMd.dp
    val RadiusLg = HyleTokens.Dimension.radiusLg.dp
    val RadiusXl = HyleTokens.Dimension.radiusXl.dp
}

/** The three Hyle shadow steps -- see [softShadow] for the elevation/tint each maps to. */
enum class ShadowLevel { SM, MD, LG }

/**
 * `hyle-design-system/tokens/shadow.json` -- the source of truth this maps from -- specifies CSS
 * box-shadows tinted `rgba(16, 24, 40, _)`, not pure black:
 * ```
 * sm  0 1px 2px    rgba(16, 24, 40, 0.06)
 * md  0 4px 8px -2px rgba(16, 24, 40, 0.10)
 * lg  0 12px 24px -6px rgba(16, 24, 40, 0.14)
 * ```
 * `#101828` is the tint kept below. The alphas are not carried over literally, though: a CSS
 * box-shadow spreads its alpha across a soft blur radius many px wide, while
 * [androidx.compose.ui.draw.shadow] paints a RenderNode shadow whose falloff is much steeper for
 * the same elevation, so the token's 6/10/14% reads as barely-there once ported straight across.
 * The alphas below are boosted (roughly x2.5-3) to land at the same *soft, close* appearance the
 * reference frames show, not to match the token file byte-for-byte.
 */
private val ShadowTint = Color(0xFF101828)

/**
 * A soft, close shadow along the Hyle depth steps -- ambient and spot colour both use the same
 * low-alpha tint so the result reads as one soft haze rather than Material's harder directional
 * default. `composed {}` so the elevation/tint pair only gets computed once per call site, the
 * same convention [androidx.compose.ui.draw.shadow] itself uses.
 *
 * Do not reach for `Surface(tonalElevation = ...)` as a substitute for this: on any `Surface`
 * whose `color` isn't `colorScheme.surface`, `tonalElevation` is a no-op under the M3 1.3 rule --
 * it silently draws nothing. This is the real depth primitive; hairline borders ([hairline]) are
 * the other half of Hyle's "no Material elevation ramps" language.
 */
fun Modifier.softShadow(level: ShadowLevel, shape: Shape): Modifier = composed {
    val (elevation, alpha) = when (level) {
        ShadowLevel.SM -> 2.dp to 0.16f
        ShadowLevel.MD -> 6.dp to 0.20f
        ShadowLevel.LG -> 14.dp to 0.26f
    }
    val tint = ShadowTint.copy(alpha = alpha)
    this.shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = tint,
        spotColor = tint,
    )
}

/**
 * The hairline border colour for the current scheme -- the flat-card half of Hyle's depth
 * language, paired with [softShadow] for floating objects. [MaterialTheme]'s own
 * `colorScheme.outlineVariant` is every theme style's already-deliberate neutral-variant swatch
 * (see `ThemeStyle.kt`), but it is drawn at full strength for text-level contrast, which reads as
 * a heavier line than a "hairline" wants. Lowering its alpha lets it optically blend into
 * whatever near-white (or, in dark themes, near-black) surface it is drawn over instead of
 * re-deriving a second hardcoded colour per theme -- e.g. the light baseline's
 * `outlineVariant` (~`#C4C6D0`) composited at this alpha over a near-white ground lands within a
 * few values of the reference frames' `#E7E9EC`-class line, without this function needing to know
 * what "near-white" means for every theme style.
 */
@Composable
fun hairline(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

/**
 * `labelSmall` widened to 0.2em tracking -- Hyle's small-section-label signature. This does not
 * uppercase the string; callers that want the uppercase-micro-label look (as opposed to just the
 * tracked type) call `.uppercase()` on the text themselves, the same way Compose's `TextStyle` has
 * no case-transform of its own.
 */
@Composable
fun microLabel(): TextStyle = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.em)
