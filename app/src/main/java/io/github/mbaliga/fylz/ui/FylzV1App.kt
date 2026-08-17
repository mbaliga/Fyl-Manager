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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableRows
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.github.mbaliga.fylz.browse.readableLabel
import io.github.mbaliga.fylz.browse.sortEntries
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.library.SavedSearch
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.network.RemoteConnectionStore
import io.github.mbaliga.fylz.network.WebDavConfig
import io.github.mbaliga.fylz.network.WebDavService
import io.github.mbaliga.fylz.core.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.FileTools
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.operations.SelectionActionPolicy
import io.github.mbaliga.fylz.pdf.PdfPageRef
import io.github.mbaliga.fylz.pdf.PdfToolService
import io.github.mbaliga.fylz.search.FylzSearch
import io.github.mbaliga.fylz.search.RecursiveSearchEngine
import io.github.mbaliga.fylz.search.SearchHit
import io.github.mbaliga.fylz.search.SearchMatchSource
import io.github.mbaliga.fylz.search.SearchProgress
import io.github.mbaliga.fylz.search.isEmptyQuery
import dev.aarso.search.Diagnostic
import dev.aarso.search.EvalContext
import dev.aarso.search.QueryChip
import dev.aarso.search.toQueryText
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.toUri
import io.github.mbaliga.fylz.ui.components.CommandPill
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.ProvideAutoAnimate
import io.github.mbaliga.fylz.ui.components.ProvideIconStyle
import io.github.mbaliga.fylz.ui.components.ProvideShowExtensions
import io.github.mbaliga.fylz.ui.components.QuickAction
import io.github.mbaliga.fylz.ui.components.CommandPillReservedHeight
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.PreviewCardMode
import io.github.mbaliga.fylz.ui.components.PreviewPane
import io.github.mbaliga.fylz.ui.components.QuickLook
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.ui.components.listingPaddingFor
import io.github.mbaliga.fylz.ui.picker.FylzPicker
import io.github.mbaliga.fylz.ui.picker.PickerMode
import io.github.mbaliga.fylz.ui.picker.PickerOutcome
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.util.FileType
import io.github.mbaliga.fylz.util.formatBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.WordWheelRail
import dev.aarso.cellshell.rememberSpatialController
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import io.github.mbaliga.fylz.operations.RecycleRecord
import io.github.mbaliga.fylz.settings.AppPreferencesStore
import io.github.mbaliga.fylz.staging.DropTarget
import io.github.mbaliga.fylz.staging.StagedItem
import io.github.mbaliga.fylz.staging.StagingTray
import io.github.mbaliga.fylz.staging.TrayKind
import io.github.mbaliga.fylz.ui.cluster.BulgeCorner
import io.github.mbaliga.fylz.ui.cluster.ClusterDragController
import io.github.mbaliga.fylz.ui.cluster.ClusterDragLayer
import io.github.mbaliga.fylz.ui.cluster.RestingBulge
import io.github.mbaliga.fylz.ui.cluster.ShredConfirmOverlay
import io.github.mbaliga.fylz.ui.cluster.TrashBrowserSheet
import io.github.mbaliga.fylz.ui.cluster.TrashGlyph
import io.github.mbaliga.fylz.ui.cluster.TrayBrowserSheet

enum class PendingDestinationAction { COPY, MOVE, EXTRACT }

/** How many previously granted SAF subtrees are restored as tabs on launch. */
private const val MAX_RESTORED_TABS = 8

/** How long a settled query has to hold still before it restarts the recursive walk or is
 *  recorded as a recent search -- long enough that a word typed at normal speed reads as one
 *  edit, short enough that pausing to think does not feel like the box stopped listening. */
private const val SEARCH_DEBOUNCE_MILLIS = 250L

/**
 * A directory grid card's lazily-fetched preview: how many children it has, and up to three of
 * them worth drawing as thumbnails. Kept in a cache the workspace owns (see `folderPeeks` below)
 * so a card scrolled off-screen and back doesn't repeat the [DocumentRepository.listChildren] read
 * that filled it the first time.
 */
private data class FolderPeek(val itemCount: Int, val thumbs: List<FileEntry>)

/**
 * The app, and the owner of its theme.
 *
 * [recoverySection] and [overlays] are composed *inside* [FylzTheme] on purpose. Recovery used to
 * be a sibling screen under a bare `MaterialTheme`, which is why it — like the Tools and Index
 * activities — arrived light inside an otherwise dark app. Content that belongs to Fylz is
 * rendered by Fylz's theme; there is no second place for that decision to be made.
 *
 * @param recoverySection the storage-and-recovery surface. It is no longer the whole bottom room:
 *   the bottom room is Actions now, and recovery is its last section — still the same edge, the
 *   same drag, and still owned by the caller so the journal it reads has one owner.
 * @param overlays dialogs the caller owns and needs drawn over everything. Handed the current
 *   "Show hidden files" preference, since an overlay that opens its own [io.github.mbaliga.fylz.ui.picker.FylzPicker]
 *   (the archive tools' source/destination pickers, say) needs the same setting the main browser
 *   and folder tree already respect — without this the caller has no way to reach a preference
 *   that lives inside this composable.
 */
@Composable
fun FylzV1App(
    recoverySection: @Composable () -> Unit = {},
    overlays: @Composable (showHidden: Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    // Both prefs live here, not inside the workspace: theme mode has to be known before
    // FylzTheme opens, and show-hidden rides along on the same small store rather than opening a
    // second one for one more boolean.
    val preferencesStore = remember { AppPreferencesStore(context.applicationContext) }
    var themeMode by remember { mutableStateOf(preferencesStore.themeMode()) }
    var showHidden by remember { mutableStateOf(preferencesStore.showHidden()) }
    var iconStyle by remember { mutableStateOf(preferencesStore.iconStyle()) }
    // Same handle as showHidden, for the same reason: every leaf that draws a name needs this
    // before it draws anything, so it rides down as a CompositionLocal rather than a parameter
    // threaded through the row, the card, the preview header, the details room and the picker.
    var showExtensions by remember { mutableStateOf(preferencesStore.showExtensions()) }
    FylzTheme(
        themeMode = themeMode,
        accentPreset = AccentPreset.MOSS,
        dynamicColor = true,
    ) {
      ProvideIconStyle(iconStyle) {
        ProvideShowExtensions(showExtensions) {
          FylzV1Workspace(
              themeMode = themeMode,
              onThemeModeChange = {
                  themeMode = it
                  preferencesStore.setThemeMode(it)
              },
              showHidden = showHidden,
              onShowHiddenChange = {
                  showHidden = it
                  preferencesStore.setShowHidden(it)
              },
              iconStyle = iconStyle,
              onIconStyleChange = {
                  iconStyle = it
                  preferencesStore.setIconStyle(it)
              },
              showExtensions = showExtensions,
              onShowExtensionsChange = {
                  showExtensions = it
                  preferencesStore.setShowExtensions(it)
              },
              recoverySection = recoverySection,
          )
          overlays(showHidden)
        }
      }
    }
}

@Composable
private fun FylzV1Workspace(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    showHidden: Boolean,
    onShowHiddenChange: (Boolean) -> Unit,
    iconStyle: IconStyle,
    onIconStyleChange: (IconStyle) -> Unit,
    showExtensions: Boolean,
    onShowExtensionsChange: (Boolean) -> Unit,
    recoverySection: @Composable () -> Unit,
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
    // A second handle on the same SharedPreferences the root holds. Theme and show-hidden have
    // to be known before FylzTheme opens so they are threaded down; the preview's own settings are
    // read and written only here and in the settings sheet this composable renders, so routing
    // them through the root would be four parameters carrying nothing the root uses.
    val preferencesStore = remember { AppPreferencesStore(context.applicationContext) }
    var quickActions by remember { mutableStateOf(preferencesStore.quickActions()) }
    var previewScale by remember { mutableStateOf(preferencesStore.previewScale()) }
    // Read and written only here and in the settings sheet, same as quickActions/previewScale
    // above -- autoplay and thumbnail motion are a preview concern, not a pre-theme one.
    var autoAnimate by remember { mutableStateOf(preferencesStore.autoAnimate()) }
    // Local mirror of the store's own MRU list -- SharedPreferences has no change stream, so
    // every write that should be visible this composition also assigns here.
    var recentSearches by remember { mutableStateOf(preferencesStore.recentSearches()) }
    val remoteStore = remember { RemoteConnectionStore(context.applicationContext) }
    val searchEngine = remember { RecursiveSearchEngine(context.applicationContext) }
    // The zone every relative date phrase ("today", "last week") in a typed query resolves
    // against -- fixed for the composition's lifetime rather than re-read per keystroke.
    val searchZone = remember { ZoneId.systemDefault() }

    val tabs = remember { mutableStateListOf<FolderTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var focusedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // `library` is a stable singleton mutated out-of-band (setTags below writes straight into
    // its SharedPreferences-backed store with no Compose state to invalidate on). Bumped
    // wherever tags actually change so a live `tag:` query can be re-ranked against them.
    var tagsVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(preferencesStore.viewMode()) }
    var previewMode by remember { mutableStateOf(PreviewMode.DOCKED) }
    // How the Quick Look card sits relative to the browser -- see PreviewCardMode. Reset to
    // EXPANDED on every dismissal (below), so an anchored or docked card never reopens still
    // anchored or docked for the next file.
    var previewCardMode by remember { mutableStateOf(PreviewCardMode.EXPANDED) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewTruncated by remember { mutableStateOf(false) }
    var editorValue by remember { mutableStateOf("") }
    var previewLoading by remember { mutableStateOf(false) }
    var pendingDestinationAction by remember { mutableStateOf<PendingDestinationAction?>(null) }
    var pendingArchiveUri by remember { mutableStateOf<Uri?>(null) }
    // What the in-app picker is currently asking for, or null while it is closed. Choosing a
    // destination inside a file manager should not mean being handed to a different one.
    var pickerRequest by remember { mutableStateOf<InAppPickerRequest?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf<String?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var batchRenameDialog by remember { mutableStateOf(false) }
    var recycleDialog by remember { mutableStateOf(false) }
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
    // Settings is a plain overlay now, not a room — it has no edge of its own to track, just
    // whether it is on screen. rememberSaveable, not remember: MainActivity declares no
    // android:configChanges, so a rotation recreates the Activity, and a plain remember would
    // silently drop the overlay mid-edit with no error shown.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // ── The cluster drag and its corner bulges ────────────────────────────────────────
    // Press-hold on a selected row gathers the selection under the finger; the corners grow
    // targets (actions top-left, trash bottom-right — opposite corners so a sloppy drop can
    // never cross from constructive to destructive). Trays are session state: they empty when
    // the app process does, like any clipboard.
    val clusterController = remember { ClusterDragController() }
    val clusterOrigins = remember { mutableStateMapOf<Uri, Offset>() }
    // Filled lazily, one directory at a time, as grid cards for it compose -- see FileCard and
    // FolderPeek. Cleared alongside the listing itself so a stale peek never outlives the folder
    // it described. Keyed on showHidden too, same as FolderTreeRail's children cache: a peek read
    // before the setting flipped would keep counting (or omitting) dotfiles the new setting
    // disagrees with, and a plain clear() from a sibling effect would race the per-card refetch
    // below -- rebinding to a fresh map is what makes the flip atomic instead.
    val folderPeeks = remember(showHidden) { mutableStateMapOf<Uri, FolderPeek>() }
    var clipboardTray by remember { mutableStateOf(StagingTray(TrayKind.CLIPBOARD)) }
    var moveTray by remember { mutableStateOf(StagingTray(TrayKind.MOVE)) }
    var openTray by remember { mutableStateOf<TrayKind?>(null) }
    var trashSheetOpen by remember { mutableStateOf(false) }
    val sessionTrashIds = remember { mutableStateListOf<String>() }
    var trashRefreshKey by remember { mutableIntStateOf(0) }
    var shredTargets by remember { mutableStateOf<List<RecycleRecord>?>(null) }
    var shredding by remember { mutableStateOf(false) }
    var pendingFolderItems by remember { mutableStateOf<List<StagedItem>>(emptyList()) }

    // Four rooms: locations LEFT, tools and settings RIGHT, details TOP, actions BOTTOM. The
    // vertical pair is the one to read together — up is what you are looking at, down is what to
    // do about it — and it is why the pull-down space stayed reserved for so long: the gesture
    // was always going to belong to the top room, and nothing had earned that room until details
    // did.
    val shell = rememberSpatialController()
    // Hoisted so the edge scrubber can read where the list is and jump it. Both are needed
    // because the browser switches between a column and a grid, and a scrubber that only worked
    // in one of them would be an affordance that silently stops meaning anything.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    val activeTab = tabs.firstOrNull { it.id == activeTabId }
    val selectedEntries = entries.filter { it.uri in selectedUris }

    // What this selection may be asked to do. Derived once and handed to the actions room, so
    // "does Extract apply" is answered by one testable policy rather than by an expression
    // written inline wherever a button happened to be drawn.
    val selectionActions = remember(selectedEntries) {
        SelectionActionPolicy.evaluate(selectedEntries.map(FileEntry::kind))
    }

    // Parsed once per keystroke, not once per consumer: the in-folder ranking below, the
    // recursive-search effect, and the pill's chip row all read this same interpretation, so a
    // kind noun or a date phrase never means something subtly different to one of the three.
    val parsedQuery = remember(query) { FylzSearch.parse(query, System.currentTimeMillis(), searchZone) }

    // Sorting is applied after filtering so the two controls compose: the user's chosen order
    // holds for the current folder, a folder filter, and recursive search results alike. Hidden
    // dotfiles are filtered last — sorting an item that will not be drawn wastes nothing, but
    // filtering before the search match would let a hidden file's name silently narrow a query.
    //
    // A live in-folder query drops sortSpec entirely rather than composing with it: Spotlight's
    // rule is best-match-first, and a result ranked by score but then re-sorted by name would
    // just be sorted by name with extra steps.
    val visibleEntries = remember(entries, parsedQuery, searchRecursive, sortSpec, showHidden, tagsVersion) {
        val base = if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
        if (query.isBlank() || searchRecursive) {
            sortEntries(base, sortSpec)
        } else {
            FylzSearch.rank(base, parsedQuery, System.currentTimeMillis(), searchZone, library::tags)
                .map { it.entry }
        }
    }

    // Already ranked and ordered by RecursiveSearchEngine (RANKING_ORDER, re-applied on every
    // emit) -- re-sorting here by sortSpec would throw that ranking away for whatever the
    // browser's own sort column says, which is exactly the ordering a live search must not use.
    val searchHits = remember(searchProgress) { searchProgress?.hits.orEmpty() }

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

    // The operations themselves, lifted out of the picker callbacks so the in-app picker and the
    // platform one drive exactly the same code. Whichever route produced the destination, what
    // happens to the files afterwards must not depend on which picker the user came through.
    fun performDestination(action: PendingDestinationAction, destination: Uri) {
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

    fun performArchive(destination: Uri) {
        if (selectedEntries.isEmpty()) return
        scope.launch {
            loading = true
            runCatching { archiveService.createZip(selectedEntries.map { it.uri }, destination) }
                .onSuccess { toast("Archive created"); refresh() }
                .onFailure { toast(it.message ?: "Unable to create archive") }
            loading = false
        }
    }

    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { destination ->
        val action = pendingDestinationAction
        pendingDestinationAction = null
        if (destination == null || action == null) return@rememberLauncherForActivityResult
        repository.persistTreePermission(destination)
        performDestination(action, destination)
    }

    val archiveCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        if (destination != null) performArchive(destination)
    }

    // Destination for PDF page extraction / merge. Kept separate from archiveCreator so the two
    // flows cannot ever write into each other's target.
    fun performPdf(destination: Uri, pages: List<PdfPageRef>, merge: Boolean, ocr: Boolean) {
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

    // Kept separate from archiveCreator so the two flows cannot ever write into each other's
    // target.
    val pdfOutputCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destination ->
        val pages = pendingPdfPages
        val merge = pendingPdfMerge
        val ocr = pendingPdfOcr
        pendingPdfPages = emptyList()
        pendingPdfMerge = false
        if (destination == null) return@rememberLauncherForActivityResult
        performPdf(destination, pages, merge, ocr)
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
    // checks for it at every folder and every entry. Unlike in-folder filtering (visibleEntries,
    // reacting every keystroke), a full-tree walk waits out a short settling period first --
    // typing "phot", "photo", "photos" should start one walk, not three.
    LaunchedEffect(query, searchRecursive, activeTab?.current?.uri, refreshKey) {
        val tab = activeTab
        if (!searchRecursive || query.isBlank() || tab == null) {
            searchProgress = null
            return@LaunchedEffect
        }
        if (parsedQuery.isEmptyQuery()) {
            searchProgress = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        searchProgress = SearchProgress(emptyList(), 0, 0, complete = false)
        searchEngine
            .search(
                treeUri = tab.treeUri,
                rootUri = tab.current.uri,
                rootName = tab.current.name,
                parsed = parsedQuery,
                registry = FylzSearch.registry(searchZone, library::tags),
                matcher = FylzSearch.matcher(searchZone, library::tags),
                ctx = EvalContext(System.currentTimeMillis()),
            )
            .collectLatest { searchProgress = it }
    }

    // "Recent" means settled on, not every keystroke along the way -- its own debounce,
    // independent of search scope, since an in-folder query never touches the effect above.
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MILLIS)
        preferencesStore.addRecentSearch(query)
        recentSearches = preferencesStore.recentSearches()
    }

    LaunchedEffect(activeTab?.current?.uri, refreshKey) {
        selectedUris = emptySet()
        // An EXPANDED card is today's plain Quick Look and is torn down like the rest of the
        // browse state below. ANCHORED and DOCKED exist precisely to survive this -- drilling
        // into a subfolder or switching tabs while comparing against a docked/anchored preview
        // must leave it on screen, not silently dismiss it out from under the user.
        if (previewCardMode == PreviewCardMode.EXPANDED) {
            focusedEntry = null
            previewText = null
        }
        // Folder peeks are keyed by uri, not by (uri, refreshKey) -- clearing here is what makes
        // a refresh (or a folder change) show newly-added thumbnails instead of a stale peek from
        // before the folder changed underneath it.
        folderPeeks.clear()
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
        val entry = focusedEntry ?: run {
            // No file focused means no card on screen, however it got dismissed -- Quick Look's
            // own close/back/tap-away already resets this, but a quick action (rename, recycle)
            // can clear focusedEntry directly, and the next file opened must still start
            // EXPANDED rather than silently inheriting whatever mode the last card was left in.
            previewCardMode = PreviewCardMode.EXPANDED
            return@LaunchedEffect
        }
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
            // Opening a file directly always starts a fresh EXPANDED Quick Look -- without this,
            // tapping file B while file A's preview sits DOCKED or ANCHORED would hand B the same
            // shrunk/scrim-less presentation, and B would silently overwrite A's docked preview
            // instead of A being protected the way docking promises.
            previewCardMode = PreviewCardMode.EXPANDED
            focusedEntry = entry
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

    fun recycleUris(uris: List<Uri>) {
        val tab = activeTab ?: return
        if (uris.isEmpty()) return
        scope.launch {
            loading = true
            runCatching {
                val root = DocumentFile.fromTreeUri(context, tab.treeUri)
                    ?: error("Unable to open the selected root.")
                val recycleRoot = root.findFile(".fylz-trash")
                    ?.takeIf(DocumentFile::isDirectory)
                    ?: root.createDirectory(".fylz-trash")
                    ?: error("This provider cannot create a recycle location.")
                uris.forEach { uri ->
                    val record = recycleBin.recycle(uri, tab.current.uri, recycleRoot.uri)
                    // Remembered so the trash bulge can offer put-back and shred for what went
                    // in during this visit — however it went in, gesture or actions room.
                    sessionTrashIds += record.itemId
                }
            }.onSuccess {
                toast("Moved to Recycle Bin")
                selectedUris = emptySet()
                trashRefreshKey += 1
                refresh()
            }.onFailure { toast(it.message ?: "Unable to recycle selection") }
            loading = false
        }
    }

    fun recycleSelection() = recycleUris(selectedEntries.map(FileEntry::uri))

    /** "Paste here" / "Move here" from an expanded tray, into the folder on screen. */
    fun commitTrayHere(kind: TrayKind) {
        val tab = activeTab ?: run {
            toast("Open a folder to paste into")
            return
        }
        val tray = if (kind == TrayKind.CLIPBOARD) clipboardTray else moveTray
        if (tray.isEmpty) return
        val segments = tab.locations.drop(1).map(FolderLocation::name)
        scope.launch {
            loading = true
            runCatching {
                if (kind == TrayKind.CLIPBOARD) {
                    fileOperations.copy(
                        sourceUris = tray.items.map(StagedItem::uri),
                        destinationTreeUri = tab.treeUri,
                        conflictPolicy = ConflictPolicy.KEEP_BOTH,
                        destinationPathSegments = segments,
                    ) { progress -> operationMessage = "Copying ${progress.displayName}" }
                } else {
                    fileOperations.move(
                        sourceUris = tray.items.map(StagedItem::uri),
                        destinationTreeUri = tab.treeUri,
                        conflictPolicy = ConflictPolicy.KEEP_BOTH,
                        destinationPathSegments = segments,
                    ) { progress -> operationMessage = "Moving ${progress.displayName}" }
                }
            }.onSuccess {
                toast(if (kind == TrayKind.CLIPBOARD) "Pasted" else "Moved")
                // A move's manifest is spent — leaving it would invite moving the same files
                // twice. The clipboard keeps its contents like any clipboard does.
                if (kind == TrayKind.MOVE) moveTray = moveTray.clear()
                openTray = null
                refresh()
            }.onFailure { toast(it.message ?: "The operation failed") }
            operationMessage = null
            loading = false
        }
    }

    /** A genie or snap flight finished: commit what it animated. */
    fun clusterFlightLanded(target: DropTarget, cargo: List<StagedItem>) {
        when (target) {
            DropTarget.CLIPBOARD -> {
                clipboardTray = clipboardTray.stage(cargo)
                selectedUris = emptySet()
                toast("On the clipboard")
            }
            DropTarget.MOVE -> {
                moveTray = moveTray.stage(cargo)
                selectedUris = emptySet()
                toast("Riding the move tray")
            }
            DropTarget.TRASH -> recycleUris(cargo.map(StagedItem::uri))
            else -> Unit
        }
    }

    /** The row's press-hold drag ended; targets without a flight commit right here. */
    fun clusterReleased() {
        val cargo = clusterController.items
        when (clusterController.release()) {
            DropTarget.NEW_FOLDER -> {
                clusterController.settle()
                pendingFolderItems = cargo
                createDialog = "cluster-folder"
            }
            DropTarget.COMPRESS -> {
                clusterController.settle()
                archiveCreator.launch("Fylz-${System.currentTimeMillis()}.zip")
            }
            // NONE returns home, the rest fly; the layer commits them on landing.
            else -> Unit
        }
    }

    fun shredNow(records: List<RecycleRecord>) {
        scope.launch {
            shredding = true
            var failure: Throwable? = null
            records.forEach { record ->
                runCatching { recycleBin.permanentlyDelete(record.itemId, confirmed = true) }
                    .onSuccess { sessionTrashIds.remove(record.itemId) }
                    .onFailure { failure = it }
            }
            shredding = false
            shredTargets = null
            trashRefreshKey += 1
            failure?.let { toast(it.message ?: "Shredding failed for some files") }
            refresh()
        }
    }

    fun shareEntries(sharing: List<FileEntry>) {
        if (sharing.isEmpty()) return
        val uris = ArrayList(sharing.map { it.uri })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(sharing.first().mimeType)
                .putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "Share files")) }
    }

    fun shareSelection() = shareEntries(selectedEntries)

    fun findDuplicates() {
        scope.launch {
            loading = true
            runCatching { fileTools.findDuplicates(entries.filterNot { it.isDirectory }.map { it.uri }) }
                .onSuccess { groups ->
                    duplicateResult = if (groups.isEmpty()) {
                        "No duplicate files found in this folder."
                    } else {
                        groups.joinToString("\n\n") { group ->
                            "${group.items.size} files · ${formatBytes(group.sizeBytes)}\n" +
                                group.items.joinToString("\n") { it.toUri().toString() }
                        }
                    }
                }
                .onFailure { toast(it.message ?: "Duplicate scan failed") }
            loading = false
        }
    }

    /**
     * Everything the actions room can ask for.
     *
     * One function rather than fifteen lambdas threaded through a parameter list: the room's job
     * is to decide what to *offer*, and the workspace's job is to know how to *do* it. Closing
     * the room first is uniform — every one of these either opens a picker, a dialog or another
     * app, and leaving a room open behind a modal is how you end up back on a surface you thought
     * you had left.
     */
    fun runAction(action: FylzAction) {
        shell.closeAll()
        when (action) {
            FylzAction.COPY -> {
                pickerRequest = InAppPickerRequest.Destination(PendingDestinationAction.COPY)
            }
            FylzAction.MOVE -> {
                pickerRequest = InAppPickerRequest.Destination(PendingDestinationAction.MOVE)
            }
            FylzAction.RECYCLE -> recycleSelection()
            FylzAction.RENAME -> renameDialog = true
            FylzAction.BATCH_RENAME -> batchRenameDialog = true
            FylzAction.TAGS -> tagDialog = true
            FylzAction.ARCHIVE -> {
                pickerRequest = InAppPickerRequest.ArchiveOutput("Fylz-${System.currentTimeMillis()}.zip")
            }
            FylzAction.EXTRACT -> {
                pendingArchiveUri = selectedEntries.firstOrNull()?.uri
                pickerRequest = InAppPickerRequest.Destination(PendingDestinationAction.EXTRACT)
            }
            FylzAction.PDF_TOOLS -> pdfDialog = true
            FylzAction.SHARE -> shareSelection()
            FylzAction.CLEAR_SELECTION -> selectedUris = emptySet()
            FylzAction.NEW_FOLDER -> createDialog = "folder"
            FylzAction.NEW_FILE -> createDialog = "file"
            FylzAction.SCAN_PDF -> startScan()
            FylzAction.FIND_DUPLICATES -> findDuplicates()
            FylzAction.AI_ORGANIZE -> aiDialog = true
        }
    }

    /**
     * Runs a preview-card action against the one file the card is showing.
     *
     * Split by *when* the action reads its subject, which is the only thing that matters here.
     * [selectedEntries] is a plain val computed during composition, so anything that consumes it
     * synchronously — share, recycle — cannot be redirected by assigning `selectedUris` first and
     * would act on whatever was selected before the preview opened. Those two are handed the
     * entry directly. The rest only read the selection later, from a dialog or a picker callback
     * that composes after the assignment lands, so pointing the selection at the previewed file is
     * both safe and correct for them.
     */
    fun runQuickAction(action: QuickAction, entry: FileEntry) {
        when (action) {
            QuickAction.OPEN_WITH -> openExternal(entry)
            QuickAction.SHARE -> shareEntries(listOf(entry))
            QuickAction.RECYCLE -> {
                focusedEntry = null
                recycleUris(listOf(entry.uri))
            }
            QuickAction.COPY, QuickAction.MOVE, QuickAction.RENAME, QuickAction.TAGS -> {
                selectedUris = setOf(entry.uri)
                focusedEntry = null
                runAction(
                    when (action) {
                        QuickAction.COPY -> FylzAction.COPY
                        QuickAction.MOVE -> FylzAction.MOVE
                        QuickAction.RENAME -> FylzAction.RENAME
                        else -> FylzAction.TAGS
                    },
                )
            }
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
            RevealedRoom({ shell.hProgress }) {
                LocationsRoom(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    activeTab = activeTab,
                    repository = repository,
                    showHidden = showHidden,
                    onOpenFolder = { path ->
                        // The rail hands back the full root-inclusive ancestor chain for the
                        // tapped folder, not just the folder itself, so the tab's stack is
                        // replaced wholesale here rather than rewound or appended to — a tap
                        // three levels deep must not strand the crumb at whatever the stack
                        // already held above that point.
                        val tab = activeTab ?: return@LocationsRoom
                        val index = tabs.indexOfFirst { it.id == tab.id }
                        if (index >= 0) {
                            tabs[index] = tab.copy(locations = path)
                        }
                        shell.closeAll()
                    },
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
                    onOpenSettings = {
                        shell.closeAll()
                        settingsOpen = true
                    },
                )
            }
        },
        // The right room is gone: Settings moved to a plain entry point in the left room
        // (SETIO reference), and omitting this slot removes its edge gesture and peek entirely
        // (SpatialShell's `right` is nullable for exactly this reason) — nothing left to
        // right-swipe into.
        top = {
            RevealedRoom({ shell.vProgress }) {
                DetailsRoom(
                    ancestors = activeTab?.locations.orEmpty(),
                    // The tree lists what the browser lists, so the two never disagree about what
                    // is in this folder — a filtered listing and an unfiltered tree would be two
                    // answers to one question.
                    children = visibleEntries,
                    folderItemCount = entries.size,
                    focused = focusedEntry,
                    selection = selectedEntries,
                    // Looked up on demand rather than remembered: the room only composes while it
                    // is revealed, and a tag saved from the actions room has to be true here the
                    // next time the user drags down — not one focus change later.
                    tagsFor = { uri -> library.tags(uri).sorted() },
                    onOpenAncestor = { index ->
                        val tab = activeTab ?: return@DetailsRoom
                        val position = tabs.indexOfFirst { it.id == tab.id }
                        if (position >= 0 && index < tab.locations.lastIndex) {
                            tabs[position] = tab.copy(locations = tab.locations.take(index + 1))
                        }
                        shell.closeAll()
                    },
                    onOpenChild = { entry ->
                        openEntry(entry)
                        shell.closeAll()
                    },
                )
            }
        },
        bottom = {
            RevealedRoom({ -shell.vProgress }) {
                ActionsRoom(
                    selection = selectionActions,
                    folderOpen = activeTab != null,
                    canFindDuplicates = entries.count { !it.isDirectory } > 1,
                    canOrganize = focusedEntry != null,
                    onAction = ::runAction,
                    recovery = recoverySection,
                )
            }
        },
    ) {
    // Scoped to exactly what the workspace draws: the browser's thumbnails and the preview card
    // below, and nothing above it (the rooms have no thumbnails of their own to animate).
    ProvideAutoAnimate(autoAnimate) {
    // Refresh is a shake, everywhere in the constellation. The pull-down space at the top of a
    // room belongs to the top-room reveal and no other gesture may claim it, so refresh moves
    // off the touch plane entirely — a deliberate shake needs no affordance, no instructional
    // copy, and competes with no scroll. The toolbar button stays for anyone who would rather
    // tap than shake.
    ShakeToRefresh(onShake = { refresh() })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    // The title is also the way into the details room for anyone who would
                    // rather tap than drag — the same courtesy the Refresh button pays the
                    // shake. What it opens is a description of the folder it is naming, which
                    // is the one thing a title could open without surprising anybody.
                    title = {
                        if (activeTab != null) {
                            Text(
                                activeTab.current.name,
                                // heightIn before clickable so the target is the 48dp DESIGN.md
                                // asks for rather than the height of the glyphs; wrapContentHeight
                                // then re-centres the text inside it.
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable { shell.open(RoomEdge.TOP) }
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .semantics {
                                        contentDescription = "${activeTab.current.name}. Show details"
                                    },
                            )
                        } else {
                            // No tab open means no folder for the details room to describe, so
                            // the title stops offering to open it — plain text, no target, no
                            // "Show details" semantics to announce a room with nothing in it.
                            Text("Fylz")
                        }
                    },
                    actions = {
                        // Both buttons act on the listing; on the storage home surface there is
                        // no listing to toggle or refresh, so they disappear rather than sit
                        // there wired to nothing.
                        if (activeTab != null) {
                            // What is left here is what changes how the listing is *displayed*.
                            // Everything that changes a file — new folder, scan, duplicates, the
                            // AI proposal, and the whole selection bar — moved to the actions
                            // room. An overflow menu mixing "make a folder here" with "open the
                            // index manager" was why it had eleven items and no shape; splitting
                            // it by "does this touch my files" is what finally gave it one.
                            IconButton(
                                onClick = {
                                    val next = when (viewMode) {
                                        ViewMode.LIST -> ViewMode.GRID
                                        ViewMode.GRID -> ViewMode.DETAILS
                                        ViewMode.DETAILS -> ViewMode.LIST
                                    }
                                    viewMode = next
                                    preferencesStore.setViewMode(next)
                                },
                            ) {
                                Icon(
                                    when (viewMode) {
                                        ViewMode.LIST -> Icons.Outlined.GridView
                                        ViewMode.GRID -> Icons.Outlined.TableRows
                                        ViewMode.DETAILS -> Icons.Outlined.List
                                    },
                                    contentDescription = when (viewMode) {
                                        ViewMode.LIST -> "Switch to grid view"
                                        ViewMode.GRID -> "Switch to details view"
                                        ViewMode.DETAILS -> "Switch to list view"
                                    },
                                )
                            }
                            IconButton(onClick = { refresh() }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (selectionActions.any) {
                    SelectionSummaryBar(
                        count = selectionActions.count,
                        onOpenActions = { shell.open(RoomEdge.BOTTOM) },
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
                        repository = repository,
                        showHidden = showHidden,
                        folderPeeks = folderPeeks,
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
                            // Selection and focus are fully decoupled: a checkbox tap used to
                            // retarget the preview on every toggle, including on deselection, so
                            // quick-look chased the selection instead of showing what was opened.
                            selectedUris = if (entry.uri in selectedUris) selectedUris - entry.uri else selectedUris + entry.uri
                        },
                        onSelectAll = {
                            // Select what is actually on screen: results while a recursive
                            // search is showing them, the folder listing otherwise -- selecting
                            // the underlying folder out from under a visible hit list would
                            // pick things the user cannot even see.
                            selectedUris = if (searchRecursive && query.isNotBlank()) {
                                searchHits.map { it.entry.uri }.toSet()
                            } else {
                                visibleEntries.map { it.uri }.toSet()
                            }
                        },
                        listState = listState,
                        gridState = gridState,
                        cluster = ClusterGestureHooks(
                            onPositioned = { uri, centre -> clusterOrigins[uri] = centre },
                            onStart = { at ->
                                clusterController.start(
                                    items = selectedEntries.map { StagedItem(it.uri, it.name, it.kind) },
                                    origins = clusterOrigins.toMap(),
                                    at = at,
                                )
                            },
                            onDrag = clusterController::drag,
                            onEnd = ::clusterReleased,
                            onCancel = clusterController::cancel,
                        ),
                        chips = parsedQuery.chips,
                        onRemoveChip = { chip -> query = (parsedQuery.chips - chip).toQueryText() },
                        diagnostics = parsedQuery.diagnostics,
                        recentSearches = recentSearches,
                        onRecentSearchSelected = { query = it },
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

        // The Niagara-style edge scrubber. Its stops follow whatever the list is sorted by —
        // letters, months, size bands or extensions — because Fylz re-keys the same folder as
        // the sort changes, and a strip showing months down an A-Z list would be a map of
        // somewhere else. It only appears when there is a listing to map: not on the storage
        // home surface, and not while a selection is live — with entries picked out, the next
        // move is an action on them, and a travel control down the edge of the list is an
        // invitation to scroll away from what you just chose. Not during a live query either:
        // an in-folder query ranks by match instead of sortSpec, so the stops' whole premise
        // (consecutive entries share a sort key) is gone, and a recursive query replaces the
        // listing with SearchResults — either way the strip would map a list nobody is seeing.
        if (activeTab != null && visibleEntries.size > 1 && selectedEntries.isEmpty() && query.isBlank()) {
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
                // Held clear of the chrome at both ends. The strip is drawn over the whole
                // workspace, so left to itself it runs the full height of the window — its top
                // letters landing on the toolbar's buttons, its foot under the command pill.
                // Both ends were unhittable: a tap there goes to whatever is on top. Insetting
                // costs a little travel and makes the entire strip a real target.
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = TOP_BAR_HEIGHT, bottom = CommandPillReservedHeight),
            )
        }

        // Transient, not pinned: quick-look is on screen exactly while focusedEntry is set, and
        // every dismissal clears it -- there is no "hidden forever" state left to fall into (the
        // old FloatingPreviewPane's onClose set previewMode = HIDDEN permanently; nothing here
        // plays that role, and PreviewMode stays reserved for the wide docked pane below).
        if (!wide) {
            QuickLook(
                entry = focusedEntry,
                textContent = previewText,
                textTruncated = previewTruncated,
                loading = previewLoading,
                rail = quickActions,
                widthFraction = previewScale.first,
                heightFraction = previewScale.second,
                onScaleChange = { cardWidth, cardHeight ->
                    previewScale = cardWidth to cardHeight
                    preferencesStore.setPreviewScale(cardWidth, cardHeight)
                },
                onAction = ::runQuickAction,
                onDismiss = { focusedEntry = null },
                mode = previewCardMode,
                onModeChange = { previewCardMode = it },
            )
        }

        // ── Resting bulges: the trays' standing presence while they hold something ─────
        // Drawn only outside a drag (the drag layer renders its own swollen versions) and only
        // while occupied — an empty tray leaves the corner clean.
        if (!clusterController.active) {
            if (!clipboardTray.isEmpty || !moveTray.isEmpty) {
                val total = clipboardTray.size + moveTray.size
                RestingBulge(
                    corner = BulgeCorner.TOP_LEFT,
                    swell = 0f,
                    label = "$total",
                    contentDescription = "Staged files: $total. Open the clipboard",
                    onTap = {
                        openTray = if (!clipboardTray.isEmpty) TrayKind.CLIPBOARD else TrayKind.MOVE
                    },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(
                        Icons.Outlined.ContentPaste,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            if (sessionTrashIds.isNotEmpty()) {
                RestingBulge(
                    corner = BulgeCorner.BOTTOM_RIGHT,
                    swell = 0f,
                    label = "${sessionTrashIds.size}",
                    contentDescription = "In the can: ${sessionTrashIds.size}. Open the trash",
                    onTap = { trashSheetOpen = true },
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    TrashGlyph(
                        proximity = 0f,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        ClusterDragLayer(
            controller = clusterController,
            onFlightLanded = ::clusterFlightLanded,
        )

        openTray?.let { kind ->
            val tray = if (kind == TrayKind.CLIPBOARD) clipboardTray else moveTray
            val other = if (kind == TrayKind.CLIPBOARD) moveTray else clipboardTray
            TrayBrowserSheet(
                tray = tray,
                otherTray = other.takeUnless(StagingTray::isEmpty),
                onPickTray = { openTray = it },
                onRemove = { item ->
                    if (kind == TrayKind.CLIPBOARD) {
                        clipboardTray = clipboardTray.without(item.uri)
                        if (clipboardTray.isEmpty) openTray = moveTray.takeUnless(StagingTray::isEmpty)?.kind
                    } else {
                        moveTray = moveTray.without(item.uri)
                        if (moveTray.isEmpty) openTray = clipboardTray.takeUnless(StagingTray::isEmpty)?.kind
                    }
                },
                onCommitHere = { commitTrayHere(kind) },
                onClear = {
                    if (kind == TrayKind.CLIPBOARD) clipboardTray = clipboardTray.clear() else moveTray = moveTray.clear()
                    openTray = null
                },
                onDismiss = { openTray = null },
            )
        }

        if (trashSheetOpen) {
            val sessionRecords = remember(trashRefreshKey, sessionTrashIds.size) {
                recycleBin.records().filter { it.itemId in sessionTrashIds }
            }
            TrashBrowserSheet(
                records = sessionRecords,
                onPutBack = { record ->
                    scope.launch {
                        runCatching { recycleBin.restore(record.itemId, conflictPolicy = ConflictPolicy.KEEP_BOTH) }
                            .onSuccess {
                                sessionTrashIds.remove(record.itemId)
                                trashRefreshKey += 1
                                refresh()
                            }
                            .onFailure { toast(it.message ?: "Restore failed") }
                        if (sessionTrashIds.isEmpty()) trashSheetOpen = false
                    }
                },
                onShred = { record -> shredTargets = listOf(record) },
                onShredAll = { shredTargets = sessionRecords },
                onDismiss = { trashSheetOpen = false },
            )
        }

        shredTargets?.let { targets ->
            ShredConfirmOverlay(
                itemCount = targets.size,
                shredding = shredding,
                onConfirm = { shredNow(targets) },
                onDismiss = { if (!shredding) shredTargets = null },
            )
        }
    }
    }
    }

    // Composed after the shell's handler so it wins while a sheet is up: Back peels the
    // shred confirm, then a sheet, before it ever reaches a room.
    BackHandler(enabled = shredTargets != null || openTray != null || trashSheetOpen) {
        when {
            shredTargets != null -> if (!shredding) shredTargets = null
            openTray != null -> openTray = null
            else -> trashSheetOpen = false
        }
    }

    createDialog?.let { kind ->
        NameDialog(
            title = when (kind) {
                "folder" -> "New folder"
                "cluster-folder" -> {
                    val count = pendingFolderItems.size
                    "New folder for $count ${if (count == 1) "file" else "files"}"
                }
                else -> "New text file"
            },
            initial = if (kind == "file") "Untitled.txt" else "New folder",
            onDismiss = {
                createDialog = null
                pendingFolderItems = emptyList()
            },
            onConfirm = { name ->
                createDialog = null
                val tab = activeTab
                activeTab?.current?.uri?.let { parent ->
                    scope.launch {
                        runCatching {
                            when (kind) {
                                "folder" -> repository.createDirectory(parent, name)
                                // Dropped on "New folder": make it, then move the cluster in.
                                // The move resolves the folder by walking display names, so it
                                // rides the same journaled path as every other transfer.
                                "cluster-folder" -> {
                                    checkNotNull(tab) { "No folder is open." }
                                    repository.createDirectory(parent, name)
                                    val segments = tab.locations.drop(1).map(FolderLocation::name) + name
                                    fileOperations.move(
                                        sourceUris = pendingFolderItems.map(StagedItem::uri),
                                        destinationTreeUri = tab.treeUri,
                                        conflictPolicy = ConflictPolicy.KEEP_BOTH,
                                        destinationPathSegments = segments,
                                    ) { progress -> operationMessage = "Moving ${progress.displayName}" }
                                }
                                else -> repository.createFile(parent, name, "text/plain")
                            }
                        }.onSuccess {
                            if (kind == "cluster-folder") {
                                toast("Moved into $name")
                                selectedUris = emptySet()
                            }
                            refresh()
                        }.onFailure { toast(it.message ?: "Unable to create item") }
                        pendingFolderItems = emptyList()
                        operationMessage = null
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
                tagsVersion += 1
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

    // ── The in-app picker ─────────────────────────────────────────────────────────────
    // Opens on the folder the browser is showing, so "put it here" starts from where the user
    // already is instead of at the top of a foreign app's storage tree.
    pickerRequest?.let { request ->
        val current = activeTab?.let { it.treeUri to it.current }
        FylzPicker(
            mode = if (request is InAppPickerRequest.Destination) PickerMode.FOLDER else PickerMode.SAVE,
            title = request.title,
            confirmLabel = request.confirmLabel,
            repository = repository,
            startAt = current,
            suggestedName = (request as? InAppPickerRequest.Named)?.suggestedName.orEmpty(),
            showHidden = showHidden,
            onDismiss = { pickerRequest = null },
            onBrowseSystem = {
                pickerRequest = null
                when (request) {
                    is InAppPickerRequest.Destination -> {
                        pendingDestinationAction = request.action
                        destinationPicker.launch(null)
                    }
                    is InAppPickerRequest.ArchiveOutput -> archiveCreator.launch(request.suggestedName)
                    is InAppPickerRequest.PdfOutput -> pdfOutputCreator.launch(request.suggestedName)
                }
            },
            onResult = { outcome ->
                pickerRequest = null
                when (request) {
                    is InAppPickerRequest.Destination ->
                        (outcome as? PickerOutcome.Folder)?.let { performDestination(request.action, it.folderUri) }
                    is InAppPickerRequest.ArchiveOutput -> (outcome as? PickerOutcome.Save)?.let { save ->
                        scope.launch {
                            runCatching { repository.createFile(save.folderUri, save.name, "application/zip") }
                                .onSuccess { performArchive(it) }
                                .onFailure { toast(it.message ?: "Unable to create that file") }
                        }
                    }
                    is InAppPickerRequest.PdfOutput -> (outcome as? PickerOutcome.Save)?.let { save ->
                        val pages = pendingPdfPages
                        val merge = pendingPdfMerge
                        val ocr = pendingPdfOcr
                        pendingPdfPages = emptyList()
                        pendingPdfMerge = false
                        scope.launch {
                            runCatching { repository.createFile(save.folderUri, save.name, "application/pdf") }
                                .onSuccess { performPdf(it, pages, merge, ocr) }
                                .onFailure { toast(it.message ?: "Unable to create that file") }
                        }
                    }
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
                pickerRequest = InAppPickerRequest.PdfOutput("Fylz-pages-${System.currentTimeMillis()}.pdf")
            },
            onMerge = { ocr ->
                pdfDialog = false
                pendingPdfPages = emptyList()
                pendingPdfOcr = ocr
                pendingPdfMerge = true
                pickerRequest = InAppPickerRequest.PdfOutput("Fylz-merged-${System.currentTimeMillis()}.pdf")
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

    if (settingsOpen) {
        // Dispatch matches the old right-room ToolsAction handler exactly, including the toast
        // fallback for the two destination activities: only where the settings live moved.
        SettingsOverlay(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            showHidden = showHidden,
            onShowHiddenChange = onShowHiddenChange,
            showExtensions = showExtensions,
            onShowExtensionsChange = onShowExtensionsChange,
            autoAnimate = autoAnimate,
            onAutoAnimateChange = {
                autoAnimate = it
                preferencesStore.setAutoAnimate(it)
            },
            iconStyle = iconStyle,
            onIconStyleChange = onIconStyleChange,
            quickActions = quickActions,
            onQuickActionsChange = {
                quickActions = it
                preferencesStore.setQuickActions(it)
            },
            onOpenRecycleBin = { settingsOpen = false; recycleDialog = true },
            onOpenRemotes = { settingsOpen = false; remoteDialog = true },
            onOpenWebDav = { settingsOpen = false; webDavDialog = true },
            onOpenTools = {
                settingsOpen = false
                runCatching {
                    context.startActivity(Intent(context, PostV1ToolsActivity::class.java))
                }.onFailure { toast("Tools are unavailable on this build") }
            },
            onOpenIndexManager = {
                settingsOpen = false
                runCatching {
                    context.startActivity(Intent(context, IndexManagerActivity::class.java))
                }.onFailure { toast("The index manager is unavailable") }
            },
            onDismiss = { settingsOpen = false },
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
    repository: DocumentRepository,
    showHidden: Boolean,
    folderPeeks: MutableMap<Uri, FolderPeek>,
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
    listState: LazyListState,
    gridState: LazyGridState,
    cluster: ClusterGestureHooks?,
    chips: List<QueryChip>,
    onRemoveChip: (QueryChip) -> Unit,
    diagnostics: List<Diagnostic>,
    recentSearches: List<String>,
    onRecentSearchSelected: (String) -> Unit,
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

    // Selection mode is de-facto, not a separate flag: any non-empty selection puts every row
    // and card into it, which is what turns a tap from "open" into "toggle" below.
    val selectionActive = selectedUris.isNotEmpty()

    // Whether SearchResults, not the plain listing, is on screen -- read in two places below
    // (which branch renders, and what "select all"/its enabled state mean) so they can never
    // disagree about which list the user is actually looking at.
    val searchActive = searchRecursive && query.isNotBlank()

    // The listing fills the surface and the pill floats over its foot, rather than a band of
    // chrome pushing the listing down. Everything the old top row held now rides the pill.
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Text(
                activeTab.locations.joinToString(" / ") { it.name },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            if (searchActive) {
                SearchResults(
                    progress = searchProgress,
                    hits = searchHits,
                    selectedUris = selectedUris,
                    focusedEntry = focusedEntry,
                    onOpen = onOpen,
                    onOpenExternal = onOpenExternal,
                    onToggleSelection = onToggleSelection,
                )
            } else if (loading) {
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
                    contentPadding = listingPaddingFor(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        FileCard(
                            entry = entry,
                            selected = entry.uri in selectedUris,
                            focused = entry.uri == focusedEntry?.uri,
                            selectionActive = selectionActive,
                            onOpen = onOpen,
                            onOpenExternal = onOpenExternal,
                            onToggleSelection = onToggleSelection,
                            cluster = cluster,
                            treeUri = activeTab.treeUri,
                            repository = repository,
                            showHidden = showHidden,
                            folderPeeks = folderPeeks,
                        )
                    }
                }
            } else if (viewMode == ViewMode.DETAILS) {
                Column(Modifier.fillMaxSize()) {
                    DetailsHeaderRow(sortSpec, onSortSpecChange)
                    HorizontalDivider()
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = CommandPillReservedHeight),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(entries, key = { it.uri.toString() }) { entry ->
                            DetailsRow(
                                entry = entry,
                                selected = entry.uri in selectedUris,
                                focused = entry.uri == focusedEntry?.uri,
                                selectionActive = selectionActive,
                                onOpen = onOpen,
                                onOpenExternal = onOpenExternal,
                                onToggleSelection = onToggleSelection,
                                cluster = cluster,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = CommandPillReservedHeight),
                ) {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        FileRowV1(
                            entry = entry,
                            selected = entry.uri in selectedUris,
                            focused = entry.uri == focusedEntry?.uri,
                            selectionActive = selectionActive,
                            onOpen = onOpen,
                            onOpenExternal = onOpenExternal,
                            onToggleSelection = onToggleSelection,
                            cluster = cluster,
                        )
                    }
                }
            }
        }

        CommandPill(
            query = query,
            onQueryChange = onQueryChange,
            canNavigateUp = activeTab.locations.size > 1,
            onNavigateUp = onNavigateUp,
            searchRecursive = searchRecursive,
            onSearchRecursiveChange = onSearchRecursiveChange,
            searchBusy = searchRecursive && searchProgress?.complete == false,
            chips = chips,
            onRemoveChip = onRemoveChip,
            diagnostics = diagnostics,
            recentSearches = recentSearches,
            onRecentSearchSelected = onRecentSearchSelected,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SortMenu(sortSpec, onSortSpecChange)
            IconButton(
                onClick = onSelectAll,
                enabled = if (searchActive) searchHits.isNotEmpty() else entries.isNotEmpty(),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Outlined.SelectAll, stringResource(R.string.browser_select_all))
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
    val selectionActive = selectedUris.isNotEmpty()
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
        LazyColumn(contentPadding = PaddingValues(bottom = CommandPillReservedHeight)) {
            items(hits, key = { it.entry.uri.toString() }) { hit ->
                FileRowV1(
                    entry = hit.entry,
                    selected = hit.entry.uri in selectedUris,
                    focused = hit.entry.uri == focusedEntry?.uri,
                    selectionActive = selectionActive,
                    onOpen = onOpen,
                    onOpenExternal = onOpenExternal,
                    onToggleSelection = onToggleSelection,
                    // Where the file lives, plus the matched line for a content hit -- a result
                    // list without a path is unusable once the search leaves one folder.
                    overline = hit.relativePath,
                    detail = hit.snippet?.let { "\u201c$it\u201d" }
                        ?: if (hit.source == SearchMatchSource.CONTENT) "Matched file contents" else null,
                    nameHighlights = hit.nameHighlights,
                )
            }
        }
    }
}

/**
 * The press-hold cluster gesture, attached only to SELECTED rows.
 *
 * An unselected row keeps its long-press meaning (select); once selected, holding the row
 * gathers the whole selection under the finger and the corners grow drop targets. Positions
 * are reported in root coordinates so the drag survives the list scrolling under it.
 */
internal class ClusterGestureHooks(
    val onPositioned: (Uri, Offset) -> Unit,
    val onStart: (Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRowV1(
    entry: FileEntry,
    selected: Boolean,
    focused: Boolean,
    selectionActive: Boolean,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    cluster: ClusterGestureHooks? = null,
    overline: String? = null,
    detail: String? = null,
    nameHighlights: List<IntRange> = emptyList(),
) {
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    val label = if (entry.isDirectory) "Folder $shownName" else shownName
    // Not read as `selected = selected` below: a local shadows a same-named extension property
    // even inside that extension's own receiver lambda, so a bare `selected` in the semantics
    // block resolves back to THIS parameter, not `SemanticsPropertyReceiver.selected` -- reaching
    // the latter needs an explicit `this.selected`. Renaming this copy keeps the assignment's
    // right-hand side from ever writing the ambiguous bare name.
    val rowSelected = selected
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .onGloballyPositioned { coordinates ->
                originInRoot = coordinates.positionInRoot()
                if (selected) cluster?.onPositioned(entry.uri, coordinates.boundsInRoot().center)
            }
            .then(
                if (selected && cluster != null) {
                    Modifier.pointerInput(entry.uri) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> cluster.onStart(originInRoot + offset) },
                            onDrag = { change, _ ->
                                change.consume()
                                cluster.onDrag(originInRoot + change.position)
                            },
                            onDragEnd = { cluster.onEnd() },
                            onDragCancel = { cluster.onCancel() },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                // At rest, a tap opens; once anything is selected, every row is in selection
                // mode and a tap toggles membership instead — the checkbox's job, without a
                // checkbox to carry it.
                onClick = { if (selectionActive) onToggleSelection(entry) else onOpen(entry) },
                onDoubleClick = { if (entry.isDirectory) onOpen(entry) else onOpenExternal(entry) },
                // A selected row's long-press belongs to the cluster drag; deselecting is a tap
                // away, so the two gestures never fight over one finger.
                onLongClick = if (selected && cluster != null) null else ({ onToggleSelection(entry) }),
            )
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    focused -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics {
                contentDescription = label
                this.selected = rowSelected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Real image/video thumbnails; falls back to a per-type icon. Previously every file in
        // the list rendered the same handful of static vectors. The selection mark rides its
        // corner rather than a gutter column of its own -- at rest there is nothing here at all.
        Box {
            EntryThumbnail(entry, size = 40.dp)
            if (selected) {
                SelectionBadge(Modifier.align(Alignment.TopStart))
            }
        }
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
            if (nameHighlights.isEmpty()) {
                Text(shownName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(highlightedName(shownName, nameHighlights), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                detail ?: listOfNotNull(
                    entry.sizeBytes?.let(::formatBytes),
                    entry.kind.readableLabel(),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * [text] with [ranges] bolded -- a search hit's name with its matched substrings called out, the
 * same way Spotlight bolds a result's title.
 *
 * [ranges] arrive in the entry's own name coordinates (`SearchHit.nameHighlights`), not
 * necessarily [text]'s -- the two agree everywhere except a stripped extension, so every bound is
 * coerced into [text]'s length rather than trusted outright. A range that lands entirely in a
 * suffix [text] no longer has (extensions hidden, a match on ".pdf") collapses to nothing instead
 * of throwing.
 */
private fun highlightedName(text: String, ranges: List<IntRange>): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (start < end) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
    }
}

/**
 * The confirmation mark for a selected item -- Photos-style, on the item, not a checkbox at
 * rest. A surface-coloured disc first so the primary-tinted check reads against any thumbnail,
 * however busy.
 */
@Composable
private fun SelectionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(18.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The directory grid card's folder-peek header: children fanned above the panel that names the
 * folder, once [FileCard] has learned the folder is not empty and has something thumbnailable to
 * show. Everything else about a directory card (loading, empty) stays the plain icon layout.
 */
@Composable
private fun FolderPeekHeader(
    name: String,
    peek: FolderPeek,
) {
    // fillMaxSize, not fillMaxWidth: the top/bottom alignments below only spread the thumbnails
    // and the name panel apart if this Box actually claims the card's full content height rather
    // than shrinking to its tallest child.
    Box(Modifier.fillMaxSize()) {
        // Up to three children, each nudged further right and down than the last so they read as
        // a loose stack peeking out from behind the name panel -- the same three the folder had
        // to read to know it wasn't empty, not a fourth thumbnail's worth of extra traffic.
        peek.thumbs.forEachIndexed { index, child ->
            EntryThumbnail(
                child,
                size = 34.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (index * 14).dp, y = (index * 6).dp),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (peek.itemCount == 1) "1 item" else "${peek.itemCount} items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    entry: FileEntry,
    selected: Boolean,
    focused: Boolean,
    selectionActive: Boolean,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    cluster: ClusterGestureHooks?,
    treeUri: Uri,
    repository: DocumentRepository,
    showHidden: Boolean,
    folderPeeks: MutableMap<Uri, FolderPeek>,
) {
    var originInRoot by remember { mutableStateOf(Offset.Zero) }

    // Read once per (folder uri, showHidden), not once per recomposition -- the cache is what
    // makes scrolling a card off-screen and back free, and the containsKey guard is what makes
    // composing the same card twice (e.g. a sort that reorders but doesn't change the entry) free
    // too. showHidden rides in the key alongside entry.uri so a setting flip restarts this effect
    // against the fresh map `folderPeeks` was just rebound to, instead of leaving the composable
    // waiting on a key change that would never come.
    LaunchedEffect(entry.uri, showHidden) {
        if (!entry.isDirectory || folderPeeks.containsKey(entry.uri)) return@LaunchedEffect
        val children = runCatching { repository.listChildren(treeUri, entry.uri) }.getOrDefault(emptyList())
        val visible = if (showHidden) children else children.filterNot { it.name.startsWith(".") }
        val thumbs = visible.filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }.take(3)
        folderPeeks[entry.uri] = FolderPeek(visible.size, thumbs)
    }
    // derivedStateOf, not a bare folderPeeks[entry.uri] read: SnapshotStateMap invalidates every
    // reader on ANY key's write, not just this one's -- with dozens of folder cards each writing
    // their own peek as they resolve, a bare read turns every card's arrival into an O(N) recompose
    // of every other already-resolved card. Scoping the read behind a derived value means this
    // card only recomposes when the value at its OWN key actually changes.
    val peekState by remember(entry.uri, folderPeeks) {
        derivedStateOf { if (entry.isDirectory) folderPeeks[entry.uri] else null }
    }
    // Copied into a plain local: a delegated property's reads aren't smart-castable, and the
    // uses below rely on the null check narrowing the type.
    val peek = peekState

    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    // See FileRowV1's identical local: a bare `selected` inside the semantics lambda below
    // would resolve back to this parameter (a local shadows the receiver's own property of the
    // same name), so the assignment there needs `this.selected` and this rename to read cleanly.
    val cardSelected = selected

    Surface(
        color = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            focused -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .height(164.dp)
            .onGloballyPositioned { coordinates ->
                originInRoot = coordinates.positionInRoot()
                if (selected) cluster?.onPositioned(entry.uri, coordinates.boundsInRoot().center)
            }
            .then(
                if (selected && cluster != null) {
                    Modifier.pointerInput(entry.uri) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> cluster.onStart(originInRoot + offset) },
                            onDrag = { change, _ ->
                                change.consume()
                                cluster.onDrag(originInRoot + change.position)
                            },
                            onDragEnd = { cluster.onEnd() },
                            onDragCancel = { cluster.onCancel() },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { if (selectionActive) onToggleSelection(entry) else onOpen(entry) },
                onDoubleClick = { if (entry.isDirectory) onOpen(entry) else onOpenExternal(entry) },
                onLongClick = if (selected && cluster != null) null else ({ onToggleSelection(entry) }),
            )
            .semantics {
                contentDescription = shownName
                this.selected = cardSelected
            },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                if (peek != null && peek.thumbs.isNotEmpty()) {
                    // The panel below carries the name and count itself, so there is nothing left
                    // for a second text block underneath -- unlike the plain-icon layout below.
                    FolderPeekHeader(shownName, peek)
                } else {
                    // Grid cells get a larger thumbnail: it is the whole point of grid view. A
                    // directory with no peek yet (still loading) or nothing thumbnailable in it
                    // falls back to the same folder icon it always drew here.
                    EntryThumbnail(entry, size = 56.dp)
                    Column {
                        Text(shownName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        // Grid used to show less than list once you looked past the icon -- no size,
                        // no kind, nothing else at all for a folder. Non-directory cards catch up to
                        // FileRowV1's caption; directories get a count once their peek resolves.
                        val caption = when {
                            peek != null -> if (peek.itemCount == 0) "Empty" else "${peek.itemCount} items"
                            entry.isDirectory -> null
                            else -> listOfNotNull(entry.kind.readableLabel(), entry.sizeBytes?.let(::formatBytes)).joinToString(" · ")
                        }
                        if (caption != null) {
                            Text(
                                caption,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // The selection mark sits at the card's own top-right, the same corner the checkbox
            // used to occupy -- composed only for a selected card, never at rest.
            if (selected) {
                SelectionBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
    }
}

/**
 * Finder's column header, tap to sort. Not a `stickyHeader` -- it sits above the [LazyColumn]
 * rather than as its first item, so it never disturbs the row index the edge scrubber tracks.
 */
@Composable
private fun DetailsHeaderRow(spec: SortSpec, onChange: (SortSpec) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(24.dp))
        DetailsHeaderLabel(
            text = "Name",
            active = spec.field == SortField.NAME,
            direction = spec.direction,
            onClick = { onChange(spec.withField(SortField.NAME)) },
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        DetailsHeaderLabel(
            text = "Size",
            active = spec.field == SortField.SIZE,
            direction = spec.direction,
            onClick = { onChange(spec.withField(SortField.SIZE)) },
            modifier = Modifier.width(76.dp),
        )
        DetailsHeaderLabel(
            text = "Modified",
            active = spec.field == SortField.MODIFIED,
            direction = spec.direction,
            onClick = { onChange(spec.withField(SortField.MODIFIED)) },
            modifier = Modifier.width(92.dp).padding(start = 8.dp),
        )
    }
}

/** One tappable header label. Direction shows as an arrow icon, never colour alone. */
@Composable
private fun DetailsHeaderLabel(
    text: String,
    active: Boolean,
    direction: SortDirection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (active) {
            Icon(
                if (direction == SortDirection.ASCENDING) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * The desktop-class row: Finder's Name | Size | Modified, one line each, the icon standing in
 * for Kind rather than a fourth column. Selection and cluster-drag gestures are duplicated from
 * [FileRowV1] verbatim rather than shared -- this row's whole point is a different shape, and a
 * shared modifier would let the two drift out of sync silently the next time only one changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsRow(
    entry: FileEntry,
    selected: Boolean,
    focused: Boolean,
    selectionActive: Boolean,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    cluster: ClusterGestureHooks? = null,
) {
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    val label = if (entry.isDirectory) "Folder $shownName" else shownName
    // See FileRowV1's identical local: a bare `selected` inside the semantics lambda below
    // would resolve back to this parameter (a local shadows the receiver's own property of the
    // same name), so the assignment there needs `this.selected` and this rename to read cleanly.
    val rowSelected = selected
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .onGloballyPositioned { coordinates ->
                originInRoot = coordinates.positionInRoot()
                if (selected) cluster?.onPositioned(entry.uri, coordinates.boundsInRoot().center)
            }
            .then(
                if (selected && cluster != null) {
                    Modifier.pointerInput(entry.uri) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> cluster.onStart(originInRoot + offset) },
                            onDrag = { change, _ ->
                                change.consume()
                                cluster.onDrag(originInRoot + change.position)
                            },
                            onDragEnd = { cluster.onEnd() },
                            onDragCancel = { cluster.onCancel() },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { if (selectionActive) onToggleSelection(entry) else onOpen(entry) },
                onDoubleClick = { if (entry.isDirectory) onOpen(entry) else onOpenExternal(entry) },
                onLongClick = if (selected && cluster != null) null else ({ onToggleSelection(entry) }),
            )
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    focused -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics {
                contentDescription = label
                this.selected = rowSelected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            EntryThumbnail(entry, size = 24.dp)
            if (selected) {
                SelectionBadge(Modifier.align(Alignment.TopStart))
            }
        }
        Text(
            shownName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text(
            entry.sizeBytes?.let(::formatBytes) ?: "—",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.width(76.dp),
        )
        Text(
            formatDetailsModified(entry.lastModifiedMillis) ?: "—",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(92.dp).padding(start = 8.dp),
        )
    }
}

/**
 * A timestamp as a person would read it, or null when the provider had nothing to say. Zero is
 * treated as nothing rather than 1970 -- `DocumentsProvider`s routinely report `0` for "unknown".
 * Duplicated from [DetailsRoom]'s own formatter rather than shared: Details view's dense column
 * and the details room's full-width fact list are free to diverge without one owning the other's
 * layout.
 */
private fun formatDetailsModified(millis: Long?): String? = millis
    ?.takeIf { it > 0L }
    ?.let {
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    }

/**
 * What a live selection looks like from the file list.
 *
 * The eleven-button horizontal scroller this replaces was the app's largest piece of chrome and
 * its least usable control: it covered the listing it acted on, it needed a sideways swipe to
 * read, and the actions past the fourth were effectively hidden. All of them are rows in the
 * actions room now.
 *
 * What survives here is only what the file list itself has to say — how many are selected, and
 * the two ways out of that state. "Actions" opens the bottom room by tap, because a control the
 * app draws may open a room directly; the drag from the bottom edge does the same thing and is
 * the gesture this bar is teaching.
 */
@Composable
private fun SelectionSummaryBar(count: Int, onOpenActions: () -> Unit, onClear: () -> Unit) {
    Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (count == 1) "1 selected" else "$count selected",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Clear") }
            FilledTonalButton(onClick = onOpenActions) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Actions", Modifier.padding(start = 6.dp))
            }
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

/**
 * A room, arriving.
 *
 * `docs/fonebrew-navigation.md` is specific about the reveal: *"the surface being revealed scales
 * from ~0.97 … Nothing fades in from nothing — material flows."* The shell already does the home
 * card's half of that — lift, shrink, part — but the room itself was sliding in at full size,
 * which reads as a panel arriving rather than a place settling into view. This is the room's
 * half: it comes up under-sized and reaches 1.0 exactly as the drag completes, so the two halves
 * of the motion finish together.
 *
 * Scale only, no fade. The distinction is the pattern's, not a preference: material that flows is
 * material that was already there.
 *
 * [progress] is read inside the layer block rather than passed as a value, so tracking a finger
 * costs a redraw instead of a recomposition of the room's whole subtree.
 *
 * This belongs in `cell-shell` beside the card's motion — one shell, one feel, and every app in
 * the constellation gets it. It lives here for now because Fylz consumes that module rather than
 * owning it. The melt's edge distortion, the other half of the note, is still unbuilt on both
 * sides.
 */
@Composable
private fun RevealedRoom(progress: () -> Float, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = ROOM_REVEAL_SCALE +
                    (1f - ROOM_REVEAL_SCALE) * progress().coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
            },
    ) {
        content()
    }
}

/** Where a revealed room starts, per the pattern's motion note. */
private const val ROOM_REVEAL_SCALE = 0.97f

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
    activeTab: FolderTab?,
    repository: DocumentRepository,
    showHidden: Boolean,
    onSelect: (String) -> Unit,
    onOpenHome: () -> Unit,
    onClose: (FolderTab) -> Unit,
    onAdd: () -> Unit,
    onOpenFolder: (List<FolderLocation>) -> Unit,
    onOpenSettings: () -> Unit,
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

        // The structure under whichever location is open, below the quick links rather than
        // beside them: the wheel answers "which location", the tree answers "where in it".
        if (activeTab != null) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                "FOLDERS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            FolderTreeRail(
                treeUri = activeTab.treeUri,
                ancestors = activeTab.locations,
                repository = repository,
                showHidden = showHidden,
                onOpenFolder = onOpenFolder,
                modifier = Modifier.weight(1f),
            )
        }

        // The quiet way in: below the wheel and whatever tree is showing, not competing with
        // either for attention. A gear this small next to a label this plain reads as "there is
        // more, if you want it" rather than as a fourth thing to navigate.
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(onClick = onOpenSettings)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/** The small uppercase label that opens a section of any room. Shared by all four. */
@Composable
internal fun RoomHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Material's own top app bar height, which the toolbar does not expose as a public constant. */
private val TOP_BAR_HEIGHT = 64.dp

/**
 * What the workspace is currently asking the in-app picker for.
 *
 * Modelled as a request rather than a bag of `pendingX` flags because each variant carries
 * exactly the context its result needs, so the callback cannot read a stale field left over from
 * a different flow — the failure mode the old `pendingDestinationAction` / `pendingArchiveUri`
 * pairing invited every time two picker journeys overlapped.
 */
private sealed interface InAppPickerRequest {

    val title: String
    val confirmLabel: String

    /** Requests that also name a file to create. */
    sealed interface Named : InAppPickerRequest {
        val suggestedName: String
    }

    data class Destination(val action: PendingDestinationAction) : InAppPickerRequest {
        override val title: String get() = when (action) {
            PendingDestinationAction.COPY -> "Copy to"
            PendingDestinationAction.MOVE -> "Move to"
            PendingDestinationAction.EXTRACT -> "Extract into"
        }
        override val confirmLabel: String get() = when (action) {
            PendingDestinationAction.COPY -> "Copy here"
            PendingDestinationAction.MOVE -> "Move here"
            PendingDestinationAction.EXTRACT -> "Extract here"
        }
    }

    data class ArchiveOutput(override val suggestedName: String) : Named {
        override val title: String get() = "Create archive"
        override val confirmLabel: String get() = "Create"
    }

    data class PdfOutput(override val suggestedName: String) : Named {
        override val title: String get() = "Save PDF"
        override val confirmLabel: String get() = "Save"
    }
}

/** Sentence-case names for the theme modes; the enum's own names are shouting. */
internal fun ThemeMode.readableLabel(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow the system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/** The storage home surface's row in the locations wheel. Not a tab, but a real destination. */
private const val HOME_WHEEL_ID = "__home__"

/** The picker's row. A verb in a list of nouns, which is why it sits at the end. */
private const val ADD_WHEEL_ID = "__add__"
