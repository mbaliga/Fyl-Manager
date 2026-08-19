package io.github.mbaliga.fylz.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.operations.RecycleBinService
import io.github.mbaliga.fylz.operations.RecycleRecord
import io.github.mbaliga.fylz.storage.FileStorageProvider
import io.github.mbaliga.fylz.storage.FullAccessPermission
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageScanScheduler
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import io.github.mbaliga.fylz.storage.StorageUsageStore
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The landing home's fifth arm: a bento of cards summarizing what is on the device, rather than
 * a single folder's listing. Reachable wherever [io.github.mbaliga.fylz.ui.landing.HomeMode] is
 * routed to it -- this screen owns none of that routing itself.
 *
 * Reads [LocalThemeStyle] directly rather than trusting a caller to have read it first: the home
 * branch this screen replaces returns before that composition local is read, so under
 * [ThemeStyle.CLI] nothing upstream can be relied on to have already made the monospace-text-rows
 * decision for it.
 *
 * Every byte and every per-kind figure on this surface is either real or explicitly absent -- see
 * [OverviewCard.QuickAccess.hasFullAccess] and [OverviewCard.Storage.usage]. Nothing here is ever
 * a zero standing in for "unknown".
 */
@Composable
fun OverviewScreen(
    repository: DocumentRepository,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    onOpenFolder: (FolderLocation) -> Unit = {},
    onOpenFile: (FileEntry) -> Unit = {},
    onGrantFullAccess: () -> Unit = {},
    onSeeDeletedFiles: () -> Unit = {},
    onFindLargeFiles: () -> Unit = {},
    onCleanUpDuplicates: () -> Unit = {},
    onOpenFavorite: (FavoriteLocation) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    topTags: List<Pair<String, Int>> = emptyList(),
    retentionDescription: String = DEFAULT_RETENTION_DESCRIPTION,
) {
    val context = LocalContext.current
    val themeStyle = LocalThemeStyle.current

    // The grant round trip happens outside this app (Settings), so nothing here recomposes on
    // its own when the user comes back -- the same ON_RESUME re-check StorageHomeScreen uses.
    var hasFullAccess by remember { mutableStateOf(FullAccessPermission.isGranted()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasFullAccess = FullAccessPermission.isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val quickAccessRoots by produceState(emptyList<StorageRoot>(), refreshKey, hasFullAccess) {
        value = withContext(Dispatchers.IO) {
            StorageAccess.available(context).flatMap { it.rootGroups(context) }
                .firstOrNull { it.title == FileStorageProvider.GROUP_FOLDERS }
                ?.roots
                ?.filter(StorageRoot::opensDirectly)
                .orEmpty()
        }
    }
    val quickAccessTarget = quickAccessRoots.firstOrNull { it.title == "Downloads" } ?: quickAccessRoots.firstOrNull()
    val quickAccessListing by produceState<List<FileEntry>?>(null, quickAccessTarget, refreshKey) {
        value = quickAccessTarget?.let { root ->
            runCatching { repository.listChildren(requireNotNull(root.treeUri), requireNotNull(root.documentUri)) }.getOrNull()
        }
    }

    val kindFolderTarget = quickAccessRoots.firstOrNull { it.title == "Pictures" }
        ?: quickAccessRoots.firstOrNull { it.title == "Camera (DCIM)" }
    val kindFolderListing by produceState<List<FileEntry>?>(null, kindFolderTarget, refreshKey) {
        value = kindFolderTarget?.let { root ->
            runCatching { repository.listChildren(requireNotNull(root.treeUri), requireNotNull(root.documentUri)) }.getOrNull()
        }
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

    // The card only ever reads the last completed scan; nothing here triggers one. `scanTick`
    // is bumped once a tap-initiated scan is observed to have finished, which is the only way
    // this re-reads the store after the initial load.
    var scanTick by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    val usageSnapshot by produceState<StorageUsageSnapshot?>(null, refreshKey, scanTick, hasFullAccess) {
        value = if (!hasFullAccess) null else withContext(Dispatchers.IO) { StorageUsageStore(context).snapshot() }
    }
    val onRefreshUsage: () -> Unit = {
        if (!scanning && hasFullAccess) {
            scanning = true
            StorageScanScheduler(context).scan()
        }
    }
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        val before = usageSnapshot?.scannedAtMillis
        repeat(SCAN_POLL_ATTEMPTS) {
            delay(SCAN_POLL_INTERVAL_MILLIS)
            val latest = withContext(Dispatchers.IO) { StorageUsageStore(context).snapshot() }
            if (latest != null && latest.scannedAtMillis != before) {
                scanTick += 1
                scanning = false
                return@LaunchedEffect
            }
        }
        scanning = false
    }

    val favorites by produceState(emptyList<FavoriteLocation>(), refreshKey) {
        value = withContext(Dispatchers.IO) { LibraryStore(context).favorites() }
    }
    val recycleRecords by produceState(emptyList<RecycleRecord>(), refreshKey) {
        value = withContext(Dispatchers.IO) { RecycleBinService(context).records() }
    }

    val cards = remember(
        quickAccessTarget, quickAccessListing, hasFullAccess,
        kindFolderTarget, kindFolderListing,
        primaryRoot, usageSnapshot, scanning,
        recycleRecords, retentionDescription,
        favorites, topTags,
    ) {
        buildOverviewCards(
            quickAccessTarget = quickAccessTarget,
            hasFullAccess = hasFullAccess,
            quickAccessListing = quickAccessListing,
            kindFolderTarget = kindFolderTarget,
            kindFolderListing = kindFolderListing,
            primaryRoot = primaryRoot,
            usageSnapshot = usageSnapshot,
            scanning = scanning,
            recycleRecords = recycleRecords,
            retentionDescription = retentionDescription,
            favorites = favorites,
            topTags = topTags,
        )
    }

    val callbacks = OverviewCallbacks(
        onOpenFolder = { root -> onOpenFolder(FolderLocation(requireNotNull(root.documentUri), root.title)) },
        onOpenFile = onOpenFile,
        onGrantFullAccess = onGrantFullAccess,
        onRefreshUsage = onRefreshUsage,
        onFindLargeFiles = onFindLargeFiles,
        onCleanUpDuplicates = onCleanUpDuplicates,
        onSeeDeletedFiles = onSeeDeletedFiles,
        onOpenFavorite = onOpenFavorite,
        onOpenTag = onOpenTag,
    )

    if (themeStyle == ThemeStyle.CLI) {
        OverviewCliRows(cards, quickAccessTarget, kindFolderTarget, callbacks, modifier)
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            itemsIndexed(cards, key = { _, card -> card.id }, span = { index, _ -> GridItemSpan(cards[index].span) }) { _, card ->
                OverviewCardTile(card, quickAccessTarget, kindFolderTarget, callbacks, Modifier)
            }
        }
    }
}

/** One [OverviewScreen]'s worth of callbacks, gathered so the grid and the CLI rows dispatch off
 *  the same set instead of two independently-drifting parameter lists. */
internal data class OverviewCallbacks(
    val onOpenFolder: (StorageRoot) -> Unit,
    val onOpenFile: (FileEntry) -> Unit,
    val onGrantFullAccess: () -> Unit,
    val onRefreshUsage: () -> Unit,
    val onFindLargeFiles: () -> Unit,
    val onCleanUpDuplicates: () -> Unit,
    val onSeeDeletedFiles: () -> Unit,
    val onOpenFavorite: (FavoriteLocation) -> Unit,
    val onOpenTag: (String) -> Unit,
)

@Composable
private fun OverviewCardTile(
    card: OverviewCard,
    quickAccessTarget: StorageRoot?,
    kindFolderTarget: StorageRoot?,
    callbacks: OverviewCallbacks,
    modifier: Modifier,
) {
    when (card) {
        is OverviewCard.QuickAccess -> QuickAccessCard(
            card,
            modifier,
            onOpen = { quickAccessTarget?.let(callbacks.onOpenFolder) },
            onOpenFile = callbacks.onOpenFile,
            onGrantFullAccess = callbacks.onGrantFullAccess,
        )
        is OverviewCard.KindFolder -> KindFolderCard(
            card,
            modifier,
            onOpen = { kindFolderTarget?.let(callbacks.onOpenFolder) },
        )
        is OverviewCard.Storage -> StorageCard(
            card,
            modifier,
            onRefreshUsage = callbacks.onRefreshUsage,
            onGrantFullAccess = callbacks.onGrantFullAccess,
            onFindLargeFiles = callbacks.onFindLargeFiles,
            onCleanUpDuplicates = callbacks.onCleanUpDuplicates,
        )
        is OverviewCard.DeletedFiles -> DeletedFilesCard(card, modifier, onSeeFiles = callbacks.onSeeDeletedFiles)
        is OverviewCard.Pinned -> PinnedCard(card, modifier, onOpenFavorite = callbacks.onOpenFavorite)
        is OverviewCard.Tags -> TagsCard(card, modifier, onOpenTag = callbacks.onOpenTag)
    }
}

@Composable
private fun OverviewCliRows(
    cards: List<OverviewCard>,
    quickAccessTarget: StorageRoot?,
    kindFolderTarget: StorageRoot?,
    callbacks: OverviewCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(cards, key = { it.id }) { card ->
            val onHeadingClick: (() -> Unit)? = when (card) {
                is OverviewCard.QuickAccess -> quickAccessTarget?.let { root -> { callbacks.onOpenFolder(root) } }
                is OverviewCard.KindFolder -> kindFolderTarget?.let { root -> { callbacks.onOpenFolder(root) } }
                is OverviewCard.DeletedFiles -> callbacks.onSeeDeletedFiles
                else -> null
            }
            CliRow(overviewCliHeading(card), style = MaterialTheme.typography.labelLarge, onClick = onHeadingClick)
            overviewCliLines(card).forEach { line -> CliRow(line, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CliRow(text: String, style: TextStyle, onClick: (() -> Unit)? = null) {
    val rowModifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    Text(text, style = style, modifier = rowModifier)
}

private fun buildOverviewCards(
    quickAccessTarget: StorageRoot?,
    hasFullAccess: Boolean,
    quickAccessListing: List<FileEntry>?,
    kindFolderTarget: StorageRoot?,
    kindFolderListing: List<FileEntry>?,
    primaryRoot: StorageRoot?,
    usageSnapshot: StorageUsageSnapshot?,
    scanning: Boolean,
    recycleRecords: List<RecycleRecord>,
    retentionDescription: String,
    favorites: List<FavoriteLocation>,
    topTags: List<Pair<String, Int>>,
): List<OverviewCard> = buildList {
    add(
        OverviewCard.QuickAccess(
            root = quickAccessTarget,
            hasFullAccess = hasFullAccess,
            entryCount = quickAccessListing?.size,
            thumbnails = quickAccessListing.orEmpty()
                .filter { it.kind == EntryKind.IMAGE || it.kind == EntryKind.VIDEO }
                .take(3),
        ),
    )

    if (kindFolderTarget != null) {
        val images = kindFolderListing.orEmpty().filter { it.kind == EntryKind.IMAGE }
        add(
            OverviewCard.KindFolder(
                folderName = kindFolderTarget.title,
                kind = EntryKind.IMAGE,
                count = images.size,
                lastModifiedMillis = images.mapNotNull(FileEntry::lastModifiedMillis).maxOrNull(),
            ),
        )
    }

    val usedBytes = primaryRoot?.availableBytes?.let { available ->
        primaryRoot.totalBytes?.let { total -> (total - available).coerceAtLeast(0L) }
    }
    add(
        OverviewCard.Storage(
            hasFullAccess = hasFullAccess,
            usedBytes = usedBytes,
            totalBytes = primaryRoot?.totalBytes,
            usage = usageSnapshot,
            scanning = scanning,
        ),
    )

    val tally = tallyBytes(recycleRecords.map(RecycleRecord::sizeBytes))
    add(
        OverviewCard.DeletedFiles(
            totalCount = recycleRecords.size,
            recoverableBytes = tally.totalBytes,
            notReportedCount = tally.unmeasuredCount,
            retentionDescription = retentionDescription,
        ),
    )

    if (favorites.isNotEmpty()) add(OverviewCard.Pinned(favorites))
    if (topTags.isNotEmpty()) add(OverviewCard.Tags(topTags))
}

private const val SCAN_POLL_ATTEMPTS = 40
private const val SCAN_POLL_INTERVAL_MILLIS = 500L
