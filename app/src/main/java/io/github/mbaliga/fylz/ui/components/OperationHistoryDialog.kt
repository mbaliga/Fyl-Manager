package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationState
import java.text.DateFormat
import java.util.Date

/** User-visible history for durable file operations. */
@Composable
fun OperationHistoryDialog(
    operations: List<FileOperation>,
    onDismiss: () -> Unit,
    onClearFinished: () -> Unit,
    onRemove: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activity") },
        text = {
            if (operations.isEmpty()) {
                Text("No file operations have been recorded yet.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(operations, key = FileOperation::id) { operation ->
                        OperationHistoryRow(
                            operation = operation,
                            onRemove = { onRemove(operation.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(
                onClick = onClearFinished,
                enabled = operations.any { operation ->
                    operation.state == OperationState.SUCCEEDED ||
                        operation.state == OperationState.FAILED ||
                        operation.state == OperationState.CANCELLED
                },
            ) {
                Text("Clear finished")
            }
        },
    )
}

@Composable
private fun OperationHistoryRow(
    operation: FileOperation,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = operationStateIcon(operation.state),
                contentDescription = null,
                tint = operationStateColor(operation.state),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = operation.type.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = operation.items.joinToString { it.displayName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = operation.state.displayName(),
                style = MaterialTheme.typography.labelMedium,
                color = operationStateColor(operation.state),
            )
        }

        operation.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = operation.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (operation.state == OperationState.SUCCEEDED ||
                operation.state == OperationState.FAILED ||
                operation.state == OperationState.CANCELLED
            ) {
                TextButton(onClick = onRemove) { Text("Dismiss") }
            }
        }

        if (operation.state == OperationState.NEEDS_ATTENTION) {
            Text(
                text = "This operation was interrupted. Verify the source and destination before retrying.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun FileOperation.summary(): String {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(updatedAtMillis))
    val completed = completedBytes.takeIf { it > 0 }?.let(::formatBytes)
    val total = totalBytes?.takeIf { it > 0 }?.let(::formatBytes)
    val bytes = when {
        completed != null && total != null -> "$completed of $total"
        completed != null -> completed
        total != null -> total
        else -> null
    }
    val error = items.firstNotNullOfOrNull { it.errorCode }
    return listOfNotNull(time, bytes, error?.replace('_', ' ')?.lowercase()).joinToString(" · ")
}

private fun FileOperationType.displayName(): String = when (this) {
    FileOperationType.COPY -> "Copy"
    FileOperationType.MOVE -> "Move"
    FileOperationType.RECYCLE -> "Move to Recycle Bin"
    FileOperationType.RESTORE -> "Restore"
    FileOperationType.PERMANENT_DELETE -> "Permanent deletion"
    FileOperationType.RENAME -> "Rename"
    FileOperationType.CREATE_DIRECTORY -> "Create folder"
    FileOperationType.CREATE_FILE -> "Create file"
    FileOperationType.ARCHIVE -> "Create archive"
    FileOperationType.EXTRACT -> "Extract archive"
}

private fun OperationState.displayName(): String = when (this) {
    OperationState.QUEUED -> "Queued"
    OperationState.PREFLIGHT -> "Checking"
    OperationState.RUNNING -> "Running"
    OperationState.PAUSED -> "Paused"
    OperationState.SUCCEEDED -> "Complete"
    OperationState.FAILED -> "Failed"
    OperationState.CANCELLED -> "Cancelled"
    OperationState.NEEDS_ATTENTION -> "Check required"
}

@Composable
private fun operationStateColor(state: OperationState) = when (state) {
    OperationState.SUCCEEDED -> MaterialTheme.colorScheme.primary
    OperationState.FAILED, OperationState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
    OperationState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.secondary
}

private fun operationStateIcon(state: OperationState) = when (state) {
    OperationState.SUCCEEDED -> Icons.Outlined.CheckCircle
    OperationState.FAILED -> Icons.Outlined.ErrorOutline
    OperationState.CANCELLED -> Icons.Outlined.Cancel
    OperationState.PAUSED -> Icons.Outlined.PauseCircle
    OperationState.NEEDS_ATTENTION -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.HourglassTop
}

private fun formatBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unit = -1
    while (amount >= 1024.0 && unit < units.lastIndex) {
        amount /= 1024.0
        unit += 1
    }
    return "%.1f %s".format(amount, units[unit])
}
