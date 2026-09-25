package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.Bar
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.MenuId
import io.github.mbaliga.fylz.actions.PlacementQuery
import io.github.mbaliga.fylz.browse.SortDirection

private val NAVIGATE_UP_ID = ActionId.parse("fylz.navigate.up")
private val SELECT_ALL_ID = ActionId.parse("fylz.select.all")
private val FOLDERS_FIRST_ID = ActionId.parse("fylz.sort.folders-first")

/**
 * The browser row's two icon buttons (`Placement.Toolbar(BROWSER_ROW)`) and the sort menu
 * (`Placement.Menu(SORT)`) -- design §2.6. Drawn only with an open tab, as today. The search field
 * and its scope chips sit between navigate-up and the sort menu in today's layout and are not
 * actions, so they stay exactly where they are in `FileBrowser`; this file supplies the three
 * pieces around them rather than one contiguous row, to keep today's exact visual order.
 */
@Composable
fun NavigateUpButton(resolver: ActionResolver, dispatcher: ActionDispatcher, state: BrowserState, ctx: ActionContext) {
    val item = resolver.resolve(PlacementQuery.Toolbar(Bar.BROWSER_ROW), state).first { it.id == NAVIGATE_UP_ID }
    IconButton(onClick = { dispatcher.run(item.id, state, null, ctx) }, enabled = item.enabled, modifier = Modifier.size(48.dp)) {
        Icon(BuiltinIcons.icon(item.iconName), item.label)
    }
}

@Composable
fun SelectAllButton(resolver: ActionResolver, dispatcher: ActionDispatcher, state: BrowserState, ctx: ActionContext) {
    val item = resolver.resolve(PlacementQuery.Toolbar(Bar.BROWSER_ROW), state).first { it.id == SELECT_ALL_ID }
    IconButton(onClick = { dispatcher.run(item.id, state, null, ctx) }, enabled = item.enabled, modifier = Modifier.size(48.dp)) {
        Icon(BuiltinIcons.icon(item.iconName), item.label)
    }
}

@Composable
fun SortMenuButton(resolver: ActionResolver, dispatcher: ActionDispatcher, state: BrowserState, ctx: ActionContext) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Sort, "Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            resolver.resolve(PlacementQuery.Menu(MenuId.SORT), state).forEach { item ->
                if (item.id == FOLDERS_FIRST_ID) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        trailingIcon = {
                            Checkbox(checked = item.checked == true, onCheckedChange = { dispatcher.run(item.id, state, null, ctx) })
                        },
                        onClick = { dispatcher.run(item.id, state, null, ctx) },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        trailingIcon = {
                            if (item.checked == true) {
                                Icon(
                                    if (state.sortSpec.direction == SortDirection.ASCENDING) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                                    contentDescription = null,
                                )
                            }
                        },
                        onClick = { dispatcher.run(item.id, state, null, ctx) },
                    )
                }
            }
        }
    }
}
