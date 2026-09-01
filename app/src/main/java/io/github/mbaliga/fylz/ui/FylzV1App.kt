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
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import io.github.mbaliga.fylz.browse.Density
import io.github.mbaliga.fylz.browse.GroupAxis
import io.github.mbaliga.fylz.browse.GroupedListing
import io.github.mbaliga.fylz.browse.ListingRow
import io.github.mbaliga.fylz.browse.SortDirection
import io.github.mbaliga.fylz.browse.SortField
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.browse.groupedListing
import io.github.mbaliga.fylz.browse.initialOf
import io.github.mbaliga.fylz.browse.monthBand
import io.github.mbaliga.fylz.browse.readableLabel
import io.github.mbaliga.fylz.browse.sizeBand
import io.github.mbaliga.fylz.browse.sortEntries
import io.github.mbaliga.fylz.canvas.CanvasLayoutStore
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.library.SavedSearch
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ShakeAction
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.network.RemoteConnectionStore
import io.github.mbaliga.fylz.network.WebDavConfig
import io.github.mbaliga.fylz.network.WebDavService
import io.github.mbaliga.fylz.core.operations.ConflictPolicy
import io.github.mbaliga.fylz.operations.FileOperationService
import io.github.mbaliga.fylz.operations.FileTools
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.operations.describe
import io.github.mbaliga.fylz.operations.OperationJournal
import io.github.mbaliga.fylz.operations.SelectionActionPolicy
import io.github.mbaliga.fylz.operations.UndoService
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
import io.github.mbaliga.fylz.storage.toItemRef
import io.github.mbaliga.fylz.storage.toUri
import io.github.mbaliga.fylz.ui.components.CliListing
import io.github.mbaliga.fylz.ui.components.CommandPill
import io.github.mbaliga.fylz.ui.components.CommandPillSearchHeight
import io.github.mbaliga.fylz.ui.components.CountChip
import io.github.mbaliga.fylz.ui.components.DogEarPage
import io.github.mbaliga.fylz.ui.components.EntryAction
import io.github.mbaliga.fylz.ui.components.FolderFace
import io.github.mbaliga.fylz.ui.components.FolderHero
import io.github.mbaliga.fylz.ui.components.LeftTimelineRail
import io.github.mbaliga.fylz.ui.components.dateSections
import io.github.mbaliga.fylz.ui.components.groupByDay
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.components.LocalFolderAppearance
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.ProvideAutoAnimate
import io.github.mbaliga.fylz.ui.components.ProvideIconStyle
import io.github.mbaliga.fylz.ui.components.ProvideShowExtensions
import io.github.mbaliga.fylz.ui.components.QuickAction
import io.github.mbaliga.fylz.ui.components.entryGestures
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.PreviewCardMode
import io.github.mbaliga.fylz.ui.components.PreviewPane
import io.github.mbaliga.fylz.ui.components.QuickLook
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.ui.components.listingPaddingFor
import io.github.mbaliga.fylz.appearance.FolderAppearanceStore
import io.github.mbaliga.fylz.ui.activity.ActivityOverlay
import io.github.mbaliga.fylz.ui.chrome.ActionsBar
import io.github.mbaliga.fylz.ui.chrome.SelectionRow
import io.github.mbaliga.fylz.ui.chrome.SelectionRowHeight
import io.github.mbaliga.fylz.ui.chrome.TabBand
import io.github.mbaliga.fylz.ui.chrome.TabBandHeight
import io.github.mbaliga.fylz.ui.chrome.TabBandItem
import io.github.mbaliga.fylz.ui.search.PullDownSearchHost
import io.github.mbaliga.fylz.ui.search.kindStartersFrom
import io.github.mbaliga.fylz.ui.search.PullDownSearchState
import io.github.mbaliga.fylz.ui.search.rememberPullDownSearchState
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileField
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch
import io.github.mbaliga.fylz.ui.tags.LocalTagsFor
import io.github.mbaliga.fylz.ui.tags.TagBrowser
import io.github.mbaliga.fylz.ui.tags.TagMark
import io.github.mbaliga.fylz.ui.tags.tagSearchQuery
import io.github.mbaliga.fylz.library.unionOfTags
import io.github.mbaliga.fylz.library.applyTagDelta
import io.github.mbaliga.fylz.storage.FullAccessPermission
import io.github.mbaliga.fylz.ui.deck.DeckItem
import io.github.mbaliga.fylz.ui.deck.DeckSource
import io.github.mbaliga.fylz.ui.deck.FileDeckSurface
import io.github.mbaliga.fylz.ui.deck.ShelfSheet
import io.github.mbaliga.fylz.ui.deck.toDeckItem
import io.github.mbaliga.fylz.ui.picker.FylzPicker
import io.github.mbaliga.fylz.ui.picker.PickerMode
import io.github.mbaliga.fylz.ui.picker.PickerOutcome
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ShadowLevel
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import io.github.mbaliga.fylz.ui.theme.hairline
import io.github.mbaliga.fylz.ui.theme.softShadow
import io.github.mbaliga.fylz.util.FileType
import io.github.mbaliga.fylz.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import dev.aarso.cellshell.ScrubberStop
import dev.aarso.cellshell.ShakeToRefresh
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.WordWheelRail
import dev.aarso.cellshell.rememberSpatialController
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.mbaliga.fylz.history.FileHistoryStore
import io.github.mbaliga.fylz.operations.RecycleRecord
import io.github.mbaliga.fylz.settings.AppPreferencesStore
import io.github.mbaliga.fylz.staging.DropTarget
import io.github.mbaliga.fylz.staging.ShelfItem
import io.github.mbaliga.fylz.staging.ShelfStore
import io.github.mbaliga.fylz.staging.StagedItem
import io.github.mbaliga.fylz.staging.StagingTray
import io.github.mbaliga.fylz.staging.TargetReaction
import io.github.mbaliga.fylz.staging.TrayKind
import io.github.mbaliga.fylz.ui.canvas.BentoMosaic
import io.github.mbaliga.fylz.ui.canvas.SelectionMark
import io.github.mbaliga.fylz.ui.canvas.SubjectCanvas
import io.github.mbaliga.fylz.ui.canvas.SubjectList
import io.github.mbaliga.fylz.ui.cluster.BulgeCorner
import io.github.mbaliga.fylz.ui.cluster.ClusterDragController
import io.github.mbaliga.fylz.ui.cluster.ClusterDragLayer
import io.github.mbaliga.fylz.ui.cluster.InkContent
import io.github.mbaliga.fylz.ui.cluster.RestingBulge
import io.github.mbaliga.fylz.ui.cluster.ShredConfirmOverlay
import io.github.mbaliga.fylz.ui.cluster.TrashBrowserSheet
import io.github.mbaliga.fylz.ui.cluster.TrayBrowserSheet
import io.github.mbaliga.fylz.ui.components.warmThumbnails
import io.github.mbaliga.fylz.ui.landing.HomeMode
import io.github.mbaliga.fylz.ui.landing.LandingGate
import io.github.mbaliga.fylz.ui.landing.LandingSplash
import io.github.mbaliga.fylz.ui.landing.LandingSubject
import io.github.mbaliga.fylz.ui.motion.FylzMotion
import io.github.mbaliga.fylz.desktop.DesktopItem
import io.github.mbaliga.fylz.desktop.DesktopPolicy
import io.github.mbaliga.fylz.desktop.DesktopStore
import io.github.mbaliga.fylz.wallpaper.WallpaperPreferences
import io.github.mbaliga.fylz.wallpaper.WallpaperSpec
import io.github.mbaliga.fylz.ui.desktop.DesktopCallbacks
import io.github.mbaliga.fylz.ui.desktop.DesktopCli
import io.github.mbaliga.fylz.ui.desktop.DesktopScreen
import io.github.mbaliga.fylz.ui.desktop.WallpaperPickerSheet
import io.github.mbaliga.fylz.intents.FylzCommand
import io.github.mbaliga.fylz.widgets.WidgetRefresher
import io.github.mbaliga.fylz.history.RecentOpensStore
import io.github.mbaliga.fylz.storage.LargeFileFact
import io.github.mbaliga.fylz.storage.StorageUsageStore
import io.github.mbaliga.fylz.storage.FileStorageProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import io.github.mbaliga.fylz.workspace.KeyboardCommand
import io.github.mbaliga.fylz.workspace.KeyboardShortcutPolicy
import io.github.mbaliga.fylz.workspace.HeldModifiers
import io.github.mbaliga.fylz.workspace.ModifierClick
import io.github.mbaliga.fylz.workspace.ModifierClickPolicy
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState

enum class PendingDestinationAction { COPY, MOVE, EXTRACT }

/** How many previously granted SAF subtrees are restored as tabs on launch. */
private const val MAX_RESTORED_TABS = 8

/** How many of the landing subject's own media entries the splash's content peek ever shows. */
private const val MAX_SUBJECT_PEEK = 3

/** How long a settled query has to hold still before it restarts the recursive walk or is
 *  recorded as a recent search -- long enough that a word typed at normal speed reads as one
 *  edit, short enough that pausing to think does not feel like the box stopped listening. */
private const val SEARCH_DEBOUNCE_MILLIS = 250L

/**
 * A directory grid card's lazily-fetched preview: how many children it has, up to three of them
 * worth drawing as thumbnails, and whether any visible child is something other than a photo or
 * clip -- [thumbs] alone can't answer that (it's capped at three, so a folder of ten photos and
 * zero documents looks identical to one with three photos and seven documents by count alone).
 * Kept in a cache the workspace owns (see `folderPeeks` below) so a card scrolled off-screen and
 * back doesn't repeat the [DocumentRepository.listChildren] read that filled it the first time.
 */
internal data class FolderPeek(val itemCount: Int, val thumbs: List<FileEntry>, val hasNonMedia: Boolean)

/** How many Shelf members are probed at once when the Shelf deck opens -- bounded so opening a
 *  large Shelf doesn't fire dozens of concurrent provider queries at once. */
private const val SHELF_PROBE_PARALLELISM = 6

/** How long a first back press at the root leaves "press again to exit" armed. */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

/**
 * The size [EntryThumbnail] is asked to render at inside a [DogEarPage] frame (Build 11.5 frame
 * 2's document grid) -- deliberately larger than any realistic 3-column cell so [DogEarPage]'s own
 * clip crops the overflow into a fill, per that composable's own KDoc ("size it to at least the
 * frame's own bounds"); [EntryThumbnail] has no aspect-fill mode of its own to ask for instead.
 */
private val DOC_THUMB_SIZE = 240.dp

/**
 * GRID's own `GridCells.Adaptive` minimum cell width (Build 11.5 frames 1/3's "2-column rhythm").
 * At a 440dp phone frame minus [listingPaddingFor]'s 16dp margins and the grid's own 24dp gutter,
 * two cells of this width (2*176 + 24 = 376dp) fit with room to spare while three (3*176 + 48 =
 * 576dp) do not -- Adaptive settles on exactly two columns there, same as the reference frames,
 * and opens up to more on a tablet's wider FileBrowser pane the way a hardcoded `Fixed(2)` never
 * would. COMFORTABLE-baseline like every other [Density] literal; scaled the same way at the one
 * call site rather than added to that shared table, since nothing else in the app reads it.
 */
private const val GRID_MIN_CELL_WIDTH = 176

/**
 * The app, and the owner of its theme.
 *
 * [recoverySection] and [overlays] are composed *inside* [FylzTheme] on purpose. Recovery used to
 * be a sibling screen under a bare `MaterialTheme`, which is why it — like the Tools and Index
 * activities — arrived light inside an otherwise dark app. Content that belongs to Fylz is
 * rendered by Fylz's theme; there is no second place for that decision to be made. The cold-start
 * [LandingSplash] joins them here too, mounted last so it draws over the workspace and the
 * overlays alike, and gated on [io.github.mbaliga.fylz.ui.landing.LandingGate] so it never
 * replays once its own dismissal has set that flag.
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
    // Hoisted from MainActivity's own launch-intent / onNewIntent handling, through FylzAppShell:
    // a launcher-surface command (a widget tap, a static shortcut, Send-to-Fylz) decoded by
    // io.github.mbaliga.fylz.intents.FylzIntents.parse. Consumed exactly once, by the workspace
    // below, via a LaunchedEffect that acts on it then calls [onCommandConsumed] -- MainActivity
    // owns nulling its own state, this composable never mutates [pendingCommand] itself.
    // Placed ahead of recoverySection/overlays below (not appended after them) so both keep their
    // trailing-lambda call convention at every existing call site -- Kotlin only elides the name
    // for a lambda argument when its parameter is the LAST one in the signature.
    pendingCommand: FylzCommand? = null,
    onCommandConsumed: () -> Unit = {},
    recoverySection: @Composable () -> Unit = {},
    overlays: @Composable (showHidden: Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Both prefs live here, not inside the workspace: theme mode has to be known before
    // FylzTheme opens, and show-hidden rides along on the same small store rather than opening a
    // second one for one more boolean. The landing prefs join them for the same shape of reason:
    // the splash mounted below is a sibling of the workspace, not something inside it.
    val preferencesStore = remember { AppPreferencesStore(context.applicationContext) }
    var themeMode by remember { mutableStateOf(preferencesStore.themeMode()) }
    var showHidden by remember { mutableStateOf(preferencesStore.showHidden()) }
    // Also has to be known before FylzTheme opens -- VINTAGE/RETRO/CLI fix the colour scheme
    // outright (ThemeStyle.scheme), so the root's own MaterialTheme call is where that decision
    // has to land, same as themeMode above.
    var themeStyle by remember { mutableStateOf(preferencesStore.themeStyle()) }
    var iconStyle by remember { mutableStateOf(preferencesStore.iconStyle()) }
    // Same handle as showHidden, for the same reason: every leaf that draws a name needs this
    // before it draws anything, so it rides down as a CompositionLocal rather than a parameter
    // threaded through the row, the card, the preview header, the details room and the picker.
    var showExtensions by remember { mutableStateOf(preferencesStore.showExtensions()) }
    var landingSplash by remember { mutableStateOf(preferencesStore.landingSplash()) }
    var homeMode by remember { mutableStateOf(preferencesStore.homeMode()) }
    // Resolved from the stored pref by the boot effect below; null while unresolved, absent, or
    // the grant it names has gone stale. A raw Pair<String, String> here would make every reader
    // re-parse and re-validate it -- resolving once into the real type is what that effect is for.
    var landingSubject by remember { mutableStateOf<LandingSubject?>(null) }
    // Up to three real media entries from the subject's own folder, for the splash's content
    // peek -- independent of homeMode, since the peek is worth showing even when Locations (the
    // default) is the chosen home.
    var subjectPeek by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    // A repository handle of its own, shelf-less on purpose (only DocumentRepository.rename()
    // ever reads the shelf argument): resolving and peeking the landing subject both have to
    // happen here, before FylzTheme opens, which is earlier than the workspace's own
    // shelf-aware repository exists.
    val homeRepository = remember { DocumentRepository(context.applicationContext) }
    // The pref exactly as read at cold start, independent of `landingSplash` above -- the user
    // can flip that live from Settings mid-session, but whether THIS process is still inside the
    // cold-start window the splash owns has to be decided once, from the value that was true (or
    // wasn't) before any such flip could happen.
    val landingSplashAtColdStart = remember { preferencesStore.landingSplash() }
    // LandingGate itself carries no Compose state -- flipping its flag alone would never trigger
    // the recomposition that actually takes the splash out of the tree once it finishes. Seeded
    // from the object's current value on every composition start, not hardcoded false: a genuine
    // process start reads false, while a rotation mid-splash re-reads whatever the flag already
    // says, which is what lets a rotation before dismissal legitimately re-show the splash while
    // one after it never does. Also seeded true when the pref was already off at cold start, so
    // turning it on later in Settings opens the window for the NEXT cold start, not this one.
    var splashDismissed by remember { mutableStateOf(LandingGate.shownThisProcess || !landingSplashAtColdStart) }

    LaunchedEffect(Unit) {
        val (treeString, folderString) = preferencesStore.landingSubject() ?: return@LaunchedEffect
        val treeUri = runCatching { Uri.parse(treeString) }.getOrNull()
        val folderUri = runCatching { Uri.parse(folderString) }.getOrNull()
        val hasGrant = treeUri != null &&
            context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        val root = treeUri
            ?.takeIf { hasGrant }
            ?.let { uri -> runCatching { homeRepository.rootLocation(uri) }.getOrNull() }
        if (treeUri != null && folderUri != null && root != null) {
            landingSubject = LandingSubject(treeUri, folderUri, root.name)
        } else {
            // Either half missing, the grant gone, or the tree no longer resolves -- forget the
            // subject rather than let the routing below re-fail this same check every time home
            // is revisited. Falling back to Locations happens naturally: no subject is exactly
            // what home shows Locations for regardless of the chosen mode.
            preferencesStore.setLandingSubject(null, null)
        }
    }

    LaunchedEffect(landingSubject) {
        val subject = landingSubject
        subjectPeek = if (subject == null) {
            emptyList()
        } else {
            runCatching { homeRepository.listChildren(subject.treeUri, subject.folderUri) }
                .getOrDefault(emptyList())
                .filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }
                .take(MAX_SUBJECT_PEEK)
        }
    }

    val landingSubjectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            homeRepository.persistTreePermission(uri)
            scope.launch {
                runCatching { homeRepository.rootLocation(uri) }
                    .onSuccess { root ->
                        preferencesStore.setLandingSubject(uri.toString(), root.uri.toString())
                        landingSubject = LandingSubject(uri, root.uri, root.name)
                    }
            }
        }
    }

    FylzTheme(
        themeMode = themeMode,
        accentPreset = AccentPreset.MOSS,
        dynamicColor = true,
        themeStyle = themeStyle,
    ) {
      // Beside ProvideIconStyle/ProvideShowExtensions below, not instead of them: those two stay
      // real user preferences, and this is the third thing a theme switch needs -- FolderFace and
      // StackCard read FolderMaterial straight off this local, no pref of their own to keep in
      // step with the icon style FylzPicker or Settings might still show open.
      CompositionLocalProvider(LocalThemeStyle provides themeStyle) {
      ProvideIconStyle(iconStyle) {
        // OR'd in rather than stored: CLI's always-visible extensions is what the style IS, not a
        // choice the user made, so it must never overwrite the stored showExtensions pref a later
        // theme switch would otherwise have to remember to restore.
        ProvideShowExtensions(showExtensions || themeStyle.forcesExtensions) {
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
              themeStyle = themeStyle,
              onThemeStyleChange = { style ->
                  themeStyle = style
                  preferencesStore.setThemeStyle(style)
                  // The theme sets the icon style; the user may still change it afterward from
                  // the advanced picker in Settings, which just writes iconStyle directly and
                  // is untouched by this cascade until the NEXT theme switch overwrites it again.
                  iconStyle = style.iconStyle
                  preferencesStore.setIconStyle(style.iconStyle)
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
              homeMode = homeMode,
              onHomeModeChange = {
                  homeMode = it
                  preferencesStore.setHomeMode(it)
              },
              landingSubject = landingSubject,
              onPickLandingSubject = { landingSubjectPicker.launch(null) },
              onLandingSubjectRelocated = { folderUri ->
                  landingSubject = landingSubject?.copy(folderUri = folderUri)
              },
              landingSplash = landingSplash,
              onLandingSplashChange = {
                  landingSplash = it
                  preferencesStore.setLandingSplash(it)
              },
              recoverySection = recoverySection,
              pendingCommand = pendingCommand,
              onCommandConsumed = onCommandConsumed,
          )
          overlays(showHidden)
          // Mounted last of all, so it draws above everything -- rooms, bulges, the drag layer,
          // QuickLook, the deck, the overlays' own dialogs. Cold-start only: splashDismissed is
          // what keeps it from ever re-showing once its own dismissal sets the flag, and
          // re-entering home from a folder never touches that flag, so it never re-fires there
          // either.
          if (landingSplash && !splashDismissed) {
              LandingSplash(
                  peekEntries = subjectPeek,
                  onFinished = {
                      LandingGate.shownThisProcess = true
                      splashDismissed = true
                  },
              )
          }
        }
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
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
    iconStyle: IconStyle,
    onIconStyleChange: (IconStyle) -> Unit,
    showExtensions: Boolean,
    onShowExtensionsChange: (Boolean) -> Unit,
    homeMode: HomeMode,
    onHomeModeChange: (HomeMode) -> Unit,
    landingSubject: LandingSubject?,
    onPickLandingSubject: () -> Unit,
    onLandingSubjectRelocated: (Uri) -> Unit = {},
    landingSplash: Boolean,
    onLandingSplashChange: (Boolean) -> Unit,
    recoverySection: @Composable () -> Unit,
    pendingCommand: FylzCommand? = null,
    onCommandConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val library = remember { LibraryStore(context.applicationContext) }
    // A second handle on the same on-disk file-history store DocumentRepository already keeps
    // for its own rename() migration -- both share FileHistoryStore's static GLOBAL_LOCK, so a
    // second instance here (held only for onItemRelocated below) never disagrees with the
    // first, the same precedent LibraryStore already sets by being constructed twice.
    val history = remember { FileHistoryStore(context.applicationContext) }
    val shelf = remember { ShelfStore(context.applicationContext) }
    // Bumped by onItemRelocated and by every direct Shelf mutation below -- shelf.items() reads
    // SharedPreferences, which carries no Compose state of its own, so this is what makes
    // shelfItems (below) recompute the same way tagsVersion makes a tag-derived read recompute.
    var shelfVersion by remember { mutableIntStateOf(0) }
    val shelfItems = remember(shelfVersion) { shelf.items() }
    // A second handle on the same SharedPreferences the root holds. Theme and show-hidden have
    // to be known before FylzTheme opens so they are threaded down; the preview's own settings
    // are read and written only here and in the settings sheet this composable renders, so
    // routing them through the root would be parameters carrying nothing the root itself uses.
    // Declared up here, ahead of where the rest of this composable's own state lives, only
    // because onItemRelocated (just below) needs it before that point.
    val preferencesStore = remember { AppPreferencesStore(context.applicationContext) }
    // Own handle onto the freeform-canvas layout store -- ShelfStore-modelled, its own
    // SharedPreferences file. onItemRelocated below is the only thing this composable ever asks
    // of it; SubjectCanvas opens its own handle onto the same file when the canvas itself renders.
    val canvasLayoutStore = remember { CanvasLayoutStore(context.applicationContext) }
    // A folder's chosen icon/colour/stickers -- FolderAppearanceStore-modelled the same as
    // canvasLayoutStore above, one SharedPreferences file of its own. Settings' own folder-
    // appearance picker opens a second handle onto this same file rather than reading this one
    // (see SettingsOverlay's FolderAppearanceSection), so this instance's only jobs are serving
    // LocalFolderAppearance below and following a folder through the relocation fan-out.
    val folderAppearanceStore = remember { FolderAppearanceStore(context.applicationContext) }
    // Bumped alongside shelfVersion by the same relocation fan-out, and read by LocalFolderAppearance
    // below to force a fresh lookup -- the appearanceVersion counter FolderFace's own KDoc names.
    var appearanceVersion by remember { mutableIntStateOf(0) }
    // The desktop's own item list (folder/file shortcuts, widget placements) -- DesktopStore is
    // ShelfStore/CanvasLayoutStore-modelled, its own SharedPreferences file. Read here (rather
    // than only inside DesktopScreen) because relocation and Pin-to-desktop both need to write
    // through it from this composable.
    val desktopStore = remember { DesktopStore(context.applicationContext) }
    // The desktop's Recents widget -- capped, newest-first, recorded from openEntry below.
    val recentOpensStore = remember { RecentOpensStore(context.applicationContext) }
    // Fired once per item a move or a batch rename actually relocates -- the one seam where all
    // four identity-keyed stores learn about a URI that changed out from under them. No store
    // type leaks past this lambda into operations/; each store translates the raw Uri pair
    // itself.
    val onItemRelocated: (Uri, Uri) -> Unit = { old, new ->
        library.migrateUri(old, new)
        history.migrateSource(old, new)
        shelf.migrateRef(old, new)
        canvasLayoutStore.migrateUri(old, new)
        folderAppearanceStore.migrateUri(old, new)
        desktopStore.migrateUri(old, new)
        recentOpensStore.migrateUri(old, new)
        if (preferencesStore.migrateLandingSubject(old, new)) onLandingSubjectRelocated(new)
        shelfVersion += 1
        appearanceVersion += 1
    }
    // onItemRelocated named explicitly (not the constructor's own fallback) so a plain rename
    // notifies every identity-keyed store a move already does -- the same fan-out, one path,
    // matching FileOperationService/FileTools below rather than the narrower three-store default
    // DocumentRepository falls back to when this argument is omitted.
    val repository = remember { DocumentRepository(context.applicationContext, shelf, onItemRelocated = onItemRelocated) }
    val fileOperations = remember {
        FileOperationService(context.applicationContext, onItemRelocated = onItemRelocated)
    }
    val recycleBin = remember { RecycleBinService(context.applicationContext) }
    // The Undo verb: reads the journal the services above already write; see UndoPolicy.
    val undoService = remember {
        UndoService(
            context = context.applicationContext,
            journal = OperationJournal(context.applicationContext),
            fileOperations = fileOperations,
            recycleBin = recycleBin,
        )
    }
    val archiveService = remember { ArchiveService(context.applicationContext) }
    val fileTools = remember {
        FileTools(context.applicationContext, onItemRelocated = onItemRelocated)
    }
    val aiVault = remember { ApiKeyVault(context.applicationContext) }
    val aiClient = remember { AiClient(aiVault) }
    val webDav = remember { WebDavService() }
    val pdfTools = remember { PdfToolService(context.applicationContext) }
    var quickActions by remember { mutableStateOf(preferencesStore.quickActions()) }
    var previewScale by remember { mutableStateOf(preferencesStore.previewScale()) }
    // Read and written only here and in the settings sheet, same as quickActions/previewScale
    // above -- autoplay and thumbnail motion are a preview concern, not a pre-theme one.
    var autoAnimate by remember { mutableStateOf(preferencesStore.autoAnimate()) }
    // Gesture and deliberation preferences, mirrored the same way autoAnimate is.
    var shakeAction by remember { mutableStateOf(preferencesStore.shakeAction()) }
    var deliberateActions by remember { mutableStateOf(preferencesStore.deliberateActions()) }
    // Local mirror of the store's own MRU list -- SharedPreferences has no change stream, so
    // every write that should be visible this composition also assigns here.
    var recentSearches by remember { mutableStateOf(preferencesStore.recentSearches()) }
    val remoteStore = remember { RemoteConnectionStore(context.applicationContext) }
    val searchEngine = remember { RecursiveSearchEngine(context.applicationContext) }
    // The zone every relative date phrase ("today", "last week") in a typed query resolves
    // against -- fixed for the composition's lifetime rather than re-read per keystroke.
    val searchZone = remember { ZoneId.systemDefault() }

    // ── The desktop and its wallpaper ──────────────────────────────────────────────────
    val wallpaperPreferences = remember { WallpaperPreferences(context.applicationContext) }
    var wallpaperSpec by remember { mutableStateOf(wallpaperPreferences.spec()) }
    LaunchedEffect(Unit) {
        // The picked image's own read grant can go stale between launches (the provider revoked
        // it, the app was reinstalled) -- re-checked once at boot, the same shape as the landing
        // subject's own grant re-check above, and degrades to WallpaperSpec.None rather than
        // leaving the desktop pointed at a uri it can no longer read.
        wallpaperSpec = wallpaperPreferences.validateGrant(context.applicationContext)
    }
    LaunchedEffect(Unit) {
        // A one-time decision DesktopStore itself remembers (its own `seeded` key) -- calling this
        // on every boot is safe; only the very first call, on a genuinely empty desktop, ever
        // writes anything.
        desktopStore.seedIfEmpty(DesktopPolicy.defaultSeed())
    }
    var wallpaperPickerOpen by remember { mutableStateOf(false) }
    var largeFilesSheetOpen by remember { mutableStateOf(false) }
    var desktopSnap by remember { mutableStateOf(preferencesStore.desktopSnap()) }
    var desktopLabels by remember { mutableStateOf(preferencesStore.desktopLabels()) }
    // Which existing QUICK_ACCESS widget the next quickAccessFolderPicker result should update --
    // set immediately before every quickAccessFolderPicker.launch() call, the same
    // set-before-launch idiom archiveSources uses for the shared archiveCreator launcher below.
    var pendingQuickAccessWidgetId by remember { mutableStateOf<String?>(null) }

    // Hoisted (rather than left to FileBrowser's own default) so FocusSearch -- reachable from a
    // desktop widget, a launcher shortcut, or a system widget with no gesture of its own to
    // replay -- can reveal and focus the same field a real pull-down would land on, whichever tab
    // happens to be open when the command arrives. See PullDownSearchState.reveal()'s own KDoc:
    // nothing equivalent existed on this state before this build, so that method is itself a
    // small, additive addition made for this call site.
    val pullDownSearchState = rememberPullDownSearchState(revealHeight = CommandPillSearchHeight)
    val searchFocusRequester = remember { FocusRequester() }
    // FocusSearch's own readiness flag for the "no tab open yet" branch: set once the freshly
    // opened tab's tree write has gone in, consumed by FileBrowser's own LaunchedEffect once that
    // tab's CommandPill has actually composed and attached searchFocusRequester -- a real signal
    // rather than a guessed delay, since a plain state write here doesn't itself guarantee the
    // next recomposition (let alone attachment) has happened yet.
    var pendingSearchFocus by remember { mutableStateOf(false) }

    val tabs = remember { mutableStateListOf<FolderTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    // A tab the user has opened but not yet given a folder. [FolderTab] cannot hold this state --
    // its `treeUri` is non-null and `current` is `locations.last()`, so a folder-less FolderTab
    // has no honest value for either and throws the first time anything reads it -- so it is an
    // id on its own here, and the launch surface is what that tab shows until a root is picked.
    // [activeTabId] deliberately stays null meanwhile: it names a tab in [tabs] or nothing, which
    // is what every reader of it already assumes (the locations wheel resolves it as
    // `activeTabId ?: HOME_WHEEL_ID`).
    var newTabId by remember { mutableStateOf<String?>(null) }
    // Which tab "+" was pressed from, so Back out of a new tab that never got a folder returns to
    // where the user was rather than to the home surface.
    var newTabOrigin by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    // Backfill for uris [entries] doesn't cover -- home-surface selections (Locations/Bento/
    // Canvas) never populate the active tab's own folder listing, so selectedEntries below would
    // silently drop them without this. Populated/pruned by onToggleSelection; cleared alongside
    // every full selectedUris reset so it never outlives the selection it describes.
    var selectedEntryDetails by remember { mutableStateOf<Map<Uri, FileEntry>>(emptyMap()) }
    var focusedEntry by remember { mutableStateOf<FileEntry?>(null) }
    // Which row's long-press menu is open, or null. Workspace state rather than the row's own:
    // the rows live inside LazyColumn/LazyVerticalGrid items that scroll (and recycle) out from
    // under anything they might have anchored, and every action on the menu is this scope's to
    // perform anyway. See [EntryMenuHooks].
    var entryMenuFor by remember { mutableStateOf<FileEntry?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // `library` is a stable singleton mutated out-of-band (setTags below writes straight into
    // its SharedPreferences-backed store with no Compose state to invalidate on). Bumped
    // wherever tags actually change so a live `tag:` query can be re-ranked against them.
    var tagsVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(preferencesStore.viewMode()) }
    // The S/M/L axis Arrange offers beside view mode -- independent of it, per Density's own
    // KDoc, so density is its own stored preference rather than folded into viewMode.
    var density by remember { mutableStateOf(preferencesStore.density()) }
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
    // The tag browser (every known tag, with counts) and, once one is tapped, a device-wide
    // result list for it -- reached from LocationsRoom's own "Tags" row and from the Overview's
    // Tags card alike, neither of which requires a folder tab to be open (unlike the folder-
    // scoped query box), so results are read straight off LibraryStore rather than routed
    // through the query pipeline.
    var tagResultsFor by remember { mutableStateOf<String?>(null) }
    var tagResults by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var batchRenameDialog by remember { mutableStateOf(false) }
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
    // When the root back rung last armed "press again to exit", or 0 while it is unarmed --
    // rememberSaveable so a config change mid-window doesn't quietly re-open it.
    var pendingExitAt by rememberSaveable { mutableStateOf(0L) }

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
    // The recycle bin sheet and its bulge/tab entry point both read recycleBin.records() directly
    // now -- no more process-scoped sessionTrashIds tracking a narrower answer than the store
    // itself gives. One store, one source of truth, for the sheet, the settings dialog's old
    // separate route (now the same sheet), and the trash tab's own entry point alike.
    var trashSheetOpen by remember { mutableStateOf(false) }
    var trashRefreshKey by remember { mutableIntStateOf(0) }
    // Where the tab band's trash tab actually IS, in root coordinates, reported by the band's own
    // layout. The drag layer's bottom-right can is drawn at a fixed geometric inset and is only
    // approximately over this tab; a drop is accepted against these MEASURED bounds so the thing
    // aimed at is the thing that catches. Null until the band has been laid out at least once --
    // and stale after it stops composing, which is why every reader also requires an active tab.
    var trashTabBounds by remember { mutableStateOf<Rect?>(null) }
    // The same, per folder tab. A plain map, not a SnapshotStateMap: it is written from layout
    // and read either from a pointer callback (the release) or from a chip already recomposing
    // because the drag position it also reads changed this frame -- so nothing needs the write
    // itself to invalidate anything, and a snapshot map would invalidate every chip on every
    // key's write for no gain. Entries for closed tabs are never pruned; every reader resolves
    // an id against [tabs] first, so a stale rect can only ever name a tab that no longer is.
    val tabChipBounds = remember { mutableMapOf<String, Rect>() }
    var shredTargets by remember { mutableStateOf<List<RecycleRecord>?>(null) }
    var shredding by remember { mutableStateOf(false) }
    var pendingFolderItems by remember { mutableStateOf<List<StagedItem>>(emptyList()) }
    // True when pendingFolderItems came from the Shelf's "New folder with" rather than a
    // cluster drop -- the two share the createDialog = "cluster-folder" path (below) but differ
    // in what a successful move means afterward: the cluster path clears the selection, the
    // Shelf path takes the moved members off the Shelf.
    var pendingFolderFromShelf by remember { mutableStateOf(false) }

    // ── The deck and the Shelf ─────────────────────────────────────────────────────────
    // Which live source the riffle-able card stack is showing, or null when it is closed --
    // hoisted the same way previewCardMode is above: an enum in the leaf (ui/deck/FileDeck.kt),
    // a var here, value and dismissal handed down together.
    var deckOpen by remember { mutableStateOf<DeckSource?>(null) }
    // Per-uri probe results for whatever the Shelf deck last opened against -- a key present
    // with a null value means "probed, found nothing" (missing); a key absent means "not probed
    // yet this opening," so a freshly opened Shelf renders its cached fields instead of a flash
    // of every card reading Missing before the probe has had a chance to answer.
    var shelfProbe by remember { mutableStateOf<Map<Uri, FileEntry?>>(emptyMap()) }
    // Which uris the next archiveCreator result should zip -- set immediately before every
    // archiveCreator.launch() call, since the launcher's own callback is fixed at declaration
    // and cannot otherwise tell a selection-driven Archive from the Shelf's Compress.
    var archiveSources by remember { mutableStateOf<List<Uri>>(emptyList()) }

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
    // The active tab's own listing is authoritative when it has the uri (it is the freshest
    // copy); selectedEntryDetails only backfills uris a home-surface selection carries that
    // [entries] was never populated with in the first place.
    val selectedEntries = selectedUris.mapNotNull { uri -> entries.find { it.uri == uri } ?: selectedEntryDetails[uri] }

    // What this selection may be asked to do. Derived once and handed to the actions room, so
    // "does Extract apply" is answered by one testable policy rather than by an expression
    // written inline wherever a button happened to be drawn. Phase 2: the policy also consults
    // the leading provider's capability set (CapabilityPolicy's first real caller). Both local
    // backends declare identical sets today, so primary() is authoritative for any tab; a
    // future backend that refuses an operation will see its actions withheld here, not fail
    // downstream.
    val selectionActions = remember(selectedEntries) {
        SelectionActionPolicy.evaluate(
            kinds = selectedEntries.map(FileEntry::kind),
            offered = StorageAccess.primary(context).capabilities,
        )
    }

    // Every known tag, cached once per write rather than re-scanned on every recomposition, per
    // allTags()'s own caching obligation -- allTagsMap keeps its natural case-insensitive order
    // for the browser dialog (TagBrowser's own KDoc contract). The by-count reordering this used
    // to also cache here (topTagsList) fed only the removed OverviewScreen's Tags card;
    // DesktopScreen now gathers that same ranking itself, so nothing here needs to precompute it
    // for a consumer that no longer exists.
    val allTagsMap = remember(tagsVersion) { library.allTags() }

    /** A tag tapped from the browser or the Overview's own chips: closes the browser (if it was
     *  the source) and probes every item carrying [tag], device-wide -- LibraryStore's own reverse
     *  scan, not the folder-scoped query pipeline, since this is reachable with no folder tab open. */
    fun openTagResults(tag: String) {
        tagResultsFor = tag
        scope.launch {
            tagResults = library.itemsWithTag(tag).mapNotNull { uri -> repository.probe(uri) }
        }
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

    // Whether the ranked result list, not the folder listing, is what the user is looking at.
    // Hoisted to the one place both the chrome and the browser can read it: the top bar's own
    // arrange/sort controls have to know (results honour neither view mode nor sort order), and
    // select-all has to know which of the two lists "all" means. FileBrowser derives the same
    // thing from its own copies of these two parameters.
    val searchActive = searchRecursive && query.isNotBlank()

    // The Stacks grouping of visibleEntries, computed here once rather than separately inside
    // FileBrowser (which renders it) and again below (where the edge scrubber's stops come from
    // its headers) -- one GroupedListing, two readers.
    //
    // groupedListing() only ever cuts a header where its key changes between NEIGHBOURS
    // (GroupedListing.kt's own KDoc) -- feeding it visibleEntries unchanged, which is ordered by
    // sortSpec.field rather than sortSpec.groupBy, would scatter one kind/date/size band across
    // several separate runs the instant the two axes disagree (group by Kind while sorted by
    // Name puts an image, a text file, another image back to back) -- two "Images" headers with
    // the same label, which is both a broken Stacks read and a duplicate LazyColumn key.
    // groupingOrder's stable sort clusters every bucket into one contiguous run first, keeping
    // each cluster's own relative order (so it still reads as "sorted by Name, arranged by Kind"
    // rather than losing the sort the user picked) -- identity when nothing is grouped.
    val stacksListing = remember(visibleEntries, sortSpec) {
        val axis = sortSpec.groupBy
        val ordered = if (axis == null) visibleEntries else groupingOrder(visibleEntries, axis)
        groupedListing(ordered, sortSpec)
    }

    // STACKS interleaves section headers into the very same LazyColumn -- and [listState] --
    // that LIST and DETAILS share with no headers of their own, so row N in Stacks is not entry
    // N the instant a header sits above it. Nothing translated the shared list's position across
    // that boundary, so switching Arrange modes into or out of Stacks jumped the visible files to
    // whatever unrelated row/entry the raw index happened to land on. Translated by the entry's
    // own uri, not by index: groupingOrder above can reorder entries into per-bucket runs, so a
    // plain index carried straight across a mode switch would still land in bounds, just on the
    // wrong file, whenever a group axis is active.
    var previousViewMode by remember { mutableStateOf(viewMode) }
    LaunchedEffect(viewMode) {
        val previous = previousViewMode
        previousViewMode = viewMode
        if (previous == viewMode) return@LaunchedEffect
        if (viewMode == ViewMode.STACKS && previous != ViewMode.STACKS) {
            val shownEntry = visibleEntries.getOrNull(listState.firstVisibleItemIndex) ?: return@LaunchedEffect
            val rowIndex = stacksListing.rows.indexOfFirst { it is ListingRow.Item && it.entry.uri == shownEntry.uri }
            if (rowIndex >= 0) listState.scrollToItem(rowIndex)
        } else if (previous == ViewMode.STACKS && viewMode != ViewMode.STACKS) {
            val shownEntry = (stacksListing.rows.drop(listState.firstVisibleItemIndex).firstOrNull { it is ListingRow.Item } as? ListingRow.Item)?.entry
                ?: return@LaunchedEffect
            val entryIndex = visibleEntries.indexOfFirst { it.uri == shownEntry.uri }
            if (entryIndex >= 0) listState.scrollToItem(entryIndex)
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun refresh() {
        refreshKey += 1
    }

    /** Selects what is actually on screen: results while a recursive search is showing them, the
     *  folder listing otherwise -- shared by the pill's own select-all and the top bar's, so the
     *  two can never disagree about what "all" means for the list currently in view. */
    fun selectAllVisible() {
        selectedUris = if (searchActive) {
            searchHits.map { it.entry.uri }.toSet()
        } else {
            visibleEntries.map { it.uri }.toSet()
        }
    }

    /**
     * Every Shelf mutation site calls this rather than touching [shelfVersion] directly -- it is
     * also what closes the Shelf deck the moment the Shelf it is showing empties out (Clear,
     * "Remove missing", or the last member leaving via Move here / New folder with), the same
     * zero-chrome-at-rest rule that keeps the TopAppBar badge from being drawn over nothing.
     */
    fun refreshShelf() {
        shelfVersion += 1
        if (deckOpen == DeckSource.SHELF && shelf.items().isEmpty()) deckOpen = null
        // The desktop's own Shelf widget reads shelf.items() the same way this composable does --
        // fire-and-forget on the existing coroutine scope, same as every other post-mutation
        // widget push in this build, so a widget's Binder round trip never rides the same frame
        // as the UI update this function itself triggers.
        scope.launch { WidgetRefresher.refreshAll(context) }
    }

    // What the Shelf deck actually draws: each member paired with its probe result when one
    // exists, or its own cached fields (never flagged missing) when the current opening hasn't
    // probed it yet -- see shelfProbe's own comment for why containment, not nullness, is the
    // signal.
    val shelfDeckItems = remember(shelfItems, shelfProbe) {
        shelfItems.map { item ->
            val uri = item.ref.toUri()
            if (shelfProbe.containsKey(uri)) {
                item.toDeckItem(shelfProbe[uri])
            } else {
                DeckItem(
                    uri = uri,
                    displayName = item.displayName,
                    kind = item.kind,
                    isDirectory = item.isDirectory,
                    sourceCrumb = item.sourceCrumb,
                    sizeBytes = item.sizeBytes,
                    entry = null,
                )
            }
        }
    }

    /** Drops a "+" tab that never chose a folder. Every route that navigates somewhere else goes
     *  through here, so the placeholder can never outlive the surface it was standing in for. */
    fun discardNewTab() {
        newTabId = null
        newTabOrigin = null
    }

    fun openTabAt(treeUri: Uri, location: FolderLocation) {
        // A waiting "+" tab outranks the reuse below: it is a promise of a NEW tab, and adopting
        // the existing tab on this tree would rewind that tab to the folder just picked instead
        // of opening the one that was asked for. Two tabs may therefore share a treeUri -- the
        // reuse lookup takes the first, and every other tab lookup in this file keys by id.
        val pending = newTabId
        if (pending != null) {
            tabs += FolderTab(id = pending, treeUri = treeUri, locations = listOf(location))
            activeTabId = pending
            discardNewTab()
            return
        }
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

    /** Leaves a "+" tab without choosing anything: back to the tab it was opened from, or the
     *  home surface when that tab has since been closed -- [activeTabId] must only ever name a
     *  tab that is actually in [tabs], or nothing. */
    fun cancelNewTab() {
        activeTabId = newTabOrigin?.takeIf { origin -> tabs.any { it.id == origin } }
        discardNewTab()
    }

    /** Opens a new tab: a real tab of this app's own, showing the launch surface until the user
     *  picks a location for it -- not the system folder picker, which is a grant, not a tab. */
    fun addTab() {
        newTabOrigin = activeTabId
        newTabId = UUID.randomUUID().toString()
        activeTabId = null
        // The file's own go-home idiom -- it re-keys the launch surface's own produceState, so a
        // tree granted a moment ago is in the list this new tab is about to offer.
        homeRefreshKey += 1
    }

    /**
     * Opens [entry] -- a folder in the listing on screen -- as a second tab, IN THE BACKGROUND.
     *
     * **Focus deliberately stays where it is.** This is reached by holding a folder while
     * browsing, which is the moment you have found somewhere to go *later*; stealing focus would
     * make it a slower version of the tap that is already right there, and it would lose the
     * place you were reading. Held-then-held-again queues several folders up and you pick from
     * the band. The band is the receipt: a new chip appears the instant this returns, and the
     * toast names it for the case where the strip has scrolled past its own end.
     *
     * Not [openTabAt]: that one adopts a waiting "+" tab and reuses any tab already on this tree,
     * both of which would rewind an existing tab instead of adding one. The new tab inherits the
     * current tab's whole breadcrumb plus this folder, the same shape [openFolderInActiveTab]
     * builds, so Up out of it walks back through the real ancestry rather than dead-ending.
     */
    fun openInNewTab(entry: FileEntry) {
        // Both guards are unreachable from the menu as it stands (its only rows are folders, and
        // every surface that offers it has a tab open) -- said out loud anyway, because a route
        // that silently does nothing is exactly what a later call site would fall into.
        if (!entry.isDirectory) return
        val tab = activeTab ?: run {
            toast("Open a folder first")
            return
        }
        tabs += FolderTab(
            treeUri = tab.treeUri,
            locations = tab.locations + FolderLocation(entry.uri, entry.name),
        )
        toast("${entry.name} opened in a new tab")
    }

    /**
     * Adds or removes [entry] from the live selection. A named function rather than the lambda
     * literal it used to be: the row long-press menu's own "Select" has to do exactly this, and
     * two copies of a selection toggle is how a selection and its details map drift apart.
     *
     * Selection and focus are fully decoupled: a checkbox tap used to retarget the preview on
     * every toggle, including on deselection, so quick-look chased the selection instead of
     * showing what was opened.
     */
    fun toggleSelection(entry: FileEntry) {
        if (entry.uri in selectedUris) {
            selectedUris = selectedUris - entry.uri
            selectedEntryDetails = selectedEntryDetails - entry.uri
        } else {
            selectedUris = selectedUris + entry.uri
            selectedEntryDetails = selectedEntryDetails + (entry.uri to entry)
        }
    }

    /**
     * What holding [entry] offers -- read by BOTH the sheet and every row's custom accessibility
     * actions, which is the whole reason it is one function rather than a lambda at each.
     *
     * Folders only, and only the two things a held folder can offer that a tap cannot: a second
     * tab, and the selection this long press would otherwise have made on its own. "Select" is
     * not a courtesy item -- it is what keeps long-press-to-start-a-selection reachable on a
     * folder now that the menu owns that press (see `resolveEntryLongPress`). A file gets an
     * empty list, which is what leaves its long press exactly as it was: toggle, no menu.
     */
    fun entryMenuActions(entry: FileEntry): List<EntryAction> = if (entry.isDirectory) {
        listOf(
            EntryAction("Open in a new tab") { openInNewTab(entry) },
            EntryAction("Select") { toggleSelection(entry) },
        )
    } else {
        emptyList()
    }

    /** Closes [tab] -- shared by the locations room's own row and the pill's tab strip, so the
     *  two never disagree about which tab becomes active once the current one is gone. */
    fun closeTab(tab: FolderTab) {
        val wasActive = activeTabId == tab.id
        tabs.remove(tab)
        if (wasActive) activeTabId = tabs.lastOrNull()?.id
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

    /**
     * Opens a favourite from the Overview's Pinned card -- finally a real tap target, per the
     * plan's own complaint that favourites had none on a phone. [FavoriteLocation] carries only
     * the folder's own document uri, not the tree grant it was reached through (`toggleFavorite`
     * never captured one), so this recovers it the only general way available: the persisted
     * grant sharing the favourite's own authority. Good enough for the common case (one grant per
     * provider authority) without a data-model change to a file this workstream does not own.
     */
    fun openFavorite(favorite: FavoriteLocation) {
        val treeUri = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.uri.authority == favorite.uri.authority }
            ?.uri
        if (treeUri == null) {
            toast("${favorite.name} is no longer reachable -- its folder grant is gone")
            return
        }
        openTabAt(treeUri, FolderLocation(favorite.uri, favorite.name))
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
        // Where the selection lives right now, journaled so Undo can send a move (or the
        // recycled copies of a copy) home by the same root+walk addressing transfers use.
        // Null when the selection came from a home surface with no open tab: those
        // operations run fine and are honestly not undoable.
        val undoHomeTree = activeTab?.treeUri
        val undoHomeSegments = activeTab?.locations?.drop(1)?.map(FolderLocation::name).orEmpty()
        scope.launch {
            loading = true
            runCatching {
                when (action) {
                    PendingDestinationAction.COPY -> fileOperations.copy(
                        selectedEntries.map { it.uri },
                        destination,
                        ConflictPolicy.KEEP_BOTH,
                        sourceParentTreeUri = undoHomeTree,
                        sourceParentSegments = undoHomeSegments,
                    ) { progress -> operationMessage = "Copying ${progress.displayName}" }
                    PendingDestinationAction.MOVE -> fileOperations.move(
                        selectedEntries.map { it.uri },
                        destination,
                        ConflictPolicy.KEEP_BOTH,
                        sourceParentTreeUri = undoHomeTree,
                        sourceParentSegments = undoHomeSegments,
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
                selectedEntryDetails = emptyMap()
                pendingArchiveUri = null
                refresh()
            }.onFailure { toast(it.message ?: "Operation failed") }
            operationMessage = null
            loading = false
        }
    }

    // Takes an explicit source list rather than reading selectedEntries implicitly: the one
    // archiveCreator launcher below serves both the selection's Archive action and the Shelf's
    // Compress button, and archiveSources (set immediately before each launch) is what tells
    // this callback which source list a given result belongs to.
    fun performArchive(sources: List<Uri>, destination: Uri) {
        if (sources.isEmpty()) return
        scope.launch {
            loading = true
            runCatching { archiveService.createZip(sources, destination) }
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
        if (destination != null) performArchive(archiveSources, destination)
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

    // ── Desktop shortcut/quick-access pickers ──────────────────────────────────────────
    // A picked folder becomes a FolderShortcut placed at the next free desktop cell -- the grant
    // is persisted the same way rootPicker's own callback persists one, and homeRefreshKey bumps
    // so DesktopScreen's own `items` reload picks the new shortcut up without needing a poked
    // Flow this store does not have.
    val desktopFolderShortcutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            repository.persistTreePermission(uri)
            scope.launch {
                runCatching { repository.rootLocation(uri) }
                    .onSuccess { root ->
                        desktopStore.upsert(
                            DesktopItem.FolderShortcut(
                                treeUri = uri,
                                folderUri = root.uri,
                                displayName = root.name,
                                placement = DesktopPolicy.nextFreePlacement(desktopStore.items().map(DesktopItem::placement)),
                            ),
                        )
                        homeRefreshKey += 1
                    }
                    .onFailure { toast(it.message ?: "Unable to open folder") }
            }
        }
    }

    // Rewrites one existing QUICK_ACCESS widget's own config target -- pendingQuickAccessWidgetId
    // names which instance, set immediately before every launch() call below (the shape
    // archiveSources already establishes for the shared archiveCreator launcher).
    val quickAccessFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val widgetId = pendingQuickAccessWidgetId
        pendingQuickAccessWidgetId = null
        if (uri != null && widgetId != null) {
            repository.persistTreePermission(uri)
            scope.launch {
                runCatching { repository.rootLocation(uri) }
                    .onSuccess { root ->
                        val current = desktopStore.items().firstOrNull { it.id == widgetId } as? DesktopItem.Widget
                        if (current != null) {
                            desktopStore.upsert(
                                current.copy(config = current.config + ("target" to "tree:$uri|folder:${root.uri}")),
                            )
                            homeRefreshKey += 1
                        }
                    }
                    .onFailure { toast(it.message ?: "Unable to open folder") }
            }
        }
    }

    /**
     * Duplicates scanning ([findDuplicates] below) is folder-scoped: it reads `entries`, the
     * ACTIVE tab's own listing. Auto-running it the instant a picked folder's tab opens would
     * race that listing's own `LaunchedEffect` (it fills asynchronously, after the tab exists) --
     * rather than guess at a delay, the honest choice named for this callback is the plainer one:
     * open the picked folder as its own tab and say in as many words what to do next.
     */
    val duplicatesFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            repository.persistTreePermission(uri)
            scope.launch {
                runCatching { repository.rootLocation(uri) }
                    .onSuccess { root ->
                        openTabAt(uri, root)
                        toast("Opened ${root.name} -- use Actions > Find duplicates here")
                    }
                    .onFailure { toast(it.message ?: "Unable to open folder") }
            }
        }
    }

    /**
     * FocusSearch's honest minimum: search only exists once a folder tab is open (the pull-down
     * field lives inside FileBrowser's own folder-open branch; CANVAS's floating CommandPill is
     * the one exception, always on screen while that view mode is active). With no tab open, the
     * primary device root is opened first (mirroring OverviewScreen's own `primaryRoot`), the same
     * as a person tapping into a folder before typing a query -- then the field is revealed and
     * focused once that tab has had a beat to compose.
     */
    fun focusSearch() {
        if (activeTab != null) {
            pullDownSearchState.reveal()
            runCatching { searchFocusRequester.requestFocus() }
        } else {
            scope.launch {
                val root = withContext(Dispatchers.IO) {
                    if (!FullAccessPermission.isGranted()) {
                        null
                    } else {
                        StorageAccess.fileProvider.rootGroups(context)
                            .firstOrNull { it.title == FileStorageProvider.GROUP_DEVICE }
                            ?.roots?.firstOrNull()
                    }
                }
                if (root?.treeUri != null && root.documentUri != null) {
                    openStorageRoot(root)
                    // FileBrowser's own LaunchedEffect (keyed on this flag) reveals and focuses
                    // the field once the newly opened tab has actually composed and attached
                    // searchFocusRequester -- see this flag's own KDoc above.
                    pendingSearchFocus = true
                } else {
                    toast("Open a folder, then search")
                }
            }
        }
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

    // A query belongs to the folder you are looking at, not to the whole app.
    //
    // Without this, one `query` stayed live through every navigation and every tab switch, and
    // the effect above that runs the recursive walk is keyed on the folder -- so opening any
    // folder while a search was live immediately re-ran that same search inside it. The owner's
    // recording shows the result: searching "pass" in Internal shared storage, then opening
    // Alarms, gives "Searching... 0 folders scanned" and then "No matches" instead of the
    // folder's own contents, and so does every folder opened after it. Paired with a search
    // field that was being clipped off screen entirely, there was no visible query to see and no
    // reachable clear button, so the app simply looked empty from there on.
    //
    // Keyed on the tab AND its current folder, deliberately NOT on refreshKey: refreshing while
    // a search is live has to keep the search, which is why this cannot just fold into the
    // selection-clearing effect below. `searchRecursive` is left alone on purpose -- the scope
    // toggle is a stated preference about how to search, not a leftover of one search.
    //
    // Clearing, rather than remembering a query per tab: a restored search would have to restore
    // its results and its scroll position too to be worth anything, and a tab silently filtered
    // by something typed minutes ago is the same trap in a smaller room.
    LaunchedEffect(activeTabId, activeTab?.current?.uri) {
        query = ""
    }

    LaunchedEffect(activeTab?.current?.uri, refreshKey) {
        selectedUris = emptySet()
        selectedEntryDetails = emptyMap()
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

    // Runs once per Shelf opening (keyed on deckOpen, not on shelfItems, so a Move/Compress
    // mid-visit doesn't restart it): probes every member's current uri and folds the results
    // back into the store, so a rename or move the app can see is reflected the next time the
    // Shelf is opened, and a member the provider no longer has anything for is surfaced as
    // missing rather than silently dropped.
    //
    // Writes through shelf.refreshMetadata rather than shelf.replaceAll: this effect can still
    // be awaiting probes when the user runs Move/Clear/Remove-missing on the Shelf it is
    // probing, and replaceAll against the `current` snapshot captured above would silently
    // resurrect whatever that concurrent mutation just removed. refreshMetadata reads the store
    // fresh at write time instead, so it only ever refreshes metadata for refs still there.
    LaunchedEffect(deckOpen) {
        if (deckOpen != DeckSource.SHELF) return@LaunchedEffect
        val current = shelfItems
        if (current.isEmpty()) return@LaunchedEffect
        val gate = Semaphore(SHELF_PROBE_PARALLELISM)
        val probed = current.map { item ->
            async { gate.withPermit { item.ref.toUri() to repository.probe(item.ref.toUri()) } }
        }.awaitAll().toMap()
        // Merged, then pruned to the membership just probed: earlier visits' entries keep the
        // deck honest while a re-probe is in flight, but entries for members long since removed
        // would otherwise accrete here for the life of the workspace.
        val currentUris = current.map { it.ref.toUri() }.toSet()
        shelfProbe = (shelfProbe + probed).filterKeys(currentUris::contains)
        val updates = current.mapNotNull { item ->
            val found = probed[item.ref.toUri()] ?: return@mapNotNull null
            item.ref to item.copy(
                displayName = found.name,
                kind = found.kind,
                isDirectory = found.isDirectory,
                sizeBytes = found.sizeBytes,
                modifiedAtMillis = found.lastModifiedMillis,
            )
        }.toMap()
        shelf.refreshMetadata(updates)
        refreshShelf()
    }

    /** Drills the active tab one level into [location] -- the directory half of [openEntry], and
     *  also what CANVAS's own [io.github.mbaliga.fylz.ui.canvas.SubjectCanvas] calls when a tile
     *  it loaded itself (not from `entries`) turns out to be a folder. */
    fun openFolderInActiveTab(location: FolderLocation) {
        val tab = activeTab ?: return
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index >= 0) {
            tabs[index] = tab.copy(locations = tab.locations + location)
        }
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            openFolderInActiveTab(FolderLocation(entry.uri, entry.name))
        } else {
            // Opening a file directly always starts a fresh EXPANDED Quick Look -- without this,
            // tapping file B while file A's preview sits DOCKED or ANCHORED would hand B the same
            // shrunk/scrim-less presentation, and B would silently overwrite A's docked preview
            // instead of A being protected the way docking promises.
            previewCardMode = PreviewCardMode.EXPANDED
            focusedEntry = entry
            // Recorded for every non-directory open, whichever surface it came from (a listing
            // row, a desktop shortcut, a Recents tap feeding right back into this same function)
            // -- one choke point, so the desktop's own Recents widget never has to be told about
            // an open from more than one call site. Dispatched to IO: record()'s own persist() is
            // a synchronous SharedPreferences commit(), and openEntry() itself is called directly
            // from bare, non-coroutine click lambdas (e.g. DetailsRoom's onOpenChild) as well as
            // from call sites already inside scope.launch{} -- either way this is opening a file,
            // the single most frequent interaction here, so the write must never block the tap.
            val kindName = entry.kind.name
            val openedAt = System.currentTimeMillis()
            scope.launch(Dispatchers.IO) {
                recentOpensStore.record(entry.uri, entry.name, kindName, openedAt)
            }
        }
    }

    /**
     * Opens a desktop [DesktopItem.FolderShortcut] -- the grant-recovering shape [openFavorite]
     * above already established, adapted to a shortcut that already carries its own tree grant
     * rather than one recovered from a favourite's bare authority. The folder's display name is
     * re-resolved through [repository] rather than trusted from the shortcut's own stored name:
     * [DesktopCallbacks.onOpenFolderShortcut] only ever hands this callback the two uris, and a
     * live name is worth one IO round trip over showing one that may since have gone stale.
     */
    fun openDesktopFolderShortcut(treeUri: Uri, folderUri: Uri) {
        val hasGrant = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!hasGrant) {
            toast("This folder's grant is gone")
            return
        }
        scope.launch {
            val name = runCatching { repository.probe(folderUri) }.getOrNull()?.name
                ?: folderUri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                ?: "Folder"
            openTabAt(treeUri, FolderLocation(folderUri, name))
        }
    }

    /** Opens a desktop [DesktopItem.FileShortcut]: probed on IO (a shortcut only ever stores a
     *  bare uri, never a live [FileEntry]), then handed to [openEntry] exactly like any listing
     *  row's own tap -- a probe that comes back null means the target is gone, told honestly
     *  rather than opening a stale placeholder. */
    fun openDesktopFileShortcut(uri: Uri) {
        scope.launch {
            val entry = runCatching { repository.probe(uri) }.getOrNull()
            if (entry == null) {
                toast("Can't find this anymore")
            } else {
                openEntry(entry)
            }
        }
    }

    /** [FylzCommand.OpenFolder]'s own grant-recovering open -- [treeUri] with [folderUri] absent
     *  opens the tree's own root (a shortcut/widget/intent that only names the tree, e.g. a
     *  restored tab); present, it opens exactly that folder via the same
     *  [openDesktopFolderShortcut] path a desktop shortcut tap uses. */
    fun openCommandFolder(treeUri: Uri, folderUri: Uri?) {
        val hasGrant = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!hasGrant) {
            toast("This folder's grant is gone")
            return
        }
        if (folderUri == null) {
            scope.launch {
                runCatching { repository.rootLocation(treeUri) }
                    .onSuccess { root -> openTabAt(treeUri, root) }
                    .onFailure { toast(it.message ?: "Unable to open folder") }
            }
        } else {
            openDesktopFolderShortcut(treeUri, folderUri)
        }
    }

    // The launch intent (or a fresh one delivered to onNewIntent while the process was already
    // running -- MainActivity is launchMode="singleTask") decoded once, hoisted all the way down
    // from there through FylzAppShell/FylzV1App. Consumed exactly once: acted on here, then
    // [onCommandConsumed] tells MainActivity to null its own state, so a rotation or any other
    // recomposition never replays the same command a second time.
    LaunchedEffect(pendingCommand) {
        val command = pendingCommand ?: return@LaunchedEffect
        when (command) {
            FylzCommand.Scan -> startScan()
            FylzCommand.FocusSearch -> focusSearch()
            FylzCommand.OpenShelf -> deckOpen = DeckSource.SHELF
            FylzCommand.OpenTrash -> trashSheetOpen = true
            is FylzCommand.OpenFolder -> openCommandFolder(command.treeUri, command.folderUri)
        }
        onCommandConsumed()
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
        // The bin lives inside the open tab's own tree (.fylz-trash under its root), so with no
        // tab there is nowhere to recycle TO. Said out loud rather than returned silently: this
        // is reachable from the home surfaces' own cluster drag, and a genie animation followed
        // by nothing at all is the worst possible answer.
        val tab = activeTab ?: run {
            toast("Open a folder to recycle into")
            return
        }
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
                uris.forEach { uri -> recycleBin.recycle(uri, tab.current.uri, recycleRoot.uri) }
            }.onSuccess {
                toast("Moved to Recycle Bin")
                selectedUris = emptySet()
                selectedEntryDetails = emptyMap()
                trashRefreshKey += 1
                refresh()
                WidgetRefresher.refreshAll(context)
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

    /**
     * Stages [items] on the persistent Shelf -- the cluster's arc slot, the Actions room card, and
     * a previewed file's own quick action all funnel into this one place. [ShelfItem.sourceCrumb]
     * comes from wherever the workspace is standing right now, not from each item's own folder --
     * items dropped from one open tab share one crumb, matching commitTrayHere's own
     * `tab.locations` idiom.
     */
    fun addToShelf(items: List<FileEntry>) {
        if (items.isEmpty()) return
        val crumb = activeTab?.locations?.joinToString(" › ") { it.name }.orEmpty()
        val staged = items.map { entry ->
            ShelfItem(
                ref = entry.uri.toItemRef(),
                displayName = entry.name,
                kind = entry.kind,
                isDirectory = entry.isDirectory,
                sizeBytes = entry.sizeBytes,
                modifiedAtMillis = entry.lastModifiedMillis,
                addedAtMillis = System.currentTimeMillis(),
                sourceCrumb = crumb,
            )
        }
        val added = shelf.add(staged)
        refreshShelf()
        toast(if (added > 0) "Added $added to Shelf" else "${staged.size} already there")
    }

    // Members the probe has positively marked missing stay out of bulk Shelf operations: one
    // vanished source fails a whole copy batch or zip, and the sheet already names those
    // members for removal. Unprobed members stay in -- the probe may simply not have reached
    // them yet, and excluding them would quietly shrink an operation whose size is on screen.
    fun reachableShelfItems(): List<ShelfItem> = shelfItems.filterNot { item ->
        val uri = item.ref.toUri()
        shelfProbe.containsKey(uri) && shelfProbe[uri] == null
    }

    /**
     * "Copy here" / "Move here" from the Shelf deck, into the folder on screen -- mirrors
     * commitTrayHere; a successful move also takes its members off the Shelf, since they have
     * arrived where the drag was staging them for.
     */
    fun commitShelfHere(move: Boolean) {
        val tab = activeTab ?: run {
            toast("Open a folder to add into")
            return
        }
        val members = reachableShelfItems()
        if (members.isEmpty()) {
            if (shelfItems.isNotEmpty()) toast("Nothing on the Shelf is still reachable")
            return
        }
        val segments = tab.locations.drop(1).map(FolderLocation::name)
        val uris = members.map { it.ref.toUri() }
        scope.launch {
            loading = true
            runCatching {
                if (move) {
                    fileOperations.move(uris, tab.treeUri, ConflictPolicy.KEEP_BOTH, segments) { progress ->
                        operationMessage = "Moving ${progress.displayName}"
                    }
                } else {
                    fileOperations.copy(uris, tab.treeUri, ConflictPolicy.KEEP_BOTH, segments) { progress ->
                        operationMessage = "Copying ${progress.displayName}"
                    }
                }
            }.onSuccess { result ->
                toast(if (move) "Moved" else "Copied")
                if (move) {
                    // The uris returned here are already post-migration (onItemRelocated ran
                    // mid-move and rewrote each member's ref as its own delete succeeded), so
                    // removing by the RESULT is what actually finds them -- removing by the
                    // original refs captured before the call would silently miss every one.
                    shelf.removeAll(result.map { it.toItemRef() })
                    refreshShelf()
                }
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
                selectedEntryDetails = emptyMap()
                toast("On the clipboard")
            }
            DropTarget.MOVE -> {
                moveTray = moveTray.stage(cargo)
                selectedUris = emptySet()
                selectedEntryDetails = emptyMap()
                toast("Riding the move tray")
            }
            DropTarget.TRASH -> recycleUris(cargo.map(StagedItem::uri))
            else -> Unit
        }
    }

    /**
     * The entry behind a uri a gesture just handed back, looked up in what is actually on screen.
     *
     * [ClusterGestureHooks.onStartSolo] names its cargo by uri because that is all a row's
     * `entryGestures(key = ...)` carries. Three sources, in the order a row could have come from:
     * the open folder's listing, the search results replacing it, and the details map that
     * backfills home-surface selections [entries] never held. Null means the row that started
     * this drag is not one this workspace can describe -- no drag rather than an invented one.
     */
    fun entryForDrag(uri: Uri): FileEntry? = entries.find { it.uri == uri }
        ?: searchHits.firstOrNull { it.entry.uri == uri }?.entry
        ?: selectedEntryDetails[uri]

    /**
     * Is the tab band's trash tab a live drop target for what is currently in flight, and is the
     * finger on it? Both halves matter: a target that lights up for a drop it cannot perform is
     * worse than no target, and the recycle path needs an open tab to have a bin at all.
     */
    fun overTrashTab(at: Offset): Boolean {
        val bounds = trashTabBounds ?: return false
        return activeTab != null && clusterController.items.isNotEmpty() && bounds.contains(at)
    }

    /**
     * Would dropping [cargo] on [destination]'s chip actually move anything?
     *
     * The three no-ops, each of which must NOT light the tab up:
     * - the tab you are already in, and the same folder reached through a second tab -- either
     *   way the files are already there;
     * - a folder dropped onto a tab standing inside it (or inside one of its children): the
     *   destination's own breadcrumb IS its ancestry, so a cargo uri appearing anywhere in
     *   [FolderTab.locations] means this move would put a folder inside itself and lose the
     *   subtree. Checked against the breadcrumb rather than by walking the provider, because the
     *   breadcrumb is exact, already in memory, and cannot fail;
     * - nothing aboard.
     */
    fun dropIntoTabIsReal(destination: FolderTab, cargo: Set<Uri>, source: FolderTab?): Boolean = when {
        cargo.isEmpty() -> false
        destination.id == source?.id -> false
        destination.current.uri == source?.current?.uri -> false
        destination.locations.any { it.uri in cargo } -> false
        else -> true
    }

    /**
     * Which open tab a release at [at] would move the cluster into, or null.
     *
     * Bounds come from the band's own layout ([tabChipBounds]), so this is the tab the finger is
     * actually on rather than a guess at where the strip put it. Three ordering rules mirror how
     * the band paints, because every chip in the strip overlaps its neighbour: the TRASH chip is
     * front-most and wins its overlap outright; the ACTIVE chip is drawn above the rest (zIndex
     * 1) and is never a real destination, so a point on it is nobody's; and among the remainder
     * the later chip is on top, so the last match wins.
     */
    fun tabDropTarget(at: Offset): FolderTab? {
        val cargo = clusterController.items.map(StagedItem::uri).toSet()
        if (cargo.isEmpty()) return null
        // Trash outranks the strip. Its chip is pinned 40dp INTO its left neighbour and drawn
        // front-most, so the overlap belongs to the can, not to the folder tab whose bounds also
        // cover it -- without this, aiming at the left half of the bin would move the files into
        // Documents instead. One guard, so the cue and the release cannot disagree about it.
        if (overTrashTab(at)) return null
        if (activeTabId?.let { tabChipBounds[it] }?.contains(at) == true) return null
        val landed = tabs.lastOrNull { tabChipBounds[it.id]?.contains(at) == true } ?: return null
        return landed.takeIf { dropIntoTabIsReal(it, cargo, activeTab) }
    }

    /**
     * The tab drop itself: move [cargo] into [destination]'s current folder, through the same
     * [FileOperationService.move] every other move in this file goes through -- same conflict
     * policy, same progress line, same identity migration on the way. An accelerator for the
     * actions room's own Move, not a second implementation of it.
     */
    fun moveCargoIntoTab(destination: FolderTab, cargo: List<StagedItem>) {
        val uris = cargo.map(StagedItem::uri)
        if (uris.isEmpty()) return
        val segments = destination.locations.drop(1).map(FolderLocation::name)
        scope.launch {
            loading = true
            runCatching {
                fileOperations.move(uris, destination.treeUri, ConflictPolicy.KEEP_BOTH, segments) { progress ->
                    operationMessage = "Moving ${progress.displayName}"
                }
            }.onSuccess {
                toast("Moved to ${destination.current.name}")
                selectedUris = emptySet()
                selectedEntryDetails = emptyMap()
                refresh()
                WidgetRefresher.refreshAll(context)
            }.onFailure { toast(it.message ?: "The move failed") }
            operationMessage = null
            loading = false
        }
    }

    /** The row's press-hold drag ended; targets without a flight commit right here. */
    fun clusterReleased() {
        val cargo = clusterController.items
        // Another tab, first: the band's own chips are targets the drag layer knows nothing
        // about, so they are resolved here, before release() is asked what the layer's corner
        // slots think. (Trash still outranks them -- tabDropTarget refuses the bin's own chip
        // itself, so the two orderings cannot drift apart.) No flight is drawn for this one --
        // the same immediate commit SHELF, NEW_FOLDER and COMPRESS already take, for the same
        // reason: there is nowhere for the cards to fly TO but the chip already under the finger.
        val intoTab = tabDropTarget(clusterController.dragPosition)
        if (intoTab != null) {
            clusterController.settle()
            moveCargoIntoTab(intoTab, cargo)
            return
        }
        // The band's measured trash tab overrides the drag layer's own fixed corner geometry.
        // ClusterDragLayer hit-tests DropTarget.TRASH against DropTargetPolicy.trashCentre -- an
        // inset struck from the bottom-right corner, which only approximately covers where the
        // tab really sits -- and this file cannot change that from outside the layer. What it CAN
        // do is write the one reaction release() reads, which routes the drop through exactly the
        // same genie flight and exactly the same recycleUris() the corner can always used: same
        // bin, same journal, same "Put back", never a hard delete.
        if (overTrashTab(clusterController.dragPosition)) {
            clusterController.reactions = listOf(TargetReaction(DropTarget.TRASH, proximity = 1f, hit = true))
        }
        when (clusterController.release()) {
            DropTarget.NEW_FOLDER -> {
                clusterController.settle()
                pendingFolderItems = cargo
                pendingFolderFromShelf = false
                createDialog = "cluster-folder"
            }
            DropTarget.COMPRESS -> {
                clusterController.settle()
                // [cargo], not selectedEntries: every other branch here already acts on what was
                // actually in flight, and a solo drag (onStartSolo) carries one entry with no
                // selection behind it at all -- read the selection and this slot would have
                // launched a save dialog for an archive of nothing.
                archiveSources = cargo.map(StagedItem::uri)
                archiveCreator.launch("Fylz-${System.currentTimeMillis()}.zip")
            }
            DropTarget.SHELF -> {
                clusterController.settle()
                addToShelf(cargo.mapNotNull { it.entry })
                selectedUris = emptySet()
                selectedEntryDetails = emptyMap()
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
                    .onFailure { failure = it }
            }
            shredding = false
            shredTargets = null
            trashRefreshKey += 1
            failure?.let { toast(it.message ?: "Shredding failed for some files") }
            refresh()
            WidgetRefresher.refreshAll(context)
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

    // ── Modifier-qualified clicks (WP-A4's click half) ────────────────────────────────

    // Resynced from every hardware key event's meta flags at the shell root, so it can never
    // hold a stale modifier. With no keyboard attached it stays None and every click is plain.
    var heldModifiers by remember { mutableStateOf(HeldModifiers.None) }

    // The last plainly-clicked or toggled entry: Shift+click ranges from here. Uri, not index,
    // because sort and filter can reorder the listing between the two clicks.
    var selectionAnchorUri by remember { mutableStateOf<Uri?>(null) }

    /** Selects [entries] the same way toggleSelection does, keeping the details map in step. */
    fun selectEntries(entries: List<FileEntry>) {
        selectedUris = selectedUris + entries.map(FileEntry::uri)
        selectedEntryDetails = selectedEntryDetails + entries.associateBy(FileEntry::uri)
    }

    /**
     * The listing's open handler: a plain click opens, Ctrl/Meta+click toggles selection,
     * Shift+click selects the visible span from the anchor, Alt+click opens externally —
     * decided by [ModifierClickPolicy] against the list currently on screen (search results
     * while a recursive search is showing them, the folder listing otherwise, the same rule
     * selectAllVisible applies).
     */
    fun openWithModifiers(entry: FileEntry) {
        val visible = if (searchActive) searchHits.map { it.entry } else visibleEntries
        val decision = ModifierClickPolicy.decide(
            modifiers = heldModifiers,
            anchorIndex = selectionAnchorUri
                ?.let { anchor -> visible.indexOfFirst { it.uri == anchor } }
                ?.takeIf { it >= 0 },
            clickedIndex = visible.indexOfFirst { it.uri == entry.uri },
        )
        when (decision) {
            ModifierClick.Open -> {
                selectionAnchorUri = entry.uri
                openEntry(entry)
            }
            ModifierClick.ToggleSelection -> {
                selectionAnchorUri = entry.uri
                toggleSelection(entry)
            }
            is ModifierClick.SelectRange ->
                // The anchor deliberately stays put: Shift+clicking further extends from the
                // same origin, which is what every desktop file manager does.
                selectEntries(visible.subList(decision.fromIndex, decision.toIndex + 1))
            ModifierClick.OpenExternal -> openExternal(entry)
        }
    }

    /**
     * The dual-pane WORKFLOW over the existing tab model: with a second tab open, the selection
     * transfers into that tab's current folder, no destination picker involved. Tabs already
     * are independent navigation states, which is what a pane actually is — the side-by-side
     * RENDERING of two of them stays gated on WP-A1's width tiers
     * (docs/product/adaptive-input-plan.md), but nothing about the workflow needed to wait.
     * With three or more tabs, the most recently used other tab wins, and the toast names the
     * folder so a transfer into the wrong tab is visible immediately, not discovered later.
     */
    fun transferToOtherTab(move: Boolean): Boolean {
        val from = activeTab ?: return false
        if (selectedEntries.isEmpty()) return false
        val other = tabs.lastOrNull { it.id != from.id } ?: run {
            toast("Open a second tab to send files across")
            return true
        }
        val destinationName = other.current.name
        scope.launch {
            loading = true
            runCatching {
                val segments = other.locations.drop(1).map(FolderLocation::name)
                val sources = selectedEntries.map { it.uri }
                if (move) {
                    fileOperations.move(
                        sourceUris = sources,
                        destinationTreeUri = other.treeUri,
                        conflictPolicy = ConflictPolicy.KEEP_BOTH,
                        destinationPathSegments = segments,
                        sourceParentTreeUri = from.treeUri,
                        sourceParentSegments = from.locations.drop(1).map(FolderLocation::name),
                    ) { progress -> operationMessage = "Moving ${progress.displayName}" }
                } else {
                    fileOperations.copy(
                        sourceUris = sources,
                        destinationTreeUri = other.treeUri,
                        conflictPolicy = ConflictPolicy.KEEP_BOTH,
                        destinationPathSegments = segments,
                        sourceParentTreeUri = from.treeUri,
                        sourceParentSegments = from.locations.drop(1).map(FolderLocation::name),
                    ) { progress -> operationMessage = "Copying ${progress.displayName}" }
                }
            }.onSuccess {
                toast((if (move) "Moved to " else "Copied to ") + "“$destinationName”")
                selectedUris = emptySet()
                selectedEntryDetails = emptyMap()
                refresh()
            }.onFailure { toast(it.message ?: "The transfer failed") }
            operationMessage = null
            loading = false
        }
        return true
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
            FylzAction.ADD_TO_SHELF -> {
                addToShelf(selectedEntries)
                selectedUris = emptySet()
                selectedEntryDetails = emptyMap()
            }
            FylzAction.PIN_TO_DESKTOP -> {
                // A folder or file shortcut needs a tree grant; the active tab's own is the
                // obvious one, but a selection can also come from a home surface with no tab open
                // (List/Bento/Canvas against the landing subject), so the subject's own grant is
                // the fallback -- one of the two is always present whenever a selection exists at
                // all, since neither surface that carries one has anything to select without it.
                val treeUri = activeTab?.treeUri ?: landingSubject?.treeUri
                if (treeUri == null) {
                    toast("Unable to pin -- no folder context")
                } else {
                    val entriesToPin = selectedEntries
                    scope.launch(Dispatchers.IO) {
                        val existing = desktopStore.items()
                        var placements = existing.map(DesktopItem::placement)
                        val newItems = entriesToPin.map { entry ->
                            val placement = DesktopPolicy.nextFreePlacement(placements)
                            placements = placements + placement
                            if (entry.isDirectory) {
                                DesktopItem.FolderShortcut(
                                    treeUri = treeUri,
                                    folderUri = entry.uri,
                                    displayName = entry.name,
                                    placement = placement,
                                )
                            } else {
                                DesktopItem.FileShortcut(
                                    uri = entry.uri,
                                    treeUri = treeUri,
                                    displayName = entry.name,
                                    placement = placement,
                                )
                            }
                        }
                        // Bound to MAX_ITEMS here, dropping the oldest first -- the same
                        // direction DesktopStore.upsert() trims in -- so newly pinned items are
                        // never the ones silently discarded by replaceAll()'s own cap check.
                        val combined = existing + newItems
                        val bounded = if (combined.size > DesktopPolicy.MAX_ITEMS) {
                            combined.takeLast(DesktopPolicy.MAX_ITEMS)
                        } else {
                            combined
                        }
                        desktopStore.replaceAll(bounded)
                        withContext(Dispatchers.Main) {
                            homeRefreshKey += 1
                            toast("Pinned to desktop")
                        }
                    }
                }
            }
            FylzAction.CLEAR_SELECTION -> {
                selectedUris = emptySet()
                selectedEntryDetails = emptyMap()
            }
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
            QuickAction.ADD_TO_SHELF -> addToShelf(listOf(entry))
            QuickAction.RECYCLE -> {
                focusedEntry = null
                recycleUris(listOf(entry.uri))
            }
            QuickAction.COPY, QuickAction.MOVE, QuickAction.RENAME, QuickAction.TAGS -> {
                selectedUris = setOf(entry.uri)
                selectedEntryDetails = mapOf(entry.uri to entry)
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

    // A folder's chosen icon/colour/stickers and an item's own tags, both reachable from any
    // surface below (rows, cards, tiles, the folder tree, Overview) without threading a store or
    // a LibraryStore handle through every one of their signatures -- the same shape as
    // LocalThemeStyle above, in this composable rather than the root because both stores
    // (folderAppearanceStore, library) live here. Re-created whenever their version counter
    // bumps so a write becomes visible the same recomposition, not a stale closure held from
    // composition start.
    val folderAppearanceLookup = remember(appearanceVersion) { { uri: Uri -> folderAppearanceStore.get(uri) } }
    val tagsLookup = remember(tagsVersion) { { uri: Uri -> library.tags(uri) } }
    CompositionLocalProvider(
        LocalFolderAppearance provides folderAppearanceLookup,
        LocalTagsFor provides tagsLookup,
    ) {
    // The rungs below a folder's own back stack was always missing: an up-arrow tap could walk
    // it, but Back itself only ever reached the rooms handler and then finish(). Composed here,
    // immediately before that rooms handler, so BackHandler's own "last composed wins" order
    // ranks these below it -- a room open still closes first, matching the comment there.
    // Individually enabled and mutually exclusive on activeTab/locations/newTabId, so with no
    // room open exactly one governs any given press: drop a folder level, land on the storage
    // home once there is no level left to drop, abandon a "+" tab that never chose a folder, then
    // require a second press to actually leave the app.
    BackHandler(enabled = shell.atHome && (activeTab?.locations?.size ?: 0) > 1) {
        val tab = activeTab
        if (tab != null) {
            val index = tabs.indexOfFirst { it.id == tab.id }
            if (index >= 0) tabs[index] = tab.copy(locations = tab.locations.dropLast(1))
        }
    }
    BackHandler(enabled = shell.atHome && activeTab != null && activeTab.locations.size == 1) {
        activeTabId = null
        homeRefreshKey += 1
    }
    // A waiting "+" tab makes activeTab null, so without this rung the exit handler below would
    // fire on the first Back out of a new tab. The two conditions are kept mutually exclusive by
    // hand rather than by composition order -- re-broadening either one turns the first Back out
    // of a new tab back into "press back again to exit".
    BackHandler(enabled = shell.atHome && newTabId != null) { cancelNewTab() }
    BackHandler(enabled = shell.atHome && activeTab == null && newTabId == null) {
        val now = System.currentTimeMillis()
        if (now - pendingExitAt < EXIT_CONFIRM_WINDOW_MS) {
            activity?.finish()
        } else {
            pendingExitAt = now
            toast("Press back again to exit")
        }
    }

    // Back closes an open room before it does anything else: a room is not a back-stack entry,
    // but Back is the gesture people reach for to leave one.
    BackHandler(enabled = !shell.atHome) { shell.closeAll() }

    /**
     * WP-A3: hardware keys reach the same handlers touch does — KeyboardShortcutPolicy resolves,
     * this dispatches, and every selection command respects the same SelectionActionPolicy gates
     * the actions room draws from, so a keyboard can never ask for what a tap could not.
     * Returns whether the command was consumed; an unhandled command bubbles to the system.
     */
    fun runKeyboardCommand(command: KeyboardCommand): Boolean = when (command) {
        KeyboardCommand.SELECT_ALL -> {
            if (activeTab != null) { selectAllVisible(); true } else false
        }
        KeyboardCommand.CLEAR_SELECTION ->
            if (selectedUris.isNotEmpty() || !shell.atHome) {
                runAction(FylzAction.CLEAR_SELECTION); true
            } else false
        KeyboardCommand.COPY ->
            if (selectionActions.copy) { runAction(FylzAction.COPY); true } else false
        KeyboardCommand.CUT ->
            if (selectionActions.move) { runAction(FylzAction.MOVE); true } else false
        KeyboardCommand.RENAME ->
            if (selectionActions.rename) { runAction(FylzAction.RENAME); true } else false
        KeyboardCommand.RECYCLE ->
            if (selectionActions.recycle) { runAction(FylzAction.RECYCLE); true } else false
        KeyboardCommand.FIND, KeyboardCommand.FOCUS_LOCATION -> { focusSearch(); true }
        KeyboardCommand.REFRESH -> { refresh(); true }
        KeyboardCommand.NEW_FOLDER ->
            if (activeTab != null) { runAction(FylzAction.NEW_FOLDER); true } else false
        KeyboardCommand.NEW_FILE ->
            if (activeTab != null) { runAction(FylzAction.NEW_FILE); true } else false
        KeyboardCommand.NEW_TAB -> { addTab(); true }
        KeyboardCommand.CLOSE_TAB -> {
            val tab = activeTab
            if (tab != null) { closeTab(tab); true } else false
        }
        KeyboardCommand.COPY_TO_OTHER_PANE ->
            if (selectionActions.copy) transferToOtherTab(move = false) else false
        KeyboardCommand.MOVE_TO_OTHER_PANE ->
            if (selectionActions.move) transferToOtherTab(move = true) else false
        KeyboardCommand.UNDO -> {
            scope.launch {
                when (val outcome = undoService.undoLast()) {
                    is UndoService.Outcome.Undone -> {
                        toast("Undone: ${outcome.description}")
                        refresh()
                        trashRefreshKey += 1
                    }
                    is UndoService.Outcome.NothingToUndo -> toast(outcome.reason)
                    is UndoService.Outcome.Failed -> toast(outcome.message)
                }
            }
            true
        }
        // Focused-item commands (OPEN, PREVIEW, arrows) and the pane/undo families need focus
        // traversal or features that do not exist yet — WP-A3b and later. Explicitly unhandled
        // so the event bubbles rather than being swallowed with nothing to show for it.
        else -> false
    }

    // The shell is the default focus owner so hardware keys dispatch into composition at all;
    // a focused text field takes precedence naturally, and its unconsumed events still bubble
    // through this ancestor. onKeyEvent (bubbling), NOT onPreviewKeyEvent: a field's own
    // Ctrl+A must keep meaning "select the text", never "select the files behind the dialog".
    val hardwareKeyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { hardwareKeyFocus.requestFocus() } }

    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        scrimColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        cardColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                // Every key event restates the full modifier set, so this resync can never
                // strand a stale Ctrl the way tracking down/up transitions could.
                heldModifiers = HeldModifiers(
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    meta = event.isMetaPressed,
                )
                val gesture = event.toShortcutGesture() ?: return@onKeyEvent false
                val command = KeyboardShortcutPolicy.resolve(gesture) ?: return@onKeyEvent false
                runKeyboardCommand(command)
            }
            .focusRequester(hardwareKeyFocus)
            .focusable(),
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
                        discardNewTab()
                        activeTabId = id
                        shell.closeAll()
                    },
                    onOpenHome = {
                        discardNewTab()
                        activeTabId = null
                        homeRefreshKey += 1
                        shell.closeAll()
                    },
                    onClose = ::closeTab,
                    // "Add a location…" is the wheel's own "+", and it opens a tab here the same
                    // way the band's does. Granting access to a tree Fylz has no permission for
                    // is a separate, deliberate step the launch surface itself offers.
                    onAdd = {
                        shell.closeAll()
                        addTab()
                    },
                    onOpenSettings = {
                        shell.closeAll()
                        settingsOpen = true
                    },
                    allTags = allTagsMap,
                    // Tapping a tag inside the room closes the room and opens its results, the
                    // same destination the browser's own tag chips reach. The room does not need
                    // to know that openTagResults is device-wide; it just hands over the name.
                    onTagSelected = { tag ->
                        shell.closeAll()
                        openTagResults(tag)
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
    // Refresh is a shake, everywhere in the constellation. The pull-down space in a room's top
    // 56dp belongs to the top-room reveal and no other gesture may claim it there — that part is
    // unchanged. Below that band, pulling down on a listing already at rest reveals search
    // (PullDownSearchHost) instead of nothing: a deliberate narrowing of the old "pull-down is
    // reserved, full stop" rule, scoped so the shell's own top-edge claim still wins outright and
    // is never contested. Refresh itself stays off the touch plane regardless — a deliberate shake
    // needs no affordance, no instructional copy, and competes with no scroll. The toolbar button
    // stays for anyone who would rather tap than shake.
    //
    // What the shake MEANS is the user's preference (owner direction): the shared detector stays
    // exactly as cell-shell ships it, only the dispatch varies. OFF mounts no detector at all —
    // the sensor never registers, rather than registering and ignoring.
    if (shakeAction != ShakeAction.OFF) {
        ShakeToRefresh(onShake = {
            when (shakeAction) {
                ShakeAction.REFRESH -> refresh()
                ShakeAction.GO_HOME -> {
                    discardNewTab()
                    activeTabId = null
                    homeRefreshKey += 1
                    shell.closeAll()
                }
                ShakeAction.OFF -> Unit
            }
        })
    }

    // How much of the top bar's leading edge the ActionsBar is covering. Measured off the bar
    // itself where it is mounted below, not restated from its own width constant -- that constant
    // is private to ui/chrome and this is the caller's business anyway. The seed is a first-frame
    // estimate of that same width and is overwritten by the real measurement on the first layout
    // pass after a selection starts.
    val layoutDensity = LocalDensity.current
    var actionsBarWidth by remember { mutableStateOf(209.dp) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // WP-A1, first step: the wide cut sits on the canonical EXPANDED window-class boundary
        // (840dp) rather than the old ad-hoc 900dp, so unfolded book-posture foldables and most
        // landscape tablets get the two-pane layout they have room for. The real WP-A1 -- a
        // medium tier for landscape phones, chrome that collapses in short windows -- is a
        // layout restructure gated on device acceptance, not a constant change; see
        // docs/product/adaptive-input-plan.md.
        val wide = maxWidth >= 840.dp
        // Hoisted here, above both consumers: FileBrowser's own onNavigateUp argument (inside
        // Scaffold's content below) and the bottom chrome's TabBand (a sibling of Scaffold in
        // this same Box, mounted after it closes -- see "Bottom chrome" further down) both need
        // the identical up-navigation behaviour and readiness check. A folder open in two places
        // at once is exactly the kind of thing that drifts if each call site re-derives it by hand.
        val canNavigateUp = activeTab?.locations?.let { it.size > 1 } ?: false
        val navigateUp: () -> Unit = {
            val tab = activeTab
            if (tab != null) {
                val index = tabs.indexOfFirst { it.id == tab.id }
                if (index >= 0 && tab.locations.size > 1) {
                    tabs[index] = tab.copy(locations = tab.locations.dropLast(1))
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    // The title is also the way into the details room for anyone who would
                    // rather tap than drag — the same courtesy the Refresh button pays the
                    // shake. What it opens is a description of the folder it is naming, which
                    // is the one thing a title could open without surprising anybody.
                    title = {
                        // No longer crossfades to a selection count: the chrome's own SelectionRow
                        // (mounted at the outer Box level, below) is the one place that count is
                        // shown now -- a live selection used to also retitle this bar with the
                        // same number, which was the "second live count" the plan asked reconciled.
                        // The title keeps naming the PLACE regardless of selection.
                        if (activeTab != null) {
                            Text(
                                activeTab.current.name,
                                // One line, ellipsised: the bar clips whatever overflows it, and a
                                // name cut by the clip looks like the name, while one cut by an
                                // ellipsis reads as a name that did not fit.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                    navigationIcon = {
                        // The ActionsBar stays an overlay at the outer Box below -- its square
                        // top-left corner has to sit flush against the screen edge, which this
                        // slot's own 4dp inset and vertical centring would break. What lives here
                        // is only its FOOTPRINT: material3 places the title at
                        // max(title inset, navigationIcon width), so a spacer as wide as the bar
                        // is what pushes the title out from under it. Without this the title is
                        // laid out at 16dp with nothing told about the ~209dp slab drawn on top
                        // of it, and a folder name simply disappears behind the bar.
                        if (selectedUris.isNotEmpty()) Spacer(Modifier.width(actionsBarWidth))
                    },
                    actions = {
                        if (selectedUris.isNotEmpty()) {
                            // Select-all needs a listing to draw from, and the top bar only has
                            // one (visibleEntries/searchHits) while a folder tab is open -- a
                            // selection made on a home surface has nothing to select-all against.
                            // No "Done"/clear button here any more: the chrome's own SelectionRow
                            // close glyph is the one clear affordance now, matching a live
                            // selection to exactly one way to leave it instead of three.
                            if (activeTab != null) {
                                TactileIconKey(
                                    icon = Icons.Outlined.SelectAll,
                                    contentDescription = stringResource(R.string.browser_select_all),
                                    onClick = ::selectAllVisible,
                                )
                            }
                        } else {
                            // Composed only while the Shelf holds something -- zero chrome at
                            // rest, and visible regardless of whether a folder tab is open, since
                            // the Shelf outlives any one of them.
                            if (shelfItems.isNotEmpty()) {
                                // BadgedBox stays stock M3 -- it is a badge-overlay layout wrapper,
                                // not one of the controls this wave converts, and it composes fine
                                // around a TactileIconKey the same way it did around the old Icon.
                                BadgedBox(badge = { Badge { Text("${shelfItems.size}") } }) {
                                    TactileIconKey(
                                        icon = Icons.Outlined.Inventory2,
                                        contentDescription = "The Shelf, ${shelfItems.size} items",
                                        onClick = { deckOpen = DeckSource.SHELF },
                                    )
                                }
                            }
                            // Both act on the listing; on the storage home surface there is no
                            // listing to arrange or refresh, so they disappear rather than sit
                            // there wired to nothing.
                            if (activeTab != null) {
                                // What is left here is what changes how the listing is
                                // *displayed*. Everything that changes a file — new folder, scan,
                                // duplicates, the AI proposal, and the whole selection bar — moved
                                // to the actions room. An overflow menu mixing "make a folder
                                // here" with "open the index manager" was why it had eleven items
                                // and no shape; splitting it by "does this touch my files" is what
                                // finally gave it one. The old two/three-mode cycle button is an
                                // Arrange picker now — five view modes plus S/M/L outgrew a single
                                // tap-to-cycle icon the moment Stacks and Canvas joined the other
                                // three.
                                ArrangeMenu(
                                    viewMode = viewMode,
                                    onViewModeChange = { mode ->
                                        viewMode = mode
                                        preferencesStore.setViewMode(mode)
                                        // Stacks with nothing to stack by reads as a plain list
                                        // with dead space where the headers should be; default it
                                        // once rather than leave every first-time visitor to find
                                        // the group picker themselves. Remembered afterward, same
                                        // as any other sortSpec field.
                                        if (mode == ViewMode.STACKS && sortSpec.groupBy == null) {
                                            sortSpec = sortSpec.copy(groupBy = GroupAxis.KIND)
                                        }
                                    },
                                    density = density,
                                    onDensityChange = { mode ->
                                        density = mode
                                        preferencesStore.setDensity(mode)
                                    },
                                    // Results are one ranked list in every view mode, so the five
                                    // modes cannot change what is on screen while a search is
                                    // showing. The S/M/L axis still can, and stays live.
                                    viewModesEnabled = !searchActive,
                                )
                                // Sort and select-all used to ride the floating command pill,
                                // which was a permanent fixture over the listing; now that the
                                // pill only shows while search is pulled down (PullDownSearchHost),
                                // both moved to the one row that is always on screen while a
                                // folder is open.
                                //
                                // Withdrawn outright during a recursive search, rather than shown
                                // disabled: unlike Arrange it has no half that still works. Hits
                                // arrive ranked by the search engine and re-sorting them by the
                                // browser's sort column is exactly the ordering a live search must
                                // not use (see searchHits' own comment) -- so every field this
                                // menu edits is unread, with nothing to enable later. Same
                                // reasoning, and the same precedent, as the edge scrubber's own
                                // `query.isBlank()` gate below.
                                if (!searchActive) {
                                    SortMenu(sortSpec, onChange = { sortSpec = it })
                                }
                                TactileIconKey(
                                    icon = Icons.Outlined.Refresh,
                                    contentDescription = "Refresh",
                                    onClick = { refresh() },
                                )
                            }
                        }
                    },
                )
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
                                    scope.launch { WidgetRefresher.refreshAll(context) }
                                }
                            },
                            onOpenRoot = { discardNewTab(); activeTabId = null; homeRefreshKey += 1 },
                            onRecycle = { trashSheetOpen = true },
                            modifier = Modifier.width(210.dp).fillMaxHeight(),
                        )
                        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                    }
                    // The fourth desktop ask: parent and current side by side, Finder's column
                    // view. Additive and read-only against activeTab.locations -- dropLast(1)'s
                    // own last entry is the parent, so this needs no navigation state of its own,
                    // and tapping a sibling folder in it replaces the tab's own last location
                    // rather than pushing a new one, exactly like the locations room's tree does.
                    // Mutually exclusive with the docked PreviewPane below: LibraryRail (210dp) +
                    // this pane (280dp) + PreviewPane (380dp) all beside FileBrowser at once would
                    // leave the actual listing a sliver at this same 900dp breakpoint, so at most
                    // one of the two optional side panes shows alongside the rail.
                    if (wide && activeTab != null && activeTab.locations.size > 1 && previewMode != PreviewMode.DOCKED) {
                        ParentFolderPane(
                            treeUri = activeTab.treeUri,
                            parent = activeTab.locations[activeTab.locations.size - 2],
                            currentUri = activeTab.current.uri,
                            repository = repository,
                            showHidden = showHidden,
                            refreshKey = refreshKey,
                            onOpenSibling = { location ->
                                val tab = activeTab
                                val index = tabs.indexOfFirst { it.id == tab.id }
                                if (index >= 0) {
                                    tabs[index] = tab.copy(locations = tab.locations.dropLast(1) + location)
                                }
                            },
                            onOpenFile = ::openEntry,
                            modifier = Modifier.width(280.dp).fillMaxHeight(),
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
                        density = density,
                        groupedListing = stacksListing,
                        refreshKey = refreshKey,
                        loading = loading,
                        operationMessage = operationMessage,
                        onOpenStorageRoot = ::openStorageRoot,
                        onPickFolder = { root -> rootPicker.launch(root?.initialUri) },
                        onOpenRemotes = { remoteDialog = true },
                        homeRefreshKey = homeRefreshKey,
                        // A "+" tab is asking "which location", and the desktop and the subject
                        // views do not answer that -- the launch surface is the only home surface
                        // that lists roots to open. Deliberately overriding the stored home
                        // preference for this one case; do not "fix" it back.
                        homeMode = if (newTabId != null) HomeMode.LOCATIONS else homeMode,
                        landingSubject = landingSubject,
                        onOpenHomeFolder = { location ->
                            landingSubject?.let { subject -> openTabAt(subject.treeUri, location) }
                        },
                        desktopStore = desktopStore,
                        wallpaperSpec = wallpaperSpec,
                        desktopSnap = desktopSnap,
                        desktopLabels = desktopLabels,
                        desktopCallbacks = DesktopCallbacks(
                            onOpenFolderShortcut = ::openDesktopFolderShortcut,
                            onOpenFileShortcut = ::openDesktopFileShortcut,
                            onOpenTrash = { trashSheetOpen = true },
                            onOpenShelf = { deckOpen = DeckSource.SHELF },
                            onFocusSearch = ::focusSearch,
                            onScan = ::startScan,
                            onOpenTag = ::openTagResults,
                            onOpenFavorite = ::openFavorite,
                            onOpenWallpaperPicker = { wallpaperPickerOpen = true },
                            onOpenLargeFiles = { largeFilesSheetOpen = true },
                            onPickFolderShortcut = { desktopFolderShortcutPicker.launch(null) },
                            onPickQuickAccessFolder = { widgetId ->
                                pendingQuickAccessWidgetId = widgetId
                                quickAccessFolderPicker.launch(null)
                            },
                            onPickDuplicatesFolder = { duplicatesFolderPicker.launch(null) },
                            onGrantFullAccess = { context.startActivity(FullAccessPermission.intent(context)) },
                        ),
                        pullDownSearchState = pullDownSearchState,
                        searchFocusRequester = searchFocusRequester,
                        pendingSearchFocus = pendingSearchFocus,
                        onSearchFocusConsumed = { pendingSearchFocus = false },
                        onQueryChange = { query = it },
                        onNavigateUp = navigateUp,
                        onOpen = ::openWithModifiers,
                        onOpenTabFolder = ::openFolderInActiveTab,
                        onOpenExternal = ::openExternal,
                        onToggleSelection = ::toggleSelection,
                        entryMenu = EntryMenuHooks(
                            actionsFor = ::entryMenuActions,
                            show = { entry -> entryMenuFor = entry },
                        ),
                        listState = listState,
                        gridState = gridState,
                        cluster = ClusterGestureHooks(
                            onPositioned = { uri, centre -> clusterOrigins[uri] = centre },
                            onStart = { at ->
                                // Fire-and-forget: primes the thumbnail cache for the cards the
                                // drag layer is about to draw so its per-frame physics loop never
                                // has to touch IO itself.
                                warmThumbnails(selectedEntries.take(5), context.contentResolver)
                                clusterController.start(
                                    items = selectedEntries.map { StagedItem(it.uri, it.name, it.kind, it) },
                                    origins = clusterOrigins.toMap(),
                                    at = at,
                                )
                            },
                            onStartSolo = { uri, at ->
                                // One entry, named by the row that was held -- no selection is
                                // consulted and none is made. Everything downstream (the layer,
                                // the targets, the flights, clusterReleased) is identical to a
                                // one-item selection drag; only the gathering differs.
                                entryForDrag(uri)?.let { entry ->
                                    warmThumbnails(listOf(entry), context.contentResolver)
                                    clusterController.start(
                                        items = listOf(StagedItem(entry.uri, entry.name, entry.kind, entry)),
                                        origins = clusterOrigins.toMap(),
                                        at = at,
                                    )
                                }
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

        // ── Bottom chrome: the folder tabs, and the selection row riding above them while a
        // selection is live ── mounted here, at the outer Box, NOT inside FileBrowser and NOT in
        // Scaffold's own bottomBar slot. The selection row occupying Scaffold's bottomBar (the
        // old SelectionSummaryBar's spot) shrinks the padding Scaffold hands its content, which
        // shrinks FileBrowser's own Box in turn — a tab band mounted inside that Box, or in the
        // bottomBar itself, would slide up and down every time a selection starts or ends. Mounted
        // as a plain overlay instead, both pieces hold one fixed position regardless of selection.
        // The tab band shows while a tab is open -- including a "+" tab that has not chosen its
        // folder yet, which is showing the launch surface behind it (LocationsRoom's word wheel
        // is still how tabs switch from the home surfaces).
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // Wrapped in its own clickable rather than SelectionRow growing an onOpenDeck param
            // (that composable is Workstream A's, and its own contract carries no such callback):
            // the close button's own, more specific clickable inside SelectionRow wins a tap
            // squarely on it, so this outer one only ever fires for the count pill and the row's
            // own dead space -- the exact "tap the count to riffle the deck" behaviour
            // SelectionSummaryBar carried, preserved rather than dropped.
            //
            // SelectionRow itself draws nothing at count <= 0, but this wrapper is not part of
            // it: mounted unconditionally it left a zero-size clickable node announcing
            // "Review 0 selected" to a screen reader at rest, a count with nothing behind it.
            if (selectedUris.isNotEmpty()) {
                Box(
                    Modifier.clickable(onClick = { deckOpen = DeckSource.SELECTION })
                        .semantics { contentDescription = "Review ${selectedUris.size} selected" },
                ) {
                    SelectionRow(
                        count = selectedUris.size,
                        onClose = { selectedUris = emptySet(); selectedEntryDetails = emptyMap() },
                    )
                }
            }
            // Also while a "+" tab is waiting for its folder: that tab has no listing behind it
            // yet, but it is on screen and it is what the band's front chip is naming, so the
            // band has to be there for the user to leave it by.
            if (activeTab != null || newTabId != null) {
                TabBand(
                    // The pending chip is appended, never merged into [tabs] -- a FolderTab with
                    // no location cannot be constructed (see [newTabId]), and TabBandItem is a
                    // plain id/label pair that needs nothing from the model to carry one.
                    tabs = tabs.map { TabBandItem(it.id, it.current.name.ifBlank { "Folder" }) } +
                        listOfNotNull(newTabId?.let { TabBandItem(it, "New tab") }),
                    activeTabId = activeTabId ?: newTabId,
                    onTabSelected = { id ->
                        // Leaving an empty new tab for a real one discards it: there is nothing
                        // in it to come back to, and keeping it would leave a second at-home-ish
                        // place behind the one the user just went to.
                        if (id != newTabId) {
                            discardNewTab()
                            activeTabId = id
                        }
                    },
                    onTabClosed = { item ->
                        if (item.id == newTabId) {
                            cancelNewTab()
                        } else {
                            tabs.firstOrNull { it.id == item.id }?.let(::closeTab)
                        }
                    },
                    onAddTab = ::addTab,
                    onTrashTap = { trashSheetOpen = true },
                    // The band's own leading chip, ahead of the tab run: an up-chevron to the
                    // enclosing folder normally, swapped for a red recycle button the moment a
                    // selection goes live -- the same "back button disappears, replaced by the
                    // recycle bin option" the owner asked for. canNavigateUp/navigateUp are
                    // hoisted above (see [BoxWithConstraints]'s own comment); recycleSelection is
                    // the very function FylzAction.RECYCLE already calls from the actions menu, so
                    // this button and that menu item do the same thing by construction, not by
                    // two implementations happening to agree.
                    canNavigateUp = canNavigateUp,
                    onNavigateUp = navigateUp,
                    selectionActive = selectedUris.isNotEmpty(),
                    onRecycleSelection = ::recycleSelection,
                    frosted = themeStyle == ThemeStyle.FYLZ,
                    // What makes the trash tab a real drop target rather than a picture of one:
                    // its own measured position, in the same root coordinate space entryGestures
                    // reports every drag position in. Read by [overTrashTab] at release.
                    //
                    // The tab draws no cue of its own (TabBand's trashProximity stays unused):
                    // ClusterDragLayer's trash bulge is composed at zIndex 20 over this whole
                    // corner, so the can the finger approaches -- swelling, tilting, lid opening,
                    // washing red once the drop would commit -- is the layer's, drawn directly on
                    // top of this tab. A second cue painted here would be behind it.
                    trashModifier = Modifier.onGloballyPositioned {
                        trashTabBounds = it.boundsInRoot()
                    },
                    onTabBounds = { id, bounds -> tabChipBounds[id] = bounds },
                    // The folder chips are far enough from the corner that the drag layer's
                    // bulges do NOT cover them, so unlike the trash tab this cue is really seen:
                    // the chip a cluster is over lifts before the finger lets go. Evaluated in
                    // the chip, and deliberately the very same predicate the release runs, so a
                    // tab that lights up is a tab that will take the drop.
                    // `active` is read first for two reasons: at rest it costs one boolean
                    // instead of a hit test per chip per frame, and -- less obviously -- it is
                    // the OBSERVABLE that ends the cue. dragPosition is not cleared when a drag
                    // finishes, so a chip subscribed only to that would sit lit until something
                    // unrelated happened to recompose it; the phase changing is what actually
                    // tells these chips the drag is over.
                    armedTabId = {
                        if (clusterController.active) {
                            tabDropTarget(clusterController.dragPosition)?.id
                        } else {
                            null
                        }
                    },
                )
            }
        }
        // Top-left, only while a selection is live -- the new route to zip/move/copy that used
        // to be SelectionSummaryBar's "Actions" button. statusBarsPadding keeps its square top-left
        // corner clear of the status bar, the same edge the tab band clears at the opposite end
        // with navigationBarsPadding.
        if (selectedUris.isNotEmpty()) {
            ActionsBar(
                onZip = { runAction(FylzAction.ARCHIVE) },
                onMove = { runAction(FylzAction.MOVE) },
                onCopy = { runAction(FylzAction.COPY) },
                // The measurement the top bar reserves against -- see [actionsBarWidth]. Chained
                // after statusBarsPadding so what is reported is the bar's whole occupied width,
                // whatever ui/chrome sizes it to, rather than a number copied out of that file.
                modifier = Modifier.align(Alignment.TopStart)
                    .statusBarsPadding()
                    .onSizeChanged { size ->
                        actionsBarWidth = with(layoutDensity) { size.width.toDp() }
                    },
            )
        }

        // Present at the top, one card per in-flight file operation -- notification, then
        // auto-minimized, then whichever one (at most) the user tapped back open. Started clear
        // of ActionsBar's own top-left corner: while a selection is live this insets by that
        // bar's own measured [actionsBarWidth] rather than a guessed constant, the same
        // measure-don't-guess trick that bar's own comment names.
        ActivityOverlay(
            tracker = fileOperations.activityTracker,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(
                    start = if (selectedUris.isNotEmpty()) actionsBarWidth + 8.dp else 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                ),
        )

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
        // CANVAS has no list index to scrub (a freeform layout isn't a sequence), and CLI draws
        // its own tree instead of any of the five view modes below, so neither reaches here either.
        // Build 11.5: nor does the STACKS+DATE arrangement (frames 2/4) -- LeftTimelineRail is its
        // own, quieter left-edge stand-in (FileBrowser's own STACKS branch), and the two rails
        // drawn at once would be a second, disagreeing scrubber for the same list.
        if (
            activeTab != null &&
            visibleEntries.size > 1 &&
            selectedEntries.isEmpty() &&
            query.isBlank() &&
            viewMode != ViewMode.CANVAS &&
            themeStyle != ThemeStyle.CLI &&
            !(viewMode == ViewMode.STACKS && sortSpec.groupBy == GroupAxis.DATE)
        ) {
            val grid = viewMode == ViewMode.GRID
            // STACKS interleaves headers, so the lazy list's own index no longer lines up with an
            // entry's index the way entryStops speaks it -- its own headers, which stacksListing
            // already placed at their exact row index, are the stops instead: scrubbing to
            // "Images" then means exactly the row reading "Images", not a translated guess at one.
            val stops = remember(visibleEntries, sortSpec, viewMode, stacksListing) {
                if (viewMode == ViewMode.STACKS) {
                    stacksListing.rows.withIndex().mapNotNull { (index, row) ->
                        (row as? ListingRow.Header)?.let { ScrubberStop(it.label, index) }
                    }
                } else {
                    entryStops(visibleEntries, sortSpec)
                }
            }
            val itemCount = if (viewMode == ViewMode.STACKS) stacksListing.rows.size else visibleEntries.size
            EdgeTimelineScrubber(
                stops = stops,
                itemCount = itemCount,
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
                // letters landing on the toolbar's buttons, its foot under the tab band. Both
                // ends were unhittable: a tap there goes to whatever is on top. Insetting costs a
                // little travel and makes the entire strip a real target. TabBandHeight alone is
                // enough here (not the selection row's height too): this scrubber's own gate
                // above already requires selectedEntries.isEmpty(), so the selection row never
                // shows while it does.
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = TOP_BAR_HEIGHT, bottom = TabBandHeight),
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

        // ── Resting bulge: the clipboard/move trays' standing presence while they hold
        // something ── drawn only outside a drag (the drag layer renders its own swollen
        // version) and only while occupied. The trash's own resting bulge is retired -- its tap
        // target and its standing count both live on the tab band's trash tab now (see the
        // bottom chrome block below); the drag-time drop zone this bulge never actually owned
        // either (ClusterDragLayer's own DropTargetPolicy.trashCentre is a fixed geometric inset,
        // not this bulge's measured position) is unaffected by its removal.
        if (!clusterController.active && (!clipboardTray.isEmpty || !moveTray.isEmpty)) {
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
                    tint = InkContent,
                    modifier = Modifier.size(22.dp),
                )
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

        // The held row's own menu. Its contents come from the same [entryMenuActions] the row
        // published as accessibility actions, so what the sheet offers and what TalkBack offers
        // cannot disagree. The emptiness check is belt-and-braces rather than a real state --
        // entryGestures only ever reaches its menu branch for a row whose actions were non-empty
        // -- and it stays a plain skip rather than clearing entryMenuFor, because a state write
        // during composition is a recomposition loop waiting for its first real caller.
        entryMenuFor?.let { entry ->
            val actions = entryMenuActions(entry)
            if (actions.isNotEmpty()) {
                EntryMenuSheet(entry = entry, actions = actions, onDismiss = { entryMenuFor = null })
            }
        }

        if (trashSheetOpen) {
            // The one recycle-bin surface now: every record the store actually holds, not a
            // process-scoped guess -- the settings dialog's old separate route into RecycleBinDialog
            // is gone too (see LibraryRail/settingsOpen below), so there is exactly one place this
            // list is read and exactly one truth about what the bin shows.
            val records = remember(trashRefreshKey) { recycleBin.records() }
            TrashBrowserSheet(
                records = records,
                onPutBack = { record ->
                    scope.launch {
                        runCatching { recycleBin.restore(record.itemId, conflictPolicy = ConflictPolicy.KEEP_BOTH) }
                            .onSuccess {
                                trashRefreshKey += 1
                                refresh()
                                WidgetRefresher.refreshAll(context)
                            }
                            .onFailure { toast(it.message ?: "Restore failed") }
                    }
                },
                onShred = { record -> shredTargets = listOf(record) },
                onShredAll = { shredTargets = records },
                onDismiss = { trashSheetOpen = false },
            )
        }

        if (wallpaperPickerOpen) {
            WallpaperPickerSheet(
                current = wallpaperSpec,
                onPick = { spec ->
                    wallpaperSpec = spec
                    wallpaperPreferences.setSpec(spec)
                },
                onDismiss = { wallpaperPickerOpen = false },
            )
        }

        if (largeFilesSheetOpen) {
            val usage = remember(homeRefreshKey) { StorageUsageStore(context).snapshot() }
            LargeFilesSheet(
                files = usage?.largestFiles.orEmpty(),
                onOpenFile = { uriString ->
                    largeFilesSheetOpen = false
                    scope.launch {
                        val entry = runCatching { repository.probe(Uri.parse(uriString)) }.getOrNull()
                        if (entry != null) openEntry(entry) else toast("Can't find this anymore")
                    }
                },
                onDismiss = { largeFilesSheetOpen = false },
            )
        }

        shredTargets?.let { targets ->
            ShredConfirmOverlay(
                itemCount = targets.size,
                shredding = shredding,
                onConfirm = { shredNow(targets) },
                onDismiss = { if (!shredding) shredTargets = null },
                deliberate = deliberateActions,
            )
        }

        // The riffle-able deck: the current selection or the Shelf, drawn last among this
        // group so it sits above the tray/trash sheets, matching the BackHandler order below
        // (deck peels first).
        when (deckOpen) {
            DeckSource.SELECTION -> {
                val crumb = activeTab?.locations?.joinToString(" › ") { it.name }
                FileDeckSurface(
                    items = selectedEntries.map { it.toDeckItem(crumb) },
                    title = "Selected",
                    caption = "${selectionActions.count} " +
                        "${if (selectionActions.count == 1) "item" else "items"} · " +
                        formatBytes(selectedEntries.sumOf { it.sizeBytes ?: 0L }),
                    onRemove = { item ->
                        selectedUris = selectedUris - item.uri
                        selectedEntryDetails = selectedEntryDetails - item.uri
                        if (selectedUris.isEmpty()) deckOpen = null
                    },
                    onDismiss = { deckOpen = null },
                )
            }
            DeckSource.SHELF -> {
                val missingRefs = shelfItems.filter { item ->
                    val uri = item.ref.toUri()
                    shelfProbe.containsKey(uri) && shelfProbe[uri] == null
                }.map(ShelfItem::ref)
                ShelfSheet(
                    items = shelfDeckItems,
                    caption = "${shelfItems.size} ${if (shelfItems.size == 1) "item" else "items"} · " +
                        formatBytes(shelfItems.sumOf { it.sizeBytes ?: 0L }),
                    hasMissing = missingRefs.isNotEmpty(),
                    canCommitHere = activeTab != null,
                    onRemove = { item -> shelf.remove(item.uri.toItemRef()); refreshShelf() },
                    onRemoveMissing = {
                        shelf.removeAll(missingRefs)
                        refreshShelf()
                    },
                    onCopyHere = { commitShelfHere(move = false) },
                    onMoveHere = { commitShelfHere(move = true) },
                    onCompress = {
                        val members = reachableShelfItems()
                        if (members.isEmpty()) {
                            toast("Nothing on the Shelf is still reachable")
                        } else {
                            archiveSources = members.map { it.ref.toUri() }
                            archiveCreator.launch("Fylz-Shelf-${System.currentTimeMillis()}.zip")
                        }
                    },
                    onNewFolderWith = {
                        val members = reachableShelfItems()
                        if (members.isEmpty()) {
                            toast("Nothing on the Shelf is still reachable")
                        } else {
                            pendingFolderItems = members.map { StagedItem(it.ref.toUri(), it.displayName, it.kind) }
                            pendingFolderFromShelf = true
                            createDialog = "cluster-folder"
                        }
                    },
                    onShare = {
                        val fileEntries = shelfDeckItems.filterNot { it.isDirectory }.mapNotNull { it.entry }
                        if (shelfDeckItems.any { it.isDirectory }) toast("Folders excluded from sharing")
                        shareEntries(fileEntries)
                    },
                    onClear = {
                        shelf.clear()
                        refreshShelf()
                    },
                    onDismiss = { deckOpen = null },
                )
            }
            null -> Unit
        }
    }
    }
    }

    // Composed after the shell's handler so it wins while a sheet is up: Back peels the deck,
    // then the shred confirm, then a tray or trash sheet, before it ever reaches a room.
    BackHandler(enabled = deckOpen != null || shredTargets != null || openTray != null || trashSheetOpen) {
        when {
            deckOpen != null -> deckOpen = null
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
                pendingFolderFromShelf = false
            },
            onConfirm = { name ->
                createDialog = null
                val tab = activeTab
                val folderFromShelf = pendingFolderFromShelf
                activeTab?.current?.uri?.let { parent ->
                    scope.launch {
                        // Only "cluster-folder" ever populates this -- the move's own resulting
                        // uris, needed afterward to take Shelf members off the Shelf by the ref
                        // they actually landed at rather than the one they started from (see
                        // commitShelfHere's identical note on why the RESULT is what to remove).
                        var movedUris: List<Uri> = emptyList()
                        runCatching {
                            when (kind) {
                                "folder" -> repository.createDirectory(parent, name)
                                // Dropped on "New folder" (cluster drag or the Shelf's "New
                                // folder with"): make it, then move the items in. The move
                                // resolves the folder by walking display names, so it rides the
                                // same journaled path as every other transfer.
                                "cluster-folder" -> {
                                    checkNotNull(tab) { "No folder is open." }
                                    repository.createDirectory(parent, name)
                                    val segments = tab.locations.drop(1).map(FolderLocation::name) + name
                                    movedUris = fileOperations.move(
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
                                if (folderFromShelf) {
                                    shelf.removeAll(movedUris.map { it.toItemRef() })
                                    refreshShelf()
                                } else {
                                    selectedUris = emptySet()
                                    selectedEntryDetails = emptyMap()
                                }
                            }
                            refresh()
                        }.onFailure { toast(it.message ?: "Unable to create item") }
                        pendingFolderItems = emptyList()
                        pendingFolderFromShelf = false
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
                            .onSuccess { selectedUris = emptySet(); selectedEntryDetails = emptyMap(); refresh() }
                            .onFailure { toast(it.message ?: "Unable to rename") }
                    }
                }
            },
        )
    }

    if (tagDialog) {
        // Seeded from the UNION of every selected entry's own tags, not just the first -- and
        // remembered once per opening (not re-derived on every keystroke the dialog's own field
        // produces), since it is also the "before" half of the delta applied on confirm. Without
        // this, a multi-select edit used to overwrite every entry but the first with the first's
        // own list, even on an untouched Save -- see LibraryStore.applyTagDelta's own KDoc.
        val tagsBefore = remember { unionOfTags(selectedEntries.map { library.tags(it.uri) }) }
        TagDialog(
            initial = tagsBefore.joinToString(", "),
            onDismiss = { tagDialog = false },
            onConfirm = { tags ->
                val tagsAfter = tags.split(',').map(String::trim).filter(String::isNotBlank).toSet()
                selectedEntries.forEach { entry ->
                    library.setTags(entry.uri, applyTagDelta(library.tags(entry.uri), tagsBefore, tagsAfter))
                }
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
                    runCatching { fileTools.executeBatchRename(plans, parentUri = activeTab?.current?.uri) }
                        .onSuccess { selectedUris = emptySet(); selectedEntryDetails = emptyMap(); refresh() }
                        .onFailure { toast(it.message ?: "Batch rename failed") }
                }
            },
        )
    }


    tagResultsFor?.let { tag ->
        TagResultsDialog(
            tag = tag,
            results = tagResults,
            onOpen = { entry ->
                tagResultsFor = null
                openEntry(entry)
            },
            onDismiss = { tagResultsFor = null },
        )
    }

    duplicateResult?.let { result ->
        AlertDialog(
            onDismissRequest = { duplicateResult = null },
            title = { Text("Duplicate files") },
            text = { Text(result) },
            confirmButton = { TactileButton(text = "Done", onClick = { duplicateResult = null }, style = TactileButtonStyle.SECONDARY) },
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
                    is InAppPickerRequest.ArchiveOutput -> {
                        archiveSources = selectedEntries.map { it.uri }
                        archiveCreator.launch(request.suggestedName)
                    }
                    is InAppPickerRequest.PdfOutput -> pdfOutputCreator.launch(request.suggestedName)
                }
            },
            onResult = { outcome ->
                pickerRequest = null
                when (request) {
                    is InAppPickerRequest.Destination ->
                        (outcome as? PickerOutcome.Folder)?.let { performDestination(request.action, it.folderUri) }
                    is InAppPickerRequest.ArchiveOutput -> (outcome as? PickerOutcome.Save)?.let { save ->
                        val sources = selectedEntries.map { it.uri }
                        scope.launch {
                            runCatching { repository.createFile(save.folderUri, save.name, "application/zip") }
                                .onSuccess { performArchive(sources, it) }
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
            themeStyle = themeStyle,
            onThemeStyleChange = onThemeStyleChange,
            density = density,
            onDensityChange = {
                density = it
                preferencesStore.setDensity(it)
            },
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
            homeMode = homeMode,
            onHomeModeChange = onHomeModeChange,
            shakeAction = shakeAction,
            onShakeActionChange = {
                shakeAction = it
                preferencesStore.setShakeAction(it)
            },
            deliberateActions = deliberateActions,
            onDeliberateActionsChange = {
                deliberateActions = it
                preferencesStore.setDeliberateActions(it)
            },
            landingSubjectName = landingSubject?.name,
            onPickLandingSubject = onPickLandingSubject,
            landingSplash = landingSplash,
            onLandingSplashChange = onLandingSplashChange,
            onOpenWallpaperPicker = { settingsOpen = false; wallpaperPickerOpen = true },
            desktopSnap = desktopSnap,
            onDesktopSnapChange = {
                desktopSnap = it
                preferencesStore.setDesktopSnap(it)
            },
            desktopLabels = desktopLabels,
            onDesktopLabelsChange = {
                desktopLabels = it
                preferencesStore.setDesktopLabels(it)
            },
            onOpenRecycleBin = { settingsOpen = false; trashSheetOpen = true },
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
            onFolderAppearanceChanged = { appearanceVersion += 1 },
            // Settings can change what the landing surfaces show (retention window, quick
            // actions, theme) without any of those screens' own state having a reason to
            // recompose on its own -- bumping this the same way every other "something the home
            // surfaces read changed underneath them" path already does.
            onDismiss = { settingsOpen = false; homeRefreshKey += 1 },
        )
    }
    } // CompositionLocalProvider(LocalFolderAppearance, LocalTagsFor)
}

/**
 * The desktop's Large files sheet -- [io.github.mbaliga.fylz.storage.StorageUsageSnapshot.largestFiles]
 * from the last completed scan, largest first, name plus size, tap to probe-then-open. Honest
 * empty state when no scan has ever run, rather than a fabricated empty list: [files] is empty
 * both when nothing large was found and when nothing has been scanned yet, and this sheet cannot
 * tell those apart on its own -- see [DesktopCallbacks.onOpenLargeFiles]'s call site, which only
 * ever hands this a real (possibly empty) [StorageUsageSnapshot.largestFiles] list.
 */
@Composable
private fun LargeFilesSheet(
    files: List<LargeFileFact>,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.integration_large_files_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (files.isEmpty()) {
                Text(
                    stringResource(R.string.integration_large_files_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(files, key = LargeFileFact::uriString) { fact ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFile(fact.uriString) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                fact.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatBytes(fact.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
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
    // The current folder's own favourited state, same lookup toggleFavorite itself uses (uri
    // membership in the favorites list) -- there is no separate isFavorite flag threaded down
    // from the caller, and this is the only fact LibraryRail needs to know to answer it.
    val isFavorited = activeTab != null && favorites.any { it.uri == activeTab.current.uri }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workspace", style = MaterialTheme.typography.titleMedium)
            // TactileButton (the kit's own fixed contract) is a text-only keycap -- no leading-icon
            // slot -- so "Open root"/"Recycle Bin" drop the FolderOpen/RestoreFromTrash glyphs they
            // used to carry; that's cosmetic, both are still legible one-shot actions from the
            // label alone. "Favourite" was the regression: its star used to be the ONLY channel
            // telling the current folder is already favourited (the label itself never changed),
            // and a stateless TactileButton has nowhere to put that back -- it has no latched
            // state. TactileIconKey does (see SortMenu/ArrangeMenu's own `latched = expanded`
            // above), so that one call site moves there instead, carrying the same
            // Star/StarBorder pair the pre-kit OutlinedButton used.
            TactileButton(
                text = "Open root",
                onClick = onOpenRoot,
                fillWidth = true,
            )
            TactileIconKey(
                icon = if (isFavorited) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = "Favourite",
                onClick = onToggleFavorite,
                enabled = activeTab != null,
                latched = isFavorited,
            )
            TactileButton(
                text = "Recycle Bin",
                onClick = onRecycle,
                style = TactileButtonStyle.SECONDARY,
                fillWidth = true,
            )
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
    density: DensityMode,
    groupedListing: GroupedListing,
    refreshKey: Int,
    loading: Boolean,
    operationMessage: String?,
    onOpenStorageRoot: (StorageRoot) -> Unit,
    onPickFolder: (StorageRoot?) -> Unit,
    onOpenRemotes: () -> Unit,
    homeRefreshKey: Int,
    homeMode: HomeMode = HomeMode.LOCATIONS,
    landingSubject: LandingSubject? = null,
    onOpenHomeFolder: (FolderLocation) -> Unit = {},
    desktopStore: DesktopStore,
    wallpaperSpec: WallpaperSpec,
    desktopSnap: Boolean = true,
    desktopLabels: Boolean = true,
    desktopCallbacks: DesktopCallbacks,
    pullDownSearchState: PullDownSearchState,
    searchFocusRequester: FocusRequester,
    pendingSearchFocus: Boolean = false,
    onSearchFocusConsumed: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onOpenTabFolder: (FolderLocation) -> Unit = {},
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    listState: LazyListState,
    gridState: LazyGridState,
    cluster: ClusterGestureHooks?,
    entryMenu: EntryMenuHooks? = null,
    chips: List<QueryChip>,
    onRemoveChip: (QueryChip) -> Unit,
    diagnostics: List<Diagnostic>,
    recentSearches: List<String>,
    onRecentSearchSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selection mode is de-facto, not a separate flag: any non-empty selection puts every row
    // and card into it, which is what turns a tap from "open" into "toggle" below. Read before
    // the home branch's early return too, now that SubjectList/BentoMosaic/SubjectCanvas carry
    // the same selection this listing does -- one flag, whichever surface is actually on screen.
    val selectionActive = selectedUris.isNotEmpty()

    // Read up here, not after the home branch's own early return: DesktopScreen/DesktopCli/CLI's
    // own dispatch below all need it (CLI's home branch has to know CLI is the theme, and the
    // desktop needs bottomChromeReserve just as much as a folder listing does), and there is only
    // ever one definition of either to avoid the two quietly drifting apart.
    val themeStyle = LocalThemeStyle.current

    // How much bottom padding a listing must reserve so its last row clears the floating chrome:
    // the tab band always, plus the selection row's own height on top of that while a selection
    // is live -- the same two heights the chrome itself stacks at the outer Box level. Used to be
    // computed only after the home branch's early return below, which meant StorageHomeScreen and
    // (now) DesktopScreen never reserved anything for chrome that, while no tab is open, never
    // actually draws -- harmless today, but a trap for whichever of the two grows a persistent
    // bottom affordance next. Hoisted here so both get the real answer instead of an assumed zero.
    val bottomChromeReserve = TabBandHeight + if (selectionActive) SelectionRowHeight else 0.dp

    // Build 11.5 frames 2/4: STACKS grouped by date gets the date-sectioned document grid (hero,
    // dog-eared thumbnails, left rail) instead of the plain header+row listing every other group
    // axis still uses -- read wherever GRID's own grid-vs-list branching already is (listAtTop,
    // the render `when` below), so the two view modes that actually need a LazyGridState agree on
    // exactly the same condition instead of two independently maintained checks drifting apart.
    val dateArrangement = viewMode == ViewMode.STACKS && sortSpec.groupBy == GroupAxis.DATE

    // With no tab open the browser shows the storage home surface, the desktop, or -- once
    // Settings has picked a subject and a view other than Locations/Desktop -- that subject
    // arranged the chosen way. StorageHomeScreen and DesktopScreen/DesktopCli alone get none of
    // the selection args below: neither lists files a selection can contain -- see their own KDoc.
    if (activeTab == null) {
        val subject = landingSubject
        when {
            homeMode == HomeMode.DESKTOP -> if (themeStyle == ThemeStyle.CLI) {
                // CLI's whole point is "the indented tree IS the theme" -- plain text rows, no
                // wallpaper layer, matching the same rule a folder listing already follows under
                // this style (FileBrowser's own CliListing branch below).
                DesktopCli(
                    store = desktopStore,
                    refreshKey = homeRefreshKey,
                    callbacks = desktopCallbacks,
                    modifier = modifier,
                )
            } else {
                DesktopScreen(
                    store = desktopStore,
                    wallpaperSpec = wallpaperSpec,
                    repository = repository,
                    refreshKey = homeRefreshKey,
                    bottomReserve = bottomChromeReserve,
                    callbacks = desktopCallbacks,
                    modifier = modifier,
                    snapEnabled = desktopSnap,
                    showLabels = desktopLabels,
                )
            }
            subject != null && homeMode == HomeMode.LIST -> SubjectList(
                subject = subject,
                repository = repository,
                refreshKey = homeRefreshKey,
                onOpenFolder = onOpenHomeFolder,
                onOpenFile = onOpen,
                modifier = modifier,
                selectedUris = selectedUris,
                selectionActive = selectionActive,
                onToggleSelection = onToggleSelection,
                cluster = cluster,
            )
            subject != null && homeMode == HomeMode.BENTO -> BentoMosaic(
                subject = subject,
                repository = repository,
                refreshKey = homeRefreshKey,
                onOpenFolder = onOpenHomeFolder,
                onOpenFile = onOpen,
                modifier = modifier,
                selectedUris = selectedUris,
                selectionActive = selectionActive,
                onToggleSelection = onToggleSelection,
                cluster = cluster,
            )
            subject != null && homeMode == HomeMode.CANVAS -> SubjectCanvas(
                subject = subject,
                repository = repository,
                refreshKey = homeRefreshKey,
                onOpenFolder = onOpenHomeFolder,
                onOpenFile = onOpen,
                modifier = modifier,
                selectedUris = selectedUris,
                selectionActive = selectionActive,
                onToggleSelection = onToggleSelection,
                cluster = cluster,
            )
            // Locations, or a chosen mode with no valid subject to show it against.
            else -> StorageHomeScreen(
                onOpenRoot = onOpenStorageRoot,
                onPickFolder = onPickFolder,
                onOpenRemotes = onOpenRemotes,
                refreshKey = homeRefreshKey,
                modifier = modifier,
                bottomReserve = bottomChromeReserve,
            )
        }
        return
    }

    // The kind starters offered on an empty search box, derived from the listing about to be
    // searched rather than from a fixed menu: a "type:pdf" starter is only worth offering where a
    // pdf actually is. Recomputed with the listing, which is the only thing that can change it.
    val kindStarters = remember(entries) { kindStartersFrom(entries) }

    // A device-index hit lives outside this tab's tree, so there is no FileEntry in hand for it --
    // probe it into one and open it the ordinary way. A probe that comes back null means the index
    // is describing a file that is no longer there; say so rather than opening nothing.
    val deviceHitScope = rememberCoroutineScope()
    val deviceHitContext = LocalContext.current
    // Resolved at composition, not inside the lambda: reading a resource off LocalContext at call
    // time is not invalidated by a Configuration change, so a locale switch would keep handing back
    // the stale string (lint's LocalContextGetResourceValueCall).
    val deviceHitGoneMessage = stringResource(R.string.search_device_hit_gone)
    val onOpenDeviceIndexHit: (Uri) -> Unit = { uri ->
        deviceHitScope.launch {
            val probed = runCatching { repository.probe(uri) }.getOrNull()
            if (probed != null) {
                onOpen(probed)
            } else {
                // The index is describing a file that is no longer where it was recorded. Say so:
                // a tapped result that simply does nothing is the worse failure.
                Toast.makeText(deviceHitContext, deviceHitGoneMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Whether SearchResults, not the plain listing, is on screen -- read by both render branches
    // below (CANVAS's own Box and the PullDownSearchHost content) so the two can never disagree
    // about which list the user is actually looking at. The caller derives the same flag from the
    // same two values to decide which top-bar controls that list can honour.
    val searchActive = searchRecursive && query.isNotBlank()

    // FocusSearch's real readiness signal for the "no tab was open yet" path: this LaunchedEffect
    // only runs once Compose has committed a composition that includes this branch, which means
    // CommandPill below (either arm) has already attached searchFocusRequester -- no fixed delay
    // to guess, and no risk of requestFocus() throwing on a requester that isn't attached yet.
    LaunchedEffect(pendingSearchFocus) {
        if (pendingSearchFocus) {
            pullDownSearchState.reveal()
            runCatching { searchFocusRequester.requestFocus() }
            onSearchFocusConsumed()
        }
    }

    Column(modifier.fillMaxSize()) {
        Text(
            activeTab.locations.joinToString(" / ") { it.name },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider()

        if (viewMode == ViewMode.CANVAS) {
            // No scroll container to hang the pull-down reveal's NestedScrollConnection off --
            // search here stays reachable from the permanent floating pill, the one mode that
            // keeps it (PullDownSearch's own contract: "CANVAS has no scroll container").

            // How much of the bottom of this Box the floating pill is occupying, measured off the
            // pill itself: it is the only branch whose search field sits ON the content rather
            // than above it, and its height is the pill's own business (a live query grows it).
            var pillHeight by remember { mutableStateOf(0.dp) }
            val pillDensity = LocalDensity.current
            Box(Modifier.weight(1f)) {
                if (searchActive) {
                    // Search wins here as it does in every other view mode. Before this, the
                    // CANVAS test ran ahead of searchActive and never consulted it, so a recursive
                    // search in this mode ran, counted folders in the pill, and then drew the
                    // unfiltered folder underneath: the results reached no surface at all.
                    //
                    // Swapped underneath the pill rather than falling through to
                    // PullDownSearchHost: that would remount the field from floating to pull-down
                    // mid-keystroke and take focus away from someone still typing.
                    SearchResults(
                        progress = searchProgress,
                        hits = searchHits,
                        selectedUris = selectedUris,
                        focusedEntry = focusedEntry,
                        onOpen = onOpen,
                        onOpenExternal = onOpenExternal,
                        onToggleSelection = onToggleSelection,
                        entryMenu = entryMenu,
                        listState = listState,
                        // Clears the pill as well as the chrome -- the pill floats over this
                        // list, so the chrome reserve alone would leave the last hits under it.
                        bottomPadding = bottomChromeReserve + pillHeight,
                        density = density,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SubjectCanvas(
                        subject = LandingSubject(activeTab.treeUri, activeTab.current.uri, activeTab.current.name),
                        repository = repository,
                        refreshKey = refreshKey,
                        onOpenFolder = onOpenTabFolder,
                        onOpenFile = onOpen,
                        selectedUris = selectedUris,
                        selectionActive = selectionActive,
                        onToggleSelection = onToggleSelection,
                        cluster = cluster,
                    )
                }
                CommandPill(
                    query = query,
                    onQueryChange = onQueryChange,
                    searchRecursive = searchRecursive,
                    onSearchRecursiveChange = onSearchRecursiveChange,
                    searchBusy = searchRecursive && searchProgress?.complete == false,
                    chips = chips,
                    onRemoveChip = onRemoveChip,
                    diagnostics = diagnostics,
                    recentSearches = recentSearches,
                    onRecentSearchSelected = onRecentSearchSelected,
                    // Wired, not defaulted. These three arrived with safe defaults so the pill
                    // could land before this file did; left unsupplied they make the owner's own
                    // zero-result ask ("if no results found, prompt to ask if the user wants to
                    // search all locations on the device instead") unreachable dead code -- the
                    // escalation is gated on resultCount == 0, and null is not 0. kindStarters is
                    // the honesty half: derived from the very listing about to be searched, so a
                    // starter is never offered for a kind with nothing behind it here.
                    resultCount = if (searchActive) searchHits.size else null,
                    kindStarters = kindStarters,
                    onOpenDeviceHit = { uri -> onOpenDeviceIndexHit(uri) },
                    focusRequester = searchFocusRequester,
                    // CANVAS has no scroll container to reserve contentPadding on, so this is
                    // the one branch that has to clear the bottom chrome by hand -- without it
                    // the tab band (and, mid-selection, the selection row) draws over the pill.
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = bottomChromeReserve)
                        .onSizeChanged { size ->
                            pillHeight = with(pillDensity) { size.height.toDp() }
                        },
                    trailing = {},
                )
            }
        } else {
            // Claimed only once the active list is scrolled to its own absolute top -- GRID and
            // the STACKS+DATE arrangement (frames 2/4's document grid, [dateArrangement]) read
            // [gridState], every other branch below (CLI, SearchResults, DETAILS, plain STACKS,
            // the plain list) shares [listState], since exactly one of them is ever on screen at a
            // time (the `when` in `content` below is as mutually exclusive as this one was).
            val listAtTop = if (viewMode == ViewMode.GRID || dateArrangement) {
                gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
            } else {
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            }
            PullDownSearchHost(
                listAtTop = listAtTop,
                revealHeight = CommandPillSearchHeight,
                state = pullDownSearchState,
                modifier = Modifier.weight(1f),
                searchField = {
                    CommandPill(
                        query = query,
                        onQueryChange = onQueryChange,
                        searchRecursive = searchRecursive,
                        onSearchRecursiveChange = onSearchRecursiveChange,
                        searchBusy = searchRecursive && searchProgress?.complete == false,
                        chips = chips,
                        onRemoveChip = onRemoveChip,
                        diagnostics = diagnostics,
                        recentSearches = recentSearches,
                        onRecentSearchSelected = onRecentSearchSelected,
                        // Wired, not defaulted. These three arrived with safe defaults so the pill
                        // could land before this file did; left unsupplied they make the owner's own
                        // zero-result ask ("if no results found, prompt to ask if the user wants to
                        // search all locations on the device instead") unreachable dead code -- the
                        // escalation is gated on resultCount == 0, and null is not 0. kindStarters is
                        // the honesty half: derived from the very listing about to be searched, so a
                        // starter is never offered for a kind with nothing behind it here.
                        resultCount = if (searchActive) searchHits.size else null,
                        kindStarters = kindStarters,
                        onOpenDeviceHit = { uri -> onOpenDeviceIndexHit(uri) },
                        focusRequester = searchFocusRequester,
                        trailing = {},
                    )
                },
                content = { listModifier ->
                    if (searchActive) {
                        SearchResults(
                            progress = searchProgress,
                            hits = searchHits,
                            selectedUris = selectedUris,
                            focusedEntry = focusedEntry,
                            onOpen = onOpen,
                            onOpenExternal = onOpenExternal,
                            onToggleSelection = onToggleSelection,
                            entryMenu = entryMenu,
                            listState = listState,
                            bottomPadding = bottomChromeReserve,
                            density = density,
                            modifier = listModifier,
                        )
                    } else if (loading) {
                        Box(listModifier, contentAlignment = Alignment.Center) {
                            Text(operationMessage ?: "Working…")
                        }
                    } else if (entries.isEmpty()) {
                        Box(listModifier, contentAlignment = Alignment.Center) {
                            Text(
                                if (query.isBlank()) {
                                    stringResource(R.string.browser_empty_folder)
                                } else {
                                    stringResource(R.string.browser_search_none)
                                },
                            )
                        }
                    } else if (themeStyle == ThemeStyle.CLI) {
                        // CLI is a look at the whole folder, not one more entry beside GRID/LIST/
                        // DETAILS -- the indented tree IS the theme, so it takes over the listing
                        // outright rather than decorating whichever view mode happened to be
                        // selected. Search still wins above (CliListing has no notion of a ranked
                        // hit), and this never reaches STACKS or the plain list below since this
                        // `when` arm returns first.
                        CliListing(
                            treeUri = activeTab.treeUri,
                            entries = entries,
                            repository = repository,
                            selected = selectedUris,
                            selectionActive = selectionActive,
                            showHidden = showHidden,
                            onOpenFile = onOpen,
                            onToggleSelect = onToggleSelection,
                            listState = listState,
                            // The one listing branch that reserved nothing for the bottom chrome:
                            // CliListing exposes no contentPadding, so its last rows sat under
                            // the tab band with no way to scroll them out. Padded on the outside
                            // instead -- it shortens the viewport rather than the scroll extent,
                            // which is the same result here since the band is opaque.
                            modifier = listModifier.padding(bottom = bottomChromeReserve),
                        )
                    } else if (viewMode == ViewMode.GRID) {
                        // Build 11.5 frames 1/3: a directory cell is FolderGridCell (the floating
                        // FolderFace + centered name + honest CountChip, no card behind it), never
                        // FileCard -- GRID_MIN_CELL_WIDTH plus the 24dp gutter below is sized so
                        // Adaptive lands on exactly two columns at phone widths (the spec's own
                        // "2-column rhythm for directories") while still opening up to more columns
                        // on a tablet's wider FileBrowser pane, unlike a hardcoded GridCells.Fixed(2)
                        // would. Files keep FileCard, now hairline+softShadow bordered instead of a
                        // flat colour fill -- the same two-primitive depth language every other flat
                        // card in this pass adopted.
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(GRID_MIN_CELL_WIDTH.dp * Density.scale(density)),
                            state = gridState,
                            contentPadding = listingPaddingFor(16.dp, bottomChromeReserve),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalArrangement = Arrangement.spacedBy(40.dp),
                            modifier = listModifier,
                        ) {
                            items(entries, key = { it.uri.toString() }) { entry ->
                                if (entry.isDirectory) {
                                    FolderGridCell(
                                        entry = entry,
                                        selected = entry.uri in selectedUris,
                                        focused = entry.uri == focusedEntry?.uri,
                                        selectionActive = selectionActive,
                                        onOpen = onOpen,
                                        onToggleSelection = onToggleSelection,
                                        cluster = cluster,
                                        entryMenu = entryMenu,
                                        treeUri = activeTab.treeUri,
                                        repository = repository,
                                        showHidden = showHidden,
                                        folderPeeks = folderPeeks,
                                    )
                                } else {
                                    FileCard(
                                        entry = entry,
                                        selected = entry.uri in selectedUris,
                                        focused = entry.uri == focusedEntry?.uri,
                                        selectionActive = selectionActive,
                                        onOpen = onOpen,
                                        onOpenExternal = onOpenExternal,
                                        onToggleSelection = onToggleSelection,
                                        cluster = cluster,
                                        cardHeight = Density.gridCardHeight(density),
                                        thumbSize = Density.gridThumb(density),
                                    )
                                }
                            }
                        }
                    } else if (viewMode == ViewMode.DETAILS) {
                        Column(listModifier) {
                            DetailsHeaderRow(sortSpec, onSortSpecChange)
                            HorizontalDivider()
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(bottom = bottomChromeReserve),
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
                                        entryMenu = entryMenu,
                                        rowHeight = Density.detailsRowHeight(density),
                                        thumbSize = Density.detailsThumb(density),
                                    )
                                }
                            }
                        }
                    } else if (dateArrangement) {
                        // Build 11.5 frames 2/4: the date-sectioned document grid. FolderHero
                        // carries the open folder's own identity, B's dateSections() grid draws
                        // DogEarPage-framed thumbnails three per row, LeftTimelineRail stands in
                        // for the right-edge scrubber this arrangement hides (see dateArrangement's
                        // own gate above the scrubber, and the [dateArrangement] KDoc for why
                        // [gridState], not [listState], is what tracks it).
                        val context = LocalContext.current
                        val undatedLabel = stringResource(R.string.browse_date_undated)
                        // A plain Resources call, not pluralStringResource: groupByDay's own
                        // countLabel parameter is a plain (Int) -> String, not a @Composable one,
                        // so a lambda literal handed to it can never itself call a composable --
                        // this is the adaptation, kept here rather than in DateSections.kt, that
                        // still gets real localized plurals out of the same strings_browse.xml
                        // resource DateSections.kt's own KDoc names for exactly this call site.
                        val documentCountLabel: (Int) -> String = { n ->
                            context.resources.getQuantityString(R.plurals.browse_date_document_count, n, n)
                        }
                        val dateSectionsList = remember(entries, undatedLabel) {
                            groupByDay(
                                entries = entries,
                                nowMillis = System.currentTimeMillis(),
                                undatedLabel = undatedLabel,
                                countLabel = documentCountLabel,
                            )
                        }
                        // Decorative and approximate, per LeftTimelineRail's own contract: each
                        // section's start as a fraction of the total item count, not a measured
                        // scroll position -- headers and a 3-wide grid's uneven last row make an
                        // exact pixel fraction not worth computing for a rail that draws ticks,
                        // not a scrollbar.
                        val sectionAnchors = remember(dateSectionsList) {
                            val total = dateSectionsList.sumOf { it.entries.size }.coerceAtLeast(1)
                            var seen = 0
                            dateSectionsList.map { section ->
                                val anchor = seen.toFloat() / total.toFloat()
                                seen += section.entries.size
                                anchor
                            }
                        }
                        // activeTab.current is a FolderLocation (uri+name only), not a FileEntry --
                        // synthesized honestly here the same way the rest of the app treats
                        // metadata it doesn't have: null size/lastModified, never a guess. entries
                        // is this folder's own already-loaded listing, so the peek built from it is
                        // the real content, not the three-thumbnail-capped lazy peek
                        // FolderGridCell computes for a *child* folder it hasn't opened yet.
                        val heroEntry = remember(activeTab.current.uri, activeTab.current.name) {
                            FileEntry(
                                uri = activeTab.current.uri,
                                name = activeTab.current.name,
                                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                                sizeBytes = null,
                                lastModifiedMillis = null,
                                flags = 0,
                                kind = EntryKind.DIRECTORY,
                            )
                        }
                        val heroPeek = remember(entries) {
                            val thumbs = entries.filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }.take(3)
                            val hasNonMedia = entries.any { it.kind != EntryKind.IMAGE && it.kind != EntryKind.VIDEO }
                            FolderPeek(entries.size, thumbs, hasNonMedia)
                        }
                        Box(listModifier) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                state = gridState,
                                // 36dp clears LeftTimelineRail per dateSections()'s own KDoc; the
                                // other three edges keep the usual 16dp rhythm.
                                contentPadding = PaddingValues(
                                    start = 36.dp,
                                    top = 16.dp,
                                    end = 16.dp,
                                    bottom = bottomChromeReserve + 16.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    FolderHero(
                                        name = activeTab.current.name,
                                        countLabel = documentCountLabel(entries.size),
                                        face = { FolderFace(heroEntry, heroPeek, modifier = Modifier.fillMaxSize(), showLabel = false) },
                                    )
                                }
                                dateSections(
                                    sections = dateSectionsList,
                                    // dateSections() wraps every DogEarPage in its own plain
                                    // Modifier.clickable(onEntryClick) -- left a no-op here on
                                    // purpose. The real gesture surface is entryGestures below,
                                    // applied one node deeper inside itemContent: Compose's pointer
                                    // dispatch lets a descendant see (and consume) a pointer event
                                    // before it bubbles to an ancestor's own detector, so
                                    // entryGestures resolves every tap, long-press, toggle-
                                    // selection and cluster-drag exactly as FileRowV1/FileCard/
                                    // DetailsRow already do, and this outer clickable never has
                                    // anything left to decide (its own onClick would be the only
                                    // thing left unconsumed on a plain tap, which is why it stays a
                                    // harmless no-op rather than a second, competing onOpen). This
                                    // is the "adapt in FylzV1App, not in DateSections.kt" call:
                                    // that file's contract is a plain click, and stacking a second
                                    // gesture detector there is exactly the bug entryGestures'
                                    // own KDoc describes it replacing.
                                    onEntryClick = {},
                                ) { entry ->
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .entryGestures(
                                                key = entry.uri,
                                                selected = entry.uri in selectedUris,
                                                selectionActive = selectionActive,
                                                onOpen = { onOpen(entry) },
                                                onToggleSelection = { onToggleSelection(entry) },
                                                cluster = cluster,
                                                onDoubleTap = { onOpenExternal(entry) },
                                                contentDescription = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current),
                                                menuActions = entryMenu.actionsOn(entry),
                                                onOpenMenu = entryMenu.openerFor(entry),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        EntryThumbnail(entry, size = DOC_THUMB_SIZE)
                                        if (entry.uri in selectedUris) {
                                            SelectionMark(themeStyle, Modifier.align(Alignment.TopEnd).padding(4.dp))
                                        }
                                    }
                                }
                            }
                            LeftTimelineRail(
                                sectionAnchors,
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                            )
                        }
                    } else if (viewMode == ViewMode.STACKS) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(bottom = bottomChromeReserve),
                            modifier = listModifier,
                        ) {
                            items(
                                groupedListing.rows,
                                key = { row ->
                                    when (row) {
                                        is ListingRow.Header -> "header:${row.label}"
                                        is ListingRow.Item -> row.entry.uri.toString()
                                    }
                                },
                            ) { row ->
                                when (row) {
                                    is ListingRow.Header -> StacksHeaderRow(row)
                                    is ListingRow.Item -> FileRowV1(
                                        entry = row.entry,
                                        selected = row.entry.uri in selectedUris,
                                        focused = row.entry.uri == focusedEntry?.uri,
                                        selectionActive = selectionActive,
                                        onOpen = onOpen,
                                        onOpenExternal = onOpenExternal,
                                        onToggleSelection = onToggleSelection,
                                        cluster = cluster,
                                        entryMenu = entryMenu,
                                        rowHeight = Density.listRowHeight(density),
                                        thumbSize = Density.listThumb(density),
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(bottom = bottomChromeReserve),
                            modifier = listModifier,
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
                                    entryMenu = entryMenu,
                                    rowHeight = Density.listRowHeight(density),
                                    thumbSize = Density.listThumb(density),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Sort controls. Previously the order was hardcoded in DocumentRepository with no UI at all. */
@Composable
private fun SortMenu(spec: SortSpec, onChange: (SortSpec) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TactileIconKey(
            icon = Icons.Outlined.Sort,
            contentDescription = stringResource(R.string.browser_sort),
            onClick = { expanded = true },
            latched = expanded,
        )
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
                    TactileSwitch(
                        checked = spec.foldersFirst,
                        onCheckedChange = { onChange(spec.copy(foldersFirst = it)) },
                    )
                },
                onClick = { onChange(spec.copy(foldersFirst = !spec.foldersFirst)) },
            )
            // Grouping shares this menu rather than getting its own: GroupAxis lives on this
            // same SortSpec, and Stacks' sections are keyed off the identical sort-field bucket
            // functions entryStops already uses -- one control for the one piece of state.
            // Inert outside ViewMode.STACKS (nothing currently reads groupBy), which is fine: the
            // choice is remembered for the next time Arrange switches into Stacks.
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("No grouping") },
                trailingIcon = { if (spec.groupBy == null) Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                onClick = { onChange(spec.copy(groupBy = null)) },
            )
            GroupAxis.entries.forEach { axis ->
                DropdownMenuItem(
                    text = { Text("Group by ${axis.label}") },
                    trailingIcon = { if (spec.groupBy == axis) Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                    onClick = { onChange(spec.copy(groupBy = axis)) },
                )
            }
        }
    }
}

/**
 * The "Arrange" popup the owner asked for: the old two/three-mode cycle icon outgrew itself the
 * moment Stacks and Canvas joined GRID/LIST/DETAILS -- five destinations need a picker, not a tap
 * that wraps around. Density (S/M/L) rides the same menu since it is the other axis Arrange
 * covers; sort field/direction and Stacks' own group-by stay in [SortMenu], reached from the
 * pill, since both already operate on [SortSpec] and gained a second control here would just be
 * the same state edited from two unrelated places.
 *
 * @param viewModesEnabled false while search results, not the folder, are on screen. Results are
 *   one ranked list whatever the view mode says, so the five modes are shown as the remembered
 *   folder setting they still are, and not as a choice about the list in front of the user --
 *   including the trigger glyph, which otherwise sits in always-visible chrome asserting Canvas
 *   or Stacks over a flat list. Density is unaffected: it reaches the result rows and works.
 */
@Composable
private fun ArrangeMenu(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    density: DensityMode,
    onDensityChange: (DensityMode) -> Unit,
    viewModesEnabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TactileIconKey(
            icon = if (viewModesEnabled) viewMode.arrangeIcon() else Icons.Outlined.Tune,
            contentDescription = "Arrange",
            onClick = { expanded = true },
            latched = expanded,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!viewModesEnabled) {
                // What makes the retained check mark below substantiable: it reads as "what this
                // folder is set to", which is true, instead of "what you are looking at", which
                // while results are showing is not.
                DropdownMenuItem(
                    text = {
                        Text(
                            "Results are one ranked list. Arrange applies to the folder.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    enabled = false,
                    onClick = {},
                )
            }
            ViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.arrangeLabel()) },
                    trailingIcon = { if (mode == viewMode) Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                    enabled = viewModesEnabled,
                    onClick = {
                        onViewModeChange(mode)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DensityMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.arrangeLabel()) },
                    trailingIcon = { if (mode == density) Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                    onClick = {
                        onDensityChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun ViewMode.arrangeIcon() = when (this) {
    ViewMode.LIST -> Icons.Outlined.List
    ViewMode.GRID -> Icons.Outlined.GridView
    ViewMode.DETAILS -> Icons.Outlined.TableRows
    ViewMode.STACKS -> Icons.Outlined.ViewSidebar
    ViewMode.CANVAS -> Icons.Outlined.Dashboard
}

private fun ViewMode.arrangeLabel(): String = when (this) {
    ViewMode.LIST -> "List"
    ViewMode.GRID -> "Grid"
    ViewMode.DETAILS -> "Details"
    ViewMode.STACKS -> "Stacks"
    ViewMode.CANVAS -> "Canvas"
}

private fun DensityMode.arrangeLabel(): String = when (this) {
    DensityMode.COMPACT -> "Small"
    DensityMode.COMFORTABLE -> "Medium"
    DensityMode.DETAILED -> "Large"
}

/** A Stacks section break. Deliberately not a `stickyHeader` -- see the comment above the LIST
 *  branch that used to sit here before STACKS joined it: a sticky header decouples the lazy
 *  list's own item index from the entry index the edge scrubber tracks, which is exactly what
 *  [GroupedListing.lazyIndexOf]/[GroupedListing.entryIndexOf] exist to keep translated instead. */
@Composable
private fun StacksHeaderRow(header: ListingRow.Header) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            header.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${header.count}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Stably reorders [entries] so every entry sharing [axis]'s bucket sits together -- see
 * [FylzV1Workspace]'s own `stacksListing` comment for why `groupedListing` needs this rather than
 * whatever order `sortSpec.field` already produced. [GroupAxis.LETTER]/[GroupAxis.DATE]/
 * [GroupAxis.SIZE] key off the exact bucket functions [entryStops] uses for NAME/MODIFIED/SIZE,
 * so a Stacks header and the (non-Stacks) scrubber's own labels never disagree about where one
 * bucket ends and the next begins; [GroupAxis.KIND] has no such precedent to match, so it keys on
 * the enum name directly.
 */
private fun groupingOrder(entries: List<FileEntry>, axis: GroupAxis): List<FileEntry> {
    val month = DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault()).withZone(ZoneId.systemDefault())
    val key: (FileEntry) -> String = when (axis) {
        GroupAxis.LETTER -> { entry -> initialOf(entry.name) }
        GroupAxis.DATE -> { entry -> monthBand(entry.lastModifiedMillis, month) }
        GroupAxis.SIZE -> { entry -> sizeBand(entry.sizeBytes) }
        GroupAxis.KIND -> { entry -> entry.kind.name }
    }
    return entries.sortedBy(key)
}

/**
 * The narrow parent-folder column a wide layout adds beside the browser -- the desktop "column
 * view" reading direction Finder popularised: what's one tap to the left, right where it can be
 * glanced at without leaving what's on screen. Reads straight off the active tab's own ancestor
 * chain rather than tracking any navigation state of its own: the parent is simply
 * `locations[locations.size - 2]`, and a tap on one of ITS children replaces the tab's own last
 * location instead of pushing a new one, so this pane and the tab it sits beside can never point
 * at two different folders.
 */
@Composable
private fun ParentFolderPane(
    treeUri: Uri,
    parent: FolderLocation,
    currentUri: Uri,
    repository: DocumentRepository,
    showHidden: Boolean,
    refreshKey: Int,
    onOpenSibling: (FolderLocation) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children by produceState<List<FileEntry>?>(initialValue = null, treeUri, parent.uri, refreshKey) {
        value = null
        value = runCatching { repository.listChildren(treeUri, parent.uri) }.getOrDefault(emptyList())
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize()) {
            Text(
                parent.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            HorizontalDivider()
            val listing = children
            when (listing) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                }
                else -> {
                    val visible = if (showHidden) listing else listing.filterNot { it.name.startsWith(".") }
                    LazyColumn {
                        items(visible, key = { it.uri.toString() }) { entry ->
                            ParentFolderRow(
                                entry = entry,
                                current = entry.uri == currentUri,
                                onClick = {
                                    if (entry.isDirectory) {
                                        onOpenSibling(FolderLocation(entry.uri, entry.name))
                                    } else {
                                        onOpenFile(entry)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParentFolderRow(entry: FileEntry, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 48dp, not the 44 this row used to carry: it is a navigation target in a scrolling
            // breadcrumb, and 44 was under the floor with nothing gained by it.
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryThumbnail(entry, size = 24.dp)
        Text(
            displayName(entry.name, entry.isDirectory, LocalShowExtensions.current),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
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
    entryMenu: EntryMenuHooks? = null,
    // Hoisted rather than internal, matching every other listing surface here -- the pull-down
    // search reveal above needs this list's own "at top" reading to decide whether it may claim
    // a downward drag.
    listState: LazyListState,
    bottomPadding: Dp,
    // The one display axis results CAN honour: unlike the five view modes (a ranked list has no
    // grid or canvas reading) and unlike sort order (the ranking is the point), S/M/L is just the
    // row's own size, so Arrange's S/M/L keeps working while this list is the one on screen.
    density: DensityMode,
    modifier: Modifier = Modifier,
) {
    val selectionActive = selectedUris.isNotEmpty()
    Column(modifier) {
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
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
            items(hits, key = { it.entry.uri.toString() }) { hit ->
                FileRowV1(
                    entry = hit.entry,
                    selected = hit.entry.uri in selectedUris,
                    focused = hit.entry.uri == focusedEntry?.uri,
                    selectionActive = selectionActive,
                    onOpen = onOpen,
                    onOpenExternal = onOpenExternal,
                    onToggleSelection = onToggleSelection,
                    entryMenu = entryMenu,
                    // Where the file lives, plus the matched line for a content hit -- a result
                    // list without a path is unusable once the search leaves one folder.
                    overline = hit.relativePath,
                    detail = hit.snippet?.let { "\u201c$it\u201d" }
                        ?: if (hit.source == SearchMatchSource.CONTENT) "Matched file contents" else null,
                    nameHighlights = hit.nameHighlights,
                    // The same two helpers the plain LIST and STACKS branches feed this same
                    // composable; omitting them here silently took FileRowV1's own COMFORTABLE
                    // defaults, which is what made S/M/L do nothing while results were showing.
                    rowHeight = Density.listRowHeight(density),
                    thumbSize = Density.listThumb(density),
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
 *
 * Public, not `internal`: the canvas/home surfaces in `ui/canvas/` (`SubjectCanvas`,
 * `SubjectList`, `BentoMosaic`) now carry the same cluster drag their browse rows always did, and
 * a public composable cannot expose an internal type in its own signature -- Kotlin enforces that
 * regardless of both sides living in this one module.
 */
class ClusterGestureHooks(
    val onPositioned: (Uri, Offset) -> Unit,
    val onStart: (Offset) -> Unit,
    /**
     * Pick up ONE entry, named by its uri, with no selection behind it.
     *
     * [onStart] gathers whatever is selected, which is why a drag used to need two long presses
     * to begin: the first one selected the row and the second one lifted it. That is the whole of
     * "I wasn't able to drag a file to one of the actions" -- the targets were always there, but
     * nothing that started with a plain held finger ever reached them.
     *
     * The uri rather than the entry because that is all
     * [io.github.mbaliga.fylz.ui.components.entryGestures] has: its `key` IS the row's uri, and a
     * host that offers this hook is a host that can resolve one.
     */
    val onStartSolo: (Uri, Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * The row long-press menu, threaded down beside [ClusterGestureHooks] for the same reason it is:
 * every listing branch draws its own rows, and one nullable parameter is what keeps the eight of
 * them from each growing their own idea of what a held folder offers.
 *
 * Two halves because the two live in different places. [actionsFor] belongs on the ROW -- it is
 * also what [io.github.mbaliga.fylz.ui.components.entryGestures] publishes as that row's custom
 * accessibility actions, so anything in the menu is reachable with no long press at all. [show]
 * belongs to the WORKSPACE, which owns the sheet; a `Modifier` cannot emit one.
 *
 * Null (and an empty [actionsFor] result) means "this row has no menu" -- the long press then
 * resolves exactly as it always did. Files pass nothing: a file has no second destination worth
 * offering, and turning its long press into a menu would cost the selection gesture for nothing.
 */
private class EntryMenuHooks(
    val actionsFor: (FileEntry) -> List<EntryAction>,
    val show: (FileEntry) -> Unit,
)

/** [EntryMenuHooks.actionsFor] through the nullable, since "no hooks" and "no actions for this
 *  entry" are the same answer to every row that asks. */
private fun EntryMenuHooks?.actionsOn(entry: FileEntry): List<EntryAction> =
    this?.actionsFor(entry).orEmpty()

/** [EntryMenuHooks.show] bound to one entry, or null when there are no hooks -- the shape
 *  `entryGestures(onOpenMenu = ...)` wants, written once instead of at each of its call sites. */
private fun EntryMenuHooks?.openerFor(entry: FileEntry): (() -> Unit)? =
    this?.let { hooks -> { hooks.show(entry) } }

/**
 * The menu itself: this row's [EntryAction]s, one per line, on the same bottom sheet every other
 * secondary surface in this file uses. Deliberately not a [DropdownMenu] -- the anchor for one
 * would have to be the row, and a row inside a `LazyColumn` is recycled out from under a popup
 * the moment the list scrolls.
 */
@Composable
private fun EntryMenuSheet(entry: FileEntry, actions: List<EntryAction>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                displayName(entry.name, entry.isDirectory, LocalShowExtensions.current),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            actions.forEach { action ->
                Text(
                    action.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The sheet dismisses first so the action lands with nothing on top of
                        // it -- opening a tab under a still-settling sheet reads as a no-op.
                        // Role.Button because a bare Text with a clickable announces as text a
                        // screen reader happens to be able to activate, not as a menu item.
                        .clickable(role = Role.Button) { onDismiss(); action.onSelect() }
                        // The kit's 48dp floor, honoured here rather than assumed: a text row
                        // sizes to its own line height, which is nowhere near a thumb.
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
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
    selectionActive: Boolean,
    onOpen: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    cluster: ClusterGestureHooks? = null,
    entryMenu: EntryMenuHooks? = null,
    overline: String? = null,
    detail: String? = null,
    nameHighlights: List<IntRange> = emptyList(),
    rowHeight: Dp = 62.dp,
    thumbSize: Dp = 40.dp,
) {
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    val label = if (entry.isDirectory) "Folder $shownName" else shownName
    val themeStyle = LocalThemeStyle.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .entryGestures(
                key = entry.uri,
                selected = selected,
                selectionActive = selectionActive,
                onOpen = { onOpen(entry) },
                onToggleSelection = { onToggleSelection(entry) },
                cluster = cluster,
                // Preserved verbatim from the raw-gesture stack this replaces: a directory opens
                // on double-tap same as a single tap would, a file opens externally instead.
                onDoubleTap = { if (entry.isDirectory) onOpen(entry) else onOpenExternal(entry) },
                contentDescription = label,
                menuActions = entryMenu.actionsOn(entry),
                onOpenMenu = entryMenu.openerFor(entry),
            )
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    focused -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Real image/video thumbnails; falls back to a per-type icon. Previously every file in
        // the list rendered the same handful of static vectors. The selection mark rides its
        // corner rather than a gutter column of its own -- at rest there is nothing here at all.
        // The tag mark rides the opposite corner so a tagged, selected item never overlaps its
        // own two marks.
        Box {
            EntryThumbnail(entry, size = thumbSize)
            if (selected) {
                SelectionMark(themeStyle, Modifier.align(Alignment.TopStart))
            }
            TagMark(entry.uri, Modifier.align(Alignment.BottomEnd))
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
 * A file's grid cell -- directories no longer reach this composable (Build 11.5 frames 1/3 split
 * them out into [FolderGridCell], which floats a bare [FolderFace] with no card behind it at all,
 * a fundamentally different visual than a bordered thumbnail tile). [hairline] plus [softShadow]
 * replace the old flat colour fill's only depth cue -- the same two-primitive language every other
 * flat card in this pass adopted, and the real one now that a plain colour `Surface` has nothing
 * to say about depth on its own ([softShadow]'s own KDoc: `tonalElevation` is a no-op here).
 */
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
    cardHeight: Dp = 164.dp,
    thumbSize: Dp = 56.dp,
) {
    val themeStyle = LocalThemeStyle.current
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    val shape = MaterialTheme.shapes.medium

    Surface(
        color = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            focused -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        shape = shape,
        modifier = Modifier
            .height(cardHeight)
            .softShadow(ShadowLevel.SM, shape)
            .border(1.dp, hairline(), shape)
            .entryGestures(
                key = entry.uri,
                selected = selected,
                selectionActive = selectionActive,
                onOpen = { onOpen(entry) },
                onToggleSelection = { onToggleSelection(entry) },
                cluster = cluster,
                onDoubleTap = { onOpenExternal(entry) },
                contentDescription = shownName,
            ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                EntryThumbnail(entry, size = thumbSize)
                Column {
                    Text(shownName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(
                        listOfNotNull(entry.kind.readableLabel(), entry.sizeBytes?.let(::formatBytes)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The selection mark sits at the card's own top-right, the same corner the checkbox
            // used to occupy -- composed only for a selected card, never at rest. The tag mark
            // rides the opposite corner so the two never overlap on a tagged, selected card.
            if (selected) {
                SelectionMark(themeStyle, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            TagMark(entry.uri, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
    }
}

/**
 * A directory's grid cell (Build 11.5 frames 1/3): the folder floats on its own -- [FolderFace]
 * already draws the sticker outline and soft shadow the frosted register wants (see
 * `ui/components/FolderFace.kt`'s own `FrostedFolderFace` KDoc) -- with its name and an honest
 * [CountChip] centered below, outside the face entirely rather than layered over a card surface
 * the way [FileCard] draws a file's name. Owns the same lazy [FolderPeek] resolution [FileCard]
 * used to (before directories split out of it): a peek still isn't known the moment a folder
 * scrolls into view, and [CountChip] renders nothing rather than a guess until [folderPeeks]
 * resolves it -- the honesty rule [CountChip] itself already holds callers to.
 */
@Composable
private fun FolderGridCell(
    entry: FileEntry,
    selected: Boolean,
    focused: Boolean,
    selectionActive: Boolean,
    onOpen: (FileEntry) -> Unit,
    onToggleSelection: (FileEntry) -> Unit,
    cluster: ClusterGestureHooks?,
    entryMenu: EntryMenuHooks? = null,
    treeUri: Uri,
    repository: DocumentRepository,
    showHidden: Boolean,
    folderPeeks: MutableMap<Uri, FolderPeek>,
) {
    LaunchedEffect(entry.uri, showHidden) {
        if (folderPeeks.containsKey(entry.uri)) return@LaunchedEffect
        val children = runCatching { repository.listChildren(treeUri, entry.uri) }.getOrDefault(emptyList())
        val visible = if (showHidden) children else children.filterNot { it.name.startsWith(".") }
        val thumbs = visible.filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }.take(3)
        val hasNonMedia = visible.any { it.kind != EntryKind.IMAGE && it.kind != EntryKind.VIDEO }
        folderPeeks[entry.uri] = FolderPeek(visible.size, thumbs, hasNonMedia)
    }
    // derivedStateOf, not a bare folderPeeks[entry.uri] read -- see FileCard's own retired copy of
    // this same comment for why (SnapshotStateMap invalidates every reader on any key's write).
    val peekState by remember(entry.uri, folderPeeks) {
        derivedStateOf { folderPeeks[entry.uri] }
    }
    val peek = peekState
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    val themeStyle = LocalThemeStyle.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .entryGestures(
                key = entry.uri,
                selected = selected,
                selectionActive = selectionActive,
                onOpen = { onOpen(entry) },
                onToggleSelection = { onToggleSelection(entry) },
                cluster = cluster,
                contentDescription = shownName,
                menuActions = entryMenu.actionsOn(entry),
                onOpenMenu = entryMenu.openerFor(entry),
            )
            .then(
                if (focused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(FylzGeometry.RadiusXl))
                } else {
                    Modifier
                },
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (peek != null) {
                FolderFace(entry, peek, modifier = Modifier.fillMaxSize(), showLabel = false)
            } else {
                // Not resolved yet -- the same bare icon placeholder FileCard always drew here
                // before FolderFace existed, centered in the face's own eventual bounds instead of
                // top-anchored so the cell doesn't visibly jump once the peek resolves.
                EntryThumbnail(entry, size = 48.dp)
            }
            if (selected) {
                SelectionMark(themeStyle, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            TagMark(entry.uri, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            shownName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (peek != null) {
            Spacer(Modifier.height(4.dp))
            CountChip(gridFolderCountLabel(peek.itemCount))
        }
    }
}

/** "1 item" vs "12 items" under a grid folder's name -- the same plain-Kotlin idiom
 *  `FolderFace.kt`'s own private `itemCountLabel` already uses for this exact wording, not a
 *  string resource: this workstream's own [strings_integration.xml] documents why (raw literals
 *  are this codebase's established convention for text like this, not the exception). */
private fun gridFolderCountLabel(count: Int): String = if (count == 1) "1 item" else "$count items"

/**
 * Finder's column header, tap to sort. Not a `stickyHeader` -- it sits above the [LazyColumn]
 * rather than as its first item, so it never disturbs the row index the edge scrubber tracks.
 */
@Composable
private fun DetailsHeaderRow(spec: SortSpec, onChange: (SortSpec) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 48dp with NO vertical padding, so each label below can fill the whole band: the
            // old 36dp-minus-6dp-each-side left every one of these three sort controls about
            // 24dp tall. The type is unchanged -- only the dead band around it is now live.
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
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
        modifier
            // The incoming modifier carries this column's WIDTH (a weight or a fixed dp); the
            // HEIGHT is claimed here, before the clickable, so the whole band is tappable rather
            // than just the line of text in it -- a labelMedium line is about 20dp tall, and
            // these three sort controls were exactly that.
            //
            // heightIn, deliberately not fillMaxHeight: the header row's own height is a
            // minimum, not a fixed size, so a child asking to FILL it would be handed the whole
            // remaining height of the screen and drag the row up to match.
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick, onClickLabel = "Sort by $text", role = Role.Button),
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
    entryMenu: EntryMenuHooks? = null,
    rowHeight: Dp = 44.dp,
    thumbSize: Dp = 24.dp,
) {
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    val label = if (entry.isDirectory) "Folder $shownName" else shownName
    val themeStyle = LocalThemeStyle.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .entryGestures(
                key = entry.uri,
                selected = selected,
                selectionActive = selectionActive,
                onOpen = { onOpen(entry) },
                onToggleSelection = { onToggleSelection(entry) },
                cluster = cluster,
                onDoubleTap = { if (entry.isDirectory) onOpen(entry) else onOpenExternal(entry) },
                contentDescription = label,
                menuActions = entryMenu.actionsOn(entry),
                onOpenMenu = entryMenu.openerFor(entry),
            )
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    focused -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            EntryThumbnail(entry, size = thumbSize)
            if (selected) {
                SelectionMark(themeStyle, Modifier.align(Alignment.TopStart))
            }
            TagMark(entry.uri, Modifier.align(Alignment.BottomEnd))
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

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TactileField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = { TactileButton(text = "Save", onClick = { onConfirm(value) }, enabled = value.isNotBlank()) },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
    )
}

@Composable
private fun TagDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = { TactileField(value = value, onValueChange = { value = it }, label = "Comma-separated tags") },
        confirmButton = { TactileButton(text = "Save", onClick = { onConfirm(value) }) },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
    )
}


/** Every item carrying [tag], device-wide -- [LibraryStore.itemsWithTag] probed through the same
 *  repository every other listing here reads, since there is no folder tab this could otherwise
 *  scope itself to. */
@Composable
private fun TagResultsDialog(tag: String, results: List<FileEntry>, onOpen: (FileEntry) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tagged “$tag”") },
        text = {
            if (results.isEmpty()) {
                Text("Nothing found -- either nothing still carries this tag, or those items are no longer reachable.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.uri.toString() }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onOpen(entry) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EntryThumbnail(entry, size = 32.dp)
                            Text(
                                displayName(entry.name, entry.isDirectory, LocalShowExtensions.current),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TactileButton(text = "Close", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
    )
}

@Composable
private fun BatchRenameDialog(count: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var prefix by remember { mutableStateOf("File-") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch rename $count items") },
        text = { TactileField(value = prefix, onValueChange = { prefix = it }, label = "Prefix") },
        confirmButton = { TactileButton(text = "Rename", onClick = { onConfirm(prefix) }, enabled = prefix.isNotBlank()) },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
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
                // The key field carries no PasswordVisualTransformation today (none was present
                // before this swap either) -- behaviour-preserving means this conversion does not
                // add masking that wasn't already there, but it's worth flagging loudly: an API
                // key field showing its value in plain text looks like a pre-existing gap a later
                // pass should close, tactile kit or not.
                TactileField(value = endpoint, onValueChange = { endpoint = it }, label = "OpenAI-compatible endpoint")
                TactileField(value = model, onValueChange = { model = it }, label = "Model")
                TactileField(value = key, onValueChange = { key = it }, label = "API key")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TactileSwitch(checked = approved, onCheckedChange = { approved = it })
                    Text("I approve this transmission", modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {
            TactileButton(
                text = "Get proposal",
                onClick = { onRun(endpoint, model, key, approved) },
                enabled = approved && model.isNotBlank() && key.isNotBlank(),
            )
        },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
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
                TactileField(value = url, onValueChange = { url = it }, label = "HTTPS server URL")
                TactileField(value = username, onValueChange = { username = it }, label = "Username")
                // No PasswordVisualTransformation was applied to this field before this swap
                // either -- preserved exactly, not fixed, per the same note on AiDialog's key
                // field above.
                TactileField(value = password, onValueChange = { password = it }, label = "Password")
                TactileField(value = path, onValueChange = { path = it }, label = "Path")
            }
        },
        confirmButton = {
            TactileButton(
                text = "List folder",
                onClick = { onConnect(url, username, password, path) },
                enabled = url.startsWith("https://") && username.isNotBlank(),
            )
        },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
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
    allTags: Map<String, Int>,
    onTagSelected: (String) -> Unit,
) {
    // Which non-tab destination the wheel is resting on. Local to the room: it is a view of this
    // room, not app state, and it clears the moment the wheel settles on anything else.
    var showTags by remember { mutableStateOf(false) }
    val items = remember(tabs) {
        buildList {
            add(WheelItem(HOME_WHEEL_ID, "Home"))
            // Tags rides the wheel beside Home because it IS a place: tapping a tag runs
            // LibraryStore.itemsWithTag device-wide and opens a real listing of every file
            // carrying it, which is the same kind of destination a folder tab is. It used to be a
            // 40dp row wedged under a divider next to the settings gear -- the owner's words were
            // "Tags is a tiny thing above settings", and the code's own comment called it "the
            // quiet way in". The feature was never the problem; where it was reachable from was.
            add(WheelItem(TAGS_WHEEL_ID, "Tags"))
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
                    HOME_WHEEL_ID -> { showTags = false; onOpenHome() }
                    // NOT onOpenTags(): WordWheelRail fires onSelect when a turn SETTLES on a row,
                    // not only when one is tapped (its own KDoc says so), which is what makes
                    // scrolling to a tab switch to it. A row that popped a modal would therefore
                    // open a dialog every time the wheel happened to rest on Tags on its way
                    // somewhere else. Settling here just shows the tag list in this room's own
                    // body, exactly the way settling on a folder tab shows that tab's tree --
                    // harmless to land on, and a real place rather than a dialog once you do.
                    TAGS_WHEEL_ID -> showTags = true
                    ADD_WHEEL_ID -> { showTags = false; onAdd() }
                    else -> { showTags = false; onSelect(id) }
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
                    TactileIconKey(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close ${tab.current.name}",
                        onClick = { onClose(tab) },
                    )
                }
            },
        )

        if (showTags) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            RoomHeading("Tags")
            TagBrowser(
                tags = allTags,
                onTagSelected = onTagSelected,
                modifier = Modifier.weight(1f),
            )
        } else if (activeTab != null) {
            // The structure under whichever location is open, below the quick links rather than
            // beside them: the wheel answers "which location", the tree answers "where in it".
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
        // more, if you want it" rather than as a fourth thing to navigate. Tags rides beside it
        // for the same reason -- the owner's own "is that done?" needed somewhere to browse from
        // that asks for no folder tab to be open, which the query box (folder-scoped) cannot be.
        // Settings alone down here now. Tags moved up to the wheel -- see the item list above.
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                // The room's own last row, and the only one that was still under the floor:
                // 40dp of height with an 18dp glyph in it. The glyph stays 18dp.
                .heightIn(min = 48.dp)
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

/** The wheel's own Tags destination -- a place beside Home, not a row beside the gear. */
private const val TAGS_WHEEL_ID = "__tags__"
