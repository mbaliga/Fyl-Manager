package io.github.mbaliga.fylz.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.history.FileHistoryRestoreStatus
import io.github.mbaliga.fylz.history.FileHistorySettings
import io.github.mbaliga.fylz.history.FileHistoryStore
import io.github.mbaliga.fylz.history.FileHistoryUsage
import io.github.mbaliga.fylz.history.FileHistoryVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun FileHistoryOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { FileHistoryStore(context.applicationContext) }
    var open by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(store.settings()) }
    var versions by remember { mutableStateOf(store.allVersions()) }
    var usage by remember { mutableStateOf(store.usage()) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        settings = store.settings()
        versions = store.allVersions()
        usage = store.usage()
    }

    Box(modifier) {
        FloatingActionButton(
            onClick = {
                refresh()
                open = true
            },
        ) {
            Icon(Icons.Outlined.History, contentDescription = "File history")
        }
    }

    if (open) {
        FileHistoryDialog(
            settings = settings,
            usage = usage,
            versions = versions,
            busy = busy,
            onDismiss = { open = false },
            onSaveSettings = { value ->
                scope.launch {
                    busy = true
                    runCatching {
                        withContext(Dispatchers.IO) { store.updateSettings(value) }
                    }.onSuccess {
                        refresh()
                        Toast.makeText(context, "File history settings saved.", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Unable to save settings.", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            },
            onRestore = { version ->
                scope.launch {
                    busy = true
                    val result = runCatching {
                        store.restore(version.id, Uri.parse(version.sourceUri))
                    }.getOrElse { failure ->
                        Toast.makeText(context, failure.message ?: "Restore failed.", Toast.LENGTH_LONG).show()
                        busy = false
                        return@launch
                    }
                    val message = when (result.status) {
                        FileHistoryRestoreStatus.RESTORED -> "Version restored."
                        FileHistoryRestoreStatus.VERIFICATION_FAILED_ROLLED_BACK ->
                            result.message ?: "Restore did not verify; the previous file was restored."
                        FileHistoryRestoreStatus.VERIFICATION_FAILED_ROLLBACK_FAILED ->
                            result.message ?: "Restore and rollback both failed. Check the file immediately."
                        FileHistoryRestoreStatus.CURRENT_VERSION_NOT_PRESERVED ->
                            result.message ?: "The current file could not be preserved, so restore was cancelled."
                        FileHistoryRestoreStatus.VERSION_NOT_FOUND -> "That saved version no longer exists."
                        FileHistoryRestoreStatus.SNAPSHOT_MISSING -> "The saved version data is missing."
                        FileHistoryRestoreStatus.TARGET_UNREADABLE -> "The original file is no longer accessible."
                        FileHistoryRestoreStatus.WRITE_FAILED -> "The provider refused the restore write."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    refresh()
                    busy = false
                }
            },
            onDelete = { version ->
                if (store.delete(version.id)) refresh()
            },
            onClear = {
                val removed = store.clear()
                refresh()
                Toast.makeText(context, "Deleted $removed saved versions.", Toast.LENGTH_LONG).show()
            },
        )
    }
}

@Composable
private fun FileHistoryDialog(
    settings: FileHistorySettings,
    usage: FileHistoryUsage,
    versions: List<FileHistoryVersion>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSaveSettings: (FileHistorySettings) -> Unit,
    onRestore: (FileHistoryVersion) -> Unit,
    onDelete: (FileHistoryVersion) -> Unit,
    onClear: () -> Unit,
) {
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var versionLimit by remember(settings) { mutableStateOf(settings.maxVersionsPerFile.toString()) }
    var fileLimitMb by remember(settings) {
        mutableStateOf((settings.maxFileBytes / MEBIBYTE).coerceAtLeast(1L).toString())
    }
    var storageLimitGb by remember(settings) {
        mutableStateOf(formatDecimal(settings.maxStorageBytes.toDouble() / GIBIBYTE.toDouble()))
    }

    val parsedVersions = versionLimit.toIntOrNull()
    val parsedFileMb = fileLimitMb.toLongOrNull()
    val parsedStorageGb = storageLimitGb.toDoubleOrNull()
    val valid = parsedVersions != null && parsedVersions in 1..100 &&
        parsedFileMb != null && parsedFileMb in 1L..102_400L &&
        parsedStorageGb != null && parsedStorageGb > 0.0 && parsedStorageGb <= 1024.0

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("File history", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Private, on-device snapshots created before Fylz overwrites eligible files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close file history")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep file versions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Disabled by default because snapshots duplicate file contents.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = versionLimit,
                        onValueChange = { versionLimit = it.filter(Char::isDigit).take(3) },
                        label = { Text("Versions/file") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = fileLimitMb,
                        onValueChange = { fileLimitMb = it.filter(Char::isDigit).take(6) },
                        label = { Text("Max file (MiB)") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = storageLimitGb,
                    onValueChange = { value ->
                        storageLimitGb = value.filter { it.isDigit() || it == '.' }.take(8)
                    },
                    label = { Text("History storage cap (GiB)") },
                    supportingText = { Text("Oldest versions are pruned first when this cap is reached.") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                Button(
                    onClick = {
                        onSaveSettings(
                            FileHistorySettings(
                                enabled = enabled,
                                maxVersionsPerFile = parsedVersions!!,
                                maxFileBytes = parsedFileMb!! * MEBIBYTE,
                                maxStorageBytes = (parsedStorageGb!! * GIBIBYTE).toLong(),
                            ),
                        )
                    },
                    enabled = valid && !busy,
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save settings")
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text(
                    "${formatBytes(usage.totalBytes)} of ${formatBytes(usage.maxBytes)} · " +
                        "${usage.versionCount} versions across ${usage.fileCount} files",
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    progress = {
                        if (usage.maxBytes <= 0L) 0f
                        else (usage.totalBytes.toFloat() / usage.maxBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                )

                if (versions.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(34.dp))
                        Text("No saved versions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (settings.enabled) {
                                "A snapshot will be added before the next eligible overwrite."
                            } else {
                                "Enable file history to begin preserving eligible versions."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        items(versions, key = FileHistoryVersion::id) { version ->
                            FileHistoryVersionCard(
                                version = version,
                                restoreEnabled = settings.enabled && !busy,
                                deleteEnabled = !busy,
                                onRestore = { onRestore(version) },
                                onDelete = { onDelete(version) },
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onClear, enabled = versions.isNotEmpty() && !busy) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear history")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss, enabled = !busy) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun FileHistoryVersionCard(
    version: FileHistoryVersion,
    restoreEnabled: Boolean,
    deleteEnabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                version.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatHistoryTime(version.capturedAtMillis)} · ${formatBytes(version.sizeBytes)} · " +
                    version.reason.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onRestore, enabled = restoreEnabled) {
                    Icon(Icons.Outlined.Restore, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Restore")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete, enabled = deleteEnabled) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete saved version")
                }
            }
        }
    }
}

private fun formatHistoryTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))

private fun formatDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

private fun formatBytes(bytes: Long): String = when {
    bytes >= GIBIBYTE -> "%.2f GiB".format(bytes.toDouble() / GIBIBYTE)
    bytes >= MEBIBYTE -> "%.1f MiB".format(bytes.toDouble() / MEBIBYTE)
    bytes >= 1024L -> "%.1f KiB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

private const val MEBIBYTE = 1024L * 1024L
private const val GIBIBYTE = 1024L * 1024L * 1024L
