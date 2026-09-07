package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ui.motion.FylzMotion
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ShadowLevel
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import io.github.mbaliga.fylz.ui.theme.softShadow

private val SwitchWidth = 64.dp
private val SwitchHeight = 36.dp
private val KnobGap = 3.dp
private val TouchTarget = 48.dp

/**
 * Compact boolean variant of the segmented control: the same plate, a single sliding keycap knob.
 * OFF is a light cap over the gray plate; ON swaps to the RAISED CAP recipe (dark gradient +
 * glint) plus a soft interior violet wash on the plate -- the ON state's second channel alongside
 * the knob's own position, never an outer glow. The 36dp visual plate sits centred inside a
 * >=48dp touch target, per the kit's own touch-target floor.
 */
@Composable
fun TactileSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalThemeStyle.current == ThemeStyle.CLI) {
        CliSwitch(checked, onCheckedChange, modifier, enabled)
        return
    }

    val palette = tactilePalette()
    // One interaction source for both press and focus -- see TactileButton's own note on why a
    // second, separately-sourced `.focusable()` next to `toggleable` is a bug, not belt-and-braces.
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val press = rememberTactilePressState(interactionSource)
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (tactileReducedMotion()) snap() else FylzMotion.settle,
        label = "tactile-switch-fraction",
    )
    val onLabel = stringResource(R.string.tactile_switch_on)
    val offLabel = stringResource(R.string.tactile_switch_off)

    val knobSide = if (checked) TactileSlantSide.LEADING else TactileSlantSide.TRAILING
    // FylzGeometry.RadiusMd, NOT half the knob's height: a radius of half turns the knob into a
    // perfect circle, and a circle has no straight edge left to lean, so the slant vocabulary that
    // every other control in this kit shares vanished here and the knob read as a stock Material
    // disc sliding in a pill.
    val knobShape = remember(knobSide) { TactileSlantShape(knobSide, FylzGeometry.RadiusMd) }

    Box(
        modifier
            .sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics {
                stateDescription = if (checked) onLabel else offLabel
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        val plateShape = remember { RoundedCornerShape(50) }
        Box(
            Modifier
                .size(width = SwitchWidth, height = SwitchHeight)
                .alpha(if (enabled) 1f else 0.38f)
                .tactileFocusRing(focused, palette.accent, cornerRadius = SwitchHeight / 2)
                .tactilePlate(palette, shape = plateShape),
        ) {
            if (checked) {
                // A sibling Box, not a chained draw modifier: tactileAccentWash's own
                // drawContent()-then-overlay shape would paint the wash ON TOP OF the knob too
                // (there is no seam in .tactilePlate()'s own drawWithContent to inject it between
                // the plate fill and this Box's children), which is exactly the outer-glow-on-the-
                // cap look the spec rules out. A plain z-ordered sibling keeps the wash under the
                // knob and only over the exposed plate.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(plateShape)
                        .background(palette.accent.copy(alpha = 0.24f)),
                )
            }
            val knobWidth = SwitchHeight - KnobGap * 2
            val travel = SwitchWidth - knobWidth - KnobGap * 2
            Box(
                Modifier
                    .offset(x = KnobGap + travel * fraction)
                    .fillMaxHeight()
                    .padding(vertical = KnobGap)
                    .width(knobWidth)
                    .tactilePressOffset(press)
                    .then(
                        if (checked) {
                            Modifier.tactileCap(palette, knobShape, showGlint = true) { press.shadow }
                        } else {
                            // A plain light cap, not the full RAISED CAP recipe: that recipe's
                            // white-alpha rim/lip strokes exist to catch light on a DARK cap and
                            // would be invisible against this one's own near-white fill.
                            //
                            // "Light" means light FOR THE SKIN, though. Pure white is the owner's
                            // light-frame value and reads as a stray white pill on the jet plate,
                            // so the dark skin raises its off-knob to the cap's own top stop
                            // instead -- still the lightest thing in the control, still clearly a
                            // key, without punching a hole in a dark screen.
                            Modifier
                                .softShadow(ShadowLevel.SM, knobShape)
                                .clip(knobShape)
                                .background(if (palette.isDark) palette.capHigh else Color.White)
                                .border(1.dp, palette.edge, knobShape)
                        },
                    ),
            )
        }
    }
}

/** CLI degrade: `[x]` checked, `[ ]` unchecked -- never a fake knob. */
@Composable
private fun CliSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    val onLabel = stringResource(R.string.tactile_switch_on)
    val offLabel = stringResource(R.string.tactile_switch_off)
    Text(
        if (checked) "[x]" else "[ ]",
        style = LocalTextStyle.current,
        modifier = modifier
            .height(TouchTarget)
            .alpha(if (enabled) 1f else 0.38f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { stateDescription = if (checked) onLabel else offLabel }
            .padding(horizontal = 8.dp),
    )
}
