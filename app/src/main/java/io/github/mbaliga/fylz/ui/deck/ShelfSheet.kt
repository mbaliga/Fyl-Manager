package io.github.mbaliga.fylz.ui.deck

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The persistent Shelf, riffled the same way a live selection is: [FileDeckSurface] plus the one
 * row of cross-location operations the Shelf exists for.
 *
 * Every callback here is a decision the workspace already knows how to make -- where "here" is,
 * how a successful move updates the Shelf afterward, what Compress zips, what "New folder with"
 * reuses. This file only lays the ops row out and wires it to [FileDeckSurface]; nothing in it
 * touches [io.github.mbaliga.fylz.staging.ShelfStore] directly.
 */
@Composable
fun ShelfSheet(
    items: List<DeckItem>,
    caption: String,
    hasMissing: Boolean,
    canCommitHere: Boolean,
    onRemove: (DeckItem) -> Unit,
    onRemoveMissing: () -> Unit,
    onCopyHere: () -> Unit,
    onMoveHere: () -> Unit,
    onCompress: () -> Unit,
    onNewFolderWith: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    FileDeckSurface(
        items = items,
        title = "The Shelf",
        caption = caption,
        onRemove = onRemove,
        onDismiss = onDismiss,
        actions = {
            ShelfOpsRow(
                hasMissing = hasMissing,
                canCommitHere = canCommitHere,
                onRemoveMissing = onRemoveMissing,
                onCopyHere = onCopyHere,
                onMoveHere = onMoveHere,
                onCompress = onCompress,
                onNewFolderWith = onNewFolderWith,
                onShare = onShare,
                onClear = onClear,
            )
        },
    )
}

/**
 * The ops row itself, horizontally scrollable: six-odd buttons never fit a phone width laid out
 * flat, and [FileDeckSurface]'s own `actions` slot is a plain, unscrolled `Row` -- this is the
 * one child it gets, so the scroll lives here rather than asking the deck surface to know the
 * Shelf carries more actions than the selection deck (which passes none at all) ever did.
 */
@Composable
private fun ShelfOpsRow(
    hasMissing: Boolean,
    canCommitHere: Boolean,
    onRemoveMissing: () -> Unit,
    onCopyHere: () -> Unit,
    onMoveHere: () -> Unit,
    onCompress: () -> Unit,
    onNewFolderWith: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onCopyHere, enabled = canCommitHere) { Text("Copy here") }
            FilledTonalButton(onClick = onMoveHere, enabled = canCommitHere) { Text("Move here") }
            TextButton(onClick = onCompress) { Text("Compress") }
            TextButton(onClick = onNewFolderWith) { Text("New folder with") }
            TextButton(onClick = onShare) { Text("Share") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (!canCommitHere) {
            Text(
                "Open a folder to copy or move into",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasMissing) {
            TextButton(onClick = onRemoveMissing) { Text("Remove missing") }
        }
    }
}
