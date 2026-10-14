package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.PlacementQuery

private val CLEAR_ID = ActionId.parse("fylz.select.clear")

/** `fylz.recycle` (design `BuiltInActions.kt`'s `selectionBar()`): the one destructive action in
 * this bar, rendered as the mockup's red circular trash button rather than through the generic
 * [ActionButton] row -- `ResolvedAction` carries no `destructive` flag of its own (only
 * `ActionDef` does, which the renderer never sees), so this is matched by id, the same way [CLEAR_ID]
 * already was. */
private val RECYCLE_ID = ActionId.parse("fylz.recycle")

/**
 * The bottom selection bar (design §2.6 MC.0; restyled per the owner's mockup -- count text, a
 * cancel/X and a red trash button on a dark bar -- `docs/agent/PROGRESS.md`), drawn only when there
 * is a selection, exactly as before. Still driven entirely by [ActionResolver.resolve]/
 * [ActionDispatcher]: every item, its order, its enabled state and its label still come from the
 * registry, and nothing here hardcodes a menu of actions -- only [CLEAR_ID] and [RECYCLE_ID] are
 * singled out of the resolved list to draw at the trailing edge in the mockup's own look, with
 * every other resolved item (cut/copy/move/rename/tags/archive/extract/batch-rename/pdf/share --
 * none of which the simplified mockup shows, but all of which stay fully reachable) still rendered
 * through [ActionButton] in between, just recoloured to read on the now-dark bar.
 */
@Composable
fun SelectionActionBar(
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
) {
    val items = resolver.resolve(PlacementQuery.SelectionBar, state)
    val clearItem = items.firstOrNull { it.id == CLEAR_ID }
    val recycleItem = items.firstOrNull { it.id == RECYCLE_ID }
    val middleItems = items.filterNot { it.id == CLEAR_ID || it.id == RECYCLE_ID }
    val onDark = MaterialTheme.colorScheme.inverseOnSurface

    // Neutral chrome, deliberately not the accent: the overflow actions on it carry the accent.
    Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${state.selectionCount} ${if (state.selectionCount == 1) "ITEM" else "ITEMS"} SELECTED",
                color = onDark,
                modifier = Modifier.padding(start = 6.dp, end = 10.dp),
            )
            // The mockup only shows the count, cancel and trash; everything else this bar can do
            // (cut/copy/move/rename/tags/archive/extract/batch-rename/pdf/share) still renders,
            // scrollable, through the shared ActionButton, drawn in `inversePrimary` -- the accent
            // Material3 reserves for text on `inverseSurface` (FylzTheme derives it from the other
            // light/dark half of the palette). The plain `primary` the button defaults to has only
            // 1.3-2.0:1 contrast on this bar in the bundled palettes; `inversePrimary` has 5-8:1.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                middleItems.forEach { item ->
                    ActionButton(
                        icon = BuiltinIcons.icon(item.iconName),
                        label = item.label,
                        onClick = { dispatcher.run(item.id, state, null, ctx) },
                        enabled = item.enabled,
                        contentColor = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
            clearItem?.let { item ->
                IconButton(onClick = { dispatcher.run(item.id, state, null, ctx) }) {
                    Icon(Icons.Outlined.Close, contentDescription = item.label, tint = onDark)
                }
            }
            recycleItem?.let { item ->
                // Fixed red on purpose: the owner's mockup destructive button, semantic rather than accent.
                Surface(color = Color(0xFFD32F2F), shape = CircleShape, modifier = Modifier.size(40.dp)) {
                    IconButton(onClick = { dispatcher.run(item.id, state, null, ctx) }, enabled = item.enabled) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = item.label, tint = Color.White)
                    }
                }
            }
        }
    }
}
