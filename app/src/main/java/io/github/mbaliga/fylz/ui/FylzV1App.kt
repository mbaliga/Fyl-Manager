package io.github.mbaliga.fylz.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.browse.sortEntries
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.data.SaveResult
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.library.SavedSearch
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ClipboardMode
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.FylzClipboard
import io.github.mbaliga.fylz.model.isBrowsableArchive
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.network.RemoteConnectionStore
import io.github.mbaliga.fylz.network.WebDavConfig
import io.github.mbaliga.fylz.network.WebDavService
import io.github.mbaliga.fylz.FylzApplication
import io.github.mbaliga.fylz.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.ConflictedItem
import io.github.mbaliga.fylz.operations.DocNode
import io.github.mbaliga.fylz.operations.findConflicts
import io.github.mbaliga.fylz.operations.FileOperationType
import io.github.mbaliga.fylz.operations.FileTools
import io.github.mbaliga.fylz.operations.OperationProgress
import io.github.mbaliga.fylz.operations.PreflightPolicy
import io.github.mbaliga.fylz.operations.PreflightResult
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.operations.RunningOperation
import io.github.mbaliga.fylz.operations.gatherPreflightItems
import io.github.mbaliga.fylz.operations.isStagingName
import io.github.mbaliga.fylz.pdf.PdfPageReference
import io.github.mbaliga.fylz.pdf.PdfPageTools
import io.github.mbaliga.fylz.preview.resolvePreviewKind
import io.github.mbaliga.fylz.search.RecursiveSearchEngine
import io.github.mbaliga.fylz.search.SearchHit
import io.github.mbaliga.fylz.search.SearchMatchSource
import io.github.mbaliga.fylz.search.SearchProgress
import io.github.mbaliga.fylz.search.SearchQuery
import io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider
import io.github.mbaliga.fylz.storage.FylzFilesDocumentsProvider
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.VolumeInfoResolver
import io.github.mbaliga.fylz.ui.components.ConflictSheet
import io.github.mbaliga.fylz.ui.components.DestinationChooserSheet
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.ExternalDocumentDialog
import io.github.mbaliga.fylz.ui.components.FloatingPreviewPane
import io.github.mbaliga.fylz.ui.components.PasswordField
import io.github.mbaliga.fylz.ui.components.PasswordPromptDialog
import io.github.mbaliga.fylz.ui.components.PermanentDeleteConfirmationDialog
import io.github.mbaliga.fylz.ui.components.PreflightSheet
import io.github.mbaliga.fylz.ui.components.PreviewPane
import io.github.mbaliga.fylz.ui.components.totalKnownBytes
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.outlined.Close
import io.github.mbaliga.fylz.browse.entryStops
import dev.aarso.cellshell.EdgeTimelineScrubber
import dev.aarso.cellshell.RoomEdge
import dev.aarso.cellshell.ShakeToRefresh
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionRegistry
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.ActionTarget
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.BrowserStateInputs
import io.github.mbaliga.fylz.actions.BuiltInActions
import io.github.mbaliga.fylz.actions.buildBrowserState
import io.github.mbaliga.fylz.actions.GestureId
import io.github.mbaliga.fylz.actions.KeyChord
import io.github.mbaliga.fylz.actions.KeyRouter
import io.github.mbaliga.fylz.actions.Routed
import io.github.mbaliga.fylz.actions.RoomId
import io.github.mbaliga.fylz.ui.actions.CommandPaletteDialog
import io.github.mbaliga.fylz.ui.actions.CompressFlowHost
import io.github.mbaliga.fylz.ui.actions.ExtractFlowHost
import io.github.mbaliga.fylz.ui.actions.LibraryRailRoom
import io.github.mbaliga.fylz.ui.actions.rememberArchiveEditFlow
import io.github.mbaliga.fylz.ui.actions.rememberCompressFlow
import io.github.mbaliga.fylz.ui.actions.rememberExtractFlow
import io.github.mbaliga.fylz.ui.actions.LocationsRoom
import io.github.mbaliga.fylz.ui.actions.NavigateUpButton
import io.github.mbaliga.fylz.ui.actions.RecoveryRoom
import io.github.mbaliga.fylz.ui.actions.RegistryProblemsDialog
import io.github.mbaliga.fylz.ui.actions.SelectAllButton
import io.github.mbaliga.fylz.ui.actions.SelectionActionBar
import io.github.mbaliga.fylz.ui.actions.ToolsRoom
import io.github.mbaliga.fylz.ui.actions.SortMenuButton
import io.github.mbaliga.fylz.ui.actions.TopAppBarActions
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo

enum class PendingDestinationAction { COPY, MOVE, EXTRACT }

/** `fylz.select.toggle`'s id (design MC.0e, §2.6): the row/grid checkbox's own click, which isn't
 * itself one of the four `GestureId`s, dispatches straight to this id rather than through
 * `ActionDispatcher.gesture`. */
private val SELECT_TOGGLE_ID = ActionId.parse("fylz.select.toggle")

/** The three room-open gesture actions' ids (design §2.6): what `registry.edgeRooms()` maps each
 * `GestureId.EDGE_*` to, and so the keys `edgeRoomContent` looks its content up by. */
private val ROOM_LOCATIONS_ID = ActionId.parse("fylz.room.locations")
private val ROOM_TOOLS_ID = ActionId.parse("fylz.room.tools")
private val ROOM_RECOVERY_ID = ActionId.parse("fylz.room.recovery")

/** The `inotify` events worth a listing refresh for (P1.11) -- deliberately excludes
 *  ACCESS/OPEN/CLOSE_NOWRITE, which fire on every read (this app's own thumbnail loads and
 *  preview opens included) and would turn "watch the folder" into "refresh on every glance". */
private const val WATCHED_FOLDER_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
    FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.MODIFY or
    FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

/** Selected entries in the order the user actually selected them (P0.10) -- [selectedUris]
 * preserves insertion order at runtime (every mutation site builds it via `Set.plus`/`.minus`,
 * which the stdlib backs with a `LinkedHashSet`), but a plain `entries.filter { it.uri in
 * selectedUris }` derives order from the folder listing instead, breaking the ordering promise
 * a dialog like PDF merge makes. */
internal fun orderedBySelection(entries: List<FileEntry>, selectedUris: Set<Uri>): List<FileEntry> =
    selectedUris.mapNotNull { uri -> entries.find { it.uri == uri } }

/** P1.8: the clipboard chip's own label -- "N item(s) cut"/"N item(s) copied". */
internal fun clipboardChipLabel(clipboard: FylzClipboard): String {
    val noun = if (clipboard.entries.size == 1) "item" else "items"
    val verb = if (clipboard.mode == ClipboardMode.CUT) "cut" else "copied"
    return "${clipboard.entries.size} $noun $verb"
}

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

/** A copy or move waiting on [io.github.mbaliga.fylz.ui.components.PreflightSheet] (P1.5) because
 * [PreflightPolicy.evaluate] found a problem -- an illegal name, a collision, a file too large for
 * the destination, or not enough free space -- before the transfer actually starts. [sources] and
 * [destination] are exactly what would otherwise have gone straight to
 * [io.github.mbaliga.fylz.operations.OperationRunner.enqueueTransfer]. */
private data class PreflightRequest(
    val result: PreflightResult,
    val action: PendingDestinationAction,
    val sources: List<Uri>,
    val destination: Uri,
)

/** A copy or move waiting on [io.github.mbaliga.fylz.ui.components.ConflictSheet] (P1.6) because
 * one or more top-level [sources] already have a same-named sibling at [destination] -- checked
 * after Preflight (P1.5), against [sources]/[nameOverrides] exactly as Preflight left them, so a
 * renamed or skipped item is checked (or not checked) correctly here too. */
private data class ConflictRequest(
    val conflicts: List<ConflictedItem>,
    val action: PendingDestinationAction,
    val sources: List<Uri>,
    val destination: Uri,
    val archiveUri: Uri?,
    val nameOverrides: Map<Uri, String>,
)

/** A Copy to…/Move to… waiting on [io.github.mbaliga.fylz.ui.components.DestinationChooserSheet]
 * (P1.8) for a destination -- [sources] is snapshotted at the moment the sheet opens, the same way
 * [PreflightRequest]/[ConflictRequest] snapshot theirs, so a selection change while the sheet is up
 * (it can't happen today -- the sheet is a full-screen [androidx.compose.ui.window.Dialog] -- but
 * matching their own shape costs nothing and keeps the three requests reasoned about the same way)
 * never changes what actually gets transferred. */
private data class DestinationChooserRequest(
    val action: PendingDestinationAction,
    val sources: List<Uri>,
    val archiveUri: Uri?,
)

/**
 * P1.5: resolves [destinationTreeUri]'s [io.github.mbaliga.fylz.storage.VolumeInfo] and gathers
 * [sources] into [io.github.mbaliga.fylz.operations.PreflightItem]s, then runs the pure
 * [PreflightPolicy] over both. A caller that can't afford to block on this (nothing here is fast:
 * a real recursive size walk, a `/proc/self/mounts` read, a `StatFs` call) should run it off the
 * main thread, which is why this itself already does -- see [android.content.Context] used only
 * for provider/file resolution, never anything UI-scoped.
 */
private suspend fun runPreflight(context: Context, sources: List<Uri>, destinationTreeUri: Uri): PreflightResult =
    withContext(Dispatchers.IO) {
        val destinationDocumentUri = DocNode.resolveDestinationUri(destinationTreeUri)
        val destinationPath = FylzFilesDocumentsProvider.fileFor(context, destinationDocumentUri)
        val volumeInfo = VolumeInfoResolver.resolve(context, destinationTreeUri, destinationPath)
        val items = gatherPreflightItems(context.contentResolver, sources)
        PreflightPolicy.evaluate(items, volumeInfo)
    }

/**
 * The app, and the owner of its theme.
 *
 * [overlays] is composed *inside* [FylzTheme] on purpose. Recovery used to be a sibling screen
 * under a bare `MaterialTheme`, which is why it — like the Tools and Index activities — arrived
 * light inside an otherwise dark app. Content that belongs to Fylz is rendered by Fylz's theme;
 * there is no second place for that decision to be made. The Recovery room itself is now built by
 * [FylzV1Workspace] straight from the action registry (design MC.0d), the same way the Locations
 * and Tools rooms are, rather than handed down as a composable slot.
 *
 * @param overlays dialogs the caller owns and needs drawn over everything.
 */
@Composable
fun FylzV1App(
    viewUri: Uri? = null,
    onShowHistory: () -> Unit = {},
    operationsNeedingAttention: Int = 0,
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
            viewUri = viewUri,
            onShowHistory = onShowHistory,
            operationsNeedingAttention = operationsNeedingAttention,
        )
        overlays()
    }
}

@Composable
private fun FylzV1Workspace(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    viewUri: Uri? = null,
    onShowHistory: () -> Unit = {},
    operationsNeedingAttention: Int = 0,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val repository = remember { DocumentRepository(context.applicationContext) }
    val recycleBin = remember { RecycleBinService(context.applicationContext) }
    val archiveService = remember { ArchiveService(context.applicationContext, (context.applicationContext as FylzApplication).decoderClient) }
    // M3.9: shared with ArchiveToolsOverlay.kt's own EXTRACT purpose.
    val archivePasswordSession = remember { (context.applicationContext as FylzApplication).archivePasswordSession }
    val fileTools = remember { FileTools(context.applicationContext) }
    val library = remember { LibraryStore(context.applicationContext) }
    val aiVault = remember { ApiKeyVault(context.applicationContext) }
    val aiClient = remember { AiClient(aiVault) }
    val webDav = remember { WebDavService() }
    val pdfTools = remember { PdfPageTools(context.applicationContext) }
    val remoteStore = remember { RemoteConnectionStore(context.applicationContext) }
    val searchEngine = remember { RecursiveSearchEngine(context.applicationContext) }
    val operationRunner = remember {
        (context.applicationContext as FylzApplication).operationRunner
    }
    val runningOperations by operationRunner.operations.collectAsState()
    val recycleRecords by recycleBin.records.collectAsState()

    // P1.10: tabs, activeTabId, selectedUris, query, viewMode, previewMode, sortSpec,
    // searchRecursive and clipboard all live in BrowserViewModel now, not in this composable's own
    // remember{} state -- delegated to local names via `by viewModel::x` (Kotlin's "delegate to
    // another property" feature) so every existing read/write below is unchanged; only where the
    // value actually lives changed. One BrowserViewModel per Activity survives exactly what
    // remember{} state didn't: a config change, and a process death the platform chooses to
    // restore -- see BrowserViewModel's own KDoc.
    val viewModel: BrowserViewModel = viewModel()
    val tabs = viewModel.tabs
    var activeTabId by viewModel::activeTabId
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var legacyBinNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedUris by viewModel::selectedUris
    var focusedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var query by viewModel::query
    var viewMode by viewModel::viewMode
    var previewMode by viewModel::previewMode
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewTruncated by remember { mutableStateOf(false) }
    var previewEncodingOk by remember { mutableStateOf(true) }
    var previewHasBom by remember { mutableStateOf(false) }
    var editorValue by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    var pendingDestinationAction by remember { mutableStateOf<PendingDestinationAction?>(null) }
    var pendingArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var extractPasswordDialog by remember { mutableStateOf(false) }
    var extractPassword by remember { mutableStateOf<CharArray?>(null) } // M3.9: a CharArray, wiped after use below.
    // M3.9: a remembered password skips the dialog; a counter, not a flag, so the effect below
    // re-fires for a second archive chosen while a first auto-launch is still pending.
    var legacyExtractAutoLaunch by remember { mutableIntStateOf(0) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf<String?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var batchRenameDialog by remember { mutableStateOf(false) }
    var recycleDialog by remember { mutableStateOf(false) }
    var permanentDeleteRequest by remember { mutableStateOf<PermanentDeleteRequest?>(null) }
    var pendingPreflight by remember { mutableStateOf<PreflightRequest?>(null) }
    var pendingConflict by remember { mutableStateOf<ConflictRequest?>(null) }
    var pendingDestinationChooser by remember { mutableStateOf<DestinationChooserRequest?>(null) }
    // P1.8: survives folder/tab navigation on purpose -- unlike selectedUris, nothing here resets
    // it, so Cut/Copy in one folder and Paste in another actually works. Owned by BrowserViewModel
    // (P1.10) like the rest of this block, but NOT part of its persisted session -- see
    // BrowserViewModel's own KDoc for why a clipboard cut/copy is the one piece of this state that
    // still doesn't survive a forced process kill.
    var clipboard by viewModel::clipboard
    var externalDocument by remember { mutableStateOf<FileEntry?>(null) }
    var commandPaletteOpen by remember { mutableStateOf(false) }
    var showRegistryProblemsDialog by remember { mutableStateOf(false) }
    // MC.0e (design §2.6): the one flag KeyRouter's `textFieldFocused` guard reads, fed by the
    // search field's and the command palette's own `onFocusChanged`.
    var textFieldFocused by remember { mutableStateOf(false) }
    var aiDialog by remember { mutableStateOf(false) }
    var webDavDialog by remember { mutableStateOf(false) }
    var remoteDialog by remember { mutableStateOf(false) }
    var pdfDialog by remember { mutableStateOf(false) }
    var pendingPdfPages by remember { mutableStateOf<List<PdfPageReference>>(emptyList()) }
    var pendingPdfOcr by remember { mutableStateOf(false) }
    var pendingPdfMerge by remember { mutableStateOf(false) }
    var duplicateResult by remember { mutableStateOf<String?>(null) }
    var sortSpec by viewModel::sortSpec
    var searchRecursive by viewModel::searchRecursive
    var searchProgress by remember { mutableStateOf<SearchProgress?>(null) }
    var homeRefreshKey by remember { mutableIntStateOf(0) }
    // P1.10: guards the folder-change reset effect below against wiping out a just-restored
    // selectedUris on its own first run -- see that effect's own comment.
    var selectionResetArmed by remember { mutableStateOf(false) }

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
    // P1.11: computed in a LaunchedEffect rather than a synchronous remember{} block, so a
    // 100,000-entry folder's filter+sort runs off the main thread instead of blocking whatever
    // recomposition reads it -- "sort once, off the main thread" per the master plan. Dispatchers
    // .Default, not .IO: this is pure in-memory computation, not blocking I/O.
    var visibleEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    LaunchedEffect(entries, query, searchRecursive, sortSpec) {
        visibleEntries = withContext(Dispatchers.Default) {
            val filtered = if (query.isBlank() || searchRecursive) {
                entries
            } else {
                val parsed = SearchQuery.parse(query)
                entries.filter { parsed.matchesMetadata(it) && parsed.matchesName(it.name) }
            }
            sortEntries(filtered, sortSpec)
        }
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
        (context.applicationContext as FylzApplication).archiveCatalog.forgetFailures()
    }

    // M3.4c (design §2.1-2.2): the whole selective-extract flow; a legacy encrypted ZIP still uses the pre-M3.4 password + destination-picker path below (ArchiveService.extractZip, zip4j).
    val extractFlow = rememberExtractFlow(
        context, scope, operationRunner, currentFolder = { activeTab?.current?.uri }, persistTreePermission = repository::persistTreePermission,
        onLegacyEncryptedZip = { archive ->
            pendingArchiveUri = archive.source
            val remembered = archivePasswordSession.passwordFor(archive.source)
            if (remembered != null) {
                extractPassword = remembered
                pendingDestinationAction = PendingDestinationAction.EXTRACT
                legacyExtractAutoLaunch += 1
            } else {
                extractPassword = null
                extractPasswordDialog = true
            }
        },
        onToast = ::toast, onExtracted = { toast("Extracted"); refresh() },
    )

    // M3.5 (design §2.1-2.2): the whole compress flow; an AES-protected ZIP still uses the
    // pre-M3.5 password path in ArchiveToolsOverlay.kt (ArchiveService.createZip, zip4j).
    val compressFlow = rememberCompressFlow(
        context, scope, operationRunner, persistTreePermission = repository::persistTreePermission,
        onToast = ::toast, onCompressed = { toast("Compressing"); refresh() },
    )
    val archiveEditFlow = rememberArchiveEditFlow(context, scope, operationRunner, onToast = ::toast, onEdited = { toast("Archive updated"); refresh() }) // M3.6

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
        // P0.10/P1.10: only a tab the user actually opened is remembered for restoration -- a
        // copy/move/extract destination, backup folder or index folder persists its own grant
        // (repository.persistTreePermission) without ever calling openTabAt, so it stays out.
        // No explicit "record" call needed here any more (P1.10): tabs IS the BrowserViewModel's
        // own state, so adding to it here already feeds the session BrowserViewModel persists.
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

    // Copy and move (P1.2) run as durable WorkManager work instead of only on the app-scoped
    // OperationRunner (P0.5, A3) directly; extract still runs there. Either way this only awaits
    // the result to update UI state on completion, so rotating away mid-transfer never cancels
    // it. Pulled out of destinationPicker's own callback (P1.5) so both the clean-preflight path
    // and the PreflightSheet's own "Continue" can call it with adjusted sources/nameOverrides.
    suspend fun runDestinationAction(
        action: PendingDestinationAction,
        sources: List<Uri>,
        destination: Uri,
        archiveUri: Uri?,
        nameOverrides: Map<Uri, String>,
        conflictResolutions: Map<Uri, ConflictPolicy>,
    ) {
        runCatching {
            when (action) {
                // P1.6: SKIP, not KEEP_BOTH, is the batch-level fallback here -- every real
                // conflict was already resolved per item by checkConflictsAndProceed below before
                // this ever runs, so this value only matters for a conflict that somehow wasn't
                // pre-resolved (a race, or a caller that skipped the check entirely), and silently
                // duplicating a file the user never approved is the wrong default for that case.
                PendingDestinationAction.COPY -> operationRunner.enqueueTransfer(
                    FileOperationType.COPY,
                    "Copying",
                    sources,
                    destination,
                    ConflictPolicy.SKIP,
                    nameOverrides,
                    conflictResolutions,
                )
                PendingDestinationAction.MOVE -> operationRunner.enqueueTransfer(
                    FileOperationType.MOVE,
                    "Moving",
                    sources,
                    destination,
                    ConflictPolicy.SKIP,
                    nameOverrides,
                    conflictResolutions,
                )
                PendingDestinationAction.EXTRACT -> operationRunner.run(FileOperationType.EXTRACT, "Extracting") {
                    archiveService.extractZip(
                        archiveUri = archiveUri ?: error("Choose an archive."),
                        destinationTreeUri = destination,
                        password = extractPassword,
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
            extractPassword = null // M3.9: already wiped by ArchiveService.extractZip's own finally.
            refresh()
        }.onFailure {
            extractPassword = null
            toast(it.message ?: "Operation failed")
        }
    }

    // P1.6: the second interactive gate, after Preflight -- checked against the POST-preflight
    // sources/names (a renamed item's collision check must use its new name, and a skipped item
    // must never be checked at all), so the two sheets compose correctly in sequence rather than
    // racing each other's view of what's actually about to be copied.
    suspend fun checkConflictsAndProceed(
        action: PendingDestinationAction,
        sources: List<Uri>,
        destination: Uri,
        archiveUri: Uri?,
        nameOverrides: Map<Uri, String>,
    ) {
        if (action != PendingDestinationAction.COPY && action != PendingDestinationAction.MOVE) {
            runDestinationAction(action, sources, destination, archiveUri, nameOverrides, emptyMap())
            return
        }
        val conflicts = runCatching {
            findConflicts(context.contentResolver, sources, destination, nameOverrides)
        }.getOrNull()
        if (conflicts.isNullOrEmpty()) {
            runDestinationAction(action, sources, destination, archiveUri, nameOverrides, emptyMap())
        } else {
            pendingConflict = ConflictRequest(conflicts, action, sources, destination, archiveUri, nameOverrides)
        }
    }

    // P1.5/P1.8: the shared entry point once a destination is actually known, whether it came from
    // the system OpenDocumentTree picker, an in-app DestinationChooserSheet pick, or a clipboard
    // Paste -- runs Preflight (P1.5) then the conflict check (P1.6) before ever enqueuing a real
    // transfer. Pulled out of destinationPicker's own callback so every entry point shares it.
    fun beginTransfer(action: PendingDestinationAction, sources: List<Uri>, destination: Uri, archiveUri: Uri?) {
        if (action == PendingDestinationAction.COPY || action == PendingDestinationAction.MOVE) {
            ensureNotificationPermissionRequested()
            // P1.5: checked before the transfer ever starts -- a problem this catches would
            // otherwise only surface mid-copy, one item at a time, as a bare provider exception,
            // with no chance to fix the name or leave just that item out first. A failure in the
            // check itself (never seen in practice, but this is advisory, not a safety gate) is
            // not a reason to block an otherwise-normal copy or move.
            scope.launch {
                val result = runCatching { runPreflight(context, sources, destination) }.getOrNull()
                if (result == null || result.isClean) {
                    checkConflictsAndProceed(action, sources, destination, archiveUri, emptyMap())
                } else {
                    pendingPreflight = PreflightRequest(result, action, sources, destination)
                }
            }
        } else {
            scope.launch { runDestinationAction(action, sources, destination, archiveUri, emptyMap(), emptyMap()) }
        }
    }

    // P1.8: Paste always targets the active tab's OWN current folder (its current document, not
    // its tree's root -- DocNode.resolveDestinationUri, wired into runPreflight/findConflicts/
    // FileOperationService, is what makes that actually land correctly), never a picker. A Cut
    // clipboard clears once its move is under way, since its items no longer exist at the URIs it
    // recorded; a Copy clipboard stays, so the same items can be pasted into more than one place.
    fun pasteClipboard() {
        val cb = clipboard ?: return
        val tab = activeTab ?: return
        val action = if (cb.mode == ClipboardMode.CUT) PendingDestinationAction.MOVE else PendingDestinationAction.COPY
        beginTransfer(action, cb.entries.map { it.uri }, tab.current.uri, null)
        if (cb.mode == ClipboardMode.CUT) clipboard = null
    }

    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { destination ->
        val action = pendingDestinationAction
        pendingDestinationAction = null
        if (destination == null || action == null) return@rememberLauncherForActivityResult
        repository.persistTreePermission(destination)
        beginTransfer(action, selectedEntries.map { it.uri }, destination, pendingArchiveUri)
    }

    // M3.9: a remembered password (found by onLegacyEncryptedZip above) skips straight to this.
    LaunchedEffect(legacyExtractAutoLaunch) {
        if (legacyExtractAutoLaunch > 0) destinationPicker.launch(null)
    }

    // Destination for PDF page extraction / merge. Kept separate from the compress flow so the
    // two cannot ever write into each other's target.
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
                        pdfTools.export(
                            references = pages,
                            destinationUri = destination,
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

    // P1.10: session restoration (tabs, each one's full navigation stack, the active tab, sort,
    // view, preview mode and search) now happens once, synchronously, inside BrowserViewModel's
    // own init -- before this composable's first frame -- rather than in a LaunchedEffect here.
    // See BrowserViewModel.restoreSession for the equivalent of what this block used to do (it
    // still filters out a tab whose grant the OS has since revoked), now working from the full
    // persisted session rather than only each tab's bare root Uri.

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
        if (!searchRecursive || query.isBlank() || tab == null || ArchiveDocumentsProvider.isArchiveUri(tab.current.uri)) {
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
        // P1.10: this effect is brand new on every fresh composition -- including one just
        // restored by BrowserViewModel after a rotation or an OS-restored process death, where
        // selectedUris already carries what the user had selected before it happened. Its FIRST
        // run in a composition must not wipe that out; only a SECOND run (a real folder change or
        // an explicit refresh, both of which recompose this same effect within the same
        // composition) means the old folder's selection/focus/preview is genuinely stale.
        if (selectionResetArmed) {
            selectedUris = emptySet()
            focusedEntry = null
            previewText = null
        }
        selectionResetArmed = true
        if (activeTab == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val tab = activeTab
        runCatching {
            // P1.11: paged -- entries updates as soon as the first batch arrives (the browser
            // paints long before a 100,000-entry folder's own cursor walk finishes), not only
            // once the whole folder has been read.
            repository.listChildren(tab.treeUri, tab.current.uri).collect { batch ->
                entries = batch.entries.filterNot { item ->
                    item.name == ".fylz-trash" || item.name in legacyBinNames || isStagingName(item.name)
                }
                if (batch.complete) loading = false
            }
        }.onFailure {
            loading = false
            toast(it.message ?: "Unable to read folder")
        }
    }

    // P1.11: watches the visible folder for changes made OUTSIDE this app (another app writing
    // through FylzFilesDocumentsProvider, adb, a sync client) -- only possible for a File-backed
    // tab (tab.treeUri.authority == FylzFilesDocumentsProvider.AUTHORITY), the same check
    // operations/DestinationClassifier.kt already uses for "is this our own provider". A foreign
    // SAF provider gives this app no real filesystem path to watch, so those tabs simply get no
    // observer -- refresh() (the pull-to-refresh action, and the P1.9 root-level flag work) is
    // still how they notice external changes.
    DisposableEffect(activeTab?.treeUri, activeTab?.current?.uri) {
        val tab = activeTab
        val watchedFile = if (tab != null && tab.treeUri.authority == FylzFilesDocumentsProvider.AUTHORITY) {
            FylzFilesDocumentsProvider.fileFor(context, tab.current.uri)
        } else {
            null
        }
        val observer = watchedFile?.let { file ->
            object : FileObserver(file, WATCHED_FOLDER_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    // Not the main thread (inotify delivers on its own thread) -- scope.launch
                    // hands the actual refreshKey mutation back to Compose's own dispatcher.
                    scope.launch { refresh() }
                }
            }
        }
        observer?.startWatching()
        onDispose { observer?.stopWatching() }
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
        if (entry.isDirectory || entry.isBrowsableArchive) {
            val tab = activeTab ?: return
            val index = tabs.indexOfFirst { it.id == tab.id }
            // M3.3: an archive pushes its provider root as the location; the fifth nesting level is refused with a toast.
            val uri = if (entry.isDirectory) entry.uri else runCatching { ArchiveDocumentsProvider.rootUri(entry) }.getOrElse { toast(it.message ?: "Unable to open this archive"); return }
            if (index >= 0) {
                tabs[index] = tab.copy(locations = tab.locations + FolderLocation(uri, entry.name))
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

    // MC.0a (design §2.8 item 1): the registry and its BrowserState/ActionContext wiring are
    // constructed and tested here, but nothing renders from them yet -- every menu below still
    // calls its own lambdas directly, unchanged. `recycleSelection`/`refresh` are captured through
    // a differently-named reference so the ActionContext overrides of the same name don't recurse
    // into themselves.
    val doRecycleSelection = ::recycleSelection
    val doRefresh = ::refresh

    // Not `remember`-wrapped: its methods close over plain (non-`remember`ed) locals recomputed on
    // every recomposition (`selectedEntries`, `activeTab`, …) -- a keyless `remember` would freeze
    // those at first composition, and keying it on everything it touches would rebuild it just as
    // often anyway, so a fresh object per recomposition is both simpler and actually correct.
    val actionContext = object : ActionContext {
        override fun cut() { clipboard = FylzClipboard(ClipboardMode.CUT, selectedEntries) }
        override fun copy() { clipboard = FylzClipboard(ClipboardMode.COPY, selectedEntries) }
        override fun copyTo() {
            pendingDestinationChooser = DestinationChooserRequest(PendingDestinationAction.COPY, selectedEntries.map { it.uri }, null)
        }
        override fun moveTo() {
            pendingDestinationChooser = DestinationChooserRequest(PendingDestinationAction.MOVE, selectedEntries.map { it.uri }, null)
        }
        override fun recycleSelection() { archiveEditFlow.recycleOrDelegate(activeTab, selectedEntries, doRecycleSelection) }
        override fun rename() { archiveEditFlow.renameOrDelegate(activeTab, selectedEntries) { renameDialog = true } }
        override fun tags() { tagDialog = true }
        override fun compress() { compressFlow.openSheet(selectedEntries.map { it.uri }) }
        override fun extract() { val entry = selectedEntries.singleOrNull() ?: return; extractFlow.openMenu(ArchiveRef(entry.uri, emptyList())) }
        override fun extractHere() { ensureNotificationPermissionRequested(); extractFlow.extractHere() }
        override fun extractIntoFolder() { ensureNotificationPermissionRequested(); extractFlow.extractIntoFolder() }
        override fun extractTo() { ensureNotificationPermissionRequested(); extractFlow.extractTo() }
        override fun extractSelected() {
            val archive = activeTab?.let { tab -> runCatching { ArchiveDocumentId.parse(tab.current.uri).archive }.getOrNull() } ?: return
            val ids = selectedEntries.mapNotNull { entry -> runCatching { ArchiveDocumentId.parse(entry.uri) }.getOrNull() }
            ensureNotificationPermissionRequested(); extractFlow.extractSelected(archive, ids)
        }
        override fun openExtractMenu(archive: Uri) { ensureNotificationPermissionRequested(); extractFlow.openMenu(ArchiveRef(archive, emptyList())) }
        override fun openCompressMenu(sources: List<Uri>) { compressFlow.openSheet(sources) }
        override fun addArchiveEntries() { archiveEditFlow.addEntries(activeTab) }
        override fun batchRename() { batchRenameDialog = true }
        override fun pdfTools() { pdfDialog = true }
        override fun share() {
            val uris = ArrayList(selectedEntries.map { it.uri })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).setType(selectedEntries.first().mimeType).putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(intent, "Share files")) }
        }
        override fun clearSelection() { selectedUris = emptySet() }

        override fun paste() { pasteClipboard() }
        override fun clearClipboard() { clipboard = null }
        override fun toggleViewMode() { viewMode = if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID }
        override fun refresh() { doRefresh() }

        override fun newFolder() { createDialog = "folder" }
        override fun newFile() { createDialog = "file" }
        override fun scanToPdf() { startScan() }
        override fun findDuplicates() {
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
        }
        override fun aiOrganize() { aiDialog = true }

        override fun navigateUp() {
            val tab = activeTab ?: return
            val index = tabs.indexOfFirst { it.id == tab.id }
            if (index >= 0 && tab.locations.size > 1) {
                tabs[index] = tab.copy(locations = tab.locations.dropLast(1))
            }
        }
        override fun selectAll() { selectedUris = visibleEntries.map { it.uri }.toSet() }

        override fun setSortField(field: SortField) { sortSpec = sortSpec.withField(field) }
        override fun toggleFoldersFirst() { sortSpec = sortSpec.copy(foldersFirst = !sortSpec.foldersFirst) }

        override fun open(entry: FileEntry) { openEntry(entry) }
        override fun openWith(entry: FileEntry) { openExternal(entry) }
        override fun toggleSelected(entry: FileEntry) {
            selectedUris = if (entry.uri in selectedUris) selectedUris - entry.uri else selectedUris + entry.uri
            focusedEntry = entry.takeUnless(FileEntry::isDirectory)
        }

        override fun addTab() { shell.closeAll(); rootPicker.launch(null) }
        override fun closeTab(tabId: String) {
            val tab = tabs.firstOrNull { it.id == tabId } ?: return
            val wasActive = activeTabId == tab.id
            tabs.remove(tab)
            if (wasActive) activeTabId = tabs.lastOrNull()?.id
        }

        override fun openRoot() { activeTabId = null; homeRefreshKey += 1 }
        override fun toggleFavourite() {
            activeTab?.let { tab -> library.toggleFavorite(tab.current.uri, tab.current.name); doRefresh() }
        }
        override fun openRecycleBin() { shell.closeAll(); recycleDialog = true }

        override fun openRemotes() { shell.closeAll(); remoteDialog = true }
        override fun openWebDavQuick() { shell.closeAll(); webDavDialog = true }
        override fun openToolsActivity() {
            shell.closeAll()
            runCatching { context.startActivity(Intent(context, PostV1ToolsActivity::class.java)) }
                .onFailure { toast("Tools are unavailable on this build") }
        }
        override fun openIndexActivity() {
            shell.closeAll()
            runCatching { context.startActivity(Intent(context, IndexManagerActivity::class.java)) }
                .onFailure { toast("The index manager is unavailable") }
        }
        override fun setThemeMode(mode: ThemeMode) { onThemeModeChange(mode) }

        override fun showOperationHistory() { onShowHistory() }

        override fun openRoom(room: RoomId) {
            when (room) {
                RoomId.LOCATIONS -> shell.open(RoomEdge.LEFT)
                RoomId.TOOLS -> shell.open(RoomEdge.RIGHT)
                RoomId.RECOVERY -> shell.open(RoomEdge.BOTTOM)
                RoomId.LIBRARY_RAIL -> Unit
            }
        }

        override fun openCommandPalette() { commandPaletteOpen = true }

        override fun showRegistryProblems() { showRegistryProblemsDialog = true }
    }

    // Static across the app's lifetime -- BuiltInActions.all()'s `run` lambdas take
    // (ActionContext, BrowserState, ActionTarget?) as parameters; nothing here closes over this
    // composable's own local state, so remembering it once is always correct, not just cheap.
    val actionRegistry = remember { ActionRegistry(BuiltInActions.all()) }
    val actionResolver = remember(actionRegistry) { ActionResolver(actionRegistry) }
    val actionDispatcher = remember(actionRegistry) { ActionDispatcher(actionRegistry) }

    val browserState = remember(
        activeTab, entries, visibleEntries, selectedEntries, selectedUris, focusedEntry, clipboard,
        sortSpec, viewMode, previewMode, query, searchRecursive, themeMode, legacyBinNames,
        operationsNeedingAttention, refreshKey, activeTabId, actionRegistry,
    ) {
        buildBrowserState(
            BrowserStateInputs(
                activeTab = activeTab, activeTabId = activeTabId, entries = entries, visibleEntries = visibleEntries,
                selection = selectedEntries, selectionOrder = selectedUris, focused = focusedEntry, clipboard = clipboard,
                sortSpec = sortSpec, viewMode = viewMode, previewMode = previewMode, query = query, searchRecursive = searchRecursive,
                themeMode = themeMode, favouriteUris = library.favorites().map { it.uri }, legacyBinCount = legacyBinNames.size,
                operationsNeedingAttention = operationsNeedingAttention, registryProblemCount = actionRegistry.problems.size, isZipFamilyArchiveLocation = archiveEditFlow.isZipFamilyArchiveLocation(activeTab),
            ),
        )
    }

    // Back closes an open room before it does anything else: a room is not a back-stack entry,
    // but Back is the gesture people reach for to leave one.
    BackHandler(enabled = !shell.atHome) { shell.closeAll() }

    // MC.0e (design §2.6): a focusable root so hardware-keyboard chords reach Compose at all in
    // touch mode, where nothing is focused otherwise. `onKeyEvent`, not `onPreviewKeyEvent`: the
    // latter runs *before* a focused child (the search field, say) sees the event, which would
    // turn that field's own Delete/Ctrl+V/Ctrl+A into recycle/paste/select-all.
    val rootFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, textFieldFocused) {
        // First composition (nothing focused yet), whenever the tracked text field gives up focus,
        // and whenever this window regains focus (a dialog -- its own Android window -- just
        // closed) all land here, so keys keep reaching the root once whatever stole focus is gone.
        if (windowFocused && !textFieldFocused) rootFocusRequester.requestFocus()
    }

    // Edge drags are declarative (design §2.6): SpatialShell still owns the drag itself -- it only
    // asks which content sits on which edge -- but *which* room that is comes from
    // `registry.edgeRooms()` rather than a hard-coded left/right/bottom assignment, so the registry
    // stays the one place that says "left = Locations".
    val edgeRooms = actionRegistry.edgeRooms()
    val edgeRoomContent: Map<ActionId, @Composable () -> Unit> = mapOf(
        ROOM_LOCATIONS_ID to {
            LocationsRoom(
                tabs = tabs,
                activeTabId = activeTabId,
                resolver = actionResolver,
                dispatcher = actionDispatcher,
                state = browserState,
                ctx = actionContext,
                onSelect = { id ->
                    activeTabId = id
                    shell.closeAll()
                },
                onOpenHome = {
                    activeTabId = null
                    homeRefreshKey += 1
                    shell.closeAll()
                },
            )
        },
        ROOM_TOOLS_ID to { ToolsRoom(actionResolver, actionDispatcher, browserState, actionContext) },
        ROOM_RECOVERY_ID to { RecoveryRoom(actionResolver, actionDispatcher, browserState, actionContext) },
    )

    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        scrimColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        cardColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val chord = KeyChord(
                    key = event.key,
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    meta = event.isMetaPressed,
                )
                when (val routed = KeyRouter.route(chord, textFieldFocused, actionRegistry, browserState)) {
                    is Routed.Dispatch -> {
                        actionDispatcher.run(routed.id, browserState, routed.target, actionContext)
                        true
                    }
                    Routed.ClearFocus -> {
                        focusManager.clearFocus()
                        true
                    }
                    null -> false
                }
            },
        left = edgeRoomContent[edgeRooms[GestureId.EDGE_LEFT]],
        right = edgeRoomContent[edgeRooms[GestureId.EDGE_RIGHT]],
        bottom = edgeRoomContent[edgeRooms[GestureId.EDGE_BOTTOM]],
    ) {
    // Refresh is a shake, everywhere in the constellation. The pull-down space at the top of a
    // room belongs to the top-room reveal and no other gesture may claim it, so refresh moves
    // off the touch plane entirely — a deliberate shake needs no affordance, no instructional
    // copy, and competes with no scroll. The toolbar button stays for anyone who would rather
    // tap than shake.
    ShakeToRefresh(onShake = { actionDispatcher.gesture(GestureId.SHAKE, null, browserState, actionContext) })

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
                    actions = { io.github.mbaliga.fylz.ui.actions.ArchiveEncodingControl(activeTab, (context.applicationContext as FylzApplication).archiveEncodingOverrides, onChanged = ::refresh); TopAppBarActions(actionResolver, actionDispatcher, browserState, actionContext) }, // M3.7
                )
            },
            bottomBar = {
                if (selectedEntries.isNotEmpty()) {
                    SelectionActionBar(actionResolver, actionDispatcher, browserState, actionContext)
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
                        LibraryRailRoom(
                            resolver = actionResolver,
                            dispatcher = actionDispatcher,
                            state = browserState,
                            ctx = actionContext,
                            favorites = library.favorites(),
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
                        resolver = actionResolver,
                        dispatcher = actionDispatcher,
                        state = browserState,
                        ctx = actionContext,
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
                        onSearchFocusChanged = { focused -> textFieldFocused = focused },
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

    pendingPreflight?.let { request ->
        PreflightSheet(
            result = request.result,
            onCancel = { pendingPreflight = null },
            onProceed = { skipped, renamed ->
                pendingPreflight = null
                val remainingSources = request.sources.filterNot { it in skipped }
                val archiveUri = pendingArchiveUri
                scope.launch { checkConflictsAndProceed(request.action, remainingSources, request.destination, archiveUri, renamed) }
            },
        )
    }

    pendingConflict?.let { request ->
        ConflictSheet(
            conflicts = request.conflicts,
            onCancel = { pendingConflict = null },
            onProceed = { resolutions ->
                pendingConflict = null
                scope.launch {
                    runDestinationAction(
                        request.action,
                        request.sources,
                        request.destination,
                        request.archiveUri,
                        request.nameOverrides,
                        resolutions,
                    )
                }
            },
        )
    }

    pendingDestinationChooser?.let { request ->
        DestinationChooserSheet(
            tabs = tabs,
            onChooseTab = { tab ->
                pendingDestinationChooser = null
                beginTransfer(request.action, request.sources, tab.current.uri, request.archiveUri)
            },
            onChooseRoot = { root ->
                pendingDestinationChooser = null
                root.documentUri?.let { destination ->
                    beginTransfer(request.action, request.sources, destination, request.archiveUri)
                }
            },
            onOtherLocation = {
                pendingDestinationChooser = null
                pendingDestinationAction = request.action
                pendingArchiveUri = request.archiveUri
                destinationPicker.launch(null)
            },
            onCancel = { pendingDestinationChooser = null },
        )
    }

    ExtractFlowHost(flow = extractFlow, tabs = tabs, resolver = actionResolver, dispatcher = actionDispatcher, state = browserState, ctx = actionContext)
    CompressFlowHost(flow = compressFlow, tabs = tabs)

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

    // M3.9: the shared prompt ArchiveToolsOverlay's own EXTRACT purpose also uses.
    if (extractPasswordDialog) {
        PasswordPromptDialog(
            title = "Archive password",
            confirmNewPassword = false,
            offerRemember = true,
            confirmLabel = "Continue",
            onDismiss = { extractPasswordDialog = false; pendingArchiveUri = null },
            onConfirm = { password, remember ->
                extractPasswordDialog = false
                if (remember && password != null) pendingArchiveUri?.let { archivePasswordSession.remember(it, password) }
                extractPassword = password
                pendingDestinationAction = PendingDestinationAction.EXTRACT
                destinationPicker.launch(null)
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

    if (commandPaletteOpen) {
        CommandPaletteDialog(
            resolver = actionResolver,
            dispatcher = actionDispatcher,
            state = browserState,
            ctx = actionContext,
            onDismiss = { commandPaletteOpen = false; textFieldFocused = false },
            onFocusChanged = { focused -> textFieldFocused = focused },
        )
    }

    if (showRegistryProblemsDialog) {
        RegistryProblemsDialog(
            problems = actionRegistry.problems,
            onDismiss = { showRegistryProblemsDialog = false },
        )
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
    resolver: ActionResolver,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
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
    onSearchFocusChanged: (Boolean) -> Unit,
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
            NavigateUpButton(resolver, dispatcher, state, ctx)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(stringResource(R.string.browser_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f).onFocusChanged { onSearchFocusChanged(it.isFocused) },
            )
            SortMenuButton(resolver, dispatcher, state, ctx)
            SelectAllButton(resolver, dispatcher, state, ctx)
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
                dispatcher = dispatcher,
                state = state,
                ctx = ctx,
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
                    FileCard(entry, entry.uri in selectedUris, entry.uri == focusedEntry?.uri, dispatcher, state, ctx)
                }
            }
        } else {
            LazyColumn(state = listState) {
                items(entries, key = { it.uri.toString() }) { entry ->
                    FileRowV1(entry, entry.uri in selectedUris, entry.uri == focusedEntry?.uri, dispatcher, state, ctx)
                }
            }
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
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
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
                    dispatcher = dispatcher,
                    state = state,
                    ctx = ctx,
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
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
    overline: String? = null,
    detail: String? = null,
) {
    val label = if (entry.isDirectory) "Folder ${entry.name}" else entry.name
    val target = ActionTarget.Entry(entry)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .combinedClickable(
                onClick = { dispatcher.gesture(GestureId.ITEM_TAP, target, state, ctx) },
                onDoubleClick = { dispatcher.gesture(GestureId.ITEM_DOUBLE_TAP, target, state, ctx) },
                onLongClick = { dispatcher.gesture(GestureId.ITEM_LONG_PRESS, target, state, ctx) },
            )
            .background(if (selected || focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { dispatcher.run(SELECT_TOGGLE_ID, state, target, ctx) })
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
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
) {
    val target = ActionTarget.Entry(entry)
    Surface(
        color = if (selected || focused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.height(140.dp).combinedClickable(
            onClick = { dispatcher.gesture(GestureId.ITEM_TAP, target, state, ctx) },
            onDoubleClick = { dispatcher.gesture(GestureId.ITEM_DOUBLE_TAP, target, state, ctx) },
            onLongClick = { dispatcher.gesture(GestureId.ITEM_LONG_PRESS, target, state, ctx) },
        ).semantics { contentDescription = entry.name },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                // Grid cells get a larger thumbnail: it is the whole point of grid view.
                EntryThumbnail(entry, size = 56.dp)
                Spacer(Modifier.weight(1f))
                Checkbox(selected, onCheckedChange = { dispatcher.run(SELECT_TOGGLE_ID, state, target, ctx) })
            }
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
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
