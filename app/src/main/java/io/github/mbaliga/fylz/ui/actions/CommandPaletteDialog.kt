package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState

/**
 * Design §2.6, moved up from MC.0e: `fylz.commands` (drawn in the overflow menu this same commit)
 * must not open a dead menu item. A text field filters every resolved, enabled, targetless,
 * palette-visible built-in across all placements ([ActionResolver.paletteCandidates]) by label;
 * Enter or a click dispatches and closes. Minimal by design -- no icons.
 *
 * [onFocusChanged] reports this field's own focus into the workspace's shared text-field-focused
 * flag (design §2.6, MC.0e), the same one the search field reports into, so `KeyRouter` knows to
 * step aside. In practice [Dialog] opens its own Android window, so the workspace root's
 * `onKeyEvent` never sees a key while this is open either way -- reporting focus here is cheap
 * defence in depth, not load-bearing, and keeps this dialog's wiring honest with the design's own
 * text.
 */
@Composable
fun CommandPaletteDialog(
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
    onDismiss: () -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val candidates = remember(state, query) {
        resolver.paletteCandidates(state).filter { it.label.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Commands") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .onKeyEvent { event ->
                            if (event.key == Key.Enter) {
                                candidates.firstOrNull()?.let { dispatcher.run(it.id, state, null, ctx) }
                                onDismiss()
                                true
                            } else {
                                false
                            }
                        },
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(candidates, key = { it.id.value }) { item ->
                        TextButton(
                            onClick = { dispatcher.run(item.id, state, null, ctx); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(item.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
