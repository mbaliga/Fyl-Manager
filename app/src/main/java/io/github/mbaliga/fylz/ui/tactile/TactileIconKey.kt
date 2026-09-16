package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle

private val IconKeyVisualWidth = 48.dp
private val IconKeyVisualHeight = 44.dp
private val TouchTarget = 48.dp

/**
 * A square-ish keycap for toolbar/nav icon actions. `latched = true` draws the RAISED CAP recipe
 * (icon carried in [TactilePalette.onCap]); otherwise plate-only. A tap always flashes the PRESS
 * recipe (sink + bevel invert/scrim) regardless of [latched] -- that flash is momentary and does
 * not itself change [latched]; the caller owns that state and passes it back in on recomposition,
 * same as every other latching control in this kit.
 */
@Composable
fun TactileIconKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    latched: Boolean = false,
    enabled: Boolean = true,
) {
    if (LocalThemeStyle.current == ThemeStyle.CLI) {
        CliIconKey(icon, contentDescription, onClick, modifier, latched, enabled)
        return
    }

    val palette = tactilePalette()
    // One interaction source for both press and focus -- see TactileButton's own note on why a
    // second, separately-sourced `.focusable()` next to `clickable` is a bug, not belt-and-braces.
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val press = rememberTactilePressState(interactionSource)
    val shape = remember { RoundedCornerShape(FylzGeometry.RadiusMd) }
    // Captured under a distinct name: `contentDescription` is also the name of the
    // SemanticsPropertyReceiver extension property assigned below, and that extension's getter
    // throws (it's write-only) -- `contentDescription = contentDescription` inside the lambda
    // would resolve its right-hand side to the receiver's own (throwing) property, not this
    // parameter, per Kotlin's implicit-receiver scoping. A differently-named local sidesteps that.
    val description = contentDescription

    Box(
        modifier
            .sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = contentDescription,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = description
                selected = latched
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = IconKeyVisualWidth, height = IconKeyVisualHeight)
                .alpha(if (enabled) 1f else 0.38f)
                .tactileFocusRing(focused, palette.accent, cornerRadius = FylzGeometry.RadiusMd)
                .tactilePressOffset(press)
                .then(
                    if (latched) {
                        Modifier.tactileCap(palette, shape) { press.shadow }
                    } else {
                        Modifier.tactilePlate(palette, shape).drawWithContent {
                            drawContent()
                            drawRect(Color.Black.copy(alpha = 0.06f * press.shadow))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (latched) palette.onCap else palette.onPlate,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** CLI degrade: bracketed monospace text carrying the same [contentDescription], never a fake
 *  keycap or a bare icon glyph the terminal reading has no equivalent for. */
@Composable
private fun CliIconKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    latched: Boolean,
    enabled: Boolean,
) {
    Text(
        if (latched) "[$contentDescription]" else " $contentDescription ",
        style = LocalTextStyle.current,
        modifier = modifier
            .sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { selected = latched }
            .padding(8.dp),
    )
}
