package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Moved out of `ui/FylzV1App.kt` (design MC.0b): one icon-and-label button, used by every
 * renderer under `ui/actions/` that draws a row of them. [contentColor] is for a bar that sits on
 * a non-default surface (the selection bar draws on `inverseSurface`, where the default `primary`
 * has too little contrast); left unspecified, the button looks exactly as it always did. */
@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentColor: Color = Color.Unspecified,
) {
    val colors = if (contentColor == Color.Unspecified) {
        ButtonDefaults.textButtonColors()
    } else {
        ButtonDefaults.textButtonColors(contentColor = contentColor, disabledContentColor = contentColor.copy(alpha = 0.38f))
    }
    TextButton(onClick = onClick, enabled = enabled, colors = colors) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 5.dp))
    }
}
