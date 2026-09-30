package io.github.mbaliga.fylz.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A masked secret field with a show/hide toggle (P0.10) -- used for anything that shoulder-surfing
 * shouldn't reveal: an AI provider API key, a WebDAV password. Pulled out of `FylzV1App.kt` (M3.9)
 * so it is a shared component rather than one screen's own private helper; the archive password
 * prompt itself uses `PasswordPromptDialog`'s own fields, not this one.
 */
@Composable
fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide $label" else "Show $label",
                )
            }
        },
    )
}
