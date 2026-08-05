package io.github.mbaliga.fylz

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.index.IndexScope
import io.github.mbaliga.fylz.index.IndexState
import io.github.mbaliga.fylz.index.LocalIndexScheduler
import io.github.mbaliga.fylz.index.LocalIndexStore
import io.github.mbaliga.fylz.library.LibraryMetadataTransfer
import java.text.DateFormat
import java.util.Date
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode

class PostV1ToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FylzTheme, not a bare MaterialTheme. This screen opened in the platform default
        // while the rest of the app was dark — the light-coloured Tools screen inside a
        // dark app that device testing turned up. An activity of Fylz is themed by Fylz.
        setContent {
            FylzTheme(
                themeMode = ThemeMode.SYSTEM,
                accentPreset = AccentPreset.MOSS,
                dynamicColor = true,
            ) { PostV1ToolsScreen() }
        }
    }
}

@Composable
private fun PostV1ToolsScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val indexStore = remember { LocalIndexStore(appContext) }
    val scheduler = remember { LocalIndexScheduler(appContext) }
    val transfer = remember { LibraryMetadataTransfer(appContext) }
    var state by remember { mutableStateOf(indexStore.state()) }
    var scopes by remember { mutableStateOf(indexStore.scopes()) }

    fun refresh() {
        state = indexStore.state()
        scopes = indexStore.scopes()
    }

    fun persist(uri: Uri, write: Boolean = false) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    val scopePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            val name = runCatching {
                DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').ifBlank { "Indexed folder" }
            }.getOrDefault("Indexed folder")
            indexStore.putScope(IndexScope(uri.toString(), name, enabled = true))
            refresh()
        }
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(transfer.export())
                } ?: error("The destination is not writable.")
            }.onSuccess {
                Toast.makeText(context, "Library metadata exported", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persist(uri)
            runCatching {
                val value = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(8_192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(output.length + count <= 8 * 1024 * 1024) { "Metadata import exceeds 8 MiB." }
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                } ?: error("The metadata file is unreadable.")
                transfer.import(value)
            }.onSuccess { result ->
                Toast.makeText(
                    context,
                    "Imported ${result.favoritesImported} favourites, ${result.searchesImported} searches and ${result.taggedFilesImported} tag records",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tools", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Local indexing, smart collections, metadata portability and advanced document/provider tools.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ToolSection("Local index") {
            IndexSummary(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { scheduler.rebuild(); refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text("Rebuild", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = {
                    val paused = !indexStore.state().paused
                    indexStore.setPaused(paused)
                    if (paused) scheduler.cancel() else scheduler.rebuild()
                    refresh()
                }) {
                    Icon(if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = null)
                    Text(if (state.paused) "Resume" else "Pause", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { indexStore.clearFiles(); refresh() }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    Text("Delete index", Modifier.padding(start = 6.dp))
                }
            }
            HorizontalDivider()
            Text("Indexed folders", style = MaterialTheme.typography.titleSmall)
            scopes.forEach { scope ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(scope.displayName, fontWeight = FontWeight.Medium)
                        Text(scope.rootUri, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = scope.enabled,
                        onCheckedChange = { enabled -> indexStore.putScope(scope.copy(enabled = enabled)); refresh() },
                    )
                    OutlinedButton(onClick = { indexStore.removeRoot(scope.rootUri); refresh() }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = { scopePicker.launch(null) }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text("Add folder", Modifier.padding(start = 6.dp))
            }
        }

        ToolSection("Library metadata") {
            Text("Export or import favourites, tags and saved searches. Credentials and file contents are never included.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportPicker.launch("fylz-library-metadata.json") }) {
                    Icon(Icons.Outlined.Upload, contentDescription = null)
                    Text("Export", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) }) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Text("Import", Modifier.padding(start = 6.dp))
                }
            }
        }

        ToolSection("More tools") {
            Text(
                "PDF/OCR, smart-rule editing, duplicate cleanup, signed model packs and remote-provider setup are being consolidated into this destination. Their deterministic services are already present; each journey remains disabled until its validation and confirmation UI is complete.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolSection(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun IndexSummary(state: IndexState) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${state.indexedFiles} indexed entries")
        Text(
            when {
                state.lastError != null -> "Last error: ${state.lastError}"
                state.lastCompletedAtMillis != null -> "Last completed ${formatTime(state.lastCompletedAtMillis)}"
                state.lastStartedAtMillis != null -> "Started ${formatTime(state.lastStartedAtMillis)}"
                else -> "Not built yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.lastError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (state.truncated) Text("The index reached its configured safety limit.", color = MaterialTheme.colorScheme.error)
    }
}

private fun formatTime(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
