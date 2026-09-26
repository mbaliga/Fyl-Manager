package io.github.mbaliga.fylz.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.archive.ArchiveFormatFamily
import io.github.mbaliga.fylz.archive.ArchiveInspectionResult
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.model.BrowsableArchiveFormats
import io.github.mbaliga.fylz.operations.ArchiveTestEntryResult
import io.github.mbaliga.fylz.operations.ArchiveTestOutcome
import io.github.mbaliga.fylz.operations.EntryOutcome
import io.github.mbaliga.fylz.ui.actions.ArchiveToolsMenuDialog
import io.github.mbaliga.fylz.ui.components.PasswordPromptDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ArchivePasswordPurpose {
    CREATE,
    EXTRACT,
}

@Composable
fun ArchiveToolsOverlay(resolver: ActionResolver, state: BrowserState, ctx: ActionContext, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember {
        ArchiveService(context.applicationContext, (context.applicationContext as FylzApplication).decoderClient)
    }
    // M3.2: inspection runs in the isolated decoder process over a seekable descriptor, through
    // the application-scoped inspector (its decoder binding must outlive this composition).
    val inspector = remember { (context.applicationContext as FylzApplication).archiveInspector }
    // M3.8: "Test archive" runs the same isolated extraction instance a real extraction does.
    val tester = remember { (context.applicationContext as FylzApplication).archiveTester }
    // M3.9: the one, shared, session-only remembered-password store -- also used by FylzV1App's
    // legacy-encrypted-ZIP extract flow, so a password remembered through either path is honoured
    // by both for the rest of this process's life.
    val passwordSession = remember { (context.applicationContext as FylzApplication).archivePasswordSession }
    var menuOpen by remember { mutableStateOf(false) }
    var passwordPurpose by remember { mutableStateOf<ArchivePasswordPurpose?>(null) }
    var selectedSources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedArchive by remember { mutableStateOf<Uri?>(null) }
    // M3.4c: the archive's own display name, so the inspection dialog can gate Extract on
    // `BrowsableArchiveFormats` (every format the queue reads) rather than ZIP alone.
    var selectedArchiveName by remember { mutableStateOf("") }
    var inspection by remember { mutableStateOf<ArchiveInspectionResult.Ready?>(null) }
    var pendingCreatePassword by remember { mutableStateOf<CharArray?>(null) }
    var pendingExtractPassword by remember { mutableStateOf<CharArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var archiveTestState by remember { mutableStateOf<ArchiveTestUiState?>(null) }
    var testCancelRequested by remember { mutableStateOf(false) }

    fun persistRead(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun persistTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    val createDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        val password = pendingCreatePassword
        pendingCreatePassword = null
        if (destination == null || selectedSources.isEmpty()) {
            password?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            runCatching { service.createZip(selectedSources, destination, password) }
                .onSuccess {
                    Toast.makeText(context, "Archive created.", Toast.LENGTH_LONG).show()
                    selectedSources = emptyList()
                }
                .onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "Unable to create archive.", Toast.LENGTH_LONG).show()
                }
            busy = false
        }
    }

    val sourcePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach(::persistRead)
        selectedSources = uris
        passwordPurpose = ArchivePasswordPurpose.CREATE
    }

    val extractDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { destination ->
        val archive = selectedArchive
        val password = pendingExtractPassword
        pendingExtractPassword = null
        if (destination == null || archive == null) {
            password?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        persistTree(destination)
        scope.launch {
            busy = true
            runCatching { service.extractZip(archive, destination, password) }
                .onSuccess {
                    Toast.makeText(context, "Archive extracted into a new folder.", Toast.LENGTH_LONG).show()
                    selectedArchive = null
                    inspection = null
                }
                .onFailure { failure ->
                    Toast.makeText(
                        context,
                        failure.message ?: "Unable to extract archive. Check the password and destination.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            busy = false
        }
    }

    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persistRead(uri)
        selectedArchive = uri
        selectedArchiveName = queryDisplayName(context, uri).orEmpty()
        scope.launch {
            busy = true
            when (val result = inspector.inspect(uri)) {
                is ArchiveInspectionResult.Ready -> inspection = result
                else -> {
                    selectedArchive = null
                    Toast.makeText(context, result.failureMessage() ?: "Unable to inspect archive.", Toast.LENGTH_LONG).show()
                }
            }
            busy = false
        }
    }

    // M3.8: "Test archive" -- verifies every entry's CRC without extracting. Gated on the same
    // `BrowsableArchiveFormats` set `fylz.extract`/`fylz.compress` already use for format
    // detection (`CAN_EXTRACT`'s own rule): a format Fylz cannot yet browse or extract is refused
    // here too, before the picked file ever reaches the catalog.
    val testArchivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persistRead(uri)
        val name = queryDisplayName(context, uri).orEmpty()
        if (!BrowsableArchiveFormats.matches(name)) {
            Toast.makeText(context, "Fylz cannot test this format yet.", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        testCancelRequested = false
        archiveTestState = ArchiveTestUiState.Testing(0, 0)
        scope.launch {
            busy = true
            when (val outcome = tester.test(ArchiveRef(uri, emptyList()), onProgress = { tested, total -> archiveTestState = ArchiveTestUiState.Testing(tested, total) }, cancelled = { testCancelRequested })) {
                is ArchiveTestOutcome.Completed -> archiveTestState = ArchiveTestUiState.Done(outcome.entries, outcome.cancelled)
                ArchiveTestOutcome.PasswordRequired -> {
                    archiveTestState = null
                    // M3.9's own scope note: prompting would only fail again, since the new
                    // engine's password field is a disabled stub until a crypto backend is built.
                    Toast.makeText(context, "This archive is password-protected; Fylz cannot test it without extracting it.", Toast.LENGTH_LONG).show()
                }
                ArchiveTestOutcome.Unavailable -> {
                    archiveTestState = null
                    Toast.makeText(context, "The archive could not be read safely.", Toast.LENGTH_LONG).show()
                }
                is ArchiveTestOutcome.Refused -> {
                    archiveTestState = null
                    Toast.makeText(context, outcome.reason, Toast.LENGTH_LONG).show()
                }
            }
            busy = false
        }
    }

    FloatingActionButton(onClick = { menuOpen = true }, modifier = modifier) {
        Icon(Icons.Outlined.Archive, contentDescription = "Open archive tools")
    }

    if (menuOpen) {
        ArchiveToolsMenuDialog(
            resolver = resolver,
            state = state,
            busy = busy,
            onDismiss = { menuOpen = false },
            onSelect = { id ->
                menuOpen = false
                when (id.value) {
                    "fylz.protect" -> sourcePicker.launch(arrayOf("*/*"))
                    "fylz.archive.inspect" -> archivePicker.launch(INSPECTABLE_ARCHIVE_MIME_TYPES)
                    "fylz.archive.test" -> testArchivePicker.launch(INSPECTABLE_ARCHIVE_MIME_TYPES)
                    "fylz.archive.add-entries" -> ctx.addArchiveEntries()
                }
            },
        )
    }

    // M3.9: the one shared password prompt, for both CREATE and EXTRACT here -- CREATE offers no
    // "Remember for this session" tick, since there is no archive identity yet to key it by before
    // a destination is chosen (`createDestination` runs after this confirms).
    passwordPurpose?.let { purpose ->
        val creating = purpose == ArchivePasswordPurpose.CREATE
        PasswordPromptDialog(
            title = if (creating) "Protect archive" else "Archive password",
            confirmNewPassword = creating,
            offerRemember = !creating,
            confirmLabel = "Choose destination",
            onDismiss = {
                passwordPurpose = null
                if (creating) selectedSources = emptyList()
            },
            onConfirm = { password, remember ->
                passwordPurpose = null
                when (purpose) {
                    ArchivePasswordPurpose.CREATE -> {
                        if (password == null) {
                            // M3.5: no AES switch -- the new Compress sheet/queue, not zip4j.
                            val sources = selectedSources
                            selectedSources = emptyList()
                            ctx.openCompressMenu(sources)
                        } else {
                            // The AES switch is on: zip4j is still the only encrypted-archive path
                            // (design §2.7's own scoped-down choice, until the new engine's crypto
                            // backend is built and M3.10 removes zip4j).
                            pendingCreatePassword = password
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            createDestination.launch("Fylz-$stamp.zip")
                        }
                    }
                    ArchivePasswordPurpose.EXTRACT -> {
                        if (remember && password != null) selectedArchive?.let { archive -> passwordSession.remember(archive, password) }
                        pendingExtractPassword = password
                        extractDestination.launch(null)
                    }
                }
            },
        )
    }

    inspection?.let { value ->
        ArchiveInspectionDialog(
            result = value,
            archiveName = selectedArchiveName,
            busy = busy,
            onDismiss = {
                inspection = null
                selectedArchive = null
            },
            onExtract = {
                if (value.summary.hasEncryptedEntries) {
                    // M3.9: a password already remembered for this archive skips the prompt.
                    val remembered = selectedArchive?.let(passwordSession::passwordFor)
                    if (remembered != null) {
                        pendingExtractPassword = remembered
                        inspection = null
                        extractDestination.launch(null)
                    } else {
                        passwordPurpose = ArchivePasswordPurpose.EXTRACT
                    }
                } else {
                    // M3.4c: a plain archive extracts through the same Extract sheet/flow
                    // `fylz.extract` opens, not the zip4j `extractDestination` picker any more --
                    // that path is now reserved for encrypted ZIPs (design §2.7).
                    val archive = selectedArchive
                    inspection = null
                    selectedArchive = null
                    if (archive != null) ctx.openExtractMenu(archive)
                }
            },
        )
    }

    archiveTestState?.let { state ->
        ArchiveTestDialog(
            state = state,
            onCancel = { testCancelRequested = true },
            onDismiss = { archiveTestState = null },
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

@Composable
private fun ArchiveInspectionDialog(
    result: ArchiveInspectionResult.Ready,
    archiveName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onExtract: () -> Unit,
) {
    val summary = result.summary
    val allowed = summary.policyAllowed
    // M3.4c: every format the queue can extract, not ZIP alone -- extraction now goes through the
    // same `ExtractFlow` `fylz.extract` opens (encrypted ZIP still detours to the password path,
    // then `ArchiveService.extractZip`, since only ZIP supports a password in this app at all).
    val extractable = BrowsableArchiveFormats.matches(archiveName)
    val family = ArchiveFormatFamily.label(summary.formatCode, summary.filters)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
        title = { Text("Archive inspection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (summary.hasEncryptedEntries) "$family, encrypted" else family)
                Text(
                    buildString {
                        append("${summary.fileCount} files · ${summary.directoryCount} folders")
                        if (summary.linkCount > 0) append(" · ${summary.linkCount} links")
                    },
                )
                Text("Archive size: ${formatArchiveBytes(summary.archiveBytes)}")
                if (summary.totalUncompressedBytes >= 0L) {
                    Text("Expanded size: ${formatArchiveBytes(summary.totalUncompressedBytes)}")
                }
                result.temporarySpace?.let { required ->
                    Text("Temporary space required: ${formatArchiveBytes(required.temporaryBytes)}")
                }
                result.temporarySpaceAvailable?.let { available ->
                    Text("Temporary space available: ${formatArchiveBytes(available)}")
                }
                if (result.staged) {
                    Text(
                        "Copied to temporary storage first: this location could not be read in place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.hasLossyNames) {
                    Text(
                        "Some entry names use a legacy encoding and are shown with replacement characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!allowed) {
                    Text(
                        summary.policyReason ?: "This archive failed safety checks.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (extractable) {
                    Text(
                        "Extraction runs through the transfer queue, with progress and cancel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Fylz can inspect this format; extracting it arrives with a later update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (extractable) {
                Button(onClick = onExtract, enabled = allowed && !busy) {
                    Text(if (summary.hasEncryptedEntries) "Enter password" else "Extract")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(if (extractable) "Cancel" else "Close") } },
    )
}

private fun formatArchiveBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.1f KiB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

/** [ArchiveTestDialog]'s own state: running (M3.8's progress numbers) or the finished result. */
private sealed interface ArchiveTestUiState {
    data class Testing(val tested: Int, val total: Int) : ArchiveTestUiState

    data class Done(val entries: List<ArchiveTestEntryResult>, val cancelled: Boolean) : ArchiveTestUiState
}

/**
 * M3.8's own result dialog, the same general shape as [ArchiveInspectionDialog]: a summary line up
 * top, a scrollable list of whatever was not a clean pass below. While [ArchiveTestUiState.Testing]
 * this cannot be dismissed by tapping outside it (`onDismissRequest = {}`, same guard
 * [ArchiveInspectionDialog] uses for `busy`) -- only "Cancel", which asks the pass to stop rather
 * than abandoning the dialog while it is still running.
 */
@Composable
private fun ArchiveTestDialog(
    state: ArchiveTestUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is ArchiveTestUiState.Testing -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
            title = { Text("Testing archive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.total > 0) "${state.tested} of ${state.total} entries tested" else "Reading archive…")
                    LinearProgressIndicator(
                        progress = { if (state.total > 0) state.tested.toFloat() / state.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is ArchiveTestUiState.Done -> {
            val failed = state.entries.count { it.outcome is EntryOutcome.Failed }
            val warned = state.entries.count { it.outcome is EntryOutcome.PassedWithWarning }
            val passed = state.entries.size - failed - warned
            val problems = state.entries.filter { it.outcome !is EntryOutcome.Passed }
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
                title = { Text("Archive test") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            buildString {
                                append("$passed of ${state.entries.size} entries passed")
                                if (warned > 0) append(", $warned with a warning")
                                if (failed > 0) append(", $failed failed")
                                append(".")
                            },
                        )
                        if (state.cancelled) {
                            Text(
                                "Testing was cancelled or the archive could not be read further; not every entry was checked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (problems.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                items(problems) { entry ->
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(entry.path, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            entry.outcome.describe(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (entry.outcome is EntryOutcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
            )
        }
    }
}

/** One line for [ArchiveTestDialog]'s problem list. */
private fun EntryOutcome.describe(): String = when (this) {
    EntryOutcome.Passed -> "OK"
    is EntryOutcome.PassedWithWarning -> "Warning: $message"
    is EntryOutcome.Failed -> "$kindLabel: $message"
}

/**
 * What Inspect offers to open (M3.2): the ZIP family the picker always offered, plus every family
 * the decoder process reads through a seekable descriptor -- this is what makes "7z and ISO are
 * read with seeks" reachable from the UI. Since M3.4c, Extract here widens to every
 * `BrowsableArchiveFormats` member too, the same set `fylz.extract`'s own `enabledWhen` accepts.
 */
private val INSPECTABLE_ARCHIVE_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
    "application/x-7z-compressed",
    "application/x-iso9660-image",
    "application/x-tar",
    "application/gzip",
    "application/x-xz",
    "application/zstd",
    "application/x-bzip2",
)
