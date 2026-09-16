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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.mbaliga.fylz.index.IndexScope
import io.github.mbaliga.fylz.index.LocalIndexScheduler
import io.github.mbaliga.fylz.index.LocalIndexStore
import io.github.mbaliga.fylz.index.RuleField
import io.github.mbaliga.fylz.index.RuleJoin
import io.github.mbaliga.fylz.index.RuleOperator
import io.github.mbaliga.fylz.index.SmartCollection
import io.github.mbaliga.fylz.index.SmartRule
import io.github.mbaliga.fylz.index.contentSnippet
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
    var collections by remember { mutableStateOf(store.collections()) }
    var activeCollectionId by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(store.query()) }
    var collectionName by remember { mutableStateOf("") }
    var extensionRule by remember { mutableStateOf("") }
    var contentRule by remember { mutableStateOf("") }

    fun refresh() {
        scopes = store.scopes()
        state = store.state()
        collections = store.collections()
        results = store.query(query, activeCollectionId)
    }

    // Follow the rebuild job itself, so the count and results move while it runs instead of
    // freezing at whatever this screen read when it opened until some other button happened to
    // call refresh().
    val workInfos by remember {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(LocalIndexScheduler.WORK_NAME)
    }.collectAsState(initial = emptyList())
    val rebuilding = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(workInfos) { refresh() }

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
            // Adding a folder is the whole reason to open this screen; making the user also find
            // "Rebuild" afterwards left "0 indexed items" on screen with no hint why.
            scheduler.rebuild()
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
                    "Only folders you explicitly add are indexed. Fylz keeps names, metadata and a short text sample " +
                        "from PDF, Word, Excel and PowerPoint files on this device so search can look inside them. " +
                        "Nothing leaves the device.",
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
                Text("${state.indexedFiles} indexed items${if (state.truncated) " · bounded" else ""}${if (rebuilding) " · rebuilding…" else ""}")
                if (rebuilding) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
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
                    onValueChange = { query = it; results = store.query(it, activeCollectionId) },
                    label = { Text("Search names, tags and document text") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (collections.isNotEmpty()) {
                item { Text("Saved collections", style = MaterialTheme.typography.titleSmall) }
                items(collections, key = SmartCollection::id) { collection ->
                    val active = collection.id == activeCollectionId
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                ruleSummary(collection),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(onClick = {
                            activeCollectionId = if (active) null else collection.id
                            results = store.query(query, activeCollectionId)
                        }) { Text(if (active) "Clear" else "Apply") }
                        IconButton(onClick = {
                            store.removeCollection(collection.id)
                            if (active) activeCollectionId = null
                            refresh()
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete collection ${collection.name}")
                        }
                    }
                }
            }
            activeCollectionId?.let { id ->
                collections.firstOrNull { it.id == id }?.let { collection ->
                    item {
                        Text(
                            "Showing ${results.size} ${if (results.size == 1) "match" else "matches"} in \"${collection.name}\"",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            items(results.take(500), key = { it.uri }) { file ->
                Column(Modifier.fillMaxWidth()) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${file.extension.ifBlank { "file" }} · ${file.mimeType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    contentSnippet(file.textSample, query)?.let { snippet ->
                        Text(
                            snippet,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                OutlinedTextField(
                    contentRule,
                    { contentRule = it },
                    // PDF and .docx/.xlsx/.pptx only -- ContentTextExtractor is what samples the
                    // text this rule matches against, and it does not read any other kind.
                    label = { Text("Contains text (PDF, Word, Excel, PowerPoint)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    enabled = collectionName.isNotBlank() && (extensionRule.isNotBlank() || contentRule.isNotBlank()),
                    onClick = {
                        val rules = buildList {
                            if (extensionRule.isNotBlank()) add(SmartRule(RuleField.EXTENSION, RuleOperator.EQUALS, extensionRule.trim().removePrefix(".")))
                            if (contentRule.isNotBlank()) add(SmartRule(RuleField.TEXT_CONTENT, RuleOperator.CONTAINS, contentRule.trim()))
                        }
                        val collection = SmartCollection(name = collectionName.trim(), join = RuleJoin.ALL, rules = rules)
                        store.putCollection(collection)
                        collectionName = ""
                        extensionRule = ""
                        contentRule = ""
                        // Apply it straight away: a saved rule that then has to be found and
                        // applied by hand reads as "nothing happened".
                        activeCollectionId = collection.id
                        refresh()
                        Toast.makeText(context, "Collection saved and applied", Toast.LENGTH_SHORT).show()
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

private fun ruleSummary(collection: SmartCollection): String = collection.rules.joinToString(" · ") { rule ->
    val field = when (rule.field) {
        RuleField.NAME -> "name"
        RuleField.PATH -> "path"
        RuleField.EXTENSION -> "extension"
        RuleField.MIME -> "type"
        RuleField.SIZE -> "size"
        RuleField.MODIFIED -> "modified"
        RuleField.TEXT_CONTENT -> "text"
        RuleField.DIRECTORY -> "folder"
    }
    val operator = when (rule.operator) {
        RuleOperator.CONTAINS -> "contains"
        RuleOperator.EQUALS -> "is"
        RuleOperator.STARTS_WITH -> "starts with"
        RuleOperator.ENDS_WITH -> "ends with"
        RuleOperator.GREATER_THAN -> "over"
        RuleOperator.LESS_THAN -> "under"
        RuleOperator.BEFORE -> "before"
        RuleOperator.AFTER -> "after"
        RuleOperator.IS -> "is"
    }
    "${if (rule.negate) "not " else ""}$field $operator \"${rule.value}\""
}
