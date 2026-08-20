package io.github.mbaliga.fylz.ui.tactile

import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ui.chrome.FolderTabSlant
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import io.github.mbaliga.fylz.ui.theme.microLabel

/**
 * A field or option row's visual state -- [Idle]/[Selected] read as a plain enum-like pair, and
 * [Error] carries an optional message rendered below the field. [TactileField] promotes [Idle] to
 * [Selected] automatically while focused (own KDoc); [TactileOptionRow] promotes it while
 * [TactileOptionRow]'s own `selected` parameter is true. A caller-supplied [Error] always wins
 * over that promotion.
 */
sealed interface TactileFieldState {
    object Idle : TactileFieldState
    object Selected : TactileFieldState
    data class Error(val message: String? = null) : TactileFieldState
}

private val FieldHeight = 48.dp
private val FieldRadius = FylzGeometry.RadiusLg
private val SlashSlotWidth = 20.dp
private val AsteriskSlot = 16.dp

/**
 * A text-entry field in the owner's state-sheet anatomy: a near-white (dark: recessed groove)
 * body whose LEADING edge is slanted, a state-coloured slash-tick hugging that edge (gray idle,
 * violet selected/focus, red error -- a floating dot above the slash reads the pair as "!"), a
 * state-coloured hairline border, and a trailing five-spoke asterisk when [mandatory]. Focusing
 * the field promotes [state] to [TactileFieldState.Selected] for the purposes of the slash/border
 * colour even when the caller still passes [TactileFieldState.Idle]; a caller-supplied
 * [TactileFieldState.Error] always wins over that promotion.
 */
@Composable
fun TactileField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    state: TactileFieldState = TactileFieldState.Idle,
    mandatory: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    if (LocalThemeStyle.current == ThemeStyle.CLI) {
        CliField(value, onValueChange, modifier, label, placeholder, state, mandatory, enabled, singleLine, keyboardOptions, visualTransformation)
        return
    }

    val palette = tactilePalette()
    var isFocused by remember { mutableStateOf(false) }
    val effectiveState = if (state is TactileFieldState.Error) state else if (isFocused) TactileFieldState.Selected else state
    val ink = MaterialTheme.colorScheme.onSurface

    // Merges the label Text (and, below, the error message Text) into the BasicTextField's own
    // accessible node -- without this, TalkBack reads the label as an unrelated static-text stop
    // and then focuses the input separately with no derived name, unlike the OutlinedTextField
    // this replaces (whose decoration box merges label/supportingText into one node so TalkBack
    // announces e.g. "Password, edit box").
    Column(modifier.semantics(mergeDescendants = true) {}) {
        label?.let {
            Text(
                it,
                style = microLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = SlashSlotWidth + 8.dp),
            )
        }
        FieldChrome(
            modifier = Modifier.fillMaxWidth(),
            palette = palette,
            effectiveState = effectiveState,
            mandatory = mandatory,
            enabled = enabled,
            focused = isFocused,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = palette.indicatorIdle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    keyboardOptions = keyboardOptions,
                    visualTransformation = visualTransformation,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) ink else palette.indicatorIdle),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                        .semantics {
                            if (effectiveState is TactileFieldState.Error) error(effectiveState.message ?: "")
                        },
                )
            }
        }
        if (effectiveState is TactileFieldState.Error && effectiveState.message != null) {
            Text(
                effectiveState.message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.dangerText,
                modifier = Modifier.padding(top = 4.dp, start = SlashSlotWidth + 8.dp),
            )
        }
    }
}

/**
 * A non-editable row sharing [TactileField]'s exact anatomy -- the owner's state sheet labels
 * this "Selected / Not Selected", i.e. [selected] (not keyboard focus) is what promotes an
 * [TactileFieldState.Idle] [state] to [TactileFieldState.Selected] here.
 */
@Composable
fun TactileOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mandatory: Boolean = false,
    enabled: Boolean = true,
    state: TactileFieldState = TactileFieldState.Idle,
) {
    if (LocalThemeStyle.current == ThemeStyle.CLI) {
        CliOptionRow(text, selected, onClick, modifier, mandatory, enabled, state)
        return
    }

    val palette = tactilePalette()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val effectiveState = when {
        state is TactileFieldState.Error -> state
        selected -> TactileFieldState.Selected
        else -> state
    }

    FieldChrome(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = text },
        palette = palette,
        effectiveState = effectiveState,
        mandatory = mandatory,
        enabled = enabled,
        focused = focused,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The shared field/option-row chrome: slanted-leading-edge groove body, state-coloured border,
 *  leading slash-tick (drawn as an overlay so it can hug/poke past the body's own clip), a
 *  trailing mandatory asterisk, and the caller's own [content] filling the middle. */
@Composable
private fun FieldChrome(
    modifier: Modifier,
    palette: TactilePalette,
    effectiveState: TactileFieldState,
    mandatory: Boolean,
    enabled: Boolean,
    focused: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val (stateColor, withErrorDot) = when (effectiveState) {
        is TactileFieldState.Error -> palette.danger to true
        TactileFieldState.Selected -> palette.accent to false
        TactileFieldState.Idle -> palette.indicatorIdle to false
    }
    val shape = remember { TactileSlantShape(TactileSlantSide.LEADING, FolderTabSlant, FieldRadius) }

    Box(modifier.alpha(if (enabled) 1f else 0.38f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = FieldHeight)
                .tactileFocusRing(focused, palette.accent, cornerRadius = FieldRadius, shape = shape)
                .tactileFieldGroove(palette, shape)
                .border(1.dp, stateColor, shape),
        ) {
            Row(
                Modifier.fillMaxSize().padding(start = SlashSlotWidth + 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
                if (mandatory) {
                    Spacer(Modifier.width(8.dp))
                    Canvas(Modifier.size(AsteriskSlot)) {
                        drawTactileAsterisk(if (effectiveState is TactileFieldState.Error) palette.danger else palette.accent)
                    }
                }
            }
        }
        Canvas(
            Modifier.align(Alignment.CenterStart).size(width = SlashSlotWidth, height = FieldHeight),
        ) {
            drawTactileSlashTick(stateColor, withErrorDot)
        }
    }
}

@Composable
private fun CliField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    label: String?,
    placeholder: String?,
    state: TactileFieldState,
    mandatory: Boolean,
    enabled: Boolean,
    singleLine: Boolean,
    keyboardOptions: KeyboardOptions,
    visualTransformation: VisualTransformation,
) {
    val suffix = if (mandatory) " *" else ""
    Column(modifier.alpha(if (enabled) 1f else 0.38f)) {
        label?.let { Text("$it$suffix", style = LocalTextStyle.current) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("> ", style = LocalTextStyle.current)
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    keyboardOptions = keyboardOptions,
                    visualTransformation = visualTransformation,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state is TactileFieldState.Error && state.message != null) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CliOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    mandatory: Boolean,
    enabled: Boolean,
    state: TactileFieldState,
) {
    val marker = if (selected) "[x]" else "[ ]"
    val suffix = if (mandatory) " *" else ""
    Column(modifier.alpha(if (enabled) 1f else 0.38f)) {
        Text(
            "$marker $text$suffix",
            style = LocalTextStyle.current,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 12.dp),
        )
        if (state is TactileFieldState.Error && state.message != null) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
