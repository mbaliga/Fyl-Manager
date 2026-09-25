package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.MenuId
import io.github.mbaliga.fylz.actions.PlacementQuery

/**
 * `fylz.extract`'s own three choices (design `DESIGN-M34-SELECTIVE-EXTRACT.md` §2.1): resolved
 * from `Menu(MenuId.EXTRACT)`, so their order, label and enabled state come from the registry
 * exactly like [io.github.mbaliga.fylz.ui.actions.ArchiveToolsMenuDialog]'s two buttons -- unlike
 * that dialog, a click here runs the resolved action through the ordinary [ActionDispatcher],
 * since each of the three has a real one-line [ActionContext] handler (`extractHere`,
 * `extractIntoFolder`, `extractTo`) rather than a documented no-op to intercept.
 */
@Composable
fun ExtractSheet(
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
        title = { Text("Extract") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                resolver.resolve(PlacementQuery.Menu(MenuId.EXTRACT), state).forEach { item ->
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            dispatcher.run(item.id, state, null, ctx)
                        },
                        enabled = item.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(item.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
