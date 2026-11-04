package io.github.mbaliga.fylz.ui

import android.content.Context
import android.provider.OpenableColumns
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import io.github.mbaliga.fylz.network.RemoteCapability
import io.github.mbaliga.fylz.network.RemoteConflictException
import io.github.mbaliga.fylz.network.RemoteWritePolicy
import io.github.mbaliga.fylz.ui.components.PermanentDeleteConfirmationDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.network.RemoteBrowser
import io.github.mbaliga.fylz.network.RemoteConnection
import io.github.mbaliga.fylz.network.RemoteConnectionStore
import io.github.mbaliga.fylz.network.RemoteKind
import io.github.mbaliga.fylz.network.RemoteObject
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Network location management: the UI that makes `SftpProvider`, `SmbProvider` and
 * `S3RemoteProvider` reachable at last.
 *
 * Before this, the only network surface in the app was a throwaway WebDAV dialog that dumped a
 * directory listing into a text alert and forgot the credentials. Connections are now saved
 * (secrets in the Keystore-backed vault), listed, browsable and deletable.
 *
 * Tapping a remote file asks where to save it (system "Create document" picker), downloads it
 * through the provider's verified atomic path into private cache, then streams it to the chosen
 * location.
 *
 * Writes (upload, new folder, delete) appear only for connections with "Allow writes" on, and all of
 * them go through [RemoteBrowser], which applies `RemoteWritePolicy` before any request: an upload
 * never silently replaces a file (the user is asked), a folder is never replaced, only empty folders
 * are deleted, and every delete needs the same typed confirmation as permanent local deletion.
 */
@Composable
fun RemoteConnectionsDialog(
    store: RemoteConnectionStore,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val browser = remember { RemoteBrowser() }

    var connections by remember { mutableStateOf(store.list()) }
    var editing by remember { mutableStateOf<RemoteConnection?>(null) }
    var addingKind by remember { mutableStateOf<RemoteKind?>(null) }
    var browsing by remember { mutableStateOf<RemoteConnection?>(null) }
    var listing by remember { mutableStateOf<List<RemoteObject>>(emptyList()) }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<RemoteObject?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteObject?>(null) }
    var replacePrompt by remember { mutableStateOf<StagedUpload?>(null) }
    var newFolderPrompt by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val canWrite = browsing?.let { RemoteCapability.UPLOAD in browser.capabilities(it) } == true

    // Staged copies normally go once their transfer settles. Anything left from a session that
    // ended mid-transfer (process death, cancellation, closing during a prompt) is swept here.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.cacheDir.listFiles { file ->
                file.name.startsWith(STAGING_PREFIX_UPLOAD) || file.name.startsWith(STAGING_PREFIX_DOWNLOAD)
            }?.forEach { it.delete() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { replacePrompt?.file?.delete() }
    }

    fun secretsFor(connection: RemoteConnection): () -> CharArray = {
        store.secret(connection.id)
            ?: error("No saved credential for ${connection.displayName}. Edit the connection to re-enter it.")
    }

    fun browse(connection: RemoteConnection, target: String) {
        val secret = store.secret(connection.id)
        if (secret == null) {
            onMessage("No saved credential for ${connection.displayName}. Edit the connection to re-enter it.")
            return
        }
        busy = true
        scope.launch {
            runCatching { browser.list(connection, target, secret) }
                .onSuccess { result ->
                    browsing = connection
                    path = target
                    listing = result.entries
                }
                .onFailure { onMessage(it.message ?: "Unable to reach ${connection.displayName}") }
            busy = false
        }
    }

    /**
     * Runs one remote write against [directory] (captured when the user acted, never re-read later),
     * reports the outcome and re-lists that folder so the user sees the result.
     */
    fun runWrite(connection: RemoteConnection, directory: String, success: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            val outcome = runCatching { block() }
            busy = false
            outcome
                .onSuccess { onMessage(success) }
                .onFailure { onMessage(it.message ?: "The remote operation failed.") }
            browse(connection, directory)
        }
    }

    fun upload(connection: RemoteConnection, staged: StagedUpload, replaceExisting: Boolean) {
        busy = true
        scope.launch {
            val outcome = runCatching {
                browser.upload(
                    connection = connection,
                    directory = staged.directory,
                    name = staged.name,
                    source = staged.file,
                    secrets = secretsFor(connection),
                    replaceExisting = replaceExisting,
                    mimeType = staged.mimeType,
                )
            }
            busy = false
            val failure = outcome.exceptionOrNull()
            if (failure is RemoteConflictException && !failure.existingIsDirectory && !replaceExisting) {
                replacePrompt = staged // keep the staged copy until the user decides
                return@launch
            }
            staged.file.delete()
            if (failure == null) onMessage("Uploaded ${staged.name}") else onMessage(failure.message ?: "Upload failed.")
            browse(connection, staged.directory)
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source: Uri? ->
        val connection = browsing
        if (source == null || connection == null) return@rememberLauncherForActivityResult
        val directory = path
        busy = true
        scope.launch {
            val staged = runCatching { stageForUpload(context, source, directory) }
            busy = false
            staged
                .onSuccess { upload(connection, it, replaceExisting = false) }
                .onFailure { onMessage(it.message ?: "Couldn’t read that file.") }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { target: Uri? ->
        val entry = pendingDownload
        val connection = browsing
        pendingDownload = null
        if (target == null || entry == null || connection == null) return@rememberLauncherForActivityResult
        val secret = store.secret(connection.id)
        if (secret == null) {
            onMessage("No saved credential for ${connection.displayName}. Edit the connection to re-enter it.")
            return@rememberLauncherForActivityResult
        }
        val remotePath = runCatching { RemoteWritePolicy.childPath(path, entry.name) }.getOrElse {
            onMessage(unaddressableName(entry.name))
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val staging = File(context.cacheDir, "$STAGING_PREFIX_DOWNLOAD${System.nanoTime()}.tmp")
            runCatching {
                browser.download(connection, remotePath, secret, staging)
                copyToUri(context, staging, target)
            }.onSuccess {
                onMessage("Saved ${entry.name}")
            }.onFailure {
                // Do not leave an empty document behind when the transfer failed.
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
                onMessage(it.message ?: "Download failed.")
            }
            staging.delete()
            busy = false
        }
    }

    fun refresh() {
        connections = store.list()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (browsing != null) {
                        IconButton(
                            // Navigating away mid-write would clear `busy` and re-enable writes.
                            enabled = !busy,
                            onClick = {
                                if (path.isBlank()) {
                                    browsing = null
                                    listing = emptyList()
                                } else {
                                    browse(browsing!!, path.substringBeforeLast('/', ""))
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            browsing?.displayName ?: "Network locations",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            browsing?.let { "/${path}" }
                                ?: "WebDAV, SFTP, SMB and S3-compatible storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close network locations")
                    }
                }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                when {
                    addingKind != null || editing != null -> RemoteConnectionForm(
                        kind = addingKind ?: editing!!.kind,
                        existing = editing,
                        hasStoredSecret = editing?.let(RemoteConnection::id)?.let(store::hasSecret) == true,
                        onCancel = { addingKind = null; editing = null },
                        onSave = { connection, secret ->
                            runCatching { store.save(connection, secret) }
                                .onSuccess { addingKind = null; editing = null; refresh() }
                                .onFailure { onMessage(it.message ?: "That connection is not valid.") }
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    browsing != null -> Column(Modifier.weight(1f, fill = false)) {
                        WriteToolbar(
                            canWrite = canWrite,
                            enabled = !busy,
                            onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                            onNewFolder = { newFolderPrompt = true },
                        )
                        RemoteListing(
                            entries = listing,
                            onOpen = { entry ->
                                if (busy) return@RemoteListing
                                if (entry.directory) {
                                    runCatching { RemoteWritePolicy.childPath(path, entry.name) }
                                        .onSuccess { browse(browsing!!, it) }
                                        .onFailure { onMessage(unaddressableName(entry.name)) }
                                } else {
                                    pendingDownload = entry
                                    saveLauncher.launch(entry.name)
                                }
                            },
                            onDelete = if (canWrite) ({ entry -> if (!busy) pendingDelete = entry }) else null,
                        )
                    }

                    else -> ConnectionList(
                        connections = connections,
                        onBrowse = { browse(it, "") },
                        onEdit = { editing = it },
                        onDelete = { store.delete(it.id); refresh() },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                if (addingKind == null && editing == null && browsing == null) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("Add a connection", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RemoteKind.entries.forEach { kind ->
                            AssistChip(
                                onClick = { addingKind = kind },
                                label = { Text(kind.name) },
                                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val connection = browsing
    pendingDelete?.let { entry ->
        if (connection == null) {
            pendingDelete = null
            return@let
        }
        PermanentDeleteConfirmationDialog(
            displayName = "${entry.name}” on “${connection.displayName}",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                val directory = path
                runWrite(connection, directory, "Deleted ${entry.name}") {
                    browser.delete(connection, directory, entry, secretsFor(connection), confirmed = true)
                }
            },
        )
    }

    replacePrompt?.let { staged ->
        if (connection == null) {
            staged.file.delete()
            replacePrompt = null
            return@let
        }
        ReplaceRemoteFileDialog(
            name = staged.name,
            location = "/${staged.directory} on ${connection.displayName}",
            onDismiss = {
                replacePrompt = null
                staged.file.delete()
                onMessage("Kept the existing ${staged.name}")
            },
            onReplace = {
                replacePrompt = null
                upload(connection, staged, replaceExisting = true)
            },
        )
    }

    if (newFolderPrompt && connection != null) {
        NewRemoteFolderDialog(
            kind = connection.kind,
            onDismiss = { newFolderPrompt = false },
            onCreate = { name ->
                newFolderPrompt = false
                val directory = path
                runWrite(connection, directory, "Created $name") {
                    browser.createDirectory(connection, directory, name, secretsFor(connection))
                }
            },
        )
    }
}

@Composable
private fun WriteToolbar(
    canWrite: Boolean,
    enabled: Boolean,
    onUpload: () -> Unit,
    onNewFolder: () -> Unit,
) {
    if (!canWrite) {
        Text(
            "Read-only. Turn on “Allow writes” in this connection’s settings to upload or delete.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = onUpload,
            enabled = enabled,
            label = { Text("Upload") },
            leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        AssistChip(
            onClick = onNewFolder,
            enabled = enabled,
            label = { Text("New folder") },
            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

@Composable
private fun NewRemoteFolderDialog(
    kind: RemoteKind,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val problem = RemoteWritePolicy.nameProblem(kind, name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                isError = name.isNotEmpty() && problem != null,
                supportingText = { if (name.isNotEmpty() && problem != null) Text(problem) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = problem == null) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReplaceRemoteFileDialog(
    name: String,
    location: String,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace “$name”?") },
        text = {
            Text(
                "A file with this name already exists in $location. Replacing it overwrites the remote " +
                    "copy, and Fylz can’t restore the old version.",
            )
        },
        confirmButton = { Button(onClick = onReplace) { Text("Replace") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep existing") } },
    )
}

/** A local copy of a picked document, ready to upload. Deleted once the upload settles. */
private data class StagedUpload(val file: File, val name: String, val mimeType: String, val directory: String)

private fun unaddressableName(name: String) =
    "Fylz can’t open “$name”: its name contains characters Fylz can’t address on this server."

private const val STAGING_PREFIX_UPLOAD = "remote-upload-"
private const val STAGING_PREFIX_DOWNLOAD = "remote-download-"

private suspend fun stageForUpload(context: Context, source: Uri, directory: String): StagedUpload =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf(String::isNotBlank) ?: error("That file has no name Fylz can use.")
        val mimeType = resolver.getType(source) ?: "application/octet-stream"
        val staging = File(context.cacheDir, "$STAGING_PREFIX_UPLOAD${System.nanoTime()}.tmp")
        try {
            val input = resolver.openInputStream(source) ?: error("Couldn’t open that file.")
            input.use { inStream -> staging.outputStream().use { inStream.copyTo(it) } }
        } catch (failure: Throwable) {
            staging.delete()
            throw failure
        }
        StagedUpload(staging, name, mimeType, directory)
    }

@Composable
private fun ConnectionList(
    connections: List<RemoteConnection>,
    onBrowse: (RemoteConnection) -> Unit,
    onEdit: (RemoteConnection) -> Unit,
    onDelete: (RemoteConnection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (connections.isEmpty()) {
        Column(
            modifier.fillMaxWidth().padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(34.dp))
            Text("No saved connections", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fylz speaks WebDAV, SFTP, SMB and S3-compatible storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(connections, key = RemoteConnection::id) { connection ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 14.dp).heightIn(min = 56.dp),
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onBrowse(connection) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(connection.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${connection.kind.label} · ${connection.summary()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onEdit(connection) }) { Text("Edit") }
                    IconButton(onClick = { onDelete(connection) }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete ${connection.displayName}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteListing(
    entries: List<RemoteObject>,
    onOpen: (RemoteObject) -> Unit,
    onDelete: ((RemoteObject) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Text(
            "This remote folder is empty.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.fillMaxWidth().padding(vertical = 30.dp),
        )
        return
    }
    LazyColumn(modifier) {
        items(entries, key = RemoteObject::key) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = if (entry.directory) "Open folder" else "Download") { onOpen(entry) }
                    .padding(vertical = 6.dp),
            ) {
                Icon(
                    if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                    contentDescription = null,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    entry.sizeBytes?.let { size ->
                        Text(
                            formatStorageBytes(size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete ${entry.name} from the server")
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteConnectionForm(
    kind: RemoteKind,
    existing: RemoteConnection?,
    hasStoredSecret: Boolean,
    onCancel: () -> Unit,
    onSave: (RemoteConnection, CharArray?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(existing?.displayName ?: kind.label) }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString() ?: defaultPort(kind)) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var secret by remember { mutableStateOf("") }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var endpoint by remember { mutableStateOf(existing?.endpoint ?: "https://") }
    var region by remember { mutableStateOf(existing?.region ?: "us-east-1") }
    var bucket by remember { mutableStateOf(existing?.bucket ?: "") }
    var fingerprint by remember { mutableStateOf(existing?.hostKeyFingerprint ?: "") }
    var writes by remember { mutableStateOf(existing?.writesEnabled ?: false) }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(kind.label, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true)

        when (kind) {
            RemoteKind.WEBDAV -> {
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS server URL") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
            }

            RemoteKind.SFTP -> {
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(
                    fingerprint,
                    { fingerprint = it },
                    label = { Text("SHA-256 host key fingerprint") },
                    supportingText = {
                        // SftpProviderConfig rejects a config without one, by design.
                        Text("Required. Run: ssh-keyscan host | ssh-keygen -lf -")
                    },
                    singleLine = true,
                )
            }

            RemoteKind.SMB -> {
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
                OutlinedTextField(share, { share = it }, label = { Text("Share") }, singleLine = true)
                OutlinedTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
            }

            RemoteKind.S3 -> {
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint") }, singleLine = true)
                OutlinedTextField(region, { region = it }, label = { Text("Region") }, singleLine = true)
                OutlinedTextField(bucket, { bucket = it }, label = { Text("Bucket") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Access key id") }, singleLine = true)
            }
        }

        OutlinedTextField(
            secret,
            { secret = it },
            label = { Text(if (kind == RemoteKind.S3) "Secret access key" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    if (hasStoredSecret) {
                        "A credential is already stored. Leave blank to keep it."
                    } else {
                        "Stored encrypted with an Android Keystore key. Never written in plaintext."
                    },
                )
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Switch(checked = writes, onCheckedChange = { writes = it })
            Spacer(Modifier.width(10.dp))
            Text("Allow writes (uploads and deletions)")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val connection = RemoteConnection(
                        id = existing?.id ?: "remote-${UUID.randomUUID()}",
                        kind = kind,
                        displayName = name.trim(),
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 0,
                        username = username.trim(),
                        share = share.trim(),
                        domain = domain.trim(),
                        endpoint = endpoint.trim(),
                        region = region.trim(),
                        bucket = bucket.trim(),
                        hostKeyFingerprint = fingerprint.trim(),
                        writesEnabled = writes,
                    )
                    onSave(connection, secret.takeIf(String::isNotEmpty)?.toCharArray())
                },
                enabled = name.isNotBlank() && (secret.isNotEmpty() || hasStoredSecret),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Save")
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun RemoteConnection.summary(): String = when (kind) {
    RemoteKind.WEBDAV -> endpoint
    RemoteKind.SFTP -> "$username@$host:${port.takeIf { it > 0 } ?: 22}"
    RemoteKind.SMB -> "\\\\$host\\$share"
    RemoteKind.S3 -> "$bucket @ $region"
}

private fun defaultPort(kind: RemoteKind): String = when (kind) {
    RemoteKind.SFTP -> "22"
    RemoteKind.SMB -> "445"
    else -> ""
}

private suspend fun copyToUri(context: Context, source: File, target: Uri) =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(target, "wt")
            ?: error("Unable to open the chosen save location.")
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
    }
