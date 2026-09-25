package io.github.mbaliga.fylz.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.ConflictedItem
import io.github.mbaliga.fylz.operations.DocNode
import io.github.mbaliga.fylz.operations.sha256Hex
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1.6: shown before a copy or move actually starts, once [io.github.mbaliga.fylz.operations.findConflicts]
 * finds a top-level source item that already has a same-named sibling at the destination -- the
 * per-item sheet the brief asks [ConflictPolicy.ASK] to wire to, in place of
 * `FileOperationService.resolveTargetPlan` simply throwing mid-transfer. Every conflicted item
 * needs a decision -- Replace, Skip, Keep both, or Replace if newer (only offered when both sides
 * have a known modified time) -- before [onProceed] runs; "Apply to remaining" copies whichever
 * choice is made next onto every other still-undecided item, so a batch of many same-shaped
 * conflicts doesn't need answering one at a time. [onCancel] refuses the whole operation.
 */
@Composable
fun ConflictSheet(
    conflicts: List<ConflictedItem>,
    onCancel: () -> Unit,
    onProceed: (resolutions: Map<Uri, ConflictPolicy>) -> Unit,
) {
    val choices = remember(conflicts) { mutableStateMapOf<Uri, ConflictPolicy>() }
    var applyToRemaining by remember(conflicts) { mutableStateOf(false) }
    val everyItemDecided = conflicts.all { choices.containsKey(it.source.uri) }

    fun choose(item: ConflictedItem, policy: ConflictPolicy) {
        if (applyToRemaining) {
            conflicts.forEach { conflict -> choices[conflict.source.uri] = policy }
        } else {
            choices[item.source.uri] = policy
        }
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Some items already exist", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The destination already has an item with this name. Choose what to do for each one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToRemaining, onCheckedChange = { applyToRemaining = it })
                    Text("Apply my next choice to every remaining item", style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(conflicts, key = { it.source.uri }) { conflict ->
                        ConflictItemRow(
                            conflict = conflict,
                            choice = choices[conflict.source.uri],
                            onChoiceChange = { choose(conflict, it) },
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onProceed(choices.toMap()) }, enabled = everyItemDecided) { Text("Continue") }
                }
            }
        }
    }
}

@Composable
private fun ConflictItemRow(
    conflict: ConflictedItem,
    choice: ConflictPolicy?,
    onChoiceChange: (ConflictPolicy) -> Unit,
) {
    val replaceIfNewerAvailable = !conflict.source.isDirectory && !conflict.existing.isDirectory &&
        conflict.source.lastModified != null && conflict.existing.lastModified != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(conflict.source.name, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompareCard(label = "Yours", node = conflict.source, hashable = conflict.hashable, modifier = Modifier.weight(1f))
            CompareCard(label = "Existing", node = conflict.existing, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = choice == ConflictPolicy.REPLACE,
                onClick = { onChoiceChange(ConflictPolicy.REPLACE) },
                label = { Text("Replace") },
            )
            FilterChip(
                selected = choice == ConflictPolicy.KEEP_BOTH,
                onClick = { onChoiceChange(ConflictPolicy.KEEP_BOTH) },
                label = { Text("Keep both") },
            )
            FilterChip(
                selected = choice == ConflictPolicy.SKIP,
                onClick = { onChoiceChange(ConflictPolicy.SKIP) },
                label = { Text("Skip") },
            )
            if (replaceIfNewerAvailable) {
                FilterChip(
                    selected = choice == ConflictPolicy.REPLACE_IF_NEWER,
                    onClick = { onChoiceChange(ConflictPolicy.REPLACE_IF_NEWER) },
                    label = { Text("Replace if newer") },
                )
            }
        }
    }
}

/** [label]ed size/modified-date/thumbnail card for one side of a conflict, plus a hash computed
 * only when asked for -- SHA-256 over a whole file is not something to spend on every row by
 * default, especially for a large batch of conflicts. [hashable] `false` (M3.4: [node] is a
 * synthetic [DocNode.descriptor], never a real document) leaves the hash row out entirely, since
 * there is nothing a `ContentResolver` can open at [DocNode.uri] to hash. */
@Composable
private fun CompareCard(label: String, node: DocNode, hashable: Boolean = true, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hash by remember(node.uri) { mutableStateOf<String?>(null) }
    var hashLoading by remember(node.uri) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (!node.isDirectory && node.mimeType.startsWith("image/")) {
                ConflictThumbnail(node.uri)
            }
            Text(
                node.size?.let { formatConflictBytes(it) } ?: "Folder",
                style = MaterialTheme.typography.bodySmall,
            )
            node.lastModified?.let {
                Text(formatConflictDate(it), style = MaterialTheme.typography.bodySmall)
            }
            when {
                !hashable -> Unit
                hash != null -> Text(hash!!.take(12) + "…", style = MaterialTheme.typography.bodySmall)
                node.isDirectory -> Unit
                hashLoading -> Text("Computing…", style = MaterialTheme.typography.bodySmall)
                else -> TextButton(onClick = { hashLoading = true }) { Text("Show hash") }
            }
        }
    }

    // Only runs once the button above has actually been tapped (hashLoading flips true), not on
    // every recomposition or on first display.
    if (hashable && hashLoading && hash == null) {
        LaunchedEffect(node.uri) {
            hash = withContext(Dispatchers.IO) { sha256Hex(context.contentResolver, node.uri) }
            hashLoading = false
        }
    }
}

@Composable
private fun ConflictThumbnail(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(96, 96), CancellationSignal()) }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            painter = BitmapPainter(it.asImageBitmap()),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
    }
}

private fun formatConflictBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}

private fun formatConflictDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
