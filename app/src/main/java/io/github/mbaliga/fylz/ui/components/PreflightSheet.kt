package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.operations.PreflightItem
import io.github.mbaliga.fylz.operations.PreflightPolicy
import io.github.mbaliga.fylz.operations.PreflightProblem
import io.github.mbaliga.fylz.operations.PreflightResult

/** The per-item choice a [PreflightSheet] row offers, per P1.5's own three-choice UI: auto-rename,
 * skip this item, or (handled outside any one row, as [PreflightSheet.onCancel]) cancel the whole
 * operation. */
enum class PreflightChoice { AUTO_RENAME, SKIP }

/**
 * P1.5: shown before a copy or move actually starts, when [PreflightPolicy.evaluate] finds a
 * problem that would otherwise only surface mid-transfer, one item at a time, as a bare provider
 * exception -- with no chance to fix the name or leave just that item out first. Every affected
 * item needs a decision (auto-rename, only offered when [PreflightPolicy.sanitizedName] can
 * actually fix the problem, or skip) before [onProceed] runs; [onCancel] refuses the whole
 * operation instead.
 */
@Composable
fun PreflightSheet(
    result: PreflightResult,
    onCancel: () -> Unit,
    onProceed: (skipped: Set<Uri>, renamed: Map<Uri, String>) -> Unit,
) {
    val byItem = remember(result) { result.problems.groupBy { it.item } }
    val choices = remember(result) {
        mutableStateMapOf<Uri, PreflightChoice>().apply {
            byItem.forEach { (item, problems) ->
                put(item.sourceUri, if (isAutoRenameFixable(problems)) PreflightChoice.AUTO_RENAME else PreflightChoice.SKIP)
            }
        }
    }
    val everyItemDecided = byItem.keys.all { choices.containsKey(it.sourceUri) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Some items need attention", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The destination can't accept these as they are. Choose how to handle each one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                result.insufficientSpace?.let { insufficient ->
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(
                        "Not enough free space: this needs about ${formatPreflightBytes(insufficient.requiredBytes)}, " +
                            "but only ${formatPreflightBytes(insufficient.availableBytes)} is free.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (byItem.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(byItem.entries.toList(), key = { it.key.sourceUri }) { (item, problems) ->
                            PreflightItemRow(
                                item = item,
                                problems = problems,
                                choice = choices[item.sourceUri],
                                onChoiceChange = { choices[item.sourceUri] = it },
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(
                        onClick = { onProceed(skippedUris(choices), renamedNames(choices, byItem.keys)) },
                        enabled = everyItemDecided,
                    ) { Text("Continue") }
                }
            }
        }
    }
}

@Composable
private fun PreflightItemRow(
    item: PreflightItem,
    problems: List<PreflightProblem>,
    choice: PreflightChoice?,
    onChoiceChange: (PreflightChoice) -> Unit,
) {
    val fixable = isAutoRenameFixable(problems)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.name, style = MaterialTheme.typography.titleSmall)
        problems.forEach { problem ->
            Text(
                problemDescription(problem),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fixable) {
                FilterChip(
                    selected = choice == PreflightChoice.AUTO_RENAME,
                    onClick = { onChoiceChange(PreflightChoice.AUTO_RENAME) },
                    label = { Text("Rename to \"${PreflightPolicy.sanitizedName(item.name)}\"") },
                )
            }
            FilterChip(
                selected = choice == PreflightChoice.SKIP,
                onClick = { onChoiceChange(PreflightChoice.SKIP) },
                label = { Text("Skip this item") },
            )
        }
    }
}

/** Whether every one of [problems] is something [PreflightPolicy.sanitizedName] can actually fix
 * -- a name collision, an over-length name, or a file too large for vfat has no character
 * substitution that helps, so those items only ever offer Skip. */
private fun isAutoRenameFixable(problems: List<PreflightProblem>): Boolean =
    problems.all { it is PreflightProblem.IllegalCharacters || it is PreflightProblem.TrailingSpaceOrDot }

private fun problemDescription(problem: PreflightProblem): String = when (problem) {
    is PreflightProblem.IllegalCharacters ->
        "Contains characters not allowed here: ${problem.characters.joinToString(" ")}"
    is PreflightProblem.TrailingSpaceOrDot -> "Ends with a space or a dot, which isn't allowed here"
    is PreflightProblem.NameCollision -> "Name collides with \"${problem.collidesWithName}\" at the destination"
    is PreflightProblem.NameTooLong -> "Name is longer than ${problem.limitBytes} bytes"
    is PreflightProblem.FileTooLargeForVfat -> "File is larger than 4 GiB, which this destination can't store"
}

private fun skippedUris(choices: Map<Uri, PreflightChoice>): Set<Uri> =
    choices.filterValues { it == PreflightChoice.SKIP }.keys

private fun renamedNames(choices: Map<Uri, PreflightChoice>, items: Set<PreflightItem>): Map<Uri, String> {
    val itemsByUri = items.associateBy { it.sourceUri }
    return choices.filterValues { it == PreflightChoice.AUTO_RENAME }.keys.associateWith { uri ->
        PreflightPolicy.sanitizedName(itemsByUri.getValue(uri).name)
    }
}

private fun formatPreflightBytes(bytes: Long): String {
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
