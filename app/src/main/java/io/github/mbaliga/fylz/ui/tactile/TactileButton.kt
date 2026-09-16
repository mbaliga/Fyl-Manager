package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle

/** [TactileButton]'s three keycap treatments -- see the type's own KDoc for what each draws. */
enum class TactileButtonStyle { PRIMARY, SECONDARY, DESTRUCTIVE }

private val ButtonHeight = 48.dp
private val ButtonHorizontalPadding = 20.dp
private val ButtonVerticalPadding = 12.dp

/**
 * A single keycap. PRIMARY is the RAISED CAP recipe in the plate's own dark tone (+glint), white
 * label; SECONDARY is plate-only with its bevel, ink label -- no cap, so PRESS reads as a soft
 * darkening scrim instead of a bevel invert; DESTRUCTIVE is PRIMARY's same recipe with its
 * gradient re-tinted around [TactilePalette.danger] (+glint), white label -- only where
 * destructive semantics already exist at the call site, never decorative.
 */
@Composable
fun TactileButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TactileButtonStyle = TactileButtonStyle.PRIMARY,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    if (LocalThemeStyle.current == ThemeStyle.CLI) {
        CliButton(text, onClick, modifier, enabled, fillWidth)
        return
    }

    val palette = tactilePalette()
    // One interaction source for both press and focus: `clickable` already composes its own
    // internal `focusable()` from the source passed to it, so a second, separately-sourced
    // `.focusable()` here would build a competing focus node that never actually receives the
    // real focus events -- the ring would silently never light up. Read both press and focus
    // off this single stream instead.
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val press = rememberTactilePressState(interactionSource)
    val shape = remember { RoundedCornerShape(FylzGeometry.RadiusLg) }

    Box(
        modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
            .widthIn(min = ButtonHeight)
            .height(ButtonHeight)
            .alpha(if (enabled) 1f else 0.38f)
            .tactileFocusRing(focused, palette.accent, cornerRadius = FylzGeometry.RadiusLg)
            .tactilePressOffset(press)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .then(
                // A DISABLED button drops to the plate recipe whatever its style: a dark PRIMARY
                // cap faded to 38% is still a big mid-gray slab that reads HEAVIER than the
                // enabled SECONDARY next to it, which is backwards. Un-pressing it into the
                // surface is the honest "not actionable" read, and it keeps the 38% fade the
                // owner's state sheet asks for instead of inventing a second disabled language.
                when (if (enabled) style else TactileButtonStyle.SECONDARY) {
                    TactileButtonStyle.SECONDARY -> Modifier.tactilePlate(palette, shape).drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = 0.06f * press.shadow))
                    }
                    TactileButtonStyle.PRIMARY -> Modifier.tactileCap(palette, shape) { press.shadow }
                    TactileButtonStyle.DESTRUCTIVE -> Modifier.tactileCap(dangerCapPalette(palette), shape) { press.shadow }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (style == TactileButtonStyle.SECONDARY || !enabled) palette.onPlate else palette.onCap,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = ButtonHorizontalPadding, vertical = ButtonVerticalPadding),
        )
    }
}

/**
 * [palette] with its RAISED CAP gradient stops re-tinted around [TactilePalette.danger], so
 * DESTRUCTIVE reuses [tactileCap] verbatim instead of a second bevel implementation.
 *
 * All three stops shade TOWARD black rather than straddling [TactilePalette.danger] itself: the
 * raw token (`#E5564B`) is light enough that the white label above it would dip under the 4.5:1
 * text-contrast floor wherever it happens to sit on the gradient.
 *
 * The shading is deliberately SHALLOW (roughly `#C44A41` down to `#8E3630`). The first pass drove
 * it to 30-60% black, which lands around `#95382E`-`#661E19` -- past "dark red" and into a muddy
 * near-brown that reads as a rendering fault rather than a destructive action. Every stop here
 * still clears the 4.5:1 floor against a white label (the lightest measures ~4.8:1) while staying
 * recognisably the danger family's own red.
 */
private fun dangerCapPalette(palette: TactilePalette): TactilePalette {
    val danger = palette.danger
    return palette.copy(
        capHigh = lerp(danger, Color.Black, 0.14f),
        capMid = lerp(danger, Color.Black, 0.26f),
        capBase = lerp(danger, Color.Black, 0.38f),
    )
}

/** CLI degrade: `[ label ]` plain text, never a fake keycap. */
@Composable
private fun CliButton(text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, fillWidth: Boolean) {
    Text(
        "[ $text ]",
        textAlign = if (fillWidth) TextAlign.Center else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(ButtonHeight)
            .wrapContentHeight(Alignment.CenterVertically)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
    )
}
