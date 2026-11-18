package io.github.mbaliga.fylz.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import io.github.mbaliga.fylz.operations.ClipItem
import io.github.mbaliga.fylz.operations.ClipboardMode
import io.github.mbaliga.fylz.operations.ClipboardPolicy
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileClipboard
import io.github.mbaliga.fylz.operations.PdfOutputNames
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.ui.draw.alpha
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.FileTools
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.pdf.PdfPageRef
import io.github.mbaliga.fylz.pdf.PdfToolService
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
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.outlined.Close
import io.github.mbaliga.fylz.browse.entryStops
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.outlined.Menu
import dev.aarso.cellshell.OpenPark
import dev.aarso.cellshell.RoomEdge
import dev.aarso.cellshell.openParkDistance
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import io.github.mbaliga.fylz.library.RecentFolder
import io.github.mbaliga.fylz.library.RecentFoldersStore
import io.github.mbaliga.fylz.ui.places.LauncherCategory
import io.github.mbaliga.fylz.ui.places.LauncherModel
import io.github.mbaliga.fylz.ui.tree.FolderFacts
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.ui.places.CommonPlace
import io.github.mbaliga.fylz.ui.places.CommonPlaces
import io.github.mbaliga.fylz.ui.places.PlaceKind
import io.github.mbaliga.fylz.ui.tree.FileTreeRoom
import dev.aarso.cellshell.EdgeTimelineScrubber
import dev.aarso.cellshell.ShakeToRefresh
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.WordWheelRail
import dev.aarso.cellshell.rememberSpatialController
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight

enum class PendingDestinationAction { COPY, MOVE, EXTRACT }

/**
 * How the places list parks the content card: the reference's numbers (47% of the window kept,
 * scaled to 80%, 28dp corners), which are the shell's own defaults for exactly this layout.
 */
private val LEFT_PARK = OpenPark()

/** The gap between stacked cards, as a fraction of the window's height. */
private const val PEEK_GAP_FRACTION = 0.02f

/** How long the pane takes to slide when the wheel moves onto another row. */
private const val PANE_SLIDE_MS = 320

/** How faint a cut item is drawn while it waits to be pasted. */
private const val CUT_ITEM_ALPHA = 0.45f

/** How many previously granted SAF subtrees are restored as tabs on launch. */
private const val MAX_RESTORED_TABS = 8

/**
 * The app, and the owner of its theme.
 *
 * [recoveryRoom] and [overlays] are composed *inside* [FylzTheme] on purpose. Recovery used to
 * be a sibling screen under a bare `MaterialTheme`, which is why it — like the Tools and Index
 * activities — arrived light inside an otherwise dark app. Content that belongs to Fylz is
 * rendered by Fylz's theme; there is no second place for that decision to be made.
 *
 * @param recoveryRoom the storage-and-recovery surface, shown in the pane when its row of the places list is focused.
 * @param overlays dialogs the caller owns and needs drawn over everything.
 */
@Composable
fun FylzV1App(
    recoveryRoom: @Composable () -> Unit = {},
    overlays: @Composable () -> Unit = {},
) {
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    FylzTheme(
        themeMode = themeMode,
        accentPreset = AccentPreset.MOSS,
        dynamicColor = true,
    ) {
        FylzV1Workspace(
            themeMode = themeMode,
            onThemeModeChange = { themeMode = it },
            recoveryRoom = recoveryRoom,
        )
        overlays()
    }
}

@Composable
private fun FylzV1Workspace(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    recoveryRoom: @Composable () -> Unit,
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
    val pdfTools = remember { PdfToolService(context.applicationContext) }
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
    var moreExpanded by remember { mutableStateOf(false) }
    var aiDialog by remember { mutableStateOf(false) }
    var webDavDialog by remember { mutableStateOf(false) }
    var remoteDialog by remember { mutableStateOf(false) }
    var pdfDialog by remember { mutableStateOf(false) }
    var pendingPdfPages by remember { mutableStateOf<List<PdfPageRef>>(emptyList()) }
    var pendingPdfOcr by remember { mutableStateOf(false) }
    var pendingPdfMerge by remember { mutableStateOf(false) }
    var duplicateResult by remember { mutableStateOf<String?>(null) }
    var sortSpec by remember { mutableStateOf(SortSpec.Default) }
    var searchRecursive by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf<SearchProgress?>(null) }
    var homeRefreshKey by remember { mutableIntStateOf(0) }
    // The clipboard lives here, above the tabs, so it survives switching folders and tabs —
    // that is the whole point of cut/copy/paste. It is in-memory only: a cut that outlived the
    // process would be a pending move nobody can see.
    var clipboard by remember { mutableStateOf<FileClipboard?>(null) }
    // Which of the app's own surfaces fills the pane instead of the file view: the recycle bin,
    // storage & recovery, or settings. They are rows in the places list like any folder, so
    // moving the wheel onto one shows it here. Null means the file view (or the launcher).
    var pane by remember { mutableStateOf<PlaceKind?>(null) }
    var recycleVersion by remember { mutableIntStateOf(0) }
    // True while Shred all runs, so a second tap cannot start a second pass over the same records.
    var shredding by remember { mutableStateOf(false) }
    val recentStore = remember { RecentFoldersStore(context.applicationContext) }
    var recentFolders by remember { mutableStateOf(recentStore.list()) }

    // Four rooms: common places LEFT, tools and settings RIGHT, recovery BOTTOM, and the file
    // tree TOP — pulled down over the file pane, which shrinks into its folder on the tree.
    // Nothing else may claim the pull-down space.
    val shell = rememberSpatialController()

    // Hoisted so the edge scrubber can read where the list is and jump it. Both are needed
    // because the browser switches between a column and a grid, and a scrubber that only worked
    // in one of them would be an affordance that silently stops meaning anything.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

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

    // Destination for PDF page extraction / merge. Kept separate from archiveCreator so the two
    // flows cannot ever write into each other's target.
    val pdfOutputCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destination ->
        val pages = pendingPdfPages
        val merge = pendingPdfMerge
        val ocr = pendingPdfOcr
        pendingPdfPages = emptyList()
        pendingPdfMerge = false
        if (destination == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            runCatching {
                if (merge) {
                    pdfTools.merge(
                        sources = selectedEntries.filter { it.kind == EntryKind.PDF }.map { it.uri },
                        outputUri = destination,
                        searchableOcr = ocr,
                    ) { done, total -> operationMessage = "Merging page $done of $total" }
                } else {
                    pdfTools.exportPages(
                        pages = pages,
                        outputUri = destination,
                        searchableOcr = ocr,
                    ) { done, total -> operationMessage = "Writing page $done of $total" }
                }
            }.onSuccess {
                toast("PDF written")
                refresh()
            }.onFailure { toast(it.message ?: "The PDF operation failed") }
            operationMessage = null
            loading = false
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

    fun copyToClipboard(mode: ClipboardMode) {
        val tab = activeTab ?: return
        if (selectedEntries.isEmpty()) return
        // A recursive-search hit lives in some folder other than the one on screen, so only
        // claim a source folder when the listing really is this folder's own.
        val folderOnScreen = tab.current.uri
            .takeUnless { searchRecursive && query.isNotBlank() }
            ?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() }
        val items = selectedEntries.mapNotNull { entry ->
            val id = runCatching { DocumentsContract.getDocumentId(entry.uri) }.getOrNull() ?: return@mapNotNull null
            ClipItem(entry.uri, entry.name, entry.isDirectory, id, folderOnScreen)
        }
        if (items.isEmpty()) {
            toast("Those items cannot be put on the clipboard")
            return
        }
        clipboard = FileClipboard(mode, items)
        selectedUris = emptySet()
        toast(if (mode == ClipboardMode.CUT) "Cut ${clipboard?.label}" else "Copied ${clipboard?.label}")
    }

    fun paste() {
        val tab = activeTab ?: run { toast("Open a folder to paste into"); return }
        val clip = clipboard ?: return
        val destinationId = runCatching { DocumentsContract.getDocumentId(tab.current.uri) }.getOrNull()
            ?: run { toast("This folder cannot be pasted into"); return }
        val plan = ClipboardPolicy.plan(clip, destinationId)
        if (plan.isEmpty) {
            toast(plan.blocked.firstOrNull()?.second ?: "Already in this folder")
            return
        }
        scope.launch {
            loading = true
            runCatching {
                val sources = plan.transfer.map { it.uri }
                // Keep-both: pasting never overwrites. Copying into the folder it came from
                // therefore yields "name (2)", which is how a file is duplicated.
                if (clip.mode == ClipboardMode.CUT) {
                    fileOperations.move(sources, tab.current.uri, ConflictPolicy.KEEP_BOTH) { progress ->
                        operationMessage = "Moving ${progress.displayName}"
                    }
                } else {
                    fileOperations.copy(sources, tab.current.uri, ConflictPolicy.KEEP_BOTH) { progress ->
                        operationMessage = "Copying ${progress.displayName}"
                    }
                }
            }.onSuccess {
                val note = if (plan.blocked.isEmpty()) "" else "; ${plan.blocked.size} folder(s) cannot go into themselves"
                toast(ClipboardPolicy.resultMessage(clip.mode, plan.transfer.size, plan.alreadyHere.size) + note)
                // A cut is spent once it lands; a copy stays so it can be pasted again.
                if (clip.mode == ClipboardMode.CUT) clipboard = null
            }.onFailure { toast(it.message ?: "Paste failed") }
            refresh()
            operationMessage = null
            loading = false
        }
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

    // The left rail's contents. Loaded the way the storage home surface loads them, so the rail
    // and the home screen can never disagree about what a "Downloads" is.
    val rootGroups by produceState(
        initialValue = emptyList<StorageRootGroup>(),
        key1 = homeRefreshKey,
    ) {
        value = runCatching {
            StorageAccess.available(context).flatMap { it.rootGroups(context) }
        }.getOrDefault(emptyList())
    }
    val places = remember(rootGroups) { CommonPlaces.build(rootGroups) }
    val largeFilesActive = searchRecursive && query == CommonPlaces.LARGE_FILES_QUERY
    // Large files or one of the landing screen's category searches.
    val launcherSearchActive = searchRecursive &&
        (query == CommonPlaces.LARGE_FILES_QUERY || LauncherCategory.entries.any { it.query == query })
    val railSelectedId = CommonPlaces.selectedId(
        places = places,
        activeTreeUri = activeTab?.treeUri?.toString(),
        largeFilesActive = largeFilesActive,
        activePaneId = pane?.let(CommonPlaces::paneId),
    )
    val folderFacts = remember(entries) { FolderFacts.from(entries) }
    val volumeLine = remember(activeTab?.treeUri, rootGroups) {
        LauncherModel.volumeFor(activeTab?.treeUri, rootGroups)?.let { volume ->
            val free = volume.availableBytes
            val total = volume.totalBytes
            if (free != null && total != null && total > 0) {
                "${formatStorageBytes(free)} free of ${formatStorageBytes(total)}"
            } else {
                null
            }
        }
    }

    // A recursive search over the device volume: "Large files" in the list, and the Documents /
    // Images / Videos / APKs tiles on the landing screen, are all this with a different query.
    fun openDeviceSearch(searchQuery: String) {
        val volume = CommonPlaces.largeFilesRoot(rootGroups)
        if (volume == null) {
            // Searching the whole device needs All files access; say so instead of doing nothing.
            toast("Allow All files access to search the whole device")
            return
        }
        pane = null
        openStorageRoot(volume)
        query = searchQuery
        searchRecursive = true
    }

    fun openLargeFiles() = openDeviceSearch(CommonPlaces.LARGE_FILES_QUERY)

    // Choosing a row of the places list. Turning the wheel onto a row IS choosing it (the rail
    // reports a settle as a selection), and the pane to the right follows: that is the point
    // of the list, so nothing here closes the room. The room is left the way it was entered.
    fun selectPlace(place: CommonPlace) {
        // Large files is a search, so leaving it must also clear the search — otherwise Downloads
        // would open filtered to files over 100 MB.
        if (place.kind != PlaceKind.LARGE_FILES && launcherSearchActive) {
            query = ""
            searchRecursive = false
        }
        when (place.kind) {
            PlaceKind.HOME -> {
                pane = null
                activeTabId = null
                homeRefreshKey += 1
            }
            PlaceKind.FOLDER -> {
                pane = null
                place.root?.let(::openStorageRoot)
            }
            PlaceKind.LARGE_FILES -> openLargeFiles()
            PlaceKind.RECYCLE_BIN, PlaceKind.RECOVERY, PlaceKind.SETTINGS -> pane = place.kind
        }
    }

    // Opening a folder puts it at the front of the launcher's Recent row.
    LaunchedEffect(activeTab?.id, activeTab?.current?.uri) {
        val tab = activeTab ?: return@LaunchedEffect
        recentFolders = recentStore.record(
            RecentFolder(tab.treeUri.toString(), tab.current.uri.toString(), tab.current.name),
        )
    }

    // Back closes an open room before it does anything else: a room is not a back-stack entry,
    // but Back is the gesture people reach for to leave one.
    BackHandler(enabled = !shell.atHome) { shell.closeAll() }
    // With the room closed, Back leaves a pane for the file view before it leaves the app.
    BackHandler(enabled = pane != null && shell.atHome) { pane = null }

    Box(Modifier.fillMaxSize()) {
    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        scrimColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        cardColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
        // The places list opens the reference's way: the content stays a large, lit, rounded
        // card on the right half of the window instead of shrinking to a band behind a scrim,
        // so moving the wheel visibly moves the pane that follows it.
        leftPark = LEFT_PARK,
        left = {
            PlacesRoom(
                places = places,
                selectedId = railSelectedId,
                onSelect = ::selectPlace,
                onMenu = { shell.closeAll() },
            )
        },
        // TOP = where you are in the folder tree, pulled DOWN over the file pane. The pane
        // shrinks into its own node on the tree (see FolderMorph). vProgress runs POSITIVE for
        // a TOP room, which is what the morph's stagger expects.
        top = {
            FileTreeRoom(
                trail = activeTab?.locations.orEmpty(),
                childFolders = entries.filter(FileEntry::isDirectory),
                facts = folderFacts,
                volumeLine = volumeLine,
                reveal = { shell.vProgress },
                onOpenAncestor = { index ->
                    val tab = activeTab
                    val at = tabs.indexOfFirst { it.id == tab?.id }
                    if (tab != null && at >= 0) {
                        tabs[at] = tab.copy(locations = tab.locations.take(index + 1))
                    }
                    shell.closeAll()
                },
                onOpenChild = { entry ->
                    openEntry(entry)
                    shell.closeAll()
                },
                onClose = { shell.closeAll() },
            )
        },
    ) {
    // Refresh is a shake, everywhere in the constellation. The pull-down space at the top of a
    // room belongs to the top-room reveal and no other gesture may claim it, so refresh moves
    // off the touch plane entirely — a deliberate shake needs no affordance, no instructional
    // copy, and competes with no scroll. The toolbar button stays for anyone who would rather
    // tap than shake.
    ShakeToRefresh(onShake = { refresh() })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        // The pane to the right of the places list. It is keyed on the focused row, so moving the
        // wheel from Downloads to Pictures moves the pane too: it slides in the direction the
        // wheel turned and the old one fades away behind it.
        AnimatedContent(
            targetState = railSelectedId,
            transitionSpec = {
                val from = places.indexOfFirst { it.id == initialState }
                val to = places.indexOfFirst { it.id == targetState }
                val down = to >= from
                (
                    slideInVertically(tween(PANE_SLIDE_MS)) { height -> if (down) height / 6 else -height / 6 } +
                        fadeIn(tween(PANE_SLIDE_MS * 3 / 4))
                    ) togetherWith (
                    slideOutVertically(tween(PANE_SLIDE_MS)) { height -> if (down) -height / 6 else height / 6 } +
                        fadeOut(tween(PANE_SLIDE_MS / 2))
                    )
            },
            label = "pane",
        ) { shownId ->
        // Which pane this content is for comes from the row it animates to, not from the live
        // state: while the old pane slides out it must keep showing itself, not flip to the
        // content that is arriving.
        val shownPane = places.firstOrNull { it.id == shownId }?.kind
            ?.takeIf { it == PlaceKind.RECYCLE_BIN || it == PlaceKind.RECOVERY || it == PlaceKind.SETTINGS }
        if (shownPane != null) {
            PaneScaffold(onMenu = { shell.open(RoomEdge.LEFT) }) {
                when (shownPane) {
                    PlaceKind.RECYCLE_BIN -> RecycleBinPane(
                        records = remember(recycleVersion, refreshKey) { recycleBin.records() },
                        onRestore = { id ->
                            scope.launch {
                                runCatching { recycleBin.restore(id, conflictPolicy = ConflictPolicy.KEEP_BOTH) }
                                    .onSuccess { refresh() }
                                    .onFailure { toast(it.message ?: "Restore failed") }
                                recycleVersion += 1
                            }
                        },
                        onDelete = { id ->
                            scope.launch {
                                runCatching { recycleBin.permanentlyDelete(id, confirmed = true) }
                                    .onFailure { toast(it.message ?: "Permanent deletion failed") }
                                recycleVersion += 1
                            }
                        },
                    )
                    PlaceKind.RECOVERY -> recoveryRoom()
                    PlaceKind.SETTINGS -> SettingsPane(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onAction = { action ->
                            when (action) {
                                ToolsAction.REMOTES -> remoteDialog = true
                                ToolsAction.WEBDAV -> webDavDialog = true
                                ToolsAction.TOOLS -> runCatching {
                                    context.startActivity(Intent(context, PostV1ToolsActivity::class.java))
                                }.onFailure { toast("Tools are unavailable on this build") }
                                ToolsAction.INDEX -> runCatching {
                                    context.startActivity(Intent(context, IndexManagerActivity::class.java))
                                }.onFailure { toast("The index manager is unavailable") }
                            }
                        },
                    )
                    else -> Unit
                }
            }
        } else Scaffold(
            topBar = {
                // The launcher has no title bar: just the glyph that opens the places list.
                if (activeTab == null) {
                    AppHeader(onMenu = { shell.open(RoomEdge.LEFT) })
                } else
                TopAppBar(
                    title = { Text(activeTab?.current?.name ?: "Fylz") },
                    // The reference's header glyph. It opens the places rail — the same room an
                    // edge swipe opens — and is the only chrome the rail has.
                    navigationIcon = {
                        IconButton(onClick = { shell.open(RoomEdge.LEFT) }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Places")
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
                                    text = { Text(clipboard?.let { "Paste ${it.label}" } ?: "Paste") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                                    enabled = clipboard != null && activeTab != null && !loading,
                                    onClick = { moreExpanded = false; paste() },
                                )
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
                                // Recycle Bin, Remotes, WebDAV, Tools, the index manager and
                                // the theme toggle all moved to the right room. They are not
                                // actions on *this folder* — they are the app's own tools, and
                                // burying them in a per-folder overflow was why the menu had
                                // eleven items and no shape. What is left here is what genuinely
                                // acts on the folder you are looking at.
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
                            }
                        }
                    },
                )
            },
            bottomBar = {
                val held = clipboard
                if (selectedEntries.isEmpty() && held != null && activeTab != null) {
                    PasteBar(
                        clipboard = held,
                        busy = loading,
                        onPaste = ::paste,
                        onClear = { clipboard = null },
                    )
                }
                if (selectedEntries.isNotEmpty()) {
                    SelectionActionBar(
                        count = selectedEntries.size,
                        onClipCut = { copyToClipboard(ClipboardMode.CUT) },
                        onClipCopy = { copyToClipboard(ClipboardMode.COPY) },
                        canRename = selectedEntries.size == 1,
                        canExtract = selectedEntries.size == 1 && selectedEntries.first().kind == EntryKind.ARCHIVE,
                        canPdfTools = selectedEntries.isNotEmpty() &&
                            selectedEntries.all { it.kind == EntryKind.PDF },
                        onPdfTools = { pdfDialog = true },
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
            // The numbered workspace chips are gone. They were a second navigation surface
            // stacked above the first, they floated out of alignment on device, and every open
            // location they listed is now a row in the left room — where switching between them
            // is the same gesture as everything else in the app.
            Column(Modifier.fillMaxSize().padding(padding)) {
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
                            onOpenRoot = {
                                // Same as choosing Home in the places list: a launcher search must not
                                // follow the user into the next folder they open.
                                if (launcherSearchActive) {
                                    query = ""
                                    searchRecursive = false
                                }
                                pane = null
                                activeTabId = null
                                homeRefreshKey += 1
                            },
                            onRecycle = { pane = PlaceKind.RECYCLE_BIN },
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
                        recents = recentFolders,
                        onOpenRecent = { recent ->
                            openTabAt(
                                Uri.parse(recent.treeUri),
                                FolderLocation(Uri.parse(recent.folderUri), recent.name),
                            )
                        },
                        onOpenSearch = ::openDeviceSearch,
                        onOpenRecycleBin = { pane = PlaceKind.RECYCLE_BIN },
                        recycleCount = remember(recycleVersion, refreshKey) { recycleBin.records().size },
                        shredding = shredding,
                        onShredAll = {
                            if (!shredding) {
                                shredding = true
                                scope.launch {
                                    // The caller has already asked the user to confirm. One failure
                                    // does not stop the rest; the failures are counted and reported.
                                    var failed = 0
                                    try {
                                        recycleBin.records().forEach { record ->
                                            try {
                                                recycleBin.permanentlyDelete(record.itemId, confirmed = true)
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                failed += 1
                                            }
                                        }
                                        if (failed > 0) toast("$failed items could not be deleted")
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // Reading the bin itself failed; nothing was shredded.
                                        toast(e.message ?: "Could not read the recycle bin")
                                    } finally {
                                        shredding = false
                                        recycleVersion += 1
                                    }
                                }
                            }
                        },
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
                        cutUris = clipboard?.takeIf { it.mode == ClipboardMode.CUT }
                            ?.items?.map { it.uri }?.toSet().orEmpty(),
                        listState = listState,
                        gridState = gridState,
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
        }

        // The Niagara-style edge scrubber. Its stops follow whatever the list is sorted by —
        // letters, months, size bands or extensions — because Fylz re-keys the same folder as
        // the sort changes, and a strip showing months down an A-Z list would be a map of
        // somewhere else. It only appears when there is a listing to map: not on the storage
        // home surface, and not while a selection has taken over the bottom bar.
        if (pane == null && activeTab != null && visibleEntries.size > 1 && selectedEntries.isEmpty()) {
            val stops = remember(visibleEntries, sortSpec) { entryStops(visibleEntries, sortSpec) }
            val grid = viewMode == ViewMode.GRID
            EdgeTimelineScrubber(
                stops = stops,
                itemCount = visibleEntries.size,
                currentIndex = if (grid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex,
                onScrubTo = { index ->
                    // scrollToItem, not the animated variant: the finger is already moving and
                    // the list must track it rather than chase it.
                    scope.launch {
                        if (grid) gridState.scrollToItem(index) else listState.scrollToItem(index)
                    }
                },
                inkColor = MaterialTheme.colorScheme.onSurface,
                accentColor = MaterialTheme.colorScheme.primary,
                bubbleTextColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
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
    }
    // The cards before and after the focused one, peeking above and below the parked card and
    // sliding with the wheel. They live above the shell because the shell leaves no room for
    // them: its left room is narrowed to the part of the window the card leaves.
    PeekCards(
        count = places.size,
        focusedIndex = places.indexOfFirst { it.id == railSelectedId }.coerceAtLeast(0),
        openness = { shell.hProgress },
    )
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

    if (pdfDialog) {
        PdfToolsDialog(
            sources = selectedEntries.filter { it.kind == EntryKind.PDF }.map { it.uri },
            service = pdfTools,
            onDismiss = { pdfDialog = false },
            onExport = { pages, ocr ->
                pdfDialog = false
                pendingPdfPages = pages
                pendingPdfOcr = ocr
                pendingPdfMerge = false
                pdfOutputCreator.launch(PdfOutputNames.pages(System.currentTimeMillis()))
            },
            onMerge = { ocr ->
                pdfDialog = false
                pendingPdfPages = emptyList()
                pendingPdfOcr = ocr
                pendingPdfMerge = true
                pdfOutputCreator.launch(PdfOutputNames.merged(System.currentTimeMillis()))
            },
            onError = ::toast,
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
    recents: List<RecentFolder>,
    onOpenRecent: (RecentFolder) -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenRecycleBin: () -> Unit,
    recycleCount: Int,
    shredding: Boolean,
    onShredAll: () -> Unit,
    onQueryChange: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    onSelectAll: () -> Unit,
    cutUris: Set<Uri>,
    listState: LazyListState,
    gridState: LazyGridState,
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
            recents = recents,
            onOpenRecent = onOpenRecent,
            onOpenSearch = onOpenSearch,
            onOpenRecycleBin = onOpenRecycleBin,
            recycleCount = recycleCount,
            shredding = shredding,
            onShredAll = onShredAll,
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
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.uri.toString() }) { entry ->
                    FileCard(entry, entry.uri in selectedUris, entry.uri == focusedEntry?.uri, onOpen, onOpenExternal, onToggleSelection, dimmed = entry.uri in cutUris)
                }
            }
        } else {
            LazyColumn(state = listState) {
                items(entries, key = { it.uri.toString() }) { entry ->
                    FileRowV1(entry, entry.uri in selectedUris, entry.uri == focusedEntry?.uri, onOpen, onOpenExternal, onToggleSelection, dimmed = entry.uri in cutUris)
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
    dimmed: Boolean = false,
) {
    val label = if (entry.isDirectory) "Folder ${entry.name}" else entry.name
    Row(
        modifier = Modifier
            // A cut item stays where it is until it is pasted, drawn faint so it reads as
            // "leaving" rather than gone.
            .alpha(if (dimmed) CUT_ITEM_ALPHA else 1f)
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
    dimmed: Boolean = false,
) {
    Surface(
        color = if (selected || focused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.alpha(if (dimmed) CUT_ITEM_ALPHA else 1f).height(140.dp).combinedClickable(
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
    canPdfTools: Boolean,
    onPdfTools: () -> Unit,
    onClipCut: () -> Unit,
    onClipCopy: () -> Unit,
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
            // Clipboard first: cut/copy then paste into any folder, in any tab.
            ActionButton(Icons.Outlined.ContentCut, "Cut", onClipCut)
            ActionButton(Icons.Outlined.ContentCopy, "Copy", onClipCopy)
            // The picker-based transfers stay, named for what they do.
            ActionButton(Icons.Outlined.ContentCopy, "Copy to…", onCopy)
            ActionButton(Icons.Outlined.DriveFileMove, "Move to…", onMove)
            ActionButton(Icons.Outlined.Delete, "Recycle", onRecycle)
            ActionButton(Icons.Outlined.Edit, "Rename", onRename, canRename)
            ActionButton(Icons.Outlined.Tag, "Tags", onTags)
            ActionButton(Icons.Outlined.Archive, "Archive", onArchive)
            ActionButton(Icons.Outlined.FolderOpen, "Extract", onExtract, canExtract)
            ActionButton(Icons.Outlined.TextSnippet, "Batch rename", onBatchRename)
            ActionButton(Icons.Outlined.PictureAsPdf, "PDF tools", onPdfTools, canPdfTools)
            ActionButton(Icons.Outlined.Share, "Share", onShare)
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/**
 * What is on the clipboard and where it can go. It shows only while nothing is selected, so it
 * never competes with the selection bar, and it is the same height so the bottom edge does not
 * jump between the two.
 */
@Composable
private fun PasteBar(
    clipboard: FileClipboard,
    busy: Boolean,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                (if (clipboard.mode == ClipboardMode.CUT) "Cut: " else "Copied: ") + clipboard.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            ActionButton(Icons.Outlined.ContentPaste, "Paste here", onPaste, enabled = !busy)
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

/**
 * The left room: the common places, and a few select smart entries — nothing else.
 *
 * It used to list every open tab, so it grew with wherever the user had wandered and stopped
 * being a map of the app. Where you are now is the file tree's job (the top room); this is the
 * short, stable list people start from: Home, the standard folders, Large files, and the picker.
 *
 * The rail is a word wheel, as in the reference: plain words, the focused one darkest and the
 * rest fading with distance. No icons, no selection box.
 */
@Composable
private fun PlacesRoom(
    places: List<CommonPlace>,
    selectedId: String,
    onSelect: (CommonPlace) -> Unit,
    onMenu: () -> Unit,
) {
    val items = remember(places) { places.map { WheelItem(it.id, it.label) } }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
        ) {
            WordWheelRail(
                items = items,
                selectedId = selectedId,
                onSelect = { id -> places.firstOrNull { it.id == id }?.let(onSelect) },
                inkColor = MaterialTheme.colorScheme.onSurface,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
        // The reference's header glyph stays top-left when the list is open; here it closes it.
        IconButton(
            onClick = onMenu,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp),
        ) {
            Icon(Icons.Outlined.Menu, contentDescription = "Close places")
        }
    }
}

/**
 * The stack of cards from the reference, outside the focused one.
 *
 * The parked card is the focused destination's real pane. Above and below it, the neighbouring
 * destinations' cards show as slivers, and as the wheel turns the whole stack slides by one
 * card, so the list on the left and the cards on the right read as one carousel. The geometry is
 * the shell's own ([LEFT_PARK], [openParkDistance]) so the slivers meet the card exactly.
 *
 * Nothing is drawn inside the parked card's own rectangle (that is the live pane), nothing
 * answers touches, and with the list closed ([openness] 0) nothing is drawn at all.
 */
@Composable
private fun PeekCards(count: Int, focusedIndex: Int, openness: () -> Float) {
    if (count < 2) return
    val position by animateFloatAsState(
        targetValue = focusedIndex.toFloat(),
        animationSpec = tween(PANE_SLIDE_MS, easing = FastOutSlowInEasing),
        label = "peek-position",
    )
    val scheme = MaterialTheme.colorScheme
    val tints = remember(scheme) {
        listOf(
            scheme.primaryContainer to scheme.tertiaryContainer,
            scheme.secondaryContainer to scheme.primaryContainer,
            scheme.tertiaryContainer to scheme.secondaryContainer,
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        val open = openness().coerceIn(0f, 1f)
        if (open <= 0.001f) return@Canvas
        val scale = 1f - (1f - LEFT_PARK.scale) * open
        val cardH = size.height * scale
        val cardTop = (size.height - cardH) / 2f
        val cardLeft = size.width * (1f - scale) / 2f + open * openParkDistance(size.width, LEFT_PARK)
        val gap = size.height * PEEK_GAP_FRACTION * open
        val pitch = cardH + gap
        val corner = CornerRadius(LEFT_PARK.cornerDp.dp.toPx() * open)
        val width = size.width - cardLeft + 2f * corner.x

        fun drawStack(clipTop: Float, clipBottom: Float) {
            clipRect(left = cardLeft, top = clipTop, right = size.width, bottom = clipBottom) {
                for (i in 0 until count) {
                    val top = cardTop + (i - position) * pitch
                    if (top > clipBottom || top + cardH < clipTop) continue
                    val (a, b) = tints[i % tints.size]
                    drawRoundRect(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(a.copy(alpha = open), b.copy(alpha = open)),
                            start = Offset(cardLeft, top),
                            end = Offset(cardLeft + width, top + cardH),
                        ),
                        topLeft = Offset(cardLeft, top),
                        size = Size(width, cardH),
                        cornerRadius = corner,
                    )
                }
            }
        }
        // Above the card and below it: the two margins the card's scale leaves.
        drawStack(clipTop = 0f, clipBottom = (cardTop - gap).coerceAtLeast(0f))
        drawStack(clipTop = (cardTop + cardH + gap).coerceAtMost(size.height), clipBottom = size.height)
    }
}

/** What a row in the tools room does. */
private enum class ToolsAction { REMOTES, WEBDAV, TOOLS, INDEX }

/**
 * Settings: the app's own tools and appearance, shown in the pane when Settings is the focused
 * row of the places list.
 *
 * These were all buried in the file browser's overflow menu, which had eleven items and no
 * shape because it mixed "make a folder here" with "open the index manager". They are not
 * actions on the folder you are looking at; they are the app, and they get a surface.
 *
 * It renders in Fylz's theme like everything else here. That is the fix for the light-coloured
 * Tools screen inside a dark app: the destination activities were painting under a bare
 * `MaterialTheme`, and so was this menu's host.
 */
@Composable
private fun SettingsPane(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAction: (ToolsAction) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))
        RoomHeading("Locations")
        ToolsRow(stringResource(R.string.remotes_title)) { onAction(ToolsAction.REMOTES) }
        ToolsRow("Quick WebDAV listing") { onAction(ToolsAction.WEBDAV) }

        Spacer(Modifier.size(16.dp))
        RoomHeading("Tools")
        ToolsRow(stringResource(R.string.tools_title)) { onAction(ToolsAction.TOOLS) }
        ToolsRow(stringResource(R.string.tools_index)) { onAction(ToolsAction.INDEX) }

        Spacer(Modifier.size(16.dp))
        RoomHeading("Appearance")
        ThemeMode.entries.forEach { mode ->
            val selected = mode == themeMode
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onThemeModeChange(mode) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A filled square for the chosen mode rather than a RadioButton: the same
                // marker the rail uses, so the two read as one app.
                Box(Modifier.size(width = 20.dp, height = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (selected) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Text(
                    mode.readableLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.6f),
                )
            }
        }
    }
}

/** The recycle bin as a pane: what was recycled, and what can come back. */
@Composable
private fun RecycleBinPane(
    records: List<io.github.mbaliga.fylz.operations.RecycleRecord>,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Recycle bin", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (records.isEmpty()) "Nothing here. Items are never removed automatically."
            else "${records.size} item${if (records.size == 1) "" else "s"}. Nothing is removed automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
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
    }
}

/**
 * The one piece of chrome the launcher and the panes have: the glyph that opens the places list
 * (the reference's header glyph). It is the same room an edge swipe opens.
 */
@Composable
private fun AppHeader(onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp)) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Outlined.Menu, contentDescription = "Places")
        }
    }
}

/** A pane under the glyph header. */
@Composable
private fun PaneScaffold(onMenu: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppHeader(onMenu)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun RoomHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ToolsRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** Sentence-case names for the theme modes; the enum's own names are shouting. */
private fun ThemeMode.readableLabel(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow the system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}


