package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.PlacementQuery

private val CLEAR_ID = ActionId.parse("fylz.select.clear")

/**
 * The bottom selection bar (design §2.6), drawn only when there is a selection, exactly as
 * before. Replaces the old private `SelectionActionBar` composable and its 13-lambda call site:
 * every item, its order, its enabled state, its label and its click handler now come from
 * [ActionResolver.resolve]; the trailing `fylz.select.clear` stays the plain `TextButton("Clear")`
 * it always was, last, per design.
 */
@Composable
fun SelectionActionBar(
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
) {
    Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("${state.selectionCount} selected", modifier = Modifier.padding(horizontal = 10.dp))
            resolver.resolve(PlacementQuery.SelectionBar, state).forEach { item ->
                if (item.id == CLEAR_ID) {
                    TextButton(onClick = { dispatcher.run(item.id, state, null, ctx) }) { Text(item.label) }
                } else {
                    ActionButton(
                        icon = BuiltinIcons.icon(item.iconName),
                        label = item.label,
                        onClick = { dispatcher.run(item.id, state, null, ctx) },
                        enabled = item.enabled,
                    )
                }
            }
        }
    }
}
