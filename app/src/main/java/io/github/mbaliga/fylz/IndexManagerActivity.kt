package io.github.mbaliga.fylz

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import io.github.mbaliga.fylz.index.IndexScope
import io.github.mbaliga.fylz.index.LocalIndexScheduler
import io.github.mbaliga.fylz.index.LocalIndexStore
import io.github.mbaliga.fylz.index.RuleField
import io.github.mbaliga.fylz.index.RuleJoin
import io.github.mbaliga.fylz.index.RuleOperator
import io.github.mbaliga.fylz.index.SmartCollection
import io.github.mbaliga.fylz.index.SmartRule
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode

class IndexManagerActivity : ComponentActivity() {
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
            ) { IndexManagerScreen(onClose = ::finish) }
        }
    }
}

@Composable
private fun IndexManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalIndexStore(context.applicationContext) }
    val scheduler = remember { LocalIndexScheduler(context.applicationContext) }
    val library = remember { LibraryStore(context.applicationContext) }
    var scopes by remember { mutableStateOf(store.scopes()) }
    var state by remember { mutableStateOf(store.state()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(store.query()) }
    var collectionName by remember { mutableStateOf("") }
    var extensionRule by remember { mutableStateOf("") }

    fun refresh() {
        scopes = store.scopes()
        state = store.state()
        results = store.query(query)
    }

    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    val scopePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            val root = DocumentFile.fromTreeUri(context, uri)
            store.putScope(IndexScope(uri.toString(), root?.name ?: "Indexed folder"))
            refresh()
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(library.exportJson()) }
                ?: error("Unable to write metadata export.")
        }.onSuccess { Toast.makeText(context, "Library metadata exported", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Unable to read metadata import.")
            library.importJson(text)
        }.onSuccess { Toast.makeText(context, "Library metadata imported", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Index & collections") },
                actions = { OutlinedButton(onClick = onClose) { Text("Close") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Only folders you explicitly add are indexed. Fylz stores names and metadata, not file contents.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { scopePicker.launch(null) }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("Add folder", Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = { scheduler.rebuild(); state = store.state() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Text("Rebuild", Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = {
                        store.setPaused(!state.paused)
                        if (state.paused) scheduler.rebuild() else scheduler.cancel()
                        refresh()
                    }) {
                        Icon(if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = null)
                        Text(if (state.paused) "Resume" else "Pause", Modifier.padding(start = 6.dp))
                    }
                }
            }
            item {
                Text("${state.indexedFiles} indexed items${if (state.truncated) " · bounded" else ""}")
                state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(scopes, key = IndexScope::rootUri) { scope ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(scope.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(scope.rootUri, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { store.removeRoot(scope.rootUri); refresh() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove indexed folder")
                    }
                }
            }
            item { HorizontalDivider() }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; results = store.query(it) },
                    label = { Text("Search local index") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(results.take(500), key = { it.uri }) { file ->
                Column(Modifier.fillMaxWidth()) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${file.extension.ifBlank { "file" }} · ${file.mimeType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { HorizontalDivider() }
            item { Text("Create a smart collection", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(collectionName, { collectionName = it }, label = { Text("Collection name") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(extensionRule, { extensionRule = it }, label = { Text("File extension, for example obj") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Button(
                    enabled = collectionName.isNotBlank() && extensionRule.isNotBlank(),
                    onClick = {
                        store.putCollection(
                            SmartCollection(
                                name = collectionName.trim(),
                                join = RuleJoin.ALL,
                                rules = listOf(SmartRule(RuleField.EXTENSION, RuleOperator.EQUALS, extensionRule.trim().removePrefix("."))),
                            ),
                        )
                        collectionName = ""
                        extensionRule = ""
                        Toast.makeText(context, "Smart collection saved", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Save collection") }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    OutlinedButton(onClick = { exportPicker.launch("fylz-library-metadata.json") }) { Text("Export metadata") }
                    OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) }) { Text("Import metadata") }
                    OutlinedButton(onClick = { store.clearFiles(); refresh() }) { Text("Delete index") }
                }
            }
        }
    }
}
