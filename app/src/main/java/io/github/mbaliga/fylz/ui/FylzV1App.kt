package io.github.mbaliga.fylz.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.github.mbaliga.fylz.ai.AiClient
import io.github.mbaliga.fylz.ai.AiProviderConfig
import io.github.mbaliga.fylz.IndexManagerActivity
import io.github.mbaliga.fylz.PostV1ToolsActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ai.ApiKeyVault
import io.github.mbaliga.fylz.browse.SortDirection
import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.browse.sortEntries
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.library.SavedSearch
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.network.RemoteConnectionStore
import io.github.mbaliga.fylz.network.WebDavConfig
import io.github.mbaliga.fylz.network.WebDavService
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.FileTools
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.search.RecursiveSearchEngine
import io.github.mbaliga.fylz.search.SearchHit
import io.github.mbaliga.fylz.search.SearchMatchSource
import io.github.mbaliga.fylz.search.SearchProgress
import io.github.mbaliga.fylz.search.SearchQuery
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.FloatingPreviewPane
import io.github.mbaliga.fylz.ui.components.PreviewPane
import io.github.mbaliga.fylz.ui.hyle.HyleFolderTabSwitcher
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PendingDestinationAction { COPY, MOVE, EXTRACT }

/** How many previously granted SAF subtrees are restored as tabs on launch. */
private const val MAX_RESTORED_TABS = 8

@Composable
fun FylzV1App() {
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    FylzTheme(
        themeMode = themeMode,
        accentPreset = AccentPreset.MOSS,
        dynamicColor = true,
    ) {
        FylzV1Workspace(themeMode = themeMode, onThemeModeChange = { themeMode = it })
    }
}

@Composable
private fun FylzV1Workspace(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val repository = remember { DocumentRepository(context.applicationContext) }
    val fileOperations = remember { FileOperationService(context.applicationContext) }
    val recycleBin = remember { RecycleBinService(context.applicationContext) }
    val archiveService = remember { ArchiveService(context.applicationContext) }
    val fileTools = remember { FileTools(context.applicationContext) }
    val library = remember { LibraryStore(context.applicationContext) }
    val aiVault = remember { ApiKeyVault(context.applicationContext) }
    val aiClient = remember { AiClient(aiVault) }
    val webDav = remember { WebDavService() }
    val remoteStore = remember { RemoteConnectionStore(context.applicationContext) }
    val searchEngine = remember { RecursiveSearchEngine(context.applicationContext) }

    val tabs = remember { mutableStateListOf<FolderTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var focusedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var previewMode by remember { mutableStateOf(PreviewMode.DOCKED) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewTruncated by remember { mutableStateOf(false) }
    var editorValue by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    var pendingDestinationAction by remember { mutableStateOf<PendingDestinationAction?>(null) }
    var pendingArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf<String?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var batchRenameDialog by remember { mutableStateOf(false) }
    var recycleDialog by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var aiDialog by remember { mutableStateOf(false) }
    var webDavDialog by remember { mutableStateOf(false) }
    var remoteDialog by remember { mutableStateOf(false) }
    var duplicateResult by remember { mutableStateOf<String?>(null) }
    var sortSpec by remember { mutableStateOf(SortSpec.Default) }
    var searchRecursive by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf<SearchProgress?>(null) }
    var homeRefreshKey by remember { mutableIntStateOf(0) }

    val activeTab = tabs.firstOrNull { it.id == activeTabId }
    val selectedEntries = entries.filter { it.uri in selectedUris }

    // Sorting is applied after filtering so the two controls compose: the user's chosen order
    // holds for the current folder, a folder filter, and recursive search results alike.
    val visibleEntries = remember(entries, query, searchRecursive, sortSpec) {
        val filtered = if (query.isBlank() || searchRecursive) {
            entries
        } else {
            val parsed = SearchQuery.parse(query)
            entries.filter { parsed.matchesMetadata(it) && parsed.matchesName(it.name) }
        }
        sortEntries(filtered, sortSpec)
    }

    val searchHits = remember(searchProgress, sortSpec) {
        val hits = searchProgress?.hits.orEmpty()
        val ordered = sortEntries(hits.map(SearchHit::entry), sortSpec)
        val byUri = hits.associateBy { it.entry.uri }
        ordered.mapNotNull { byUri[it.uri] }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun refresh() {
        refreshKey += 1
    }

    fun openTabAt(treeUri: Uri, location: FolderLocation) {
        val existing = tabs.indexOfFirst { it.treeUri == treeUri }
        if (existing >= 0) {
            tabs[existing] = tabs[existing].copy(locations = listOf(location))
            activeTabId = tabs[existing].id
            return
        }
        val tab = FolderTab(treeUri = treeUri, locations = listOf(location))
        tabs += tab
        activeTabId = tab.id
    }

    /**
     * Opens a launch-surface row directly. In the full flavor this is the whole storage story:
     * no picker, no grant round-trip, the tab just opens.
     */
    fun openStorageRoot(root: StorageRoot) {
        val treeUri = root.treeUri ?: return
        val documentUri = root.documentUri ?: return
        openTabAt(treeUri, FolderLocation(documentUri, root.title))
    }

    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            repository.persistTreePermission(uri)
            scope.launch {
                runCatching { repository.rootLocation(uri) }
                    .onSuccess { root ->
                        openTabAt(uri, root)
                        homeRefreshKey += 1
                    }
                    .onFailure { toast(it.message ?: "Unable to open folder") }
            }
        }
    }

    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { destination ->
        val action = pendingDestinationAction
        pendingDestinationAction = null
        if (destination == null || action == null) return@rememberLauncherForActivityResult
        repository.persistTreePermission(destination)
        scope.launch {
            loading = true
            runCatching {
                when (action) {
                    PendingDestinationAction.COPY -> fileOperations.copy(
                        selectedEntries.map { it.uri },
                        destination,
                        ConflictPolicy.KEEP_BOTH,
                    ) { progress -> operationMessage = "Copying ${progress.displayName}" }
                    PendingDestinationAction.MOVE -> fileOperations.move(
                        selectedEntries.map { it.uri },
                        destination,
                        ConflictPolicy.KEEP_BOTH,
                    ) { progress -> operationMessage = "Moving ${progress.displayName}" }
                    PendingDestinationAction.EXTRACT -> archiveService.extractZip(
                        archiveUri = pendingArchiveUri ?: error("Choose an archive."),
                        destinationTreeUri = destination,
                    )
                }
            }.onSuccess {
                toast(
                    when (action) {
                        PendingDestinationAction.COPY -> "Copied"
                        PendingDestinationAction.MOVE -> "Moved"
                        PendingDestinationAction.EXTRACT -> "Extracted"
                    },
                )
                selectedUris = emptySet()
                pendingArchiveUri = null
                refresh()
            }.onFailure { toast(it.message ?: "Operation failed") }
            operationMessage = null
            loading = false
        }
    }

    val archiveCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        if (destination != null && selectedEntries.isNotEmpty()) {
            scope.launch {
                loading = true
                runCatching { archiveService.createZip(selectedEntries.map { it.uri }, destination) }
                    .onSuccess { toast("Archive created") }
                    .onFailure { toast(it.message ?: "Unable to create archive") }
                loading = false
            }
        }
    }

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdfUri = scan?.pdf?.uri ?: return@rememberLauncherForActivityResult
        val folder = activeTab?.current ?: return@rememberLauncherForActivityResult
        scope.launch {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            runCatching {
                val destination = repository.createFile(folder.uri, "Scan-$stamp.pdf", "application/pdf")
                repository.copyStream(pdfUri, destination)
            }.onSuccess {
                toast("Scan saved")
                refresh()
            }.onFailure { toast(it.message ?: "Unable to save scan") }
        }
    }

    fun startScan() {
        val host = activity ?: run {
            toast("Scanner requires an Android activity")
            return
        }
        scanner.getStartScanIntent(host)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { toast(it.message ?: "Scanner is unavailable") }
    }

    // Restore previously granted subtrees as tabs. This used to be the ONLY way content ever
    // appeared, which is why a fresh install showed nothing at all; the storage home surface below
    // is now the real entry point and this is just tab restoration on top of it.
    LaunchedEffect(Unit) {
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .take(MAX_RESTORED_TABS)
            .forEach { permission ->
                runCatching { repository.rootLocation(permission.uri) }.getOrNull()?.let { root ->
                    val tab = FolderTab(treeUri = permission.uri, locations = listOf(root))
                    tabs += tab
                }
            }
    }

    // Recursive search. Cancelled and restarted whenever the query, scope or folder changes --
    // LaunchedEffect's own cancellation is what makes an in-flight walk stop, and the engine
    // checks for it at every folder and every entry.
    LaunchedEffect(query, searchRecursive, activeTab?.current?.uri, refreshKey) {
        val tab = activeTab
        if (!searchRecursive || query.isBlank() || tab == null) {
            searchProgress = null
            return@LaunchedEffect
        }
        val parsed = SearchQuery.parse(query)
        if (parsed.isEmpty) {
            searchProgress = null
            return@LaunchedEffect
        }
        searchProgress = SearchProgress(emptyList(), 0, 0, complete = false)
        searchEngine
            .search(tab.treeUri, tab.current.uri, tab.current.name, parsed)
            .collectLatest { searchProgress = it }
    }

    LaunchedEffect(activeTab?.current?.uri, refreshKey) {
        selectedUris = emptySet()
        focusedEntry = null
        previewText = null
        if (activeTab == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        runCatching { repository.listChildren(activeTab.treeUri, activeTab.current.uri) }
            .onSuccess { entries = it.filterNot { item -> item.name == ".fylz-trash" } }
            .onFailure { toast(it.message ?: "Unable to read folder") }
        loading = false
    }

    LaunchedEffect(focusedEntry?.uri) {
        previewText = null
        previewTruncated = false
        editorValue = ""
        val entry = focusedEntry ?: return@LaunchedEffect
        if (!FileType.isTextPreviewable(entry.kind)) return@LaunchedEffect
        previewLoading = true
        runCatching { repository.readText(entry.uri) }
            .onSuccess {
                previewText = it.value
                previewTruncated = it.truncated
                editorValue = it.value
            }
            .onFailure { previewText = it.message ?: "Unable to preview" }
        previewLoading = false
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            val tab = activeTab ?: return
            val index = tabs.indexOfFirst { it.id == tab.id }
            if (index >= 0) {
                tabs[index] = tab.copy(locations = tab.locations + FolderLocation(entry.uri, entry.name))
            }
        } else {
            focusedEntry = entry
            selectedUris = setOf(entry.uri)
        }
    }

    fun openExternal(entry: FileEntry) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(entry.uri, entry.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }.onFailure { toast("No app can open this file") }
    }

    fun recycleSelection() {
        val tab = activeTab ?: return
        if (selectedEntries.isEmpty()) return
        scope.launch {
            loading = true
            runCatching {
                val root = DocumentFile.fromTreeUri(context, tab.treeUri)
                    ?: error("Unable to open the selected root.")
                val recycleRoot = root.findFile(".fylz-trash")
                    ?.takeIf(DocumentFile::isDirectory)
                    ?: root.createDirectory(".fylz-trash")
                    ?: error("This provider cannot create a recycle location.")
                selectedEntries.forEach { entry ->
                    recycleBin.recycle(entry.uri, tab.current.uri, recycleRoot.uri)
                }
            }.onSuccess {
                toast("Moved to Recycle Bin")
                selectedUris = emptySet()
                refresh()
            }.onFailure { toast(it.message ?: "Unable to recycle selection") }
            loading = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Fylz") },
                    navigationIcon = {
                        // Returns to the storage home surface instead of firing the picker: the
                        // home surface is now the app's real entry point, and the picker is one
                        // action on it rather than the only way in.
                        IconButton(
                            onClick = { activeTabId = null; homeRefreshKey += 1 },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Home,
                                contentDescription = stringResource(R.string.browser_open_home),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewMode = if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID }) {
                            Icon(
                                if (viewMode == ViewMode.GRID) Icons.Outlined.List else Icons.Outlined.GridView,
                                contentDescription = "Change view",
                            )
                        }
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                        Box {
                            IconButton(onClick = { moreExpanded = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                            }
                            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                    enabled = activeTab != null,
                                    onClick = { moreExpanded = false; createDialog = "folder" },
                                )
                                DropdownMenuItem(
                                    text = { Text("New text file") },
                                    leadingIcon = { Icon(Icons.Outlined.TextSnippet, null) },
                                    enabled = activeTab != null,
                                    onClick = { moreExpanded = false; createDialog = "file" },
                                )
                                DropdownMenuItem(
                                    text = { Text("Scan to PDF") },
                                    leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                                    enabled = activeTab != null,
                                    onClick = { moreExpanded = false; startScan() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Recycle Bin") },
                                    leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                                    onClick = { moreExpanded = false; recycleDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Find duplicates") },
                                    enabled = entries.count { !it.isDirectory } > 1,
                                    onClick = {
                                        moreExpanded = false
                                        scope.launch {
                                            loading = true
                                            runCatching { fileTools.findDuplicates(entries.filterNot { it.isDirectory }.map { it.uri }) }
                                                .onSuccess { groups ->
                                                    duplicateResult = if (groups.isEmpty()) {
                                                        "No duplicate files found in this folder."
                                                    } else {
                                                        groups.joinToString("\n\n") { group ->
                                                            "${group.items.size} files · ${formatBytes(group.sizeBytes)}\n${group.items.joinToString("\n")}" 
                                                        }
                                                    }
                                                }
                                                .onFailure { toast(it.message ?: "Duplicate scan failed") }
                                            loading = false
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("AI organize proposal") },
                                    enabled = focusedEntry != null,
                                    onClick = { moreExpanded = false; aiDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remotes_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.Cloud, null) },
                                    onClick = { moreExpanded = false; remoteDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Quick WebDAV listing") },
                                    onClick = { moreExpanded = false; webDavDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tools_title)) },
                                    onClick = {
                                        moreExpanded = false
                                        runCatching {
                                            context.startActivity(
                                                Intent(context, PostV1ToolsActivity::class.java),
                                            )
                                        }.onFailure { toast("Tools are unavailable on this build") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tools_index)) },
                                    onClick = {
                                        moreExpanded = false
                                        runCatching {
                                            context.startActivity(
                                                Intent(context, IndexManagerActivity::class.java),
                                            )
                                        }.onFailure { toast("The index manager is unavailable") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (themeMode == ThemeMode.DARK) "Use light theme" else "Use dark theme") },
                                    onClick = {
                                        moreExpanded = false
                                        onThemeModeChange(if (themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK)
                                    },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (selectedEntries.isNotEmpty()) {
                    SelectionActionBar(
                        count = selectedEntries.size,
                        canRename = selectedEntries.size == 1,
                        canExtract = selectedEntries.size == 1 && selectedEntries.first().kind == EntryKind.ARCHIVE,
                        onCopy = { pendingDestinationAction = PendingDestinationAction.COPY; destinationPicker.launch(null) },
                        onMove = { pendingDestinationAction = PendingDestinationAction.MOVE; destinationPicker.launch(null) },
                        onRecycle = ::recycleSelection,
                        onRename = { renameDialog = true },
                        onTags = { tagDialog = true },
                        onArchive = { archiveCreator.launch("Fylz-${System.currentTimeMillis()}.zip") },
                        onExtract = {
                            pendingArchiveUri = selectedEntries.first().uri
                            pendingDestinationAction = PendingDestinationAction.EXTRACT
                            destinationPicker.launch(null)
                        },
                        onBatchRename = { batchRenameDialog = true },
                        onShare = {
                            val uris = ArrayList(selectedEntries.map { it.uri })
                            val intent = if (uris.size == 1) {
                                Intent(Intent.ACTION_SEND)
                                    .setType(selectedEntries.first().mimeType)
                                    .putExtra(Intent.EXTRA_STREAM, uris.first())
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE)
                                    .setType("*/*")
                                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(Intent.createChooser(intent, "Share files")) }
                        },
                        onClear = { selectedUris = emptySet() },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                HyleFolderTabSwitcher(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    onSelect = { activeTabId = it.id },
                    onClose = { tab ->
                        val wasActive = activeTabId == tab.id
                        tabs.remove(tab)
                        if (wasActive) activeTabId = tabs.lastOrNull()?.id
                    },
                    onAdd = { rootPicker.launch(null) },
                )
                Row(Modifier.weight(1f)) {
                    if (wide) {
                        LibraryRail(
                            activeTab = activeTab,
                            favorites = library.favorites(),
                            onToggleFavorite = {
                                activeTab?.let { tab ->
                                    library.toggleFavorite(tab.current.uri, tab.current.name)
                                    refresh()
                                }
                            },
                            onOpenRoot = { activeTabId = null; homeRefreshKey += 1 },
                            onRecycle = { recycleDialog = true },
                            modifier = Modifier.width(210.dp).fillMaxHeight(),
                        )
                        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                    }
                    FileBrowser(
                        activeTab = activeTab,
                        entries = visibleEntries,
                        searchHits = searchHits,
                        searchProgress = searchProgress,
                        searchRecursive = searchRecursive,
                        onSearchRecursiveChange = { searchRecursive = it },
                        sortSpec = sortSpec,
                        onSortSpecChange = { sortSpec = it },
                        selectedUris = selectedUris,
                        focusedEntry = focusedEntry,
                        query = query,
                        viewMode = viewMode,
                        loading = loading,
                        operationMessage = operationMessage,
                        onOpenStorageRoot = ::openStorageRoot,
                        onPickFolder = { root -> rootPicker.launch(root?.initialUri) },
                        onOpenRemotes = { remoteDialog = true },
                        homeRefreshKey = homeRefreshKey,
                        onQueryChange = { query = it },
                        onNavigateUp = {
                            val tab = activeTab ?: return@FileBrowser
                            val index = tabs.indexOfFirst { it.id == tab.id }
                            if (index >= 0 && tab.locations.size > 1) {
                                tabs[index] = tab.copy(locations = tab.locations.dropLast(1))
                            }
                        },
                        onOpen = ::openEntry,
                        onOpenExternal = ::openExternal,
                        onToggleSelection = { entry ->
                            selectedUris = if (entry.uri in selectedUris) selectedUris - entry.uri else selectedUris + entry.uri
                            focusedEntry = entry.takeUnless(FileEntry::isDirectory)
                        },
                        onSelectAll = { selectedUris = visibleEntries.map { it.uri }.toSet() },
                        modifier = Modifier.weight(1f),
                    )
                    if (wide && previewMode == PreviewMode.DOCKED) {
                        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                        PreviewPane(
                            entry = focusedEntry,
                            textContent = previewText,
                            textTruncated = previewTruncated,
                            loading = previewLoading,
                            editorValue = editorValue,
                            onEditorValueChange = { editorValue = it },
                            onSave = {
                                focusedEntry?.let { entry ->
                                    scope.launch {
                                        runCatching { repository.writeText(entry.uri, editorValue) }
                                            .onSuccess { previewText = editorValue; toast("Saved") }
                                            .onFailure { toast(it.message ?: "Unable to save") }
                                    }
                                }
                            },
                            modifier = Modifier.width(380.dp).fillMaxHeight(),
                        )
                    }
                }
            }
        }

        if (!wide && focusedEntry != null && previewMode != PreviewMode.HIDDEN) {
            FloatingPreviewPane(
                onDock = { previewMode = PreviewMode.HIDDEN },
                onClose = { previewMode = PreviewMode.HIDDEN },
            ) {
                PreviewPane(
                    entry = focusedEntry,
                    textContent = previewText,
                    textTruncated = previewTruncated,
                    loading = previewLoading,
                    editorValue = editorValue,
                    onEditorValueChange = { editorValue = it },
                    onSave = {
                        focusedEntry?.let { entry ->
                            scope.launch { repository.writeText(entry.uri, editorValue) }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    createDialog?.let { kind ->
        NameDialog(
            title = if (kind == "folder") "New folder" else "New text file",
            initial = if (kind == "folder") "New folder" else "Untitled.txt",
            onDismiss = { createDialog = null },
            onConfirm = { name ->
                createDialog = null
                activeTab?.current?.uri?.let { parent ->
                    scope.launch {
                        runCatching {
                            if (kind == "folder") repository.createDirectory(parent, name)
                            else repository.createFile(parent, name, "text/plain")
                        }.onSuccess { refresh() }.onFailure { toast(it.message ?: "Unable to create item") }
                    }
                }
            },
        )
    }

    if (renameDialog) {
        NameDialog(
            title = "Rename",
            initial = selectedEntries.singleOrNull()?.name.orEmpty(),
            onDismiss = { renameDialog = false },
            onConfirm = { name ->
                renameDialog = false
                selectedEntries.singleOrNull()?.let { entry ->
                    scope.launch {
                        runCatching { repository.rename(entry.uri, name) }
                            .onSuccess { selectedUris = emptySet(); refresh() }
                            .onFailure { toast(it.message ?: "Unable to rename") }
                    }
                }
            },
        )
    }

    if (tagDialog) {
        TagDialog(
            initial = selectedEntries.firstOrNull()?.let { library.tags(it.uri).joinToString(", ") }.orEmpty(),
            onDismiss = { tagDialog = false },
            onConfirm = { tags ->
                selectedEntries.forEach { library.setTags(it.uri, tags.split(',')) }
                tagDialog = false
                toast("Tags saved")
            },
        )
    }

    if (batchRenameDialog) {
        BatchRenameDialog(
            count = selectedEntries.size,
            onDismiss = { batchRenameDialog = false },
            onConfirm = { prefix ->
                batchRenameDialog = false
                scope.launch {
                    val plans = fileTools.planBatchRename(selectedEntries.map { it.uri to it.name }, prefix)
                    runCatching { fileTools.executeBatchRename(plans) }
                        .onSuccess { selectedUris = emptySet(); refresh() }
                        .onFailure { toast(it.message ?: "Batch rename failed") }
                }
            },
        )
    }

    if (recycleDialog) {
        RecycleBinDialog(
            records = recycleBin.records(),
            onDismiss = { recycleDialog = false },
            onRestore = { id ->
                scope.launch {
                    runCatching { recycleBin.restore(id, conflictPolicy = ConflictPolicy.KEEP_BOTH) }
                        .onSuccess { refresh() }
                        .onFailure { toast(it.message ?: "Restore failed") }
                }
            },
            onDelete = { id ->
                scope.launch {
                    runCatching { recycleBin.permanentlyDelete(id, confirmed = true) }
                        .onSuccess { recycleDialog = false; recycleDialog = true }
                        .onFailure { toast(it.message ?: "Permanent deletion failed") }
                }
            },
        )
    }

    duplicateResult?.let { result ->
        AlertDialog(
            onDismissRequest = { duplicateResult = null },
            title = { Text("Duplicate files") },
            text = { Text(result) },
            confirmButton = { TextButton(onClick = { duplicateResult = null }) { Text("Done") } },
        )
    }

    if (aiDialog) {
        AiDialog(
            entry = focusedEntry,
            onDismiss = { aiDialog = false },
            onRun = { endpoint, model, key, approved ->
                val entry = focusedEntry ?: return@AiDialog
                aiDialog = false
                scope.launch {
                    runCatching {
                        aiVault.save("custom", key.toCharArray())
                        val text = if (FileType.isTextPreviewable(entry.kind)) previewText else null
                        aiClient.proposeOrganization(
                            config = AiProviderConfig("custom", "Custom provider", endpoint, model),
                            fileName = entry.name,
                            mimeType = entry.mimeType,
                            boundedText = text,
                            userApprovedTransmission = approved,
                        )
                    }.onSuccess { proposal ->
                        duplicateResult = buildString {
                            append(proposal.summary)
                            proposal.suggestedFolder?.let { append("\n\nFolder: ").append(it) }
                            proposal.suggestedName?.let { append("\nName: ").append(it) }
                            if (proposal.suggestedTags.isNotEmpty()) append("\nTags: ").append(proposal.suggestedTags.joinToString())
                            append("\n\nNo changes were applied.")
                        }
                    }.onFailure { toast(it.message ?: "AI proposal failed") }
                }
            },
        )
    }

    if (remoteDialog) {
        RemoteConnectionsDialog(
            store = remoteStore,
            onDismiss = { remoteDialog = false },
            onError = ::toast,
        )
    }

    if (webDavDialog) {
        WebDavDialog(
            onDismiss = { webDavDialog = false },
            onConnect = { baseUrl, username, password, path ->
                webDavDialog = false
                scope.launch {
                    runCatching {
                        webDav.list(
                            WebDavConfig("webdav", "WebDAV", baseUrl, username),
                            path,
                            password.toCharArray(),
                        )
                    }.onSuccess { remote ->
                        duplicateResult = if (remote.isEmpty()) "Remote folder is empty." else remote.joinToString("\n") {
                            (if (it.directory) "📁 " else "") + it.name
                        }
                    }.onFailure { toast(it.message ?: "WebDAV connection failed") }
                }
            },
        )
    }
}

@Composable
private fun LibraryRail(
    activeTab: FolderTab?,
    favorites: List<io.github.mbaliga.fylz.library.FavoriteLocation>,
    onToggleFavorite: () -> Unit,
    onOpenRoot: () -> Unit,
    onRecycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workspace", style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(onClick = onOpenRoot, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, null)
                Text("Open root", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onToggleFavorite, enabled = activeTab != null, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (activeTab?.current?.uri in favorites.map { it.uri }) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    null,
                )
                Text("Favourite", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onRecycle, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.RestoreFromTrash, null)
                Text("Recycle Bin", Modifier.padding(start = 8.dp))
            }
            HorizontalDivider()
            Text("FAVOURITES", style = MaterialTheme.typography.labelSmall)
            favorites.forEach { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.weight(1f))
            Text("SAF providers supply local, cloud, USB, SMB and SFTP roots installed on the device.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FileBrowser(
    activeTab: FolderTab?,
    entries: List<FileEntry>,
    searchHits: List<SearchHit>,
    searchProgress: SearchProgress?,
    searchRecursive: Boolean,
    onSearchRecursiveChange: (Boolean) -> Unit,
    sortSpec: SortSpec,
    onSortSpecChange: (SortSpec) -> Unit,
    selectedUris: Set<Uri>,
    focusedEntry: FileEntry?,
    query: String,
    viewMode: ViewMode,
    loading: Boolean,
    operationMessage: String?,
    onOpenStorageRoot: (StorageRoot) -> Unit,
    onPickFolder: (StorageRoot?) -> Unit,
    onOpenRemotes: () -> Unit,
    homeRefreshKey: Int,
    onQueryChange: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // With no tab open the browser shows the storage home surface, not an empty label. This is
    // the single change that answers "the app doesn't show any folders on launch".
    if (activeTab == null) {
        StorageHomeScreen(
            onOpenRoot = onOpenStorageRoot,
            onPickFolder = onPickFolder,
            onOpenRemotes = onOpenRemotes,
            refreshKey = homeRefreshKey,
            modifier = modifier,
        )
        return
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onNavigateUp,
                enabled = activeTab.locations.size > 1,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.ArrowBack, stringResource(R.string.browser_parent_folder))
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(stringResource(R.string.browser_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            SortMenu(sortSpec, onSortSpecChange)
            IconButton(
                onClick = onSelectAll,
                enabled = entries.isNotEmpty(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.SelectAll, stringResource(R.string.browser_select_all))
            }
        }

        if (query.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The old placeholder honestly read "Filter this folder" because that is all it
                // did. Recursive search is now a real, selectable scope.
                FilterChip(
                    selected = !searchRecursive,
                    onClick = { onSearchRecursiveChange(false) },
                    label = { Text("This folder") },
                )
                FilterChip(
                    selected = searchRecursive,
                    onClick = { onSearchRecursiveChange(true) },
                    label = { Text("Everything below") },
                )
                if (searchRecursive && searchProgress?.complete == false) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                }
            }
            Text(
                stringResource(R.string.browser_search_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Text(
            activeTab.locations.joinToString(" / ") { it.name },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider()

        if (searchRecursive && query.isNotBlank()) {
            SearchResults(
                progress = searchProgress,
                hits = searchHits,
                selectedUris = selectedUris,
                focusedEntry = focusedEntry,
                onOpen = onOpen,
                onOpenExternal = onOpenExternal,
                onToggleSelection = onToggleSelection,
            )
            return@Column
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(operationMessage ?: "Working…")
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) {
                        stringResource(R.string.browser_empty_folder)
                    } else {
                        stringResource(R.string.browser_search_none)
                    },
                )
            }
        } else if (viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(130.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.uri.toString() }) { entry ->
                    FileCard(entry, entry.uri in selectedUris, entry.uri == focusedEntry?.uri, onOpen, onOpenExternal, onToggleSelection)
                }
            }
        } else {
            LazyColumn {
                items(entries, key = { it.uri.toString() }) { entry ->
                    FileRowV1(entry, entry.uri in selectedUris, entry.uri == focusedEntry?.uri, onOpen, onOpenExternal, onToggleSelection)
                }
            }
        }
    }
}

/** Sort controls. Previously the order was hardcoded in DocumentRepository with no UI at all. */
@Composable
private fun SortMenu(spec: SortSpec, onChange: (SortSpec) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Sort, stringResource(R.string.browser_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field ->
                val active = spec.field == field
                DropdownMenuItem(
                    text = {
                        // Direction is spelled out, not only implied by an arrow: DESIGN.md
                        // forbids colour or a lone glyph carrying state.
                        Text(
                            if (active) "${field.label} · ${spec.direction.label}" else field.label,
                        )
                    },
                    trailingIcon = {
                        if (active) {
                            Icon(
                                if (spec.direction == SortDirection.ASCENDING) {
                                    Icons.Outlined.ArrowUpward
                                } else {
                                    Icons.Outlined.ArrowDownward
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    onClick = { onChange(spec.withField(field)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_sort_folders_first)) },
                trailingIcon = {
                    Checkbox(
                        checked = spec.foldersFirst,
                        onCheckedChange = { onChange(spec.copy(foldersFirst = it)) },
                    )
                },
                onClick = { onChange(spec.copy(foldersFirst = !spec.foldersFirst)) },
            )
        }
    }
}

@Composable
private fun SearchResults(
    progress: SearchProgress?,
    hits: List<SearchHit>,
    selectedUris: Set<Uri>,
    focusedEntry: FileEntry?,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            when {
                progress == null -> stringResource(R.string.browser_search_none)
                !progress.complete -> stringResource(R.string.browser_searching, progress.foldersScanned)
                else -> stringResource(R.string.browser_search_results, hits.size)
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (progress?.limitReached == true) {
            Text(
                stringResource(R.string.browser_search_limit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider()
        if (hits.isEmpty() && progress?.complete == true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.browser_search_none))
            }
            return@Column
        }
        LazyColumn {
            items(hits, key = { it.entry.uri.toString() }) { hit ->
                FileRowV1(
                    entry = hit.entry,
                    selected = hit.entry.uri in selectedUris,
                    focused = hit.entry.uri == focusedEntry?.uri,
                    onOpen = onOpen,
                    onOpenExternal = onOpenExternal,
                    onToggleSelection = onToggleSelection,
                    // Where the file lives, plus the matched line for a content hit -- a result
                    // list without a path is unusable once the search leaves one folder.
                    overline = hit.relativePath,
                    detail = hit.snippet?.let { "\u201c$it\u201d" }
                        ?: if (hit.source == SearchMatchSource.CONTENT) "Matched file contents" else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRowV1(
    entry: FileEntry,
    selected: Boolean,
    focused: Boolean,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    overline: String? = null,
    detail: String? = null,
) {
    val label = if (entry.isDirectory) "Folder ${entry.name}" else entry.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .combinedClickable(
                onClick = { onOpen(entry) },
                onDoubleClick = { if (entry.isDirectory) onOpen(entry) else onOpenExternal(entry) },
                onLongClick = { onToggleSelection(entry) },
            )
            .background(if (selected || focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggleSelection(entry) })
        // Real image/video thumbnails; falls back to a per-type icon. Previously every file in
        // the list rendered the same handful of static vectors.
        EntryThumbnail(entry, size = 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (overline != null) {
                Text(
                    overline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail ?: listOfNotNull(
                    entry.sizeBytes?.let(::formatBytes),
                    libraryKind(entry.kind),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    entry: FileEntry,
    selected: Boolean,
    focused: Boolean,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
) {
    Surface(
        color = if (selected || focused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.height(140.dp).combinedClickable(
            onClick = { onOpen(entry) },
            onDoubleClick = { if (entry.isDirectory) onOpen(entry) else onOpenExternal(entry) },
            onLongClick = { onToggleSelection(entry) },
        ).semantics { contentDescription = entry.name },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                // Grid cells get a larger thumbnail: it is the whole point of grid view.
                EntryThumbnail(entry, size = 56.dp)
                Spacer(Modifier.weight(1f))
                Checkbox(selected, onCheckedChange = { onToggleSelection(entry) })
            }
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    canRename: Boolean,
    canExtract: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRecycle: () -> Unit,
    onRename: () -> Unit,
    onTags: () -> Unit,
    onArchive: () -> Unit,
    onExtract: () -> Unit,
    onBatchRename: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("$count selected", modifier = Modifier.padding(horizontal = 10.dp))
            ActionButton(Icons.Outlined.ContentCopy, "Copy", onCopy)
            ActionButton(Icons.Outlined.DriveFileMove, "Move", onMove)
            ActionButton(Icons.Outlined.Delete, "Recycle", onRecycle)
            ActionButton(Icons.Outlined.Edit, "Rename", onRename, canRename)
            ActionButton(Icons.Outlined.Tag, "Tags", onTags)
            ActionButton(Icons.Outlined.Archive, "Archive", onArchive)
            ActionButton(Icons.Outlined.FolderOpen, "Extract", onExtract, canExtract)
            ActionButton(Icons.Outlined.TextSnippet, "Batch rename", onBatchRename)
            ActionButton(Icons.Outlined.Share, "Share", onShare)
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TagDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Comma-separated tags") }) },
        confirmButton = { Button(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BatchRenameDialog(count: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var prefix by remember { mutableStateOf("File-") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch rename $count items") },
        text = { OutlinedTextField(prefix, { prefix = it }, label = { Text("Prefix") }) },
        confirmButton = { Button(onClick = { onConfirm(prefix) }, enabled = prefix.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecycleBinDialog(
    records: List<io.github.mbaliga.fylz.operations.RecycleRecord>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recycle Bin") },
        text = {
            if (records.isEmpty()) Text("Recycle Bin is empty. Items are never removed automatically.")
            else LazyColumn(Modifier.height(360.dp)) {
                items(records, key = { it.itemId }) { record ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(record.originalDisplayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            TextButton(onClick = { onRestore(record.itemId) }) { Text("Restore") }
                            TextButton(onClick = { onDelete(record.itemId) }) { Text("Delete permanently") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun AiDialog(entry: FileEntry?, onDismiss: () -> Unit, onRun: (String, String, String, Boolean) -> Unit) {
    var endpoint by remember { mutableStateOf("https://api.openai.com/v1") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var approved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI organization proposal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Only ${entry?.name.orEmpty()} and bounded preview text will be sent. Fylz will not apply changes automatically.")
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("OpenAI-compatible endpoint") })
                OutlinedTextField(model, { model = it }, label = { Text("Model") })
                OutlinedTextField(key, { key = it }, label = { Text("API key") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(approved, { approved = it })
                    Text("I approve this transmission")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRun(endpoint, model, key, approved) }, enabled = approved && model.isNotBlank() && key.isNotBlank()) {
                Text("Get proposal")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WebDavDialog(onDismiss: () -> Unit, onConnect: (String, String, String, String) -> Unit) {
    var url by remember { mutableStateOf("https://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS server URL") })
                OutlinedTextField(username, { username = it }, label = { Text("Username") })
                OutlinedTextField(password, { password = it }, label = { Text("Password") })
                OutlinedTextField(path, { path = it }, label = { Text("Path") })
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(url, username, password, path) }, enabled = url.startsWith("https://") && username.isNotBlank()) {
                Text("List folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun fileIcon(kind: EntryKind) = when (kind) {
    EntryKind.ARCHIVE -> Icons.Outlined.Archive
    EntryKind.PDF -> Icons.Outlined.PictureAsPdf
    EntryKind.TEXT, EntryKind.MARKDOWN -> Icons.Outlined.TextSnippet
    else -> Icons.Outlined.ViewSidebar
}

private fun libraryKind(kind: EntryKind): String = kind.name.lowercase().replaceFirstChar(Char::uppercase)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}
