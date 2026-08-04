package io.github.mbaliga.fylz.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.mbaliga.fylz.data.ArchiveInspection
import io.github.mbaliga.fylz.data.ArchiveService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ArchivePasswordPurpose {
    CREATE,
    EXTRACT,
}

@Composable
fun ArchiveToolsOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { ArchiveService(context.applicationContext) }
    var menuOpen by remember { mutableStateOf(false) }
    var passwordPurpose by remember { mutableStateOf<ArchivePasswordPurpose?>(null) }
    var selectedSources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedArchive by remember { mutableStateOf<Uri?>(null) }
    var inspection by remember { mutableStateOf<ArchiveInspection?>(null) }
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

    FloatingActionButton(onClick = { menuOpen = true }, modifier = modifier) {
        Icon(Icons.Outlined.Archive, contentDescription = "Open archive tools")
    }

    if (menuOpen) {
        AlertDialog(
            onDismissRequest = { if (!busy) menuOpen = false },
            icon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
            title = { Text("Archive tools") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Create standard or AES-256 password-protected ZIP files, or inspect and safely extract an existing ZIP.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            menuOpen = false
                            sourcePicker.launch(arrayOf("*/*"))
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Archive, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create ZIP")
                    }
                    OutlinedButton(
                        onClick = {
                            menuOpen = false
                            archivePicker.launch(ZIP_MIME_TYPES)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Unarchive, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Inspect and extract ZIP")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuOpen = false }) { Text("Done") } },
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
                        pendingCreatePassword = password
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        createDestination.launch("Fylz-$stamp.zip")
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
                    extractDestination.launch(null)
                }
            },
        )
    }
}

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
            Button(onClick = onExtract, enabled = allowed && !busy) {
                Text(if (inspection.encrypted) "Enter password" else "Choose destination")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
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
