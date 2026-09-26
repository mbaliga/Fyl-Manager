package io.github.mbaliga.fylz.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRetryPolicy
import io.github.mbaliga.fylz.operations.OperationState
import io.github.mbaliga.fylz.operations.RetryDispatcher
import io.github.mbaliga.fylz.ui.components.OperationHistoryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * App-level chrome for the file workspace and durable storage/recovery tools.
 *
 * There is no chrome left. Recovery used to be one of two `NavigationBarItem`s across the
 * bottom of the app; it is a **room** now — a surface parked off the bottom edge that the file
 * workspace lifts and parts to reveal, rendered by [FylzV1App]'s `SpatialShell`. Tabs went for
 * the reason the owner gave after the first test build: a bottom tab bar is not the fonebrew
 * pattern, and two permanent tabs for a screen most sessions never open is chrome charging rent.
 *
 * The room's content itself is built by [FylzV1App]/`FylzV1Workspace` straight from the action
 * registry (design MC.0d) -- one `RecoveryActionCard` per resolved `Room(RECOVERY)` action, in
 * `ui/actions/RoomActionsRenderer.kt` -- since that is where the registry, resolver and dispatcher
 * already live. This function keeps only what Recovery *needs* that the registry can't supply
 * itself: the operations journal, and `showHistory`/`operationsNeedingAttention`, threaded down so
 * the Operation-history card's FAB and its attention count stay wired to this journal instance.
 */
@Composable
fun FylzAppShell(viewUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val journal = remember { OperationJournal(context.applicationContext) }
    // P1.11: this journal instance, not a second default-constructed one -- see OperationJournal's
    // own KDoc for why retrying through a DIFFERENT instance would leave `operations` below stale.
    val fileOperations = remember { FileOperationService(context.applicationContext, journal = journal) }
    // M3.4/M3.5: retry dispatch (operations/RetryDispatcher.kt); a re-claimed run is re-enqueued.
    val retries = remember {
        val runner = (context.applicationContext as FylzApplication).operationRunner
        RetryDispatcher(
            fileOperations = fileOperations,
            journal = journal,
            enqueueExtract = { id -> runner.enqueueExtract(id) },
            enqueueCreate = { id -> runner.enqueueCreate(id) },
        )
    }
    var showHistory by remember { mutableStateOf(false) }
    // P1.11: journal.operations replaces the old 1-second poll (a plain SQLite read directly on
    // Compose's own dispatcher, forever, for the app's whole lifetime) -- see OperationJournal's
    // own KDoc.
    val operations by journal.operations.collectAsState()
    // Also handed to FylzV1App/FylzV1Workspace's own BrowserState (P1.10-style split state --
    // FylzAppShell owns showHistory and the operations journal, FylzV1Workspace owns everything
    // else a registry condition reads, including the Recovery room's own cards) via
    // onShowHistory/operationsNeedingAttention below.
    val attentionCount = operations.count { it.state == OperationState.NEEDS_ATTENTION }

    FylzV1App(
        viewUri = viewUri,
        onShowHistory = { showHistory = true },
        operationsNeedingAttention = attentionCount,
    ) {
        if (showHistory) {
            OperationHistoryDialog(
                operations = operations,
                onDismiss = { showHistory = false },
                onClearFinished = {
                    scope.launch(Dispatchers.IO) { journal.clearFinished() }
                },
                onDismissOperation = { id ->
                    scope.launch(Dispatchers.IO) { journal.remove(id) }
                },
                onRetry = { operation ->
                    val plan = OperationRetryPolicy.plan(operation)
                    if (plan == null) {
                        Toast.makeText(context, "This operation cannot be retried safely.", Toast.LENGTH_LONG).show()
                    } else {
                        scope.launch {
                            runCatching { retries.dispatch(plan) }.onSuccess {
                                Toast.makeText(context, "Recovery action completed.", Toast.LENGTH_LONG).show()
                            }.onFailure { failure ->
                                Toast.makeText(context, failure.message ?: "Recovery action failed.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
            )
        }
    }
}
