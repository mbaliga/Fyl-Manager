package io.github.mbaliga.fylz.ui

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRetryPlan
import io.github.mbaliga.fylz.operations.OperationRetryPolicy
import io.github.mbaliga.fylz.operations.OperationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class RootDestination {
    FILES,
    RECOVERY,
}

/** App-level chrome for the file workspace and durable storage/recovery tools. */
@Composable
fun FylzAppShell() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val journal = remember { OperationJournal(context.applicationContext) }
    val fileOperations = remember { FileOperationService(context.applicationContext) }
    var destination by remember { mutableStateOf(RootDestination.FILES) }
    var showHistory by remember { mutableStateOf(false) }
    var operations by remember { mutableStateOf(journal.list()) }

    LaunchedEffect(Unit) {
        while (true) {
            operations = journal.list()
            delay(1_000)
        }
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = destination == RootDestination.FILES,
                        onClick = { destination = RootDestination.FILES },
                        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        label = { Text("Files") },
                    )
                    NavigationBarItem(
                        selected = destination == RootDestination.RECOVERY,
                        onClick = { destination = RootDestination.RECOVERY },
                        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
                        label = { Text("Recovery") },
                    )
                }
            },
        ) { padding ->
            when (destination) {
                RootDestination.FILES -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    FylzV1App()
                }
                RootDestination.RECOVERY -> RecoveryHome(
                    operations = operations,
                    onOpenOperations = {
                        operations = journal.list()
                        showHistory = true
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }

        if (showHistory) {
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
                onRetry = { operation ->
                    val plan = OperationRetryPolicy.plan(operation)
                    if (plan == null) {
                        Toast.makeText(context, "This operation cannot be retried safely.", Toast.LENGTH_LONG).show()
                    } else {
                        scope.launch {
                            runCatching {
                                when (plan) {
                                    is OperationRetryPlan.Transfer -> when (plan.type) {
                                        FileOperationType.COPY -> fileOperations.copy(
                                            sourceUris = plan.sourceUris,
                                            destinationTreeUri = plan.destinationTreeUri,
                                            conflictPolicy = plan.conflictPolicy,
                                        )
                                        FileOperationType.MOVE -> fileOperations.move(
                                            sourceUris = plan.sourceUris,
                                            destinationTreeUri = plan.destinationTreeUri,
                                            conflictPolicy = plan.conflictPolicy,
                                        )
                                        else -> error("Unsupported retry type.")
                                    }
                                    is OperationRetryPlan.FinishMoveCleanup ->
                                        fileOperations.finishMoveCleanup(plan.operationId)
                                }
                            }.onSuccess {
                                Toast.makeText(context, "Recovery action completed.", Toast.LENGTH_LONG).show()
                            }.onFailure { failure ->
                                Toast.makeText(
                                    context,
                                    failure.message ?: "Recovery action failed.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            operations = journal.list()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RecoveryHome(
    operations: List<FileOperation>,
    onOpenOperations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val attentionCount = operations.count { it.state == OperationState.NEEDS_ATTENTION }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("Storage & recovery", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Review file operations, restore earlier file versions, manage backups, and safely work with ZIP archives.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RecoveryActionCard(
            icon = Icons.Outlined.History,
            title = "Operation history",
            description = if (attentionCount == 0) {
                "Review completed, failed, cancelled, and interrupted file operations."
            } else {
                "$attentionCount operation${if (attentionCount == 1) "" else "s"} need attention."
            },
            action = {
                FloatingActionButton(onClick = onOpenOperations) {
                    Icon(Icons.Outlined.History, contentDescription = "Open operation history")
                }
            },
        )
        RecoveryActionCard(
            icon = Icons.Outlined.History,
            title = "File history",
            description = "Configure local version retention, inspect saved versions, and perform verified restores.",
            action = { FileHistoryOverlay() },
        )
        RecoveryActionCard(
            icon = Icons.Outlined.Backup,
            title = "Backup plans",
            description = "Create, schedule, run, inspect, restore, and delete transactional backups.",
            action = { BackupOverlay() },
        )
        RecoveryActionCard(
            icon = Icons.Outlined.RestorePage,
            title = "Import existing backups",
            description = "Rediscover verified manifest-bearing backup folders after reinstall or app-data loss.",
            action = { BackupImportOverlay() },
        )
        RecoveryActionCard(
            icon = Icons.Outlined.Archive,
            title = "Archive tools",
            description = "Create standard or AES-256 protected ZIP files, inspect archives, and extract through safety limits.",
            action = { ArchiveToolsOverlay() },
        )

        Text(
            "Recovery metadata stays private to this app and is excluded from Android cloud backup. External backup snapshots and archives remain in the destinations you selected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun RecoveryActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    action: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

@Composable
private fun OperationHistoryDialog(
    operations: List<FileOperation>,
    onDismiss: () -> Unit,
    onClearFinished: () -> Unit,
    onDismissOperation: (String) -> Unit,
    onRetry: (FileOperation) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Operation history", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Copy, move, recycle, restore, delete, archive, extraction, and rename activity stored on this device.",
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
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
                                onRetry = { onRetry(operation) },
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
    onRetry: () -> Unit,
) {
    val presentation = operation.state.presentation()
    val pendingMoveCleanup = operation.items.any {
        it.errorCode == OperationRetryPolicy.MOVE_SOURCE_DELETE_PENDING
    }
    Surface(
        color = if (operation.state == OperationState.NEEDS_ATTENTION) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(14.dp)) {
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
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "${presentation.label} · ${formatOperationTime(operation.updatedAtMillis)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (operation.state == OperationState.NEEDS_ATTENTION) {
                    Text(
                        if (pendingMoveCleanup) {
                            "The destination copy verified, but the provider did not remove the original. Finish move retries source cleanup only."
                        } else {
                            "The operation needs review before a safe recovery action can continue."
                        },
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
                if (OperationRetryPolicy.canRetry(operation)) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Replay, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(OperationRetryPolicy.actionLabel(operation))
                    }
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
