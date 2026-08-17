package io.github.mbaliga.fylz.ui.picker

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.storage.StorageRootKind
import kotlinx.coroutines.launch

/** What the picker is asking the user to choose. */
enum class PickerMode {
    /** A destination folder — move, copy, extract-into. */
    FOLDER,

    /** One or more existing files — archive sources, merge inputs. */
    FILES,

    /** A folder plus a filename to create in it — zip and PDF outputs. */
    SAVE,
}

/** One rung of the picker's own navigation: which granted tree, and where inside it. */
private data class PickerCrumb(val treeUri: Uri, val folderUri: Uri, val name: String)

/** What the user settled on. */
sealed interface PickerOutcome {
    data class Folder(val treeUri: Uri, val folderUri: Uri) : PickerOutcome
    data class Files(val uris: List<Uri>) : PickerOutcome
    data class Save(val treeUri: Uri, val folderUri: Uri, val name: String) : PickerOutcome
}

/**
 * Fylz's own file and folder picker.
 *
 * ### Why this exists
 *
 * Archive, extract, move-to, copy-to and every "write the output here" flow used to launch
 * `ACTION_OPEN_DOCUMENT_TREE` / `CreateDocument`, which hands the user to the *system* file
 * manager to pick a folder — inside a file manager. It is a jarring handoff (a different app,
 * different theme, different navigation), it loses everything Fylz knows about where you were,
 * and it makes the app look like it cannot do the one thing it is for.
 *
 * Everywhere Fylz already holds access, it can browse for itself: the granted trees are the
 * same ones the browser lists, and [DocumentRepository.listChildren] is the same call the
 * listing makes. So this picker browses them in-app, in Fylz's theme, starting from the folder
 * you were already looking at.
 *
 * ### Where SAF still has to appear
 *
 * Granting access to a *new* location is a platform decision that only the system UI can make,
 * so [onBrowseSystem] stays as an explicit escape hatch — a button the user presses on purpose,
 * not a redirection that happens to them. That is the honest split: browsing is ours, granting
 * is the platform's.
 *
 * @param startAt the folder to open on, when the caller has one — normally whatever the browser
 *   is showing, so the picker begins where the user's attention already is.
 * @param onBrowseSystem escape hatch into the platform picker, for locations Fylz has not been
 *   granted. Null hides the affordance entirely.
 * @param showHidden dotfile entries (`name.startsWith(".")`) are read but left out of the listing
 *   unless this is on, matching the main browser and the folder tree. Defaults off so a caller
 *   that has not wired the preference through still gets today's behaviour.
 */
@Composable
fun FylzPicker(
    mode: PickerMode,
    title: String,
    confirmLabel: String,
    repository: DocumentRepository,
    onDismiss: () -> Unit,
    onResult: (PickerOutcome) -> Unit,
    startAt: Pair<Uri, FolderLocation>? = null,
    suggestedName: String = "",
    onBrowseSystem: (() -> Unit)? = null,
    showHidden: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var crumbs by remember {
        mutableStateOf(
            startAt?.let { (tree, location) ->
                listOf(PickerCrumb(tree, location.uri, location.name))
            } ?: emptyList(),
        )
    }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var fileName by remember { mutableStateOf(suggestedName) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }

    val here = crumbs.lastOrNull()

    // The granted roots, only needed while the picker is at its top level.
    val groups by produceState(initialValue = emptyList<StorageRootGroup>(), here) {
        value = if (here != null) {
            emptyList()
        } else {
            runCatching { StorageAccess.available(context).flatMap { it.rootGroups(context) } }
                .getOrDefault(emptyList())
        }
    }

    val children by produceState(initialValue = null as List<FileEntry>?, here, refreshKey) {
        value = null
        value = here?.let { crumb ->
            runCatching { repository.listChildren(crumb.treeUri, crumb.folderUri) }
                .onFailure { failure = it.message ?: "Unable to read that folder" }
                .getOrDefault(emptyList())
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // ── Header ────────────────────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (crumbs.isEmpty()) onDismiss() else crumbs = crumbs.dropLast(1)
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            crumbs.joinToString(" / ") { it.name }.ifEmpty { "Choose a location" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (here != null && mode != PickerMode.FILES) {
                        IconButton(onClick = { newFolderOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                        }
                    }
                }
                HorizontalDivider()

                // ── Listing ───────────────────────────────────────────────────────────
                Box(Modifier.weight(1f)) {
                    when {
                        here == null -> RootList(
                            groups = groups,
                            onOpen = { root ->
                                val tree = root.treeUri
                                val document = root.documentUri
                                if (tree != null && document != null) {
                                    crumbs = listOf(PickerCrumb(tree, document, root.title))
                                }
                            },
                        )
                        children == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        else -> EntryList(
                            entries = children.orEmpty(),
                            mode = mode,
                            selected = selected,
                            showHidden = showHidden,
                            onOpenFolder = { entry ->
                                crumbs = crumbs + PickerCrumb(here.treeUri, entry.uri, entry.name)
                            },
                            onToggleFile = { entry ->
                                selected = if (entry.uri in selected) selected - entry.uri else selected + entry.uri
                            },
                        )
                    }
                }

                failure?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                HorizontalDivider()

                // ── Commit ────────────────────────────────────────────────────────────
                if (mode == PickerMode.SAVE) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBrowseSystem != null) {
                        TextButton(onClick = onBrowseSystem) { Text("Other app…") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            val crumb = here ?: return@Button
                            when (mode) {
                                PickerMode.FOLDER -> onResult(PickerOutcome.Folder(crumb.treeUri, crumb.folderUri))
                                PickerMode.FILES -> onResult(PickerOutcome.Files(selected.toList()))
                                PickerMode.SAVE -> onResult(
                                    PickerOutcome.Save(crumb.treeUri, crumb.folderUri, fileName.trim()),
                                )
                            }
                        },
                        enabled = when (mode) {
                            PickerMode.FOLDER -> here != null
                            PickerMode.FILES -> selected.isNotEmpty()
                            PickerMode.SAVE -> here != null && fileName.isNotBlank()
                        },
                    ) {
                        Text(
                            if (mode == PickerMode.FILES && selected.isNotEmpty()) {
                                "$confirmLabel (${selected.size})"
                            } else {
                                confirmLabel
                            },
                        )
                    }
                }
            }
        }
    }

    if (newFolderOpen) {
        NewFolderDialog(
            onDismiss = { newFolderOpen = false },
            onCreate = { name ->
                newFolderOpen = false
                val crumb = here ?: return@NewFolderDialog
                scope.launch {
                    runCatching { repository.createDirectory(crumb.folderUri, name) }
                        .onSuccess { refreshKey += 1 }
                        .onFailure { failure = it.message ?: "Unable to create that folder" }
                }
            },
        )
    }
}

@Composable
private fun RootList(groups: List<StorageRootGroup>, onOpen: (StorageRoot) -> Unit) {
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No locations are available yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        groups.forEach { group ->
            item(key = "header:${group.title}") {
                Text(
                    group.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            // Only rows Fylz can actually browse. A picker shortcut here would be a row that
            // silently bounces the user out to the system picker — the exact handoff this
            // screen exists to remove.
            items(group.roots.filter { it.opensDirectly }, key = { it.id }) { root ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onOpen(root) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when (root.kind) {
                            StorageRootKind.REMOVABLE -> Icons.Outlined.SdCard
                            StorageRootKind.INTERNAL -> Icons.Outlined.Smartphone
                            else -> Icons.Outlined.Folder
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(root.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        root.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryList(
    entries: List<FileEntry>,
    mode: PickerMode,
    selected: Set<Uri>,
    showHidden: Boolean,
    onOpenFolder: (FileEntry) -> Unit,
    onToggleFile: (FileEntry) -> Unit,
) {
    val showExtensions = LocalShowExtensions.current
    // Folders first, then files. In folder and save modes the files are still drawn — greyed and
    // inert — because a folder shown empty when it is not is a folder the user distrusts.
    val ordered = remember(entries, showHidden) {
        val visible = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
        visible.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
    if (ordered.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "This folder is empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        items(ordered, key = { it.uri.toString() }) { entry ->
            val pickable = entry.isDirectory || mode == PickerMode.FILES
            val checked = entry.uri in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .then(
                        if (!pickable) {
                            Modifier
                        } else {
                            Modifier.clickable {
                                if (entry.isDirectory) onOpenFolder(entry) else onToggleFile(entry)
                            }
                        },
                    )
                    .background(
                        if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    )
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mode == PickerMode.FILES && !entry.isDirectory) {
                    Checkbox(checked = checked, onCheckedChange = { onToggleFile(entry) })
                    Spacer(Modifier.size(4.dp))
                } else {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    )
                    Spacer(Modifier.size(16.dp))
                }
                Text(
                    displayName(entry.name, entry.isDirectory, showExtensions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pickable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
