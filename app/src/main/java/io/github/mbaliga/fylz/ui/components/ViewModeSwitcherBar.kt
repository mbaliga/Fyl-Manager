package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.ViewMode

/**
 * The selection-mode mockup's top icon row (file-type/grid/list icons, one highlighted). Fylz's
 * only existing way to change [ViewMode] today is `fylz.view.toggle` (`BuiltInActions.kt`), a
 * single action that flips GRID<->LIST and is bound to a top-app-bar icon button
 * (`ui/actions/TopAppBarRenderer.kt`); nothing in the app reaches [ViewMode.DETAILS]. Per the
 * brief ("restyle/relocate [it], rather than inventing a new switching mechanism"), this
 * composable does NOT fabricate a three-way action the registry has no handler for -- it is a
 * second, additive way to reach the SAME [ViewMode] state var this screen already owns (like
 * `onQueryChange`/`onSearchFocusChanged`, plain callbacks the call site wires directly, not every
 * piece of this screen's UI goes through the action registry), restyled to the mockup's pill-row
 * look and extended to cover all three [ViewMode] values since the state already supports them.
 * The original `fylz.view.toggle` top-app-bar button is left exactly as it was, for keyboard/
 * accessibility parity (a view-mode switch should not only be reachable while a selection is
 * active, which is the one place this row renders -- see its call site in `FylzV1App.kt`).
 */
@Composable
fun ViewModeSwitcherBar(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        // Neutral chrome, deliberately not the accent: only the active icon below carries it.
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
        modifier = modifier,
    ) {
        Row(Modifier.padding(6.dp)) {
            ViewModeIconButton(Icons.Outlined.InsertDriveFile, ViewMode.DETAILS, viewMode, onViewModeChange)
            ViewModeIconButton(Icons.Outlined.GridView, ViewMode.GRID, viewMode, onViewModeChange)
            ViewModeIconButton(Icons.Outlined.List, ViewMode.LIST, viewMode, onViewModeChange)
        }
    }
}

@Composable
private fun ViewModeIconButton(icon: ImageVector, mode: ViewMode, current: ViewMode, onChange: (ViewMode) -> Unit) {
    val active = mode == current
    val contentColor = if (active) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.inverseOnSurface
    Surface(
        color = if (active) MaterialTheme.colorScheme.inversePrimary else Color.Transparent, // accent role: the chosen accent on the inverse bar
        contentColor = contentColor,
        shape = CircleShape,
    ) {
        IconButton(onClick = { onChange(mode) }, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = mode.name, tint = contentColor)
        }
    }
}
