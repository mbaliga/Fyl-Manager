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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.archive.ArchiveFormatFamily
import io.github.mbaliga.fylz.archive.ArchiveInspectionResult
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.model.BrowsableArchiveFormats
import io.github.mbaliga.fylz.ui.actions.ArchiveToolsMenuDialog
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
                }
            },
        )
    }

    passwordPurpose?.let { purpose ->
        ArchivePasswordDialog(
            purpose = purpose,
            onDismiss = {
                passwordPurpose = null
                if (purpose == ArchivePasswordPurpose.CREATE) selectedSources = emptyList()
            },
            onConfirm = { password ->
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
                            // (design §2.7's own scoped-down choice, until M3.9 gives every format
                            // a password prompt through the queue and M3.10 removes zip4j).
                            pendingCreatePassword = password
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            createDestination.launch("Fylz-$stamp.zip")
                        }
                    }
                    ArchivePasswordPurpose.EXTRACT -> {
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
                    passwordPurpose = ArchivePasswordPurpose.EXTRACT
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
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

@Composable
private fun ArchivePasswordDialog(
    purpose: ArchivePasswordPurpose,
    onDismiss: () -> Unit,
    onConfirm: (CharArray?) -> Unit,
) {
    var encrypted by remember { mutableStateOf(purpose == ArchivePasswordPurpose.EXTRACT) }
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Encrypt with AES-256")
                            Text(
                                "Leave disabled to create a standard ZIP.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = encrypted, onCheckedChange = { encrypted = it })
                    }
                }
                if (encrypted) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(256) },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (creating) {
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it.take(256) },
                            label = { Text("Confirm password") },
                            visualTransformation = PasswordVisualTransformation(),
                            supportingText = {
                                Text(
                                    when {
                                        password.length < 8 -> "Use at least 8 characters."
                                        confirmation.isNotEmpty() && password != confirmation -> "Passwords do not match."
                                        else -> "Fylz cannot recover a forgotten archive password."
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = if (encrypted) password.toCharArray() else null
                    password = ""
                    confirmation = ""
                    onConfirm(result)
                },
                enabled = valid,
            ) {
                Text(if (creating) "Choose destination" else "Choose destination")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
