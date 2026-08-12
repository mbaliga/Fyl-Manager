package io.github.mbaliga.fylz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.core.operations.OperationState
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import java.text.DateFormat
import java.util.Date

class OperationHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FylzTheme(
                themeMode = ThemeMode.SYSTEM,
                accentPreset = AccentPreset.MOSS,
                dynamicColor = true,
            ) {
                OperationHistoryScreen(
                    journal = remember { OperationJournal(applicationContext) },
                    onBack = ::finish,
                )
            }
        }
    }
}

@Composable
private fun OperationHistoryScreen(
    journal: OperationJournal,
    onBack: () -> Unit,
) {
    var operations by remember { mutableStateOf(journal.list()) }
    var confirmClear by remember { mutableStateOf(false) }

    fun refresh() {
        operations = journal.list()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = operations.any {
                            it.state == OperationState.SUCCEEDED ||
                                it.state == OperationState.FAILED ||
                                it.state == OperationState.CANCELLED
                        },
                    ) {
                        Text("Clear finished")
                    }
                },
            )
        },
    ) { padding ->
        if (operations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No file operations have been recorded yet.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    val interrupted = operations.count { it.state == OperationState.NEEDS_ATTENTION }
                    if (interrupted > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                "$interrupted interrupted operation${if (interrupted == 1) "" else "s"} need review. " +
                                    "Fylz does not automatically retry destructive work after process death.",
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                items(operations, key = FileOperation::id) { operation ->
                    OperationCard(operation)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear finished activity?") },
            text = {
                Text(
                    "Completed, failed, and cancelled records will be removed. " +
                        "Interrupted operations will remain visible until reviewed.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        journal.clearFinished()
                        confirmClear = false
                        refresh()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun OperationCard(operation: FileOperation) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        operation.type.name.replace('_', ' ').lowercase().replaceFirstChar(Char::titlecase),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(operation.updatedAtMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    operation.state.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor(operation.state),
                )
            }

            operation.progress?.let { progress ->
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatBytes(operation.completedBytes)} of ${formatBytes(operation.totalBytes ?: 0L)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            operation.items.take(5).forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        item.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val details = buildList {
                        add(item.state.name.replace('_', ' '))
                        item.errorCode?.let(::add)
                        item.expectedBytes?.let { expected ->
                            add("${formatBytes(item.completedBytes)} / ${formatBytes(expected)}")
                        }
                    }.joinToString(" · ")
                    Text(
                        details,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (operation.items.size > 5) {
                Text(
                    "+${operation.items.size - 5} more items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun stateColor(state: OperationState) = when (state) {
    OperationState.SUCCEEDED -> MaterialTheme.colorScheme.primary
    OperationState.FAILED,
    OperationState.NEEDS_ATTENTION,
    -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index += 1
    }
    return "%.1f %s".format(value, units[index])
}
