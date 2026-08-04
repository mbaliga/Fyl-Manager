package io.github.mbaliga.fylz.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.operations.FileOperation
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRetryPlan
import io.github.mbaliga.fylz.operations.OperationRetryPolicy
import io.github.mbaliga.fylz.operations.OperationState
import io.github.mbaliga.fylz.ui.components.OperationHistoryDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
