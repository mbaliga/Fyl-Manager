package io.github.mbaliga.fylz.ui.actions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import io.github.mbaliga.fylz.archive.ArchiveCatalog
import io.github.mbaliga.fylz.archive.ArchiveFrameWriter
import io.github.mbaliga.fylz.decoder.ArchiveWriteOptions
import io.github.mbaliga.fylz.decoder.ArchiveWriteResult
import io.github.mbaliga.fylz.decoder.DecoderCall
import io.github.mbaliga.fylz.decoder.DecoderClient
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.operations.CompressFormat
import io.github.mbaliga.fylz.operations.CompressManifestEntry
import io.github.mbaliga.fylz.operations.CompressPlanResult
import io.github.mbaliga.fylz.operations.CompressPlanner
import io.github.mbaliga.fylz.operations.CompressProblem
import io.github.mbaliga.fylz.operations.CompressProblemDecision
import io.github.mbaliga.fylz.operations.CompressRequest
import io.github.mbaliga.fylz.operations.CompressSummary
import io.github.mbaliga.fylz.operations.CompressPlannerUi
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.OperationRunner
import io.github.mbaliga.fylz.operations.levelFor
import io.github.mbaliga.fylz.operations.openSourceStreamOf
import io.github.mbaliga.fylz.operations.sourceLengthOf
import io.github.mbaliga.fylz.storage.VolumeInfo
import io.github.mbaliga.fylz.storage.VolumeInfoResolver
import io.github.mbaliga.fylz.ui.components.DestinationChooserSheet
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The whole compress flow (`docs/agent/DESIGN-M35-CREATE.md` §2.1-2.2): the Compress sheet's own
 * form, [CompressPlanner.plan]'s own back-and-forth (a "Scanning…" dialog with Cancel, source
 * problems, conflicts resolved as one unit), then handing the planned operation to the transfer
 * queue exactly like an extraction. Mirrors `ExtractFlow` in shape; unlike it, a "Save as…"
 * destination (no tree grant) never reaches the queue at all -- [writeDirectly] runs the one pass
 * itself, synchronously, with no staging, no split and no durable retry, since
 * [io.github.mbaliga.fylz.operations.ArchiveCreator] itself refuses a plan with no destination
 * folder outright.
 *
 * One instance lives for `FylzV1Workspace`'s composition ([remember]); [Content] renders whichever
 * one of its own dialogs is current, alongside `FylzV1App`'s other flow dialogs.
 */
class CompressFlow(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
    catalog: ArchiveCatalog,
    private val volumeFor: suspend (destination: Uri) -> VolumeInfo?,
    private val journal: OperationJournal,
    private val operationRunner: OperationRunner,
    private val writerClient: () -> DecoderClient,
    private val onToast: (String) -> Unit,
    private val onCompressed: () -> Unit,
) {
    private val planner = CompressPlanner(resolver, catalog, volumeFor)

    /** One question [InteractiveUi] is currently asking, or the "Scanning…" progress. */
    sealed interface Step {
        data object Scanning : Step
        data class Problems(val summary: CompressSummary, val respond: (CompressProblemDecision?) -> Unit) : Step
        data class Conflict(val existingBaseName: String, val existingParts: List<String>, val respond: (ConflictPolicy?) -> Unit) : Step
    }

    /** The sheet's own sources, or `null` when it is closed. */
    var sheetSources by mutableStateOf<List<Uri>?>(null)
        private set

    var destinationChooserOpen by mutableStateOf(false)
        private set

    var step by mutableStateOf<Step?>(null)
        private set

    /** The sources and form the destination chooser resolves against, set by [chooseFolder] --
     * [sheetSources] itself is already cleared (the sheet is closed) by the time a folder lands. */
    private var pendingFolderChoice: Pair<List<Uri>, CompressDraft>? = null

    /** As [pendingFolderChoice], for [saveAs]'s own `CreateDocument` picker. */
    private var pendingSaveAs: Pair<List<Uri>, CompressDraft>? = null

    /** The system folder picker's own `launch(null)`, set by [rememberCompressFlow]. */
    var systemFolderPicker: (() -> Unit)? = null

    /** The system `CreateDocument` picker for "Save as…", set by [rememberCompressFlow]; takes
     * the suggested file name (already carrying the format's own extension). */
    var systemSaveAsPicker: ((String) -> Unit)? = null

    private var job: Job? = null

    fun openSheet(sources: List<Uri>) {
        if (sources.isEmpty()) return
        sheetSources = sources
    }

    fun dismissSheet() {
        sheetSources = null
    }

    /** [CompressSheet]'s "Choose folder…": the destination chooser next, planning once one lands. */
    fun chooseFolder(draft: CompressDraft) {
        val sources = sheetSources ?: return
        sheetSources = null
        pendingFolderChoice = sources to draft
        destinationChooserOpen = true
    }

    fun cancelDestinationChooser() {
        destinationChooserOpen = false
        pendingFolderChoice = null
    }

    fun useSystemFolderPicker() {
        destinationChooserOpen = false
        systemFolderPicker?.invoke()
    }

    fun chooseDestinationFolder(destination: Uri) {
        destinationChooserOpen = false
        val (sources, draft) = pendingFolderChoice ?: return
        pendingFolderChoice = null
        start(sources, draft, destination)
    }

    /** [CompressSheet]'s "Save as…": the system `CreateDocument` picker next, [writeDirectly] once
     * a document lands (never the queue -- see this class's own KDoc). */
    fun saveAs(draft: CompressDraft) {
        val sources = sheetSources ?: return
        sheetSources = null
        pendingSaveAs = sources to draft
        systemSaveAsPicker?.invoke("${draft.archiveName}.${draft.format.extension}")
    }

    fun chooseSaveAsDestination(destination: Uri) {
        val (sources, draft) = pendingSaveAs ?: return
        pendingSaveAs = null
        scope.launch { runSaveAs(sources, draft, destination) }
    }

    /** "Scanning…"'s own Cancel: only the planning phase, exactly as `ExtractFlow.cancelReading`. */
    fun cancelScanning() {
        job?.cancel()
        job = null
        step = null
    }

    private fun requestFor(sources: List<Uri>, draft: CompressDraft, destinationFolder: Uri?): CompressRequest = CompressRequest(
        sources = sources,
        format = draft.format,
        level = draft.format.levelFor(draft.level),
        split = draft.splitBytes?.let { io.github.mbaliga.fylz.operations.SplitSize.At(it) } ?: io.github.mbaliga.fylz.operations.SplitSize.Off,
        relativeToSelection = draft.relativeToSelection,
        archiveName = draft.archiveName,
        destinationFolder = destinationFolder,
    )

    private fun start(sources: List<Uri>, draft: CompressDraft, destinationFolder: Uri) {
        step = Step.Scanning
        job = scope.launch {
            val request = requestFor(sources, draft, destinationFolder)
            val result = try {
                planner.plan(request, InteractiveUi())
            } finally {
                step = null
            }
            when (result) {
                is CompressPlanResult.Planned -> enqueue(result)
                is CompressPlanResult.Refused -> onToast(result.reason)
                CompressPlanResult.Cancelled -> Unit
            }
        }
    }

    private fun enqueue(planned: CompressPlanResult.Planned) {
        scope.launch(Dispatchers.IO) {
            journal.putWithCreatePlan(planned.operation, planned.plan, planned.manifest)
            operationRunner.enqueueCreate(planned.operation.id, "Compressing", planned.manifest.size)
            onCompressed()
        }
    }

    /** "Save as…" plans on the same [scope] (its own "Scanning…"/problems questions still ask),
     * then writes the one pass directly instead of ever touching the journal or the queue. */
    private suspend fun runSaveAs(sources: List<Uri>, draft: CompressDraft, destination: Uri) {
        step = Step.Scanning
        val request = requestFor(sources, draft, destinationFolder = null)
        val result = try {
            planner.plan(request, InteractiveUi())
        } finally {
            step = null
        }
        when (result) {
            is CompressPlanResult.Planned -> writeDirectly(result.manifest, draft.format, draft.format.levelFor(draft.level), destination)
            is CompressPlanResult.Refused -> onToast(result.reason)
            CompressPlanResult.Cancelled -> Unit
        }
    }

    /**
     * The one pass a "Save as…" destination gets: [ArchiveCreator][io.github.mbaliga.fylz.operations.ArchiveCreator]
     * itself refuses a plan with no destination folder outright (design section 2.3), so this feeds
     * the same `FZW1` frames over the same isolated write instance directly into the chosen
     * document's own stream -- no staging, no split (`CompressSheet` already disables this button
     * once a split is chosen), no verification, no durable retry across a process death. A manifest
     * entry needing spooling (an archive-sourced entry of unknown size, `tar.*` only) has nowhere to
     * spool to here and is refused outright rather than silently dropped.
     */
    private suspend fun writeDirectly(manifest: List<CompressManifestEntry>, format: CompressFormat, level: Int, destination: Uri) {
        if (manifest.any { it.needsSpooling }) {
            onToast("This selection needs a destination folder, not Save as, for this format.")
            return
        }
        val client = writerClient()
        try {
            val options = ArchiveWriteOptions(format.writeFormat, level)
            val call: DecoderCall<ArchiveWriteResult> = withContext(Dispatchers.IO) {
                client.callTwoPipes(
                    inactivityMillis = DecoderClient.STREAM_INACTIVITY_MILLIS,
                    feed = { output -> feedManifest(manifest, output) },
                    drain = { input -> drainToDestination(input, destination) },
                ) { service, input, output -> service.writeArchive(input, options, output) }
            }
            when (call) {
                is DecoderCall.Ok -> if (call.value.outcome == ArchiveWriteResult.OUTCOME_OK) {
                    onCompressed()
                } else {
                    onToast(call.value.message ?: "Unable to create the archive.")
                }
                DecoderCall.TimedOut, DecoderCall.Failed -> onToast("Unable to create the archive.")
            }
        } catch (failure: Exception) {
            onToast(failure.message ?: "Unable to create the archive.")
        } finally {
            client.unbind()
        }
    }

    private fun feedManifest(manifest: List<CompressManifestEntry>, output: OutputStream) {
        val writer = ArchiveFrameWriter(output)
        for (entry in manifest) {
            if (entry.isDirectory) {
                writer.entry(entry.ordinal, true, null, entry.mtimeEpochMillis, DIRECTORY_MODE, entry.archivePath)
                continue
            }
            val length = sourceLengthOf(resolver, entry.sourceUri)
            writer.entry(entry.ordinal, false, length, entry.mtimeEpochMillis, FILE_MODE, entry.archivePath)
            var written = 0L
            openSourceStreamOf(resolver, entry.sourceUri).use { input ->
                val buffer = ByteArray(512 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    writer.data(entry.ordinal, buffer, 0, n)
                    written += n
                }
            }
            writer.end(entry.ordinal, written)
        }
        writer.finish()
    }

    private fun drainToDestination(input: java.io.InputStream, destination: Uri) {
        val out = resolver.openOutputStream(destination, "w") ?: throw java.io.IOException("no output stream for $destination")
        out.use { input.copyTo(it, bufferSize = 512 * 1024) }
    }

    private inner class InteractiveUi : CompressPlannerUi {
        override suspend fun resolveProblems(summary: CompressSummary): CompressProblemDecision? =
            ask { respond -> Step.Problems(summary, respond) }

        override suspend fun resolveConflict(existingBaseName: String, existingParts: List<String>): ConflictPolicy? =
            ask { respond -> Step.Conflict(existingBaseName, existingParts, respond) }
    }

    private suspend fun <T> ask(makeStep: ((T) -> Unit) -> Step): T = suspendCancellableCoroutine { continuation ->
        step = makeStep { value ->
            step = Step.Scanning
            if (continuation.isActive) continuation.resume(value)
        }
        continuation.invokeOnCancellation { step = null }
    }

    private companion object {
        const val DIRECTORY_MODE = 0x1ED // 0755: Kotlin has no octal literal syntax
        const val FILE_MODE = 0x1A4 // 0644
    }
}

/**
 * Constructs one [CompressFlow] for the composition ([remember]ed), wiring the two pieces only a
 * composable can supply: the system folder/`CreateDocument` pickers and the application's own
 * archive catalog, journal, volume lookup and isolated write instance.
 */
@Composable
fun rememberCompressFlow(
    context: Context,
    scope: CoroutineScope,
    operationRunner: OperationRunner,
    persistTreePermission: (Uri) -> Unit,
    onToast: (String) -> Unit,
    onCompressed: () -> Unit,
): CompressFlow {
    val flow = remember {
        val app = context.applicationContext as FylzApplication
        CompressFlow(
            scope = scope,
            resolver = context.contentResolver,
            catalog = app.archiveCatalog,
            volumeFor = { destination -> withContext(Dispatchers.IO) { VolumeInfoResolver.resolveForDestination(app, destination) } },
            journal = OperationJournal(app),
            operationRunner = operationRunner,
            writerClient = { app.decoderClient.writer() },
            onToast = onToast,
            onCompressed = onCompressed,
        )
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistTreePermission(uri)
            flow.chooseDestinationFolder(uri)
        }
    }
    flow.systemFolderPicker = { folderPicker.launch(null) }
    val saveAsPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) flow.chooseSaveAsDestination(uri)
    }
    flow.systemSaveAsPicker = { name -> saveAsPicker.launch(name) }
    return flow
}

/** Renders whichever of [flow]'s own dialogs is current, alongside every other flow dialog
 * `FylzV1App.kt` renders. */
@Composable
fun CompressFlowHost(flow: CompressFlow, tabs: List<FolderTab>) {
    flow.sheetSources?.let { sources ->
        CompressSheet(
            sourceCount = sources.size,
            defaultName = "Archive",
            onDismiss = flow::dismissSheet,
            onChooseFolder = flow::chooseFolder,
            onSaveAs = flow::saveAs,
        )
    }

    if (flow.destinationChooserOpen) {
        DestinationChooserSheet(
            tabs = tabs,
            onChooseTab = { tab -> flow.chooseDestinationFolder(tab.current.uri) },
            onChooseRoot = { root -> root.documentUri?.let(flow::chooseDestinationFolder) },
            onOtherLocation = flow::useSystemFolderPicker,
            onCancel = flow::cancelDestinationChooser,
        )
    }

    when (val current = flow.step) {
        null -> Unit
        CompressFlow.Step.Scanning -> ScanningDialog(onCancel = flow::cancelScanning)
        is CompressFlow.Step.Problems -> CompressProblemsDialog(current)
        is CompressFlow.Step.Conflict -> CompressConflictDialog(current)
    }
}

@Composable
private fun ScanningDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Scanning…") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Looking at what's selected.")
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CompressProblemsDialog(step: CompressFlow.Step.Problems) {
    val summary = step.summary
    var acceptSplit by remember(summary) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { step.respond(null) },
        title = { Text("Before compressing") },
        text = {
            Text(
                buildString {
                    if (summary.problems.isNotEmpty()) {
                        append("${summary.problems.size} item(s) cannot be included:\n")
                        summary.problems.take(5).forEach { problem -> append("• ${problem.name} (${problem.reasonText()})\n") }
                    }
                    if (summary.suggestSplit) append("\nThis destination may need the archive split into parts.")
                },
            )
        },
        confirmButton = {
            Button(onClick = { step.respond(CompressProblemDecision(skip = summary.problems.map { it.sourceUri }.toSet(), acceptSuggestedSplit = acceptSplit)) }) {
                Text(if (summary.problems.isEmpty()) "Continue" else "Skip and continue")
            }
        },
        dismissButton = {
            Row {
                if (summary.suggestSplit) {
                    Checkbox(checked = acceptSplit, onCheckedChange = { acceptSplit = it })
                    Text("Split")
                }
                TextButton(onClick = { step.respond(null) }) { Text("Cancel") }
            }
        },
    )
}

private fun CompressProblem.reasonText(): String = when (this) {
    is CompressProblem.LinkOrEncrypted -> reason
    is CompressProblem.TooLarge -> "too large"
    is CompressProblem.ArchiveRefused -> reason
    is CompressProblem.StreamFormatTooManyEntries -> "too many entries; extract it first"
    is CompressProblem.SymlinkLoop -> "a link loop"
}

@Composable
private fun CompressConflictDialog(step: CompressFlow.Step.Conflict) {
    AlertDialog(
        onDismissRequest = { step.respond(null) },
        title = { Text("\"${step.existingBaseName}\" already exists") },
        text = {
            Text(
                if (step.existingParts.isEmpty()) {
                    "Replace it, or keep both?"
                } else {
                    "Its split parts (${step.existingParts.joinToString()}) already exist too. Replace the whole set, or keep both?"
                },
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { step.respond(ConflictPolicy.REPLACE) }) { Text("Replace") }
                Button(onClick = { step.respond(ConflictPolicy.KEEP_BOTH) }) { Text("Keep both") }
            }
        },
        dismissButton = { TextButton(onClick = { step.respond(null) }) { Text("Cancel") } },
    )
}
