package io.github.mbaliga.fylz.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.collectAsState
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.github.mbaliga.fylz.ai.AiClient
import io.github.mbaliga.fylz.ai.AiProviderConfig
import io.github.mbaliga.fylz.IndexManagerActivity
import io.github.mbaliga.fylz.PostV1ToolsActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ai.ApiKeyVault
import io.github.mbaliga.fylz.scan.DocumentScanner
import io.github.mbaliga.fylz.scan.GmsDocumentScannerAdapter
import io.github.mbaliga.fylz.browse.OpenTabsStore
import io.github.mbaliga.fylz.browse.SortDirection
import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.browse.sortEntries
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.data.SaveResult
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
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.FileTools
import io.github.mbaliga.fylz.operations.OperationProgress
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.operations.RunningOperation
import io.github.mbaliga.fylz.operations.isStagingName
import io.github.mbaliga.fylz.pdf.PdfPageRef
import io.github.mbaliga.fylz.pdf.PdfToolService
import io.github.mbaliga.fylz.preview.FileFormatRegistry
import io.github.mbaliga.fylz.preview.resolvePreviewKind
import io.github.mbaliga.fylz.search.RecursiveSearchEngine
import io.github.mbaliga.fylz.search.SearchHit
import io.github.mbaliga.fylz.search.SearchMatchSource
import io.github.mbaliga.fylz.search.SearchProgress
import io.github.mbaliga.fylz.search.SearchQuery
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.ExternalDocumentDialog
import io.github.mbaliga.fylz.ui.components.FloatingPreviewPane
import io.github.mbaliga.fylz.ui.components.PermanentDeleteConfirmationDialog
import io.github.mbaliga.fylz.ui.components.PreviewPane
import io.github.mbaliga.fylz.ui.components.totalKnownBytes
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
import dev.aarso.cellshell.EdgeTimelineScrubber
import dev.aarso.cellshell.ShakeToRefresh
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.WordWheelRail
import dev.aarso.cellshell.rememberSpatialController
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

enum class PendingDestinationAction { COPY, MOVE, EXTRACT }

/** Extensions [io.github.mbaliga.fylz.data.ArchiveService.extractZip] can actually extract
 * (P0.10): it's a ZIP reader (zip4j), so offering Extract for `.7z`/`.rar`/`.tar`/... would fail
 * on every attempt despite [io.github.mbaliga.fylz.model.EntryKind.ARCHIVE] covering all of them. */
private val ZIP_FAMILY_EXTENSIONS = setOf("zip", "zipx", "jar", "apk", "cbz")

internal fun isZipFamilyArchive(name: String): Boolean =
    FileFormatRegistry.compoundExtension(name) in ZIP_FAMILY_EXTENSIONS

/** Selected entries in the order the user actually selected them (P0.10) -- [selectedUris]
 * preserves insertion order at runtime (every mutation site builds it via `Set.plus`/`.minus`,
 * which the stdlib backs with a `LinkedHashSet`), but a plain `entries.filter { it.uri in
 * selectedUris }` derives order from the folder listing instead, breaking the ordering promise
 * a dialog like PDF merge makes. */
internal fun orderedBySelection(entries: List<FileEntry>, selectedUris: Set<Uri>): List<FileEntry> =
    selectedUris.mapNotNull { uri -> entries.find { it.uri == uri } }

/** P0.10: an escaped `\${…}` in these two output names produced the literal text `${…}` instead
 * of the timestamp -- valid Kotlin (an escaped dollar sign), so it compiled clean and never
 * surfaced as anything but a wrong file name on every export. */
internal fun pdfPagesFileName(nowMillis: Long): String = "Fylz-pages-$nowMillis.pdf"
internal fun pdfMergedFileName(nowMillis: Long): String = "Fylz-merged-$nowMillis.pdf"

/** A permanent delete (one recycle bin item, or Empty Recycle Bin) waiting on the high-friction
 * [io.github.mbaliga.fylz.ui.components.PermanentDeleteConfirmationDialog] (P0.8, contract
 * §2.5-2.8) before [onConfirmed] actually runs it. */
private data class PermanentDeleteRequest(
    val itemCount: Int,
    val totalBytes: Long?,
    val itemName: String? = null,
    val onConfirmed: () -> Unit,
)

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
 * @param recoveryRoom the storage-and-recovery surface, shown as the shell's bottom room.
 * @param overlays dialogs the caller owns and needs drawn over everything.
 */
@Composable
fun FylzV1App(
    viewUri: Uri? = null,
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
            viewUri = viewUri,
        )
        overlays()
    }
}

@Composable
private fun FylzV1Workspace(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    recoveryRoom: @Composable () -> Unit,
    viewUri: Uri? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val repository = remember { DocumentRepository(context.applicationContext) }
    val openTabsStore = remember { OpenTabsStore(context.applicationContext) }
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
    val operationRunner = remember {
        (context.applicationContext as FylzApplication).operationRunner
    }
    val runningOperations by operationRunner.operations.collectAsState()
    val recycleRecords by recycleBin.records.collectAsState()

    val tabs = remember { mutableStateListOf<FolderTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var legacyBinNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var focusedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var previewMode by remember { mutableStateOf(PreviewMode.DOCKED) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewTruncated by remember { mutableStateOf(false) }
    var previewEncodingOk by remember { mutableStateOf(true) }
    var previewHasBom by remember { mutableStateOf(false) }
    var editorValue by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    var pendingDestinationAction by remember { mutableStateOf<PendingDestinationAction?>(null) }
    var pendingArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var extractPasswordDialog by remember { mutableStateOf(false) }
    var extractPassword by remember { mutableStateOf("") }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf<String?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var batchRenameDialog by remember { mutableStateOf(false) }
    var recycleDialog by remember { mutableStateOf(false) }
    var permanentDeleteRequest by remember { mutableStateOf<PermanentDeleteRequest?>(null) }
    var externalDocument by remember { mutableStateOf<FileEntry?>(null) }
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

    // Three rooms: locations LEFT, tools and settings RIGHT, recovery BOTTOM. The top edge is
    // deliberately empty — it is reserved for the top room, and nothing else may claim the
    // pull-down space.
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
        // P0.10: only a tab the user actually opened is remembered for restoration -- a
        // copy/move/extract destination, backup folder or index folder persists its own grant
        // (repository.persistTreePermission) without ever calling openTabAt, so it stays out.
        openTabsStore.record(treeUri)
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

    // P1.2: durable transfers run as WorkManager foreground work, whose progress notification
    // needs this permission on API 33+. Requesting it is best-effort -- a denial (or a pre-33
    // device, which needs no request at all) never blocks the transfer itself from running, only
    // its notification from showing.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    fun ensureNotificationPermissionRequested() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        val sources = selectedEntries.map { it.uri }
        val archiveUri = pendingArchiveUri
        if (action == PendingDestinationAction.COPY || action == PendingDestinationAction.MOVE) {
            ensureNotificationPermissionRequested()
        }
        // Copy and move (P1.2) run as durable WorkManager work instead of only on the app-scoped
        // OperationRunner (P0.5, A3) directly; extract still runs there. Either way this launch
        // only awaits the result to update UI state on completion, so rotating away mid-transfer
        // never cancels it.
        scope.launch {
            runCatching {
                when (action) {
                    PendingDestinationAction.COPY -> operationRunner.enqueueTransfer(
                        FileOperationType.COPY,
                        "Copying",
                        sources,
                        destination,
                        ConflictPolicy.KEEP_BOTH,
                    )
                    PendingDestinationAction.MOVE -> operationRunner.enqueueTransfer(
                        FileOperationType.MOVE,
                        "Moving",
                        sources,
                        destination,
                        ConflictPolicy.KEEP_BOTH,
                    )
                    PendingDestinationAction.EXTRACT -> operationRunner.run(FileOperationType.EXTRACT, "Extracting") {
                        archiveService.extractZip(
                            archiveUri = archiveUri ?: error("Choose an archive."),
                            destinationTreeUri = destination,
                            password = extractPassword.takeIf { it.isNotEmpty() }?.toCharArray(),
                        )
                    }.await()
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
                extractPassword = ""
                refresh()
            }.onFailure { toast(it.message ?: "Operation failed") }
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
            runCatching {
                operationRunner.run(FileOperationType.PDF, if (merge) "Merging PDF" else "Writing PDF") { report ->
                    if (merge) {
                        pdfTools.merge(
                            sources = selectedEntries.filter { it.kind == EntryKind.PDF }.map { it.uri },
                            outputUri = destination,
                            searchableOcr = ocr,
                        ) { done, total ->
                            report(OperationProgress(label = "Merging page $done of $total", itemIndex = done, itemCount = total))
                        }
                    } else {
                        pdfTools.exportPages(
                            pages = pages,
                            outputUri = destination,
                            searchableOcr = ocr,
                        ) { done, total ->
                            report(OperationProgress(label = "Writing page $done of $total", itemIndex = done, itemCount = total))
                        }
                    }
                }.await()
            }.onSuccess {
                toast("PDF written")
                refresh()
            }.onFailure { toast(it.message ?: "The PDF operation failed") }
        }
    }

    val scanner: DocumentScanner = remember { GmsDocumentScannerAdapter() }
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

    // Restore previously OPENED subtrees as tabs (P0.10) -- not every persisted grant, since a
    // one-off copy/move/extract destination, backup folder or index folder also persists a grant
    // without ever being something the user meant to browse. openTabsStore only ever gains an
    // entry through openTabAt, so this is exactly the set of tabs the user actually opened; a
    // grant the OS has since revoked is still filtered out here rather than restored broken.
    LaunchedEffect(Unit) {
        val livePermissions = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapTo(mutableSetOf()) { it.uri }
        openTabsStore.list()
            .filter { it in livePermissions }
            .take(MAX_RESTORED_TABS)
            .forEach { uri ->
                runCatching { repository.rootLocation(uri) }.getOrNull()?.let { root ->
                    val tab = FolderTab(treeUri = uri, locations = listOf(root))
                    tabs += tab
                }
            }
    }

    // P0.12: another app's "Open with Fylz" (ACTION_VIEW). Keyed on the incoming uri itself
    // (from MainActivity's own Compose state, via FylzAppShell), not Unit, so a *second*
    // "Open with Fylz" while Fylz is already running (singleTask -> onNewIntent) opens that
    // file too, not just the first one this composition ever saw.
    LaunchedEffect(viewUri) {
        val uri = viewUri ?: return@LaunchedEffect
        runCatching { repository.resolveExternalEntry(uri) }
            .onSuccess { externalDocument = it }
            .onFailure { toast(it.message ?: "Unable to open this file") }
    }

    // Legacy (pre-P0.3, dot-stripped) recycle bins for the active tree, if any -- excluded from
    // browsing and search exactly like .fylz-trash, until tidied via the explicit action in the
    // Recycle Bin dialog (defect 3; nothing about them happens automatically).
    LaunchedEffect(activeTab?.treeUri, refreshKey) {
        val tab = activeTab
        legacyBinNames = if (tab == null) {
            emptySet()
        } else {
            runCatching { recycleBin.legacyRecycleFolderNames(tab.treeUri) }.getOrDefault(emptySet())
        }
    }

    // Recursive search. Cancelled and restarted whenever the query, scope or folder changes --
    // LaunchedEffect's own cancellation is what makes an in-flight walk stop, and the engine
    // checks for it at every folder and every entry.
    LaunchedEffect(query, searchRecursive, activeTab?.current?.uri, refreshKey, legacyBinNames) {
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
            .search(tab.treeUri, tab.current.uri, tab.current.name, parsed, excludedDirectoryNames = legacyBinNames)
            .collectLatest { searchProgress = it }
    }

    LaunchedEffect(activeTab?.current?.uri, refreshKey, legacyBinNames) {
        selectedUris = emptySet()
        focusedEntry = null
        previewText = null
        if (activeTab == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        runCatching { repository.listChildren(activeTab.treeUri, activeTab.current.uri) }
            .onSuccess {
                entries = it.filterNot { item ->
                    item.name == ".fylz-trash" || item.name in legacyBinNames || isStagingName(item.name)
                }
            }
            .onFailure { toast(it.message ?: "Unable to read folder") }
        loading = false
    }

    LaunchedEffect(focusedEntry?.uri) {
        previewText = null
        previewTruncated = false
        previewEncodingOk = true
        previewHasBom = false
        editorValue = ""
        val entry = focusedEntry ?: return@LaunchedEffect
        // P0.9: `.ts` is TypeScript source or an MPEG transport stream depending on content, not
        // extension/MIME alone -- resolvePreviewKind sniffs it rather than trusting entry.kind.
        val kind = resolvePreviewKind(entry, context.contentResolver)
        if (!FileType.isTextPreviewable(kind)) return@LaunchedEffect
        previewLoading = true
        runCatching { repository.readText(entry.uri) }
            .onSuccess {
                previewText = it.value
                previewTruncated = it.truncated
                previewEncodingOk = it.encodingOk
                previewHasBom = it.hasBom
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

    fun saveEditorText() {
        val entry = focusedEntry ?: return
        scope.launch {
            when (val result = repository.writeText(entry.uri, editorValue, hasBom = previewHasBom)) {
                is SaveResult.Success -> {
                    previewText = editorValue
                    toast("Saved")
                }
                is SaveResult.Failed -> toast(result.message)
            }
        }
    }

    fun recycleSelection() {
        val tab = activeTab ?: return
        val selection = selectedEntries
        if (selection.isEmpty()) return
        scope.launch {
            runCatching {
                operationRunner.run(FileOperationType.RECYCLE, "Moving to Recycle Bin") { report ->
                    val recycleRoot = recycleBin.recycleRootFor(tab.treeUri)
                    selection.forEachIndexed { index, entry ->
                        report(
                            OperationProgress(
                                label = "Moving ${entry.name} to Recycle Bin",
                                itemIndex = index + 1,
                                itemCount = selection.size,
                            ),
                        )
                        recycleBin.recycle(entry.uri, tab.current.uri, recycleRoot.uri)
                    }
                }.await()
            }.onSuccess {
                toast("Moved to Recycle Bin")
                selectedUris = emptySet()
                refresh()
            }.onFailure { toast(it.message ?: "Unable to recycle selection") }
        }
    }

    // Back closes an open room before it does anything else: a room is not a back-stack entry,
    // but Back is the gesture people reach for to leave one.
    BackHandler(enabled = !shell.atHome) { shell.closeAll() }

    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        scrimColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        cardColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
        left = {
            LocationsRoom(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelect = { id ->
                    activeTabId = id
                    shell.closeAll()
                },
                onOpenHome = {
                    activeTabId = null
                    homeRefreshKey += 1
                    shell.closeAll()
                },
                onClose = { tab ->
                    val wasActive = activeTabId == tab.id
                    tabs.remove(tab)
                    if (wasActive) activeTabId = tabs.lastOrNull()?.id
                },
                onAdd = {
                    shell.closeAll()
                    rootPicker.launch(null)
                },
            )
        },
        right = {
            ToolsRoom(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onAction = { action ->
                    shell.closeAll()
                    when (action) {
                        ToolsAction.RECYCLE_BIN -> recycleDialog = true
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
        },
        bottom = recoveryRoom,
    ) {
    // Refresh is a shake, everywhere in the constellation. The pull-down space at the top of a
    // room belongs to the top-room reveal and no other gesture may claim it, so refresh moves
    // off the touch plane entirely — a deliberate shake needs no affordance, no instructional
    // copy, and competes with no scroll. The toolbar button stays for anyone who would rather
    // tap than shake.
    ShakeToRefresh(onShake = { refresh() })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        // P0.9: Dock and Close both leave previewMode permanently HIDDEN; without this, a phone
        // user who ever dismissed the preview once could never see another file's preview again.
        LaunchedEffect(focusedEntry?.uri) {
            if (!wide && focusedEntry != null) previewMode = PreviewMode.FLOATING
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(activeTab?.current?.name ?: "Fylz") },
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
                if (selectedEntries.isNotEmpty()) {
                    SelectionActionBar(
                        count = selectedEntries.size,
                        canRename = selectedEntries.size == 1,
                        canExtract = selectedEntries.size == 1 &&
                            selectedEntries.first().kind == EntryKind.ARCHIVE &&
                            isZipFamilyArchive(selectedEntries.first().name),
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
                            val archiveUri = selectedEntries.first().uri
                            pendingArchiveUri = archiveUri
                            extractPassword = ""
                            scope.launch {
                                val encrypted = runCatching { archiveService.inspectZip(archiveUri).encrypted }
                                    .getOrDefault(false)
                                if (encrypted) {
                                    extractPasswordDialog = true
                                } else {
                                    pendingDestinationAction = PendingDestinationAction.EXTRACT
                                    destinationPicker.launch(null)
                                }
                            }
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
                        runningOperations = runningOperations,
                        onCancelOperation = operationRunner::cancel,
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
                            textEncodingOk = previewEncodingOk,
                            loading = previewLoading,
                            editorValue = editorValue,
                            onEditorValueChange = { editorValue = it },
                            onSave = ::saveEditorText,
                            modifier = Modifier.width(380.dp).fillMaxHeight(),
                        )
                    }
                }
            }
        }

        // The Niagara-style edge scrubber. Its stops follow whatever the list is sorted by —
        // letters, months, size bands or extensions — because Fylz re-keys the same folder as
        // the sort changes, and a strip showing months down an A-Z list would be a map of
        // somewhere else. It only appears when there is a listing to map: not on the storage
        // home surface, and not while a selection has taken over the bottom bar.
        if (activeTab != null && visibleEntries.size > 1 && selectedEntries.isEmpty()) {
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
                showDock = wide,
            ) {
                PreviewPane(
                    entry = focusedEntry,
                    textContent = previewText,
                    textTruncated = previewTruncated,
                    textEncodingOk = previewEncodingOk,
                    loading = previewLoading,
                    editorValue = editorValue,
                    onEditorValueChange = { editorValue = it },
                    onSave = ::saveEditorText,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
            selection = selectedEntries.map { it.uri to it.name },
            planPreview = { prefix -> fileTools.planBatchRename(selectedEntries.map { it.uri to it.name }, prefix) },
            onDismiss = { batchRenameDialog = false },
            onConfirm = { prefix ->
                batchRenameDialog = false
                val tab = activeTab
                if (tab == null) {
                    toast("No folder is open")
                } else {
                    scope.launch {
                        runCatching {
                            val plans = fileTools.planBatchRename(selectedEntries.map { it.uri to it.name }, prefix)
                            fileTools.executeBatchRename(tab.current.uri, plans)
                        }
                            .onSuccess { selectedUris = emptySet(); refresh() }
                            .onFailure { toast(it.message ?: "Batch rename failed") }
                    }
                }
            },
        )
    }

    if (recycleDialog) {
        RecycleBinDialog(
            records = recycleRecords,
            legacyBinCount = legacyBinNames.size,
            onDismiss = { recycleDialog = false },
            onRestore = { id ->
                scope.launch {
                    runCatching { recycleBin.restore(id, conflictPolicy = ConflictPolicy.KEEP_BOTH) }
                        .onSuccess { refresh() }
                        .onFailure { toast(it.message ?: "Restore failed") }
                }
            },
            onDelete = { id ->
                val record = recycleRecords.firstOrNull { it.itemId == id }
                permanentDeleteRequest = PermanentDeleteRequest(
                    itemCount = 1,
                    totalBytes = record?.sizeBytes,
                    itemName = record?.originalDisplayName,
                    onConfirmed = {
                        scope.launch {
                            runCatching { recycleBin.permanentlyDelete(id, confirmed = true) }
                                .onFailure { toast(it.message ?: "Permanent deletion failed") }
                        }
                    },
                )
            },
            onEmptyRecycleBin = {
                permanentDeleteRequest = PermanentDeleteRequest(
                    itemCount = recycleRecords.size,
                    totalBytes = totalKnownBytes(recycleRecords.map { it.sizeBytes }),
                    onConfirmed = {
                        scope.launch {
                            val succeeded = runCatching { recycleBin.emptyBin(confirmed = true) }
                                .onFailure { toast(it.message ?: "Unable to empty the Recycle Bin") }
                                .getOrNull()
                            if (succeeded != null) toast("Permanently deleted $succeeded item(s)")
                        }
                    },
                )
            },
            onTidyLegacyBins = {
                val tab = activeTab ?: return@RecycleBinDialog
                scope.launch {
                    runCatching {
                        recycleBin.legacyRecycleFolders(tab.treeUri).forEach { legacy ->
                            recycleBin.tidyLegacyRecycleFolder(tab.treeUri, legacy)
                        }
                    }
                        .onSuccess { toast("Legacy recycle folders tidied"); refresh() }
                        .onFailure { toast(it.message ?: "Unable to tidy legacy recycle folders") }
                }
            },
        )
    }

    permanentDeleteRequest?.let { request ->
        PermanentDeleteConfirmationDialog(
            itemCount = request.itemCount,
            totalBytes = request.totalBytes,
            itemName = request.itemName,
            onDismiss = { permanentDeleteRequest = null },
            onConfirm = {
                permanentDeleteRequest = null
                request.onConfirmed()
            },
        )
    }

    externalDocument?.let { entry ->
        ExternalDocumentDialog(
            entry = entry,
            repository = repository,
            onDismiss = { externalDocument = null },
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

    if (extractPasswordDialog) {
        AlertDialog(
            onDismissRequest = { extractPasswordDialog = false; pendingArchiveUri = null },
            title = { Text("Archive password") },
            text = {
                PasswordField(
                    value = extractPassword,
                    onValueChange = { extractPassword = it },
                    label = "Password",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        extractPasswordDialog = false
                        pendingDestinationAction = PendingDestinationAction.EXTRACT
                        destinationPicker.launch(null)
                    },
                    enabled = extractPassword.isNotEmpty(),
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { extractPasswordDialog = false; pendingArchiveUri = null }) { Text("Cancel") }
            },
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
            // P0.10: merge order must follow selection order, not the folder listing's order.
            sources = orderedBySelection(entries, selectedUris).filter { it.kind == EntryKind.PDF }.map { it.uri },
            service = pdfTools,
            onDismiss = { pdfDialog = false },
            onExport = { pages, ocr ->
                pdfDialog = false
                pendingPdfPages = pages
                pendingPdfOcr = ocr
                pendingPdfMerge = false
                pdfOutputCreator.launch(pdfPagesFileName(System.currentTimeMillis()))
            },
            onMerge = { ocr ->
                pdfDialog = false
                pendingPdfPages = emptyList()
                pendingPdfOcr = ocr
                pendingPdfMerge = true
                pdfOutputCreator.launch(pdfMergedFileName(System.currentTimeMillis()))
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
    runningOperations: List<RunningOperation>,
    onCancelOperation: (String) -> Unit,
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

        if (runningOperations.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                runningOperations.forEach { operation ->
                    OperationProgressRow(operation, onCancel = { onCancelOperation(operation.id) })
                }
            }
            HorizontalDivider()
        }

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
                    FileCard(entry, entry.uri in selectedUris, entry.uri == focusedEntry?.uri, onOpen, onOpenExternal, onToggleSelection)
                }
            }
        } else {
            LazyColumn(state = listState) {
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

/** One row of an in-flight operation (P0.5): its label, a determinate progress bar when bytes or
 * item counts are known and an indeterminate one otherwise, and a Cancel button. */
@Composable
private fun OperationProgressRow(operation: RunningOperation, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(operation.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                operationProgressDetail(operation)?.let { detail ->
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, "Cancel ${operation.label}")
            }
        }
        val fraction = operationProgressFraction(operation)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun operationProgressFraction(operation: RunningOperation): Float? {
    val totalBytes = operation.totalBytes
    return when {
        totalBytes != null && totalBytes > 0 ->
            (operation.completedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        operation.itemCount > 0 ->
            (operation.itemIndex.toFloat() / operation.itemCount.toFloat()).coerceIn(0f, 1f)
        else -> null
    }
}

private fun operationProgressDetail(operation: RunningOperation): String? {
    val totalBytes = operation.totalBytes
    return when {
        totalBytes != null && totalBytes > 0 ->
            "${formatBytes(operation.completedBytes)} of ${formatBytes(totalBytes)}"
        operation.itemCount > 0 -> "Item ${operation.itemIndex} of ${operation.itemCount}"
        else -> null
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
    canPdfTools: Boolean,
    onPdfTools: () -> Unit,
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
            ActionButton(Icons.Outlined.PictureAsPdf, "PDF tools", onPdfTools, canPdfTools)
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
private fun BatchRenameDialog(
    selection: List<Pair<Uri, String>>,
    planPreview: (String) -> List<io.github.mbaliga.fylz.operations.BatchRenamePlan>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var prefix by remember { mutableStateOf("File-") }
    // planBatchRename is pure (no I/O) but throws on a prefix it can't turn into legal names
    // (P0.4) -- runCatching turns that into an inline error instead of crashing the dialog.
    val preview = remember(prefix, selection) { runCatching { planPreview(prefix) } }
    val error = preview.exceptionOrNull()?.message
    val plans = preview.getOrNull().orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch rename ${selection.size} items") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    prefix,
                    { prefix = it },
                    label = { Text("Prefix") },
                    isError = error != null,
                    supportingText = { if (error != null) Text(error) },
                    singleLine = true,
                )
                if (plans.isNotEmpty()) {
                    LazyColumn(Modifier.height(240.dp)) {
                        items(plans, key = { it.source.toString() }) { plan ->
                            Text(
                                "${plan.oldName} → ${plan.newName}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(prefix) }, enabled = error == null && plans.isNotEmpty()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecycleBinDialog(
    records: List<io.github.mbaliga.fylz.operations.RecycleRecord>,
    legacyBinCount: Int,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onTidyLegacyBins: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recycle Bin") },
        text = {
            Column {
                if (legacyBinCount > 0) {
                    Text(
                        "Found $legacyBinCount recycle folder(s) left over from before this app kept their " +
                            "hidden name. Their items already show below and are safe where they are.",
                    )
                    TextButton(onClick = onTidyLegacyBins) { Text("Tidy legacy recycle folders") }
                    HorizontalDivider()
                }
                if (records.isEmpty()) {
                    Text("Recycle Bin is empty. Items are never removed automatically.")
                } else {
                    TextButton(onClick = onEmptyRecycleBin) { Text("Empty Recycle Bin") }
                    LazyColumn(Modifier.height(360.dp)) {
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** A masked secret field with a show/hide toggle (P0.10) -- used for anything that shoulder-surfing
 * shouldn't reveal: an AI provider API key, a WebDAV password, an archive password. */
@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide $label" else "Show $label",
                )
            }
        },
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
                PasswordField(key, { key = it }, label = "API key")
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
                PasswordField(password, { password = it }, label = "Password")
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
 * The left room: every open location, plus the ways to get another one.
 *
 * This replaces the numbered chip row that used to sit above the file list. The chips were a
 * second navigation surface stacked on the first, they mis-aligned on device, and — the deeper
 * problem — they made "which folder am I in" a horizontal scroll through abbreviations. As rows
 * in the word wheel the same locations are readable, and reaching them is the same gesture as
 * everything else in the app.
 *
 * The wheel's `selectedId` uses a sentinel for the home surface rather than a nullable id: the
 * rail always has exactly one focused row, and "no tab open" is a real place in this app (the
 * storage home screen), not the absence of one.
 */
@Composable
private fun LocationsRoom(
    tabs: List<FolderTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onOpenHome: () -> Unit,
    onClose: (FolderTab) -> Unit,
    onAdd: () -> Unit,
) {
    val items = remember(tabs) {
        buildList {
            add(WheelItem(HOME_WHEEL_ID, "Home"))
            tabs.forEach { add(WheelItem(it.id, it.current.name.ifBlank { "Folder" })) }
            add(WheelItem(ADD_WHEEL_ID, "Add a location…"))
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding()
            .padding(start = 24.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
    ) {
        WordWheelRail(
            items = items,
            selectedId = activeTabId ?: HOME_WHEEL_ID,
            onSelect = { id ->
                when (id) {
                    HOME_WHEEL_ID -> onOpenHome()
                    ADD_WHEEL_ID -> onAdd()
                    else -> onSelect(id)
                }
            },
            inkColor = MaterialTheme.colorScheme.onSurface,
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            trailing = { item ->
                // Only the focused row gets a trailing slot, so closing is offered for the
                // location you are actually looking at — which is also the only one where
                // "close" has an unambiguous meaning.
                tabs.firstOrNull { it.id == item.id }?.let { tab ->
                    IconButton(onClick = { onClose(tab) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Close ${tab.current.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}

/** What a row in the tools room does. */
private enum class ToolsAction { RECYCLE_BIN, REMOTES, WEBDAV, TOOLS, INDEX }

/**
 * The right room: the app's own tools and settings.
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
private fun ToolsRoom(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAction: (ToolsAction) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RoomHeading("Tools")
        ToolsRow("Recycle Bin") { onAction(ToolsAction.RECYCLE_BIN) }
        ToolsRow(stringResource(R.string.remotes_title)) { onAction(ToolsAction.REMOTES) }
        ToolsRow("Quick WebDAV listing") { onAction(ToolsAction.WEBDAV) }
        ToolsRow(stringResource(R.string.tools_title)) { onAction(ToolsAction.TOOLS) }
        ToolsRow(stringResource(R.string.tools_index)) { onAction(ToolsAction.INDEX) }

        Spacer(Modifier.size(20.dp))
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
                // marker the rail uses, so the two rooms read as one app.
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

/** The storage home surface's row in the locations wheel. Not a tab, but a real destination. */
private const val HOME_WHEEL_ID = "__home__"

/** The picker's row. A verb in a list of nouns, which is why it sits at the end. */
private const val ADD_WHEEL_ID = "__add__"
