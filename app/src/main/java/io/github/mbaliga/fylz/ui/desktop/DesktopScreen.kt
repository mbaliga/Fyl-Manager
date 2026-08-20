package io.github.mbaliga.fylz.ui.desktop

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.desktop.DesktopItem
import io.github.mbaliga.fylz.desktop.DesktopPolicy
import io.github.mbaliga.fylz.desktop.DesktopStore
import io.github.mbaliga.fylz.desktop.DesktopWidgetType
import io.github.mbaliga.fylz.history.RecentOpen
import io.github.mbaliga.fylz.history.RecentOpensStore
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.operations.RecycleBinRetentionStore
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.operations.RecycleRecord
import io.github.mbaliga.fylz.operations.describe
import io.github.mbaliga.fylz.staging.ShelfItem
import io.github.mbaliga.fylz.staging.ShelfStore
import io.github.mbaliga.fylz.storage.FileStorageProvider
import io.github.mbaliga.fylz.storage.FullAccessPermission
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageScanScheduler
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import io.github.mbaliga.fylz.storage.StorageUsageStore
import io.github.mbaliga.fylz.ui.overview.OverviewCard
import io.github.mbaliga.fylz.ui.overview.tallyBytes
import io.github.mbaliga.fylz.wallpaper.WallpaperSpec
import io.github.mbaliga.fylz.widgets.WidgetRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything [DesktopScreen] can be asked to do -- gathered here so the workspace's job is "know
 * how", not "thread fifteen lambdas through a parameter list", the same split
 * [io.github.mbaliga.fylz.ui.ActionsRoom]'s own `runAction` makes. The exact shape named in the
 * shared API contract (`onOpenFolderShortcut` through `onPickDuplicatesFolder`) is preserved
 * verbatim; [onGrantFullAccess] is the one addition -- see this file's own KDoc for why.
 */
data class DesktopCallbacks(
    val onOpenFolderShortcut: (treeUri: Uri, folderUri: Uri) -> Unit,
    val onOpenFileShortcut: (Uri) -> Unit,
    val onOpenTrash: () -> Unit,
    val onOpenShelf: () -> Unit,
    val onFocusSearch: () -> Unit,
    val onScan: () -> Unit,
    val onOpenTag: (String) -> Unit,
    val onOpenFavorite: (FavoriteLocation) -> Unit,
    val onOpenWallpaperPicker: () -> Unit,
    val onOpenLargeFiles: () -> Unit,
    val onPickFolderShortcut: () -> Unit,
    val onPickQuickAccessFolder: (widgetId: String) -> Unit,
    val onPickDuplicatesFolder: () -> Unit,
    // Additive, beyond the shared contract shape: the Storage and Quick Access widget content
    // (io.github.mbaliga.fylz.ui.desktop.WidgetRenderers, Workstream C's) already calls
    // `callbacks.onGrantFullAccess` for their permission CTA -- the same callback
    // io.github.mbaliga.fylz.ui.overview.OverviewScreen's own contract already carries. Left out
    // of the contract's bare list but required by code Workstream C already shipped, so it is
    // added here rather than left unresolved. Defaults to a no-op so a caller that predates this
    // note still compiles.
    val onGrantFullAccess: () -> Unit = {},
)

/**
 * The freeform desktop landing surface: [io.github.mbaliga.fylz.desktop.DesktopItem]s (folder and
 * file shortcuts, widgets) scattered over [wallpaperSpec], dragged one at a time, with an edit
 * mode for adding and removing.
 *
 * **Workstream-boundary note.** This file was specified by the shared API contract as
 * Workstream C's deliverable (`io.github.mbaliga.fylz.ui.desktop` is C's package). C's own report
 * for this build came back empty -- no `DesktopScreen` or `DesktopCallbacks` was ever produced,
 * only the supporting pieces this composable leans on: [DesktopTile] (the per-item gesture/visual
 * host), [WidgetRegistry] (the widget gallery's metadata), [DesktopWidgetContent]/[DesktopWidgetData]
 * (widget dispatch, already reusing [io.github.mbaliga.fylz.ui.overview]'s card composables). Since
 * every other workstream's integration and the shared contract both depend on this composable
 * existing, Workstream W (integration) wrote it -- a deviation from W's own file-ownership list
 * (`ui/desktop/` was never W's), made only because nothing else would compile or run otherwise.
 * Called out loudly in the integration summary; a future pass by whoever owns C's package should
 * feel free to replace this with something more considered.
 *
 * Widget data ([DesktopWidgetData]) is gathered here the same way
 * [io.github.mbaliga.fylz.ui.overview.OverviewScreen] gathers its own cards -- `produceState` off
 * `Dispatchers.IO`, keyed on [refreshKey] -- so none of the plain card composables it dispatches to
 * ever touch a repository, a store, or IO directly.
 *
 * @param snapEnabled whether a drag settles onto the grid ([DesktopPolicy.snap]) or the freeform
 *   safe band ([DesktopPolicy.clamp]) -- Settings' "Snap icons to grid" toggle.
 * @param showLabels whether shortcut tiles show a name beneath their thumbnail -- Settings' "Icon
 *   labels" toggle.
 */
@Composable
fun DesktopScreen(
    store: DesktopStore,
    wallpaperSpec: WallpaperSpec,
    repository: DocumentRepository,
    refreshKey: Int,
    bottomReserve: Dp,
    callbacks: DesktopCallbacks,
    modifier: Modifier = Modifier,
    snapEnabled: Boolean = true,
    showLabels: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(store.items()) }
    fun reload() { items = store.items() }
    LaunchedEffect(refreshKey) { reload() }

    var editing by remember { mutableStateOf(false) }
    var arrangingId by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    // The desktop's own edit mode gets its own back rung, the same shape
    // io.github.mbaliga.fylz.ui.FylzV1App's own three root-level BackHandlers already use
    // (individually enabled, each governing one level) -- a first press while editing exits edit
    // mode rather than falling through to that composable's own "press again to exit" handler.
    BackHandler(enabled = editing) {
        editing = false
        showAddSheet = false
    }

    // The grant round trip happens outside this app (Settings); re-checked on ON_RESUME, the same
    // pattern OverviewScreen/StorageHomeScreen both use.
    var hasFullAccess by remember { mutableStateOf(FullAccessPermission.isGranted()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasFullAccess = FullAccessPermission.isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val primaryRoot by produceState<StorageRoot?>(null, refreshKey, hasFullAccess) {
        value = if (!hasFullAccess) {
            null
        } else {
            withContext(Dispatchers.IO) {
                StorageAccess.fileProvider.rootGroups(context)
                    .firstOrNull { it.title == FileStorageProvider.GROUP_DEVICE }
                    ?.roots?.firstOrNull()
            }
        }
    }

    var scanTick by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    val usageSnapshot by produceState<StorageUsageSnapshot?>(null, refreshKey, scanTick, hasFullAccess) {
        value = if (!hasFullAccess) null else withContext(Dispatchers.IO) { StorageUsageStore(context).snapshot() }
    }
    val onRefreshStorage: () -> Unit = {
        if (!scanning && hasFullAccess) {
            scanning = true
            StorageScanScheduler(context).scan()
        }
    }
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        val before = usageSnapshot?.scannedAtMillis
        repeat(DESKTOP_SCAN_POLL_ATTEMPTS) {
            delay(DESKTOP_SCAN_POLL_INTERVAL_MILLIS)
            val latest = withContext(Dispatchers.IO) { StorageUsageStore(context).snapshot() }
            if (latest != null && latest.scannedAtMillis != before) {
                scanTick += 1
                scanning = false
                // The Storage system widget shows the same last-scan figures this card does --
                // push it a fresh render the moment a tap-initiated scan actually finishes,
                // fire-and-forget, same as every other post-mutation refresh in this build.
                WidgetRefresher.refreshAll(context)
                return@LaunchedEffect
            }
        }
        scanning = false
    }

    val recycleRecords by produceState(emptyList<RecycleRecord>(), refreshKey) {
        value = withContext(Dispatchers.IO) { RecycleBinService(context).records() }
    }
    val favorites by produceState(emptyList<FavoriteLocation>(), refreshKey) {
        value = withContext(Dispatchers.IO) { LibraryStore(context).favorites() }
    }
    val topTags by produceState(emptyList<Pair<String, Int>>(), refreshKey) {
        value = withContext(Dispatchers.IO) {
            LibraryStore(context).allTags().entries.sortedByDescending { it.value }.take(DESKTOP_TOP_TAGS).map { it.key to it.value }
        }
    }
    val shelfItemsState by produceState(emptyList<ShelfItem>(), refreshKey) {
        value = withContext(Dispatchers.IO) { ShelfStore(context).items() }
    }
    val recents by produceState(emptyList<RecentOpen>(), refreshKey) {
        value = withContext(Dispatchers.IO) { RecentOpensStore(context).items() }
    }
    val retentionDescription = remember(refreshKey) { RecycleBinRetentionStore(context).period().describe() }

    val widgetData = remember(
        hasFullAccess, primaryRoot, usageSnapshot, scanning,
        recycleRecords, retentionDescription, favorites, topTags,
        shelfItemsState, recents,
    ) {
        val usedBytes = primaryRoot?.availableBytes?.let { available ->
            primaryRoot?.totalBytes?.let { total -> (total - available).coerceAtLeast(0L) }
        }
        val tally = tallyBytes(recycleRecords.map(RecycleRecord::sizeBytes))
        DesktopWidgetData(
            nowMillis = System.currentTimeMillis(),
            storage = OverviewCard.Storage(
                hasFullAccess = hasFullAccess,
                usedBytes = usedBytes,
                totalBytes = primaryRoot?.totalBytes,
                usage = usageSnapshot,
                scanning = scanning,
            ),
            onRefreshStorage = onRefreshStorage,
            recycleBin = OverviewCard.DeletedFiles(
                totalCount = recycleRecords.size,
                recoverableBytes = tally.totalBytes,
                notReportedCount = tally.unmeasuredCount,
                retentionDescription = retentionDescription,
            ),
            tags = OverviewCard.Tags(topTags),
            pinned = OverviewCard.Pinned(favorites),
            shelfCount = shelfItemsState.size,
            shelfPreviewNames = shelfItemsState.take(DESKTOP_SHELF_PREVIEW).map { it.displayName },
            recents = recents,
        )
    }

    fun addWidget(type: DesktopWidgetType, config: Map<String, String> = emptyMap()) {
        val registration = WidgetRegistry.of(type)
        val placement = DesktopPolicy.nextFreePlacement(items.map(DesktopItem::placement))
        store.upsert(
            DesktopItem.Widget(
                type = type,
                size = registration.defaultSize,
                config = config,
                placement = placement,
            ),
        )
        reload()
    }

    Box(modifier.fillMaxSize()) {
        WallpaperLayer(wallpaperSpec, Modifier.fillMaxSize())

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidthPx = constraints.maxWidth.toFloat()
            val viewportHeightPx = constraints.maxHeight.toFloat()
            val maxZ = items.maxOfOrNull { it.placement.z } ?: 0

            items.forEach { item ->
                DesktopTile(
                    item = item,
                    editing = editing,
                    arranging = arrangingId == item.id,
                    snapEnabled = snapEnabled,
                    showLabels = showLabels,
                    viewportWidthDp = maxWidth,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    repository = repository,
                    refreshKey = refreshKey,
                    widgetData = widgetData,
                    callbacks = callbacks,
                    onEnterArrange = {
                        // arrangingId flips first, synchronously, so the lift (scale/elevation in
                        // DesktopTile) shows the instant the long-press is recognised -- the
                        // z-raise itself is a SharedPreferences commit() (blocking disk I/O) and
                        // must never sit between the haptic and that visual feedback, nor block
                        // the drag's own pointer-tracking loop from starting.
                        arrangingId = item.id
                        val itemId = item.id
                        val raised = DesktopPolicy.raise(item.placement, maxZ)
                        scope.launch(Dispatchers.IO) {
                            store.place(itemId, raised)
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                    onExitArrange = { if (arrangingId == item.id) arrangingId = null },
                    onCommit = { placement ->
                        store.place(item.id, placement)
                        reload()
                    },
                    onRemove = {
                        store.remove(item.id)
                        reload()
                        if (arrangingId == item.id) arrangingId = null
                    },
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(bottom = bottomReserve + 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (editing) {
                Surface(
                    onClick = { editing = false; showAddSheet = false },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.desktop_edit_done))
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { editing = true; showAddSheet = true },
                icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                text = { Text(stringResource(R.string.desktop_edit_add)) },
            )
        }
    }

    if (showAddSheet) {
        DesktopAddSheet(
            atCapacity = items.size >= DesktopPolicy.MAX_ITEMS,
            onAddWidget = { type -> addWidget(type); showAddSheet = false },
            onAddFolder = { showAddSheet = false; callbacks.onPickFolderShortcut() },
            onOpenWallpaper = { showAddSheet = false; callbacks.onOpenWallpaperPicker() },
            onDismiss = { showAddSheet = false },
        )
    }
}

@Composable
private fun DesktopAddSheet(
    atCapacity: Boolean,
    onAddWidget: (DesktopWidgetType) -> Unit,
    onAddFolder: () -> Unit,
    onOpenWallpaper: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !atCapacity, onClick = onAddFolder).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                Text(stringResource(R.string.desktop_add_folder), Modifier.padding(start = 16.dp))
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenWallpaper).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Wallpaper, contentDescription = null)
                Text(stringResource(R.string.desktop_add_wallpaper), Modifier.padding(start = 16.dp))
            }
            if (atCapacity) {
                Text(
                    stringResource(R.string.integration_desktop_full),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    stringResource(R.string.desktop_add_widget),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(WidgetRegistry.entries) { registration ->
                        Surface(
                            onClick = { onAddWidget(registration.type) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                stringResource(registration.displayNameRes),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val DESKTOP_SCAN_POLL_ATTEMPTS = 40
private const val DESKTOP_SCAN_POLL_INTERVAL_MILLIS = 500L
private const val DESKTOP_TOP_TAGS = 12
private const val DESKTOP_SHELF_PREVIEW = 3
