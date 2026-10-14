package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.Bar
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.MenuId
import io.github.mbaliga.fylz.actions.PlacementQuery
import io.github.mbaliga.fylz.model.ClipboardMode
import io.github.mbaliga.fylz.model.ViewMode

private val PASTE_ID = ActionId.parse("fylz.paste")
private val CLIPBOARD_CLEAR_ID = ActionId.parse("fylz.clipboard.clear")
private val VIEW_TOGGLE_ID = ActionId.parse("fylz.view.toggle")
private val REFRESH_ID = ActionId.parse("fylz.refresh")

/**
 * The top app bar's own icon row plus its one overflow menu (design §2.6): the clipboard chip,
 * view toggle and refresh render from `Placement.Toolbar(TOP_APP_BAR)`; the overflow items from
 * `Placement.Menu(OVERFLOW)`. The clipboard chip's leading icon (Cut/Copy) and the view toggle's
 * icon (List/GridView) stay state-dependent -- a single static `IconRef` can't represent them, so
 * which name to look up comes from [state] here, exactly as the old code chose, rather than from
 * the resolved item's own icon. The "more actions" trigger itself isn't a registered action (it
 * only opens the menu), so its icon stays a plain literal, same as before.
 */
@Composable
fun TopAppBarActions(
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val toolbarItems = resolver.resolve(PlacementQuery.Toolbar(Bar.TOP_APP_BAR), state)
    val pasteItem = toolbarItems.find { it.id == PASTE_ID }
    val clipboardClearItem = toolbarItems.find { it.id == CLIPBOARD_CLEAR_ID }
    val viewToggleItem = toolbarItems.first { it.id == VIEW_TOGGLE_ID }
    val refreshItem = toolbarItems.first { it.id == REFRESH_ID }

    if (pasteItem != null) {
        InputChip(
            selected = false,
            enabled = pasteItem.enabled,
            onClick = { dispatcher.run(pasteItem.id, state, null, ctx) },
            label = { Text(pasteItem.label) },
            leadingIcon = {
                Icon(
                    if (state.clipboard?.mode == ClipboardMode.CUT) BuiltinIcons.icon("ContentCut") else BuiltinIcons.icon("ContentCopy"),
                    contentDescription = null,
                )
            },
            trailingIcon = clipboardClearItem?.let { clear ->
                {
                    Icon(
                        BuiltinIcons.icon(clear.iconName),
                        contentDescription = clear.label,
                        modifier = Modifier.size(16.dp).clickable { dispatcher.run(clear.id, state, null, ctx) },
                    )
                }
            },
        )
    }
    IconButton(onClick = { dispatcher.run(viewToggleItem.id, state, null, ctx) }, enabled = viewToggleItem.enabled) {
        Icon(
            if (state.viewMode == ViewMode.GRID) BuiltinIcons.icon("List") else BuiltinIcons.icon("GridView"),
            contentDescription = viewToggleItem.label,
        )
    }
    IconButton(onClick = { dispatcher.run(refreshItem.id, state, null, ctx) }, enabled = refreshItem.enabled) {
        Icon(BuiltinIcons.icon(refreshItem.iconName), contentDescription = refreshItem.label)
    }
    Box {
        IconButton(onClick = { overflowExpanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
            resolver.resolve(PlacementQuery.Menu(MenuId.OVERFLOW), state).forEach { item ->
                val leading = overflowLeadingIcon(item.id)
                DropdownMenuItem(
                    text = { Text(item.label) },
                    leadingIcon = leading?.let { icon -> { Icon(icon, null) } },
                    enabled = item.enabled,
                    onClick = { overflowExpanded = false; dispatcher.run(item.id, state, null, ctx) },
                )
            }
        }
    }
}

// Matches the old overflow menu exactly: only these three items ever had a leadingIcon
// (FylzV1App.kt's "Find duplicates"/"AI organize proposal"/the new "Commands" stay unadorned).
private fun overflowLeadingIcon(id: ActionId): ImageVector? = when (id.value) {
    "fylz.new-folder" -> BuiltinIcons.icon("CreateNewFolder")
    "fylz.new-file" -> BuiltinIcons.icon("TextSnippet")
    "fylz.scan-to-pdf" -> BuiltinIcons.icon("PictureAsPdf")
    else -> null
}
