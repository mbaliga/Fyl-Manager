package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Moved out of `ui/FylzV1App.kt` unchanged (design MC.0b): one icon-and-label button, used by
 * every renderer under `ui/actions/` that draws a row of them. */
@Composable
fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 5.dp))
    }
}
