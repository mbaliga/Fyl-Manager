package io.github.mbaliga.fylz.ui.actions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.ConflictedItem
import io.github.mbaliga.fylz.operations.DocNode
import io.github.mbaliga.fylz.operations.ExtractConflict
import io.github.mbaliga.fylz.operations.ExtractLayoutRequest
import io.github.mbaliga.fylz.operations.ExtractPlanResult
import io.github.mbaliga.fylz.operations.ExtractPlanner
import io.github.mbaliga.fylz.operations.ExtractRequest
import io.github.mbaliga.fylz.operations.ExtractSelection
import io.github.mbaliga.fylz.operations.ExtractSummary
import io.github.mbaliga.fylz.operations.HereChoice
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRunner
import io.github.mbaliga.fylz.operations.PlannerUi
import io.github.mbaliga.fylz.operations.PreflightDecision
import io.github.mbaliga.fylz.operations.PreflightResult
import io.github.mbaliga.fylz.storage.VolumeInfo
import io.github.mbaliga.fylz.storage.VolumeInfoResolver
import io.github.mbaliga.fylz.ui.components.ConflictSheet
import io.github.mbaliga.fylz.ui.components.DestinationChooserSheet
import io.github.mbaliga.fylz.ui.components.PreflightSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The whole selective-extract flow (design `DESIGN-M34-SELECTIVE-EXTRACT.md` §2.1-2.2): the
 * Extract sheet's own three choices (`fylz.extract`), the destination chooser for `.to`/inside-an-
 * archive's `.selected`, [ExtractPlanner.plan]'s own back-and-forth (a "Reading archive…" dialog
 * with Cancel, the vfat/top-level preflight, top-level conflicts, and the consent-aware confirm),
 * then handing the planned operation to the transfer queue exactly like a copy or move.
 *
 * One instance lives for `FylzV1Workspace`'s composition (`remember`); [Content] renders whichever
 * one of its own dialogs is current, alongside `FylzV1App`'s other flow dialogs. A whole encrypted
 * ZIP ([ExtractPlanResult.LegacyEncryptedZip]) is handed to [onLegacyEncryptedZip] rather than run
 * here -- that path is `data.ArchiveService.extractZip` (zip4j) until M3.9/M3.10.
 *
 * Cancelling the "Reading archive…" dialog ([cancelReading]) only cancels *planning*: once a plan
 * is written to the journal, [enqueue] runs on its own coroutine so the actual extraction (and its
 * own notification/cancel) outlives whatever cancelled the dialog that started it.
 */
class ExtractFlow(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    catalog: ArchiveCatalog,
    volumeFor: suspend (destination: Uri) -> VolumeInfo?,
    private val journal: OperationJournal,
    private val operationRunner: OperationRunner,
    private val onLegacyEncryptedZip: (ArchiveRef) -> Unit,
    private val onToast: (String) -> Unit,
    private val onExtracted: () -> Unit,
) {
    private val planner = ExtractPlanner(resolver, catalog, volumeFor)

    /** One question [InteractiveUi] is currently asking, or the "Reading archive…" progress. */
    sealed interface Step {
        data object Reading : Step
        data class HereOrFolder(val rootCount: Int, val folderName: String, val respond: (HereChoice?) -> Unit) : Step
        data class Preflight(val result: PreflightResult, val respond: (PreflightDecision?) -> Unit) : Step
        data class Conflicts(val items: List<ExtractConflict>, val respond: (Map<Int, ConflictPolicy>?) -> Unit) : Step
        data class Confirm(val summary: ExtractSummary, val respond: (Boolean?) -> Unit) : Step
    }

    /** `fylz.extract`'s own sheet: the archive it opened for, or `null` when it is closed. */
    var menuArchive by mutableStateOf<ArchiveRef?>(null)
        private set

    /** Whether the in-app destination chooser (`.to`/`.selected`) is showing. */
    var destinationChooserOpen by mutableStateOf(false)
        private set

    var step by mutableStateOf<Step?>(null)
        private set

    /** What to do with the destination once one is known -- survives the chooser sheet closing
     * for "Other location…", which hides the sheet but must still resolve through the system
     * picker's own result; cleared by [chooseDestination]/[cancelDestinationChooser]. */
    private var pendingDestination: ((Uri) -> Unit)? = null

    /** The system folder picker's own `launch(null)`, set by [rememberExtractFlow] (only a
     * composable can `rememberLauncherForActivityResult`); `null` is a no-op, never a crash, for
     * any caller that builds an [ExtractFlow] by hand (a test). */
    var systemPicker: (() -> Unit)? = null

    /** The active tab's own current folder, re-set on every [rememberExtractFlow] recomposition
     * rather than taken as a frozen constructor closure -- `activeTab` is a plain recomposed
     * `val`, not a stable-backed state read, so a `remember`-frozen reference to it would go stale
     * the moment the active tab changed without also rebuilding this [ExtractFlow]. */
    var currentFolder: () -> Uri? = { null }

    private var job: Job? = null

    fun openMenu(archive: ArchiveRef) {
        menuArchive = archive
    }

    fun dismissMenu() {
        menuArchive = null
    }

    fun extractHere() {
        val archive = menuArchive ?: return
        val destination = currentFolder() ?: return
        menuArchive = null
        start { ExtractRequest(archive, ExtractSelection.All, destination, ExtractLayoutRequest.Here) }
    }

    fun extractIntoFolder() {
        val archive = menuArchive ?: return
        val destination = currentFolder() ?: return
        menuArchive = null
        start { intoFolderRequest(archive, destination) }
    }

    fun extractTo() {
        val archive = menuArchive ?: return
        menuArchive = null
        pendingDestination = { destination -> start { intoFolderRequest(archive, destination) } }
        destinationChooserOpen = true
    }

    /** `fylz.extract.selected` (a browsed archive's own selection bar): the destination chooser,
     * then [ids] extracted directly (`ExtractLayout.ENTRIES`, per [ExtractPlanner.plan]'s own
     * mapping of a `Here` layout over an `Entries` selection). */
    fun extractSelected(archive: ArchiveRef, ids: List<ArchiveDocumentId>) {
        if (ids.isEmpty()) return
        pendingDestination = { destination ->
            start { ExtractRequest(archive, ExtractSelection.Entries(ids), destination, ExtractLayoutRequest.Here) }
        }
        destinationChooserOpen = true
    }

    fun cancelDestinationChooser() {
        destinationChooserOpen = false
        pendingDestination = null
    }

    /** "Other location…": the in-app sheet hides and [systemPicker] takes over; its result still
     * resolves through [chooseDestination]. */
    fun useSystemPicker() {
        destinationChooserOpen = false
        systemPicker?.invoke()
    }

    fun chooseDestination(destination: Uri) {
        destinationChooserOpen = false
        val onChosen = pendingDestination
        pendingDestination = null
        onChosen?.invoke(destination)
    }

    /** "Reading archive…"'s own Cancel (design §2.2): only the planning phase. A plan already
     * written to the journal is queued through [enqueue] on a coroutine of its own, unaffected. */
    fun cancelReading() {
        job?.cancel()
        job = null
        step = null
    }

    private fun intoFolderRequest(archive: ArchiveRef, destination: Uri): ExtractRequest {
        val folderName = ExtractPlanner.folderNameFor(resolver, archive)
        return ExtractRequest(archive, ExtractSelection.All, destination, ExtractLayoutRequest.IntoFolder(folderName))
    }

    private fun start(buildRequest: suspend () -> ExtractRequest) {
        step = Step.Reading
        job = scope.launch {
            val request = withContext(Dispatchers.IO) { buildRequest() }
            val result = try {
                planner.plan(request, InteractiveUi())
            } finally {
                step = null
            }
            when (result) {
                is ExtractPlanResult.Planned -> enqueue(result)
                is ExtractPlanResult.Refused -> onToast(result.reason)
                ExtractPlanResult.Cancelled -> Unit
                ExtractPlanResult.LegacyEncryptedZip -> onLegacyEncryptedZip(request.archive)
            }
        }
    }

    private fun enqueue(planned: ExtractPlanResult.Planned) {
        scope.launch(Dispatchers.IO) {
            journal.putWithExtractPlan(planned.operation, planned.plan)
            operationRunner.enqueueExtract(planned.operation.id, "Extracting", planned.plan.items.size)
            onExtracted()
        }
    }

    private inner class InteractiveUi : PlannerUi {
        override suspend fun chooseHereLayout(rootCount: Int, folderName: String): HereChoice? =
            ask { respond -> Step.HereOrFolder(rootCount, folderName, respond) }

        override suspend fun resolvePreflight(preflight: PreflightResult): PreflightDecision? =
            ask { respond -> Step.Preflight(preflight, respond) }

        override suspend fun resolveConflicts(conflicts: List<ExtractConflict>): Map<Int, ConflictPolicy>? =
            ask { respond -> Step.Conflicts(conflicts, respond) }

        override suspend fun confirm(summary: ExtractSummary): Boolean? =
            ask { respond -> Step.Confirm(summary, respond) }
    }

    /** Shows the step [makeStep] builds, suspends for its answer, then shows "Reading archive…"
     * again while planning continues to the next question or the final result. */
    private suspend fun <T> ask(makeStep: ((T) -> Unit) -> Step): T = suspendCancellableCoroutine { continuation ->
        step = makeStep { value ->
            step = Step.Reading
            if (continuation.isActive) continuation.resume(value)
        }
        continuation.invokeOnCancellation { step = null }
    }
}

/** [ExtractConflict.itemIndex] round-tripped through a synthetic [Uri] -- what [ConflictSheet]
 * needs as a per-row key, since an archive entry has no real destination document [Uri] of its
 * own until it is written. Never opened for I/O ([ConflictedItem.hashable] is `false`). */
private fun conflictUri(itemIndex: Int): Uri = Uri.parse("fylz-extract-conflict:$itemIndex")

private fun ExtractConflict.toConflictedItem(): ConflictedItem = ConflictedItem(
    source = DocNode.descriptor(
        uri = conflictUri(itemIndex),
        name = name,
        size = bytes,
        isDirectory = isDirectory,
        lastModified = mtimeEpochSeconds?.times(1000L),
    ),
    existing = existing,
    hashable = false,
)

/**
 * Constructs one [ExtractFlow] for the composition ([remember]ed), wiring the two pieces only a
 * composable can supply: the system folder picker ([ExtractFlow.systemPicker], since only a
 * composable can `rememberLauncherForActivityResult`) and the application's own archive catalog,
 * journal and volume lookup. `FylzV1App.kt` supplies the rest -- what happens on a legacy
 * encrypted ZIP, a toast, and a completed extraction -- since those close over its own state.
 */
@Composable
fun rememberExtractFlow(
    context: Context,
    scope: CoroutineScope,
    operationRunner: OperationRunner,
    currentFolder: () -> Uri?,
    persistTreePermission: (Uri) -> Unit,
    onLegacyEncryptedZip: (ArchiveRef) -> Unit,
    onToast: (String) -> Unit,
    onExtracted: () -> Unit,
): ExtractFlow {
    val flow = remember {
        val app = context.applicationContext as FylzApplication
        ExtractFlow(
            scope = scope,
            resolver = context.contentResolver,
            catalog = app.archiveCatalog,
            volumeFor = { destination -> withContext(Dispatchers.IO) { VolumeInfoResolver.resolveForDestination(app, destination) } },
            journal = OperationJournal(app),
            operationRunner = operationRunner,
            onLegacyEncryptedZip = onLegacyEncryptedZip,
            onToast = onToast,
            onExtracted = onExtracted,
        )
    }
    flow.currentFolder = currentFolder
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            flow.chooseDestination(uri)
        }
    }
    flow.systemPicker = { picker.launch(null) }
    return flow
}

/** Renders whichever of [flow]'s own dialogs is current, alongside every other flow dialog
 * `FylzV1App.kt` renders. */
@Composable
fun ExtractFlowHost(
    flow: ExtractFlow,
    tabs: List<FolderTab>,
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
) {
    if (flow.menuArchive != null) {
        ExtractSheet(resolver = resolver, dispatcher = dispatcher, state = state, ctx = ctx, onDismiss = flow::dismissMenu)
    }

    if (flow.destinationChooserOpen) {
        DestinationChooserSheet(
            tabs = tabs,
            onChooseTab = { tab -> flow.chooseDestination(tab.current.uri) },
            onChooseRoot = { root -> root.documentUri?.let(flow::chooseDestination) },
            onOtherLocation = flow::useSystemPicker,
            onCancel = flow::cancelDestinationChooser,
        )
    }

    when (val current = flow.step) {
        null -> Unit
        ExtractFlow.Step.Reading -> ReadingArchiveDialog(onCancel = flow::cancelReading)
        is ExtractFlow.Step.HereOrFolder -> HereOrFolderDialog(current)
        is ExtractFlow.Step.Preflight -> PreflightSheet(
            result = current.result,
            onCancel = { current.respond(null) },
            // The `renamed` map is always empty here: every top-level name FAT sanitisation could
            // fix was already sanitised before this preflight ran (design §2.2 step 5), so nothing
            // this sheet flags is ever auto-rename-fixable -- see ExtractPlanner's own KDoc.
            onProceed = { skipped, _ -> current.respond(PreflightDecision(skip = skipped)) },
        )
        is ExtractFlow.Step.Conflicts -> {
            val byUri = current.items.associateBy { conflictUri(it.itemIndex) }
            ConflictSheet(
                conflicts = current.items.map { it.toConflictedItem() },
                onCancel = { current.respond(null) },
                onProceed = { resolutions ->
                    current.respond(resolutions.mapNotNull { (uri, policy) -> byUri[uri]?.itemIndex?.let { it to policy } }.toMap())
                },
            )
        }
        is ExtractFlow.Step.Confirm -> ExtractConfirmDialog(
            summary = current.summary,
            onCancel = { current.respond(null) },
            onConfirm = { current.respond(true) },
        )
    }
}

@Composable
private fun ReadingArchiveDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Reading archive…") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("This can take a moment for a large or compressed archive.")
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun HereOrFolderDialog(step: ExtractFlow.Step.HereOrFolder) {
    AlertDialog(
        onDismissRequest = { step.respond(null) },
        title = { Text("Extract here or into a folder?") },
        text = { Text("Extract ${step.rootCount} items directly here, or into \"${step.folderName}/\"?") },
        confirmButton = {
            Button(onClick = { step.respond(HereChoice.INTO_FOLDER) }) { Text("Into \"${step.folderName}/\"") }
        },
        dismissButton = {
            TextButton(onClick = { step.respond(HereChoice.HERE) }) { Text("Extract directly") }
        },
    )
}

/** The confirm sheet's own numbers (design §2.2 step 7): entries, expanded size and ratio, with an
 * explicit tick required before Extract is enabled whenever [ExtractSummary.needsConsent]. */
@Composable
private fun ExtractConfirmDialog(
    summary: ExtractSummary,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var consented by remember(summary) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Extract ${summary.itemCount} item(s)?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${summary.entryCount} entries · ${formatExtractBytes(summary.totalBytes)}")
                summary.ratio?.let { ratio -> Text("Expands the archive about ${"%.1f".format(ratio)}×") }
                if (summary.skippedLinks > 0) {
                    Text(
                        "${summary.skippedLinks} link(s)/special file(s) will be skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.sanitizedComponents > 0) {
                    Text(
                        "${summary.sanitizedComponents} name(s) will be adjusted for this destination.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.needsConsent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consented, onCheckedChange = { consented = it })
                        Text("This is a large extraction; I want to continue anyway.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !summary.needsConsent || consented) { Text("Extract") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun formatExtractBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.1f KiB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}
