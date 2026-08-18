package io.github.mbaliga.fylz.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.operations.FileOperation
import io.github.mbaliga.fylz.core.operations.FileOperationType
import io.github.mbaliga.fylz.core.operations.OperationRetryPlan
import io.github.mbaliga.fylz.core.operations.OperationRetryPolicy
import io.github.mbaliga.fylz.core.operations.OperationState
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.storage.toUri
import io.github.mbaliga.fylz.ui.components.OperationHistoryDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-level chrome for the file workspace and durable storage/recovery tools.
 *
 * There is no chrome left. Recovery used to be one of two `NavigationBarItem`s across the
 * bottom of the app; it became a **room** — a surface parked off the bottom edge that the file
 * workspace lifts and parts to reveal — and it is now the last *section* of that room, which is
 * Actions. That verdict was about screens, not folders: a bottom `NavigationBar` picking between
 * Files and Recovery is still gone, and stays gone, for the reason the owner gave after the first
 * test build (not the fonebrew pattern, and two permanent tabs for a screen most sessions never
 * open is chrome charging rent). Folder tabs are a different thing wearing the same word — opened
 * and closed by what the session is actually doing, not two fixed destinations nobody chooses
 * between — and now live in their own strip inside [io.github.mbaliga.fylz.ui.components.CommandPill],
 * not a second app-level nav surface.
 *
 * The edge did not move and neither did the gesture; what changed is what shares it. Finishing
 * an interrupted move is an action on files, so it sits with the other actions on files rather
 * than owning a whole surface for a screen most sessions never open.
 *
 * This function keeps only what Recovery *needs* — the journal, its polling, and the operation
 * history dialog — and hands the section down as content. That matters for more than tidiness:
 * the content is composed inside [FylzV1App]'s theme, so Recovery finally paints in the app's own
 * colours instead of the bare `MaterialTheme` default it used to sit in.
 */
@Composable
fun FylzAppShell() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val journal = remember { OperationJournal(context.applicationContext) }
    val fileOperations = remember { FileOperationService(context.applicationContext) }
    var showHistory by remember { mutableStateOf(false) }
    // One open flag per recovery card. Each overlay now hosts its own dialogs; the shell's only
    // job is to remember which card was tapped and hand that boolean down to the matching host.
    var showFileHistory by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showBackupImport by remember { mutableStateOf(false) }
    var showArchiveTools by remember { mutableStateOf(false) }
    var operations by remember { mutableStateOf(journal.list()) }

    LaunchedEffect(Unit) {
        while (true) {
            operations = journal.list()
            delay(1_000)
        }
    }

    FylzV1App(
        recoverySection = {
            RecoveryHome(
                operations = operations,
                onOpenOperations = {
                    operations = journal.list()
                    showHistory = true
                },
                onOpenFileHistory = { showFileHistory = true },
                onOpenBackup = { showBackup = true },
                onOpenBackupImport = { showBackupImport = true },
                onOpenArchiveTools = { showArchiveTools = true },
            )
        },
    ) { showHidden ->
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
                                            sourceUris = plan.sourceRefs.map { it.toUri() },
                                            destinationTreeUri = plan.destinationRef.toUri(),
                                            conflictPolicy = plan.conflictPolicy,
                                        )
                                        FileOperationType.MOVE -> fileOperations.move(
                                            sourceUris = plan.sourceRefs.map { it.toUri() },
                                            destinationTreeUri = plan.destinationRef.toUri(),
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
        FileHistoryHost(open = showFileHistory, onDismiss = { showFileHistory = false })
        BackupHost(open = showBackup, onDismiss = { showBackup = false })
        BackupImportHost(open = showBackupImport, onDismiss = { showBackupImport = false })
        ArchiveToolsHost(
            open = showArchiveTools,
            showHidden = showHidden,
            onDismiss = { showArchiveTools = false },
        )
    }
}

/**
 * The recovery section of the actions room.
 *
 * It owns neither its scroll nor its heading any more — the room does both, and a `verticalScroll`
 * nested inside the room's own would be a crash, not a style choice. What is left is the card
 * carousel and the sentence explaining what it is for. Every card is the same [ActionCard] the
 * rest of the room uses, whole-card clickable — there is no FAB left to find inside one.
 */
@Composable
private fun RecoveryHome(
    operations: List<FileOperation>,
    onOpenOperations: () -> Unit,
    onOpenFileHistory: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenBackupImport: () -> Unit,
    onOpenArchiveTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val attentionCount = operations.count { it.state == OperationState.NEEDS_ATTENTION }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            "Review file operations, restore earlier file versions, manage backups, and safely work with ZIP archives.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ActionCardRow {
            item {
                ActionCard(
                    icon = Icons.Outlined.History,
                    title = "Operation history",
                    subtitle = if (attentionCount == 0) {
                        "Review past file operations"
                    } else {
                        "$attentionCount operation${if (attentionCount == 1) "" else "s"} need attention"
                    },
                    onClick = onOpenOperations,
                )
            }
            item {
                ActionCard(
                    icon = Icons.Outlined.History,
                    title = "File history",
                    subtitle = "Restore an earlier version",
                    onClick = onOpenFileHistory,
                )
            }
            item {
                ActionCard(
                    icon = Icons.Outlined.Backup,
                    title = "Backup plans",
                    subtitle = "Schedule automatic backups",
                    onClick = onOpenBackup,
                )
            }
            item {
                ActionCard(
                    icon = Icons.Outlined.RestorePage,
                    title = "Import backups",
                    subtitle = "Rediscover backup folders",
                    onClick = onOpenBackupImport,
                )
            }
            item {
                ActionCard(
                    icon = Icons.Outlined.Archive,
                    title = "Archive tools",
                    subtitle = "Create or extract a ZIP",
                    onClick = onOpenArchiveTools,
                )
            }
        }

        Text(
            "Recovery metadata stays private to this app and is excluded from Android cloud backup. External backup snapshots and archives remain in the destinations you selected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
