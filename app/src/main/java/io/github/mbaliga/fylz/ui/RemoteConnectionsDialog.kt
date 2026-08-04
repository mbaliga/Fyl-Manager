package io.github.mbaliga.fylz.ui

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
 * Scope note: this browses and inspects remote locations. Remote-to-local transfers still run
 * through each provider's `download`, which is not yet wired to a destination picker -- that is
 * called out in the PR rather than faked here.
 */
@Composable
fun RemoteConnectionsDialog(
    store: RemoteConnectionStore,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
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

    fun refresh() {
        connections = store.list()
    }

    fun browse(connection: RemoteConnection, target: String) {
        val secret = store.secret(connection.id)
        if (secret == null) {
            onError("No saved credential for ${connection.displayName}. Edit the connection to re-enter it.")
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
                .onFailure { onError(it.message ?: "Unable to reach ${connection.displayName}") }
            busy = false
        }
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
                                .onFailure { onError(it.message ?: "That connection is not valid.") }
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    browsing != null -> RemoteListing(
                        entries = listing,
                        onOpen = { entry ->
                            val next = if (path.isBlank()) entry.name else "$path/${entry.name}"
                            browse(browsing!!, next)
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )

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
                    .clickable(enabled = entry.directory) { onOpen(entry) }
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
