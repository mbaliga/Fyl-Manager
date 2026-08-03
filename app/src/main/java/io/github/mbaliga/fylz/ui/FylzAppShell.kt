package io.github.mbaliga.fylz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** App-level chrome that keeps durable operation recovery visible without coupling it to browsing. */
@Composable
fun FylzAppShell() {
    val context = LocalContext.current
    val journal = remember { OperationJournal(context.applicationContext) }
    var showHistory by remember { mutableStateOf(false) }
    var operations by remember { mutableStateOf(journal.list()) }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            FylzV1App()

            OperationHistoryButton(
                attentionCount = operations.count { it.state == OperationState.NEEDS_ATTENTION },
                onClick = {
                    operations = journal.list()
                    showHistory = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 88.dp),
            )
        }

        if (showHistory) {
            LaunchedEffect(Unit) {
                while (true) {
                    operations = journal.list()
                    delay(1_000)
                }
            }
            OperationHistoryDialog(
                operations = operations,
                onDismiss = { showHistory = false },
                onClearFinished = {
                    journal.clearFinished()
                    operations = journal.list()
                },
                onDismissOperation = { id ->
                    journal.remove(id)
                    operations = journal.list()
                },
            )
        }
    }
}

@Composable
private fun OperationHistoryButton(
    attentionCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        FloatingActionButton(onClick = onClick) {
            Icon(Icons.Outlined.History, contentDescription = "Operation history")
        }
        if (attentionCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text(
                    text = attentionCount.coerceAtMost(99).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun OperationHistoryDialog(
    operations: List<FileOperation>,
    onDismiss: () -> Unit,
    onClearFinished: () -> Unit,
    onDismissOperation: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Operation history", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Copy, move, recycle, restore, delete, and rename activity stored on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close operation history")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                if (operations.isEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(36.dp))
                        Text("No recorded operations", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "New file operations will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        items(operations, key = FileOperation::id) { operation ->
                            OperationHistoryCard(
                                operation = operation,
                                onDismiss = { onDismissOperation(operation.id) },
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = onClearFinished,
                        enabled = operations.any {
                            it.state == OperationState.SUCCEEDED ||
                                it.state == OperationState.FAILED ||
                                it.state == OperationState.CANCELLED
                        },
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear finished")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun OperationHistoryCard(
    operation: FileOperation,
    onDismiss: () -> Unit,
) {
    val presentation = operation.state.presentation()
    Surface(
        color = if (operation.state == OperationState.NEEDS_ATTENTION) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                tint = if (operation.state == OperationState.NEEDS_ATTENTION) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${operation.type.label()} · ${operation.items.size} ${if (operation.items.size == 1) "item" else "items"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                operation.items.firstOrNull()?.let { first ->
                    Text(
                        first.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                operation.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "${presentation.label} · ${formatOperationTime(operation.updatedAtMillis)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (operation.state == OperationState.NEEDS_ATTENTION) {
                    Text(
                        "The app stopped before completion. Check the source and destination before retrying.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                operation.items.mapNotNull { it.errorCode }.distinct().takeIf { it.isNotEmpty() }?.let { codes ->
                    Text(
                        codes.joinToString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (operation.state in dismissibleStates) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss operation record")
                }
            }
        }
    }
}

private data class StatePresentation(val label: String, val icon: ImageVector)

private fun OperationState.presentation(): StatePresentation = when (this) {
    OperationState.QUEUED -> StatePresentation("Queued", Icons.Outlined.Schedule)
    OperationState.PREFLIGHT -> StatePresentation("Checking", Icons.Outlined.Schedule)
    OperationState.RUNNING -> StatePresentation("Running", Icons.Outlined.Schedule)
    OperationState.PAUSED -> StatePresentation("Paused", Icons.Outlined.Schedule)
    OperationState.SUCCEEDED -> StatePresentation("Completed", Icons.Outlined.CheckCircle)
    OperationState.FAILED -> StatePresentation("Failed", Icons.Outlined.ErrorOutline)
    OperationState.CANCELLED -> StatePresentation("Cancelled", Icons.Outlined.Cancel)
    OperationState.NEEDS_ATTENTION -> StatePresentation("Needs attention", Icons.Outlined.WarningAmber)
}

private fun FileOperationType.label(): String = name
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

private fun formatOperationTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))

private val dismissibleStates = setOf(
    OperationState.SUCCEEDED,
    OperationState.FAILED,
    OperationState.CANCELLED,
    OperationState.NEEDS_ATTENTION,
)
