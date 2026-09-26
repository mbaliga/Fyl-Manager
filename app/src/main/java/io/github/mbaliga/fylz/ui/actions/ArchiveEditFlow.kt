package io.github.mbaliga.fylz.ui.actions

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.model.BrowsableArchiveFormats
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.operations.ArchiveEditRequest
import io.github.mbaliga.fylz.operations.EditPlanResult
import io.github.mbaliga.fylz.operations.EditPlanner
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRunner
import io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What [archiveEditContext] found: the archive being browsed (its [ArchiveRef], never nested --
 * M3.6's own scope narrowing), the folder that holds it (never an [ArchiveDocumentsProvider] Uri,
 * since [ArchiveEditFlow] writes the replacement archive there through the ordinary SAF tree), and
 * whether it is a ZIP-family archive at all -- the gate `BuiltInActions.kt`'s
 * `ARCHIVE_ENTRY_WRITABLE` already applied to reach here, re-checked so a stale caller can never
 * force an edit of a format that has no writer.
 */
data class ArchiveEditContext(val archive: ArchiveRef, val containingFolder: Uri, val isZipFamily: Boolean)

/**
 * `null` when the current location is not inside an archive at all, or that archive is nested
 * inside another one (M3.6's own top-level-only scope, checked against [tab]'s current location
 * itself -- never assumed from where the archive-authority run of locations happens to start,
 * which for a zip-inside-zip would name the *outer* archive's own chain, empty, while the browser
 * is actually inside the *inner* one). Once the current archive is confirmed top-level, its own
 * root location -- the one `openEntry` pushed when the `.zip` was opened -- is exactly the first
 * archive-authority location in the whole stack (there being no nesting to account for), found by
 * walking from the end for the last *non*-archive location; the one right before it is the real
 * folder that holds the archive file.
 */
fun archiveEditContext(tab: FolderTab?): ArchiveEditContext? {
    val locations = tab?.locations ?: return null
    val currentUri = locations.lastOrNull()?.uri ?: return null
    if (!ArchiveDocumentsProvider.isArchiveUri(currentUri)) return null
    val archive = runCatching { ArchiveDocumentId.parse(currentUri).archive }.getOrNull() ?: return null
    if (archive.chain.isNotEmpty()) return null
    val archiveStart = locations.indexOfLast { !ArchiveDocumentsProvider.isArchiveUri(it.uri) } + 1
    if (archiveStart <= 0 || archiveStart >= locations.size) return null
    val rootName = locations[archiveStart].name
    return ArchiveEditContext(archive, locations[archiveStart - 1].uri, BrowsableArchiveFormats.isZipFamily(rootName))
}

/** The folder inside the archive currently being browsed (`""` for the root), for `addEntries`'s
 * own [ArchiveEditRequest.addAtPath]. */
private fun currentArchivePath(tab: FolderTab?): String =
    tab?.current?.uri?.let { uri -> runCatching { ArchiveDocumentId.parse(uri).path }.getOrNull() } ?: ""

/**
 * `fylz.rename`/`fylz.recycle` acting on a ZIP-family archive's own entries, and
 * `fylz.archive.add-entries` (M3.6, `docs/agent/MASTER_PLAN.md`'s M3.6/`REVIEW_QUEUE.md`'s own
 * entry for the architecture this takes): each submits its own single-purpose [ArchiveEditRequest]
 * to [EditPlanner] and, once planned, queues it through the same `FileOperationType.ARCHIVE` path
 * M3.5's Compress sheet already uses ([OperationRunner.enqueueCreate]) -- there is no multi-step
 * "editing session"; every action here commits immediately, exactly as `fylz.rename`/`fylz.recycle`
 * already do for an ordinary folder.
 *
 * One instance lives for `FylzV1Workspace`'s composition ([rememberArchiveEditFlow], which also
 * renders whichever of its own dialogs is current).
 */
class ArchiveEditFlow(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    catalog: ArchiveCatalog,
    private val journal: OperationJournal,
    private val operationRunner: OperationRunner,
    private val onToast: (String) -> Unit,
    private val onEdited: () -> Unit,
) {
    private val planner = EditPlanner(resolver, catalog)

    /** A pending "Remove from archive?" confirmation, or `null` when none is showing. */
    var pendingDelete by mutableStateOf<PendingDelete?>(null)
        private set

    /** A pending rename text field, or `null` when none is showing. */
    var pendingRename by mutableStateOf<PendingRename?>(null)
        private set

    class PendingDelete(val context: ArchiveEditContext, val entries: List<FileEntry>)
    class PendingRename(val context: ArchiveEditContext, val entry: FileEntry)

    /** Where the multi-file picker's own result lands once one is chosen ([addEntries]); the
     * system launcher itself is [filesPicker], set by [rememberArchiveEditFlow]. */
    private var pendingAdditions: Pair<ArchiveEditContext, String>? = null

    var filesPicker: (() -> Unit)? = null

    /** [io.github.mbaliga.fylz.actions.ActionContext.recycleSelection]'s own delegate: a ZIP-family
     * archive location asks to confirm removing [selection] from it; anything else falls back to
     * [onOrdinaryLocation] (the folder recycle-to-bin path this action already had). */
    fun recycleOrDelegate(tab: FolderTab?, selection: List<FileEntry>, onOrdinaryLocation: () -> Unit) {
        val context = archiveEditContext(tab)
        if (context == null || !context.isZipFamily || selection.isEmpty()) {
            onOrdinaryLocation()
            return
        }
        pendingDelete = PendingDelete(context, selection)
    }

    /** As [recycleOrDelegate], for `fylz.rename` (already gated to exactly one selected entry). */
    fun renameOrDelegate(tab: FolderTab?, selection: List<FileEntry>, onOrdinaryLocation: () -> Unit) {
        val context = archiveEditContext(tab)
        val entry = selection.singleOrNull()
        if (context == null || !context.isZipFamily || entry == null) {
            onOrdinaryLocation()
            return
        }
        pendingRename = PendingRename(context, entry)
    }

    /** `fylz.archive.add-entries`: nothing to confirm first, straight to the picker. */
    fun addEntries(tab: FolderTab?) {
        val context = archiveEditContext(tab) ?: return
        if (!context.isZipFamily) return
        pendingAdditions = context to currentArchivePath(tab)
        filesPicker?.invoke()
    }

    /** `BrowserState.isZipFamilyArchiveLocation`'s own value: whether [tab]'s current location is
     * a ZIP-family archive this flow could edit. A plain method rather than a free function so
     * `FylzV1App.kt` (already holding this flow to call its other methods) needs no further import
     * to read it. */
    fun isZipFamilyArchiveLocation(tab: FolderTab?): Boolean = archiveEditContext(tab)?.isZipFamily == true

    fun cancelDelete() {
        pendingDelete = null
    }

    fun confirmDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        val ids = pending.entries.mapNotNull { entry -> runCatching { ArchiveDocumentId.parse(entry.uri) }.getOrNull() }
        if (ids.isEmpty()) return
        run(pending.context, ArchiveEditRequest(archive = pending.context.archive, deletions = ids))
    }

    fun cancelRename() {
        pendingRename = null
    }

    fun confirmRename(newName: String) {
        val pending = pendingRename ?: return
        pendingRename = null
        val id = runCatching { ArchiveDocumentId.parse(pending.entry.uri) }.getOrNull() ?: return
        if (newName.isBlank()) return
        run(pending.context, ArchiveEditRequest(archive = pending.context.archive, rename = id to newName))
    }

    /** The multi-file picker's own result. */
    fun onFilesPicked(uris: List<Uri>) {
        val (context, addAtPath) = pendingAdditions ?: return
        pendingAdditions = null
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        run(context, ArchiveEditRequest(archive = context.archive, addAtPath = addAtPath, addSources = uris))
    }

    private fun run(context: ArchiveEditContext, request: ArchiveEditRequest) {
        scope.launch {
            val result = planner.plan(request, context.containingFolder)
            when (result) {
                is EditPlanResult.Planned -> {
                    journal.putWithCreatePlan(result.operation, result.plan, result.manifest)
                    operationRunner.enqueueCreate(result.operation.id, "Editing archive", result.manifest.size)
                    onEdited()
                }
                is EditPlanResult.Refused -> onToast(result.reason)
            }
        }
    }
}

/**
 * Constructs one [ArchiveEditFlow] for the composition ([remember]ed), wiring the multi-file
 * picker only a composable can supply -- the same [ActivityResultContracts.OpenMultipleDocuments]
 * contract `ArchiveToolsOverlay`'s own Compress-sheet picker uses -- and the application's own
 * archive catalog, journal and (M3.5's own reused instance) write client. Also renders whichever
 * of the flow's own dialogs is current ([ArchiveEditFlowDialogs]): unlike `ExtractFlow`/
 * `CompressFlow`, which split that into a separate `*FlowHost` composable called elsewhere in
 * `FylzV1App.kt`, this flow's dialogs are plain [AlertDialog]s with no dependency on where in the
 * composition they are hosted from, and folding them in here keeps `FylzV1App.kt` itself (at its
 * own line-count ratchet, `ui/FylzV1AppSizeTest.kt`) to the one call site this already needed.
 */
@Composable
fun rememberArchiveEditFlow(
    context: Context,
    scope: CoroutineScope,
    operationRunner: OperationRunner,
    onToast: (String) -> Unit,
    onEdited: () -> Unit,
): ArchiveEditFlow {
    val flow = remember {
        val app = context.applicationContext as FylzApplication
        ArchiveEditFlow(
            scope = scope,
            resolver = context.contentResolver,
            catalog = app.archiveCatalog,
            journal = OperationJournal(app),
            operationRunner = operationRunner,
            onToast = onToast,
            onEdited = onEdited,
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> flow.onFilesPicked(uris) }
    flow.filesPicker = { picker.launch(arrayOf("*/*")) }
    ArchiveEditFlowDialogs(flow)
    return flow
}

/** Renders whichever of [flow]'s own dialogs is current -- folded into [rememberArchiveEditFlow]
 * itself; see that function's own doc comment for why. */
@Composable
private fun ArchiveEditFlowDialogs(flow: ArchiveEditFlow) {
    flow.pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = flow::cancelDelete,
            title = { Text(if (pending.entries.size == 1) "Remove from archive?" else "Remove ${pending.entries.size} items from archive?") },
            text = { Text("The archive is rewritten and the previous version goes to the Recycle Bin.") },
            confirmButton = { Button(onClick = flow::confirmDelete) { Text("Remove") } },
            dismissButton = { TextButton(onClick = flow::cancelDelete) { Text("Cancel") } },
        )
    }
    flow.pendingRename?.let { pending ->
        ArchiveRenameDialog(initial = pending.entry.name, onDismiss = flow::cancelRename, onConfirm = flow::confirmRename)
    }
}

@Composable
private fun ArchiveRenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
