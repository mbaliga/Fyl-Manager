package io.github.mbaliga.fylz.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.ArchiveInspection
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.data.CreatableArchiveFormat
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.ui.picker.FylzPicker
import io.github.mbaliga.fylz.ui.picker.PickerMode
import io.github.mbaliga.fylz.ui.picker.PickerOutcome
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileField
import io.github.mbaliga.fylz.ui.tactile.TactileFieldState
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ArchivePasswordPurpose {
    CREATE,
    EXTRACT,
}

/**
 * What the archive tools are currently asking the in-app picker for.
 *
 * All four steps of both journeys used to hand the user to the system file manager — sources,
 * output, the archive to inspect, and the folder to extract into. Picking files for a file
 * manager's own archiver, inside a different file manager, is the handoff this removes.
 */
private sealed interface ArchivePick {
    /** Files to compress. */
    data object Sources : ArchivePick

    /** An existing ZIP to inspect. */
    data object Archive : ArchivePick

    /** Where to write a new ZIP, and what to call it. */
    data class CreateOutput(val name: String) : ArchivePick

    /** The folder to extract into. */
    data object ExtractInto : ArchivePick
}

/**
 * Owns the create/inspect/extract state machine and every dialog it drives. Hoisted so a caller
 * that already has its own open/close affordance (a recovery card) can drive this directly instead
 * of going through a FAB it doesn't want — [open] stands in for the old menu-choice dialog's own
 * `menuOpen` flag.
 *
 * @param showHidden dotfile entries stay out of the in-app picker's listing unless this is on,
 *   matching the main browser, the folder tree and the move/copy/PDF-output picker — all four are
 *   answering the same "Show hidden files" setting, not four independent choices.
 */
@Composable
fun ArchiveToolsHost(open: Boolean, showHidden: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { ArchiveService(context.applicationContext) }
    val repository = remember { DocumentRepository(context.applicationContext) }
    var pick by remember { mutableStateOf<ArchivePick?>(null) }
    var passwordPurpose by remember { mutableStateOf<ArchivePasswordPurpose?>(null) }
    var selectedSources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedArchive by remember { mutableStateOf<Uri?>(null) }
    var inspection by remember { mutableStateOf<ArchiveInspection?>(null) }
    var pendingCreatePassword by remember { mutableStateOf<CharArray?>(null) }
    var pendingCreateFormat by remember { mutableStateOf(CreatableArchiveFormat.ZIP) }
    var pendingExtractPassword by remember { mutableStateOf<CharArray?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun persistRead(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun persistTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    // The operations, lifted out of the picker callbacks so the in-app picker and the platform
    // one drive identical code however the destination was chosen.
    fun runCreate(destination: Uri) {
        val password = pendingCreatePassword
        pendingCreatePassword = null
        val format = pendingCreateFormat
        if (selectedSources.isEmpty()) {
            password?.fill(NUL)
            return
        }
        scope.launch {
            busy = true
            runCatching {
                when (format) {
                    CreatableArchiveFormat.ZIP -> service.createZip(selectedSources, destination, password)
                    CreatableArchiveFormat.SEVEN_Z -> service.createSevenZip(selectedSources, destination, password)
                }
            }
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

    fun runExtract(destination: Uri) {
        val archive = selectedArchive
        val password = pendingExtractPassword
        pendingExtractPassword = null
        if (archive == null) {
            password?.fill(NUL)
            return
        }
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

    fun runInspect(uri: Uri) {
        selectedArchive = uri
        scope.launch {
            busy = true
            runCatching { service.inspectZip(uri) }
                .onSuccess { inspection = it }
                .onFailure { failure ->
                    selectedArchive = null
                    Toast.makeText(context, failure.message ?: "Unable to inspect archive.", Toast.LENGTH_LONG).show()
                }
            busy = false
        }
    }

    /** Wipes whichever password a cancelled journey was holding. */
    fun discardPassword(purpose: ArchivePasswordPurpose) {
        when (purpose) {
            ArchivePasswordPurpose.CREATE -> {
                pendingCreatePassword?.fill(NUL)
                pendingCreatePassword = null
            }
            ArchivePasswordPurpose.EXTRACT -> {
                pendingExtractPassword?.fill(NUL)
                pendingExtractPassword = null
            }
        }
    }

    val createZipDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CreatableArchiveFormat.ZIP.mimeType),
    ) { destination ->
        if (destination == null) discardPassword(ArchivePasswordPurpose.CREATE) else runCreate(destination)
    }

    // CreateDocument's mime type is fixed at registration, not at launch -- one contract cannot
    // serve both formats, so each format gets its own launcher.
    val createSevenZipDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CreatableArchiveFormat.SEVEN_Z.mimeType),
    ) { destination ->
        if (destination == null) discardPassword(ArchivePasswordPurpose.CREATE) else runCreate(destination)
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
        if (destination == null) {
            discardPassword(ArchivePasswordPurpose.EXTRACT)
        } else {
            persistTree(destination)
            runExtract(destination)
        }
    }

    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persistRead(uri)
        runInspect(uri)
    }

    // ── The in-app picker, driving all four steps of both journeys ────────────────────
    pick?.let { request ->
        FylzPicker(
            mode = when (request) {
                ArchivePick.Sources, ArchivePick.Archive -> PickerMode.FILES
                is ArchivePick.CreateOutput -> PickerMode.SAVE
                ArchivePick.ExtractInto -> PickerMode.FOLDER
            },
            title = when (request) {
                ArchivePick.Sources -> "Files to compress"
                ArchivePick.Archive -> "Choose a ZIP"
                is ArchivePick.CreateOutput -> "Save archive"
                ArchivePick.ExtractInto -> "Extract into"
            },
            confirmLabel = when (request) {
                ArchivePick.Sources -> "Compress"
                ArchivePick.Archive -> "Inspect"
                is ArchivePick.CreateOutput -> "Create"
                ArchivePick.ExtractInto -> "Extract here"
            },
            repository = repository,
            suggestedName = (request as? ArchivePick.CreateOutput)?.name.orEmpty(),
            showHidden = showHidden,
            onDismiss = {
                pick = null
                // Abandoning a destination step abandons the password with it, rather than
                // leaving a key in memory for a journey the user walked away from.
                if (request is ArchivePick.CreateOutput) discardPassword(ArchivePasswordPurpose.CREATE)
                if (request is ArchivePick.ExtractInto) discardPassword(ArchivePasswordPurpose.EXTRACT)
            },
            onBrowseSystem = {
                pick = null
                when (request) {
                    ArchivePick.Sources -> sourcePicker.launch(arrayOf("*/*"))
                    ArchivePick.Archive -> archivePicker.launch(ZIP_MIME_TYPES)
                    is ArchivePick.CreateOutput -> when (pendingCreateFormat) {
                        CreatableArchiveFormat.ZIP -> createZipDestination.launch(request.name)
                        CreatableArchiveFormat.SEVEN_Z -> createSevenZipDestination.launch(request.name)
                    }
                    ArchivePick.ExtractInto -> extractDestination.launch(null)
                }
            },
            onResult = { outcome ->
                pick = null
                when (request) {
                    ArchivePick.Sources -> (outcome as? PickerOutcome.Files)?.let { files ->
                        if (files.uris.isNotEmpty()) {
                            selectedSources = files.uris
                            passwordPurpose = ArchivePasswordPurpose.CREATE
                        }
                    }
                    ArchivePick.Archive ->
                        (outcome as? PickerOutcome.Files)?.uris?.firstOrNull()?.let(::runInspect)
                    is ArchivePick.CreateOutput -> (outcome as? PickerOutcome.Save)?.let { save ->
                        scope.launch {
                            runCatching {
                                repository.createFile(save.folderUri, save.name, pendingCreateFormat.mimeType)
                            }
                                .onSuccess { runCreate(it) }
                                .onFailure { failure ->
                                    discardPassword(ArchivePasswordPurpose.CREATE)
                                    Toast.makeText(
                                        context,
                                        failure.message ?: "Unable to create that file.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    }
                    ArchivePick.ExtractInto ->
                        (outcome as? PickerOutcome.Folder)?.let { runExtract(it.folderUri) }
                }
            },
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            icon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
            title = { Text("Archive tools") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Create standard or AES-256 password-protected ZIP or 7z archives, or " +
                            "inspect and safely extract an existing ZIP.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // TactileButton carries a single text label, no icon slot -- the leading
                    // Archive/Unarchive glyphs these two buttons used to show are dropped here,
                    // same as every other icon+label Button/OutlinedButton this conversion touches.
                    TactileButton(
                        text = "Create archive",
                        onClick = {
                            onDismiss()
                            pick = ArchivePick.Sources
                        },
                        enabled = !busy,
                        fillWidth = true,
                    )
                    TactileButton(
                        text = "Inspect and extract ZIP",
                        onClick = {
                            onDismiss()
                            pick = ArchivePick.Archive
                        },
                        style = TactileButtonStyle.SECONDARY,
                        enabled = !busy,
                        fillWidth = true,
                    )
                }
            },
            confirmButton = { TactileButton(text = "Done", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
        )
    }

    passwordPurpose?.let { purpose ->
        ArchivePasswordDialog(
            purpose = purpose,
            onDismiss = {
                passwordPurpose = null
                if (purpose == ArchivePasswordPurpose.CREATE) selectedSources = emptyList()
            },
            onConfirm = { format, password ->
                passwordPurpose = null
                when (purpose) {
                    ArchivePasswordPurpose.CREATE -> {
                        pendingCreatePassword = password
                        pendingCreateFormat = format
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        pick = ArchivePick.CreateOutput("Fylz-$stamp.${format.extension}")
                    }
                    ArchivePasswordPurpose.EXTRACT -> {
                        pendingExtractPassword = password
                        pick = ArchivePick.ExtractInto
                    }
                }
            },
        )
    }

    inspection?.let { value ->
        ArchiveInspectionDialog(
            inspection = value,
            busy = busy,
            onDismiss = {
                inspection = null
                selectedArchive = null
            },
            onExtract = {
                if (value.encrypted) {
                    passwordPurpose = ArchivePasswordPurpose.EXTRACT
                } else {
                    pendingExtractPassword = null
                    pick = ArchivePick.ExtractInto
                }
            },
        )
    }
}

@Composable
private fun ArchivePasswordDialog(
    purpose: ArchivePasswordPurpose,
    onDismiss: () -> Unit,
    onConfirm: (CreatableArchiveFormat, CharArray?) -> Unit,
) {
    var encrypted by remember { mutableStateOf(purpose == ArchivePasswordPurpose.EXTRACT) }
    var format by remember { mutableStateOf(CreatableArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val creating = purpose == ArchivePasswordPurpose.CREATE
    val valid = if (!encrypted) {
        creating
    } else if (creating) {
        password.length in 8..256 && password == confirmation
    } else {
        password.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(if (creating) "Protect archive" else "Archive password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (creating) {
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    CreatableArchiveFormat.entries.forEach { candidate ->
                        TactileOptionRow(
                            text = candidate.label,
                            selected = format == candidate,
                            onClick = { format = candidate },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Encrypt with AES-256")
                            Text(
                                "Leave disabled to create a standard archive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TactileSwitch(checked = encrypted, onCheckedChange = { encrypted = it })
                    }
                }
                if (encrypted) {
                    TactileField(
                        value = password,
                        onValueChange = { password = it.take(256) },
                        label = "Password",
                        visualTransformation = PasswordVisualTransformation(),
                        mandatory = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (creating) {
                        // The Error state below reuses the exact same length/match condition that
                        // already gates `valid` (and therefore the confirm button) -- it is not a
                        // new validation rule, just that existing rule surfaced through the field's
                        // own slash/border colour instead of a plain caption.
                        val confirmProblem = when {
                            password.length < 8 -> "Use at least 8 characters."
                            confirmation.isNotEmpty() && password != confirmation -> "Passwords do not match."
                            else -> null
                        }
                        TactileField(
                            value = confirmation,
                            onValueChange = { confirmation = it.take(256) },
                            label = "Confirm password",
                            visualTransformation = PasswordVisualTransformation(),
                            state = confirmProblem?.let(TactileFieldState::Error) ?: TactileFieldState.Idle,
                            mandatory = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (confirmProblem == null) {
                            Text(
                                "Fylz cannot recover a forgotten archive password.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TactileButton(
                text = "Choose destination",
                onClick = {
                    val result = if (encrypted) password.toCharArray() else null
                    password = ""
                    confirmation = ""
                    onConfirm(format, result)
                },
                enabled = valid,
            )
        },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
    )
}

@Composable
private fun ArchiveInspectionDialog(
    inspection: ArchiveInspection,
    busy: Boolean,
    onDismiss: () -> Unit,
    onExtract: () -> Unit,
) {
    val allowed = inspection.extractionDecision.allowed
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
        title = { Text("Archive inspection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (inspection.encrypted) "Encrypted ZIP" else "Standard ZIP")
                Text("${inspection.fileCount} files · ${inspection.directoryCount} folders")
                Text("Archive size: ${formatArchiveBytes(inspection.archiveBytes)}")
                inspection.totalUncompressedBytes?.let {
                    Text("Expanded size: ${formatArchiveBytes(it)}")
                }
                inspection.temporarySpaceRequiredBytes?.let { required ->
                    Text("Temporary space required: ${formatArchiveBytes(required)}")
                }
                inspection.temporarySpaceAvailableBytes?.let { available ->
                    Text("Temporary space available: ${formatArchiveBytes(available)}")
                }
                if (!allowed) {
                    Text(
                        inspection.extractionDecision.reason ?: "This archive failed safety checks.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "Extraction creates a new folder and rolls it back if copying fails.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TactileButton(
                text = if (inspection.encrypted) "Enter password" else "Choose destination",
                onClick = onExtract,
                enabled = allowed && !busy,
            )
        },
        dismissButton = {
            TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY, enabled = !busy)
        },
    )
}

private fun formatArchiveBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.1f KiB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

private val ZIP_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

/** The character a wiped password buffer is filled with. */
private const val NUL = '\u0000'
