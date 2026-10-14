package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.MenuId
import io.github.mbaliga.fylz.actions.PlacementQuery

private val PROTECT_ID = ActionId.parse("fylz.protect")

/**
 * `ArchiveToolsOverlay`'s own two-button menu, over `Menu(ARCHIVE_TOOLS)` (design §2.6): item
 * list, order, labels and enabled state come from the resolver; a click doesn't dispatch through
 * [io.github.mbaliga.fylz.actions.ActionDispatcher] -- both bindings' `run` are documented no-ops,
 * since the overlay's own picker launchers are what actually does the work -- so this renderer
 * takes the resolved items and an [onSelect] the overlay maps to its two existing lambdas
 * (Create/Protect ZIP, Inspect and extract), keeping all of the overlay's own state exactly where
 * it is.
 */
@Composable
fun ArchiveToolsMenuDialog(
    resolver: ActionResolver,
    state: BrowserState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ActionId) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
        title = { Text("Archive tools") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Create standard or AES-256 password-protected ZIP files, or inspect, test and safely extract an existing archive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                resolver.resolve(PlacementQuery.Menu(MenuId.ARCHIVE_TOOLS), state).forEach { item ->
                    val onClick = { onSelect(item.id) }
                    val icon = BuiltinIcons.icon(item.iconName)
                    if (item.id == PROTECT_ID) {
                        Button(onClick = onClick, enabled = item.enabled && !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(icon, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(item.label)
                        }
                    } else {
                        OutlinedButton(onClick = onClick, enabled = item.enabled && !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(icon, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(item.label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
