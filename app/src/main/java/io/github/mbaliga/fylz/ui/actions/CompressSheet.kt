package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.operations.CompressFormat
import io.github.mbaliga.fylz.operations.CompressLevelTier
import io.github.mbaliga.fylz.operations.SplitSize

/** The Compress sheet's own form, before any planning starts (`docs/agent/DESIGN-M35-CREATE.md`
 * section 2.1): everything [io.github.mbaliga.fylz.operations.CompressRequest] needs except the
 * destination, which [CompressSheet] resolves through one of its own two buttons instead of a
 * field, since choosing it is a whole picker flow of its own. */
data class CompressDraft(
    val archiveName: String,
    val format: CompressFormat,
    val level: CompressLevelTier,
    val splitBytes: Long?,
    val relativeToSelection: Boolean,
)

/** One numbered choice [CompressSheet]'s split menu offers, alongside "Off". */
private val SPLIT_CHOICES = listOf(
    "100 MB" to SplitSize.HUNDRED_MB,
    "700 MB (CD)" to SplitSize.SEVEN_HUNDRED_MB,
    "4 GB (FAT32)" to SplitSize.FAT32,
)

/**
 * The Compress sheet (design section 2.1): archive name, format, Fast/Normal/Best, an optional
 * split size, and the "paths relative to the selection" toggle. Two ways forward stand in for a
 * single "Next" button, because each needs its own picker flow: [onChooseFolder] opens the
 * in-app/system folder chooser [CompressFlow] already owns for every other destination pick in
 * this app, and [onSaveAs] launches `CreateDocument` for a single output file with no tree grant
 * at all -- disabled here whenever a split is chosen, since one system-picked document is the only
 * output that destination can ever offer (mirrors `CompressPlanner`'s own refusal for the same
 * combination).
 */
@Composable
fun CompressSheet(
    sourceCount: Int,
    defaultName: String,
    onDismiss: () -> Unit,
    onChooseFolder: (CompressDraft) -> Unit,
    onSaveAs: (CompressDraft) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf(CompressFormat.ZIP) }
    var level by remember { mutableStateOf(CompressLevelTier.NORMAL) }
    var splitIndex by remember { mutableStateOf(-1) }
    var relative by remember { mutableStateOf(true) }
    var formatMenuOpen by remember { mutableStateOf(false) }
    var splitMenuOpen by remember { mutableStateOf(false) }
    val splitBytes = SPLIT_CHOICES.getOrNull(splitIndex)?.second

    fun draft() = CompressDraft(
        archiveName = name.ifBlank { defaultName },
        format = format,
        level = level,
        splitBytes = splitBytes,
        relativeToSelection = relative,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress $sourceCount item(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Archive name") },
                    suffix = { Text(".${format.extension}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    Text("Format", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { formatMenuOpen = true }) { Text(format.name.replace('_', '.').lowercase()) }
                        DropdownMenu(expanded = formatMenuOpen, onDismissRequest = { formatMenuOpen = false }) {
                            CompressFormat.entries.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.name.replace('_', '.').lowercase()) },
                                    onClick = { format = entry; formatMenuOpen = false },
                                )
                            }
                            DropdownMenuItem(text = { Text("7z (coming soon)") }, onClick = {}, enabled = false)
                        }
                    }
                }

                Column {
                    Text("Compression level", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow {
                        CompressLevelTier.entries.forEachIndexed { index, tier ->
                            SegmentedButton(
                                selected = level == tier,
                                onClick = { level = tier },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = CompressLevelTier.entries.size),
                            ) { Text(tier.name.lowercase().replaceFirstChar(Char::uppercase)) }
                        }
                    }
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Split into parts")
                        Text(
                            splitBytes?.let { "at ${SPLIT_CHOICES[splitIndex].first}" } ?: "Off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { splitMenuOpen = true }) { Text(if (splitBytes == null) "Choose…" else "Change…") }
                    DropdownMenu(expanded = splitMenuOpen, onDismissRequest = { splitMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Off") }, onClick = { splitIndex = -1; splitMenuOpen = false })
                        SPLIT_CHOICES.forEachIndexed { index, (label, _) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { splitIndex = index; splitMenuOpen = false })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Paths relative to the selection")
                        Text(
                            "Off keeps each item's full path below where it was selected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = relative, onCheckedChange = { relative = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onChooseFolder(draft()) }) { Text("Choose folder…") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onSaveAs(draft()) }, enabled = splitBytes == null) { Text("Save as…") }
            }
        },
    )
}
