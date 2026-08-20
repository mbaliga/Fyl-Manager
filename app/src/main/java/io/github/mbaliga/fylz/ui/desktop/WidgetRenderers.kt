package io.github.mbaliga.fylz.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mbaliga.fylz.desktop.DesktopItem
import io.github.mbaliga.fylz.desktop.DesktopWidgetType
import io.github.mbaliga.fylz.history.RecentOpen
import io.github.mbaliga.fylz.ui.overview.DeletedFilesCard
import io.github.mbaliga.fylz.ui.overview.LargeFilesCard
import io.github.mbaliga.fylz.ui.overview.OverviewCard
import io.github.mbaliga.fylz.ui.overview.PinnedCard
import io.github.mbaliga.fylz.ui.overview.QuickAccessCard
import io.github.mbaliga.fylz.ui.overview.QuickActionsCard
import io.github.mbaliga.fylz.ui.overview.RecentsCard
import io.github.mbaliga.fylz.ui.overview.SearchPill
import io.github.mbaliga.fylz.ui.overview.ShelfCard
import io.github.mbaliga.fylz.ui.overview.StorageCard
import io.github.mbaliga.fylz.ui.overview.TagsCard

/**
 * Everything [DesktopWidgetContent] needs to draw the widgets that read shared, device-wide facts
 * -- gathered above it by [DesktopScreen] (`produceState` off `Dispatchers.IO`, keyed on
 * `refreshKey`, mirroring `OverviewScreen.kt:100-170`) so nothing in this file, or in the plain
 * card composables it dispatches to, ever touches a repository, a store, or IO directly.
 *
 * [QUICK_ACCESS][io.github.mbaliga.fylz.desktop.DesktopWidgetType.QUICK_ACCESS] is deliberately
 * NOT a field here: its target is per-widget config (`downloads`, or one specific picked folder),
 * so [DesktopTile] resolves it itself, once per widget instance, and hands the result to
 * [DesktopWidgetContent] as [QuickAccessResolution] rather than this shared holder trying to
 * carry a map keyed by every quick-access widget's own id.
 */
data class DesktopWidgetData(
    val nowMillis: Long,
    val storage: OverviewCard.Storage? = null,
    val onRefreshStorage: () -> Unit = {},
    val recycleBin: OverviewCard.DeletedFiles? = null,
    val tags: OverviewCard.Tags? = null,
    val pinned: OverviewCard.Pinned? = null,
    val shelfCount: Int = 0,
    val shelfPreviewNames: List<String> = emptyList(),
    val recents: List<RecentOpen> = emptyList(),
)

/** A resolved QUICK_ACCESS target, built by [DesktopTile] from one widget's own `config` -- see
 *  [DesktopWidgetData]'s own KDoc for why this travels separately from the rest of the data. */
data class QuickAccessResolution(
    val card: OverviewCard.QuickAccess,
    val onOpen: () -> Unit,
)

/**
 * Dispatches one [DesktopItem.Widget] to the plain card composable
 * [io.github.mbaliga.fylz.ui.overview.OverviewCards] already defines for its own type -- every
 * branch below reuses that composable verbatim rather than redrawing the same content a second
 * way, so the desktop widget and (where one still exists) the overview card can never quietly
 * disagree about what a given fact looks like.
 *
 * Exhaustive over [DesktopWidgetType] by construction (no `else`): a type added to that enum
 * without a matching branch here fails the build, the same guarantee [WidgetRegistry.of] pins for
 * the gallery metadata side.
 */
@Composable
internal fun DesktopWidgetContent(
    item: DesktopItem.Widget,
    data: DesktopWidgetData,
    quickAccess: QuickAccessResolution?,
    callbacks: DesktopCallbacks,
    modifier: Modifier = Modifier,
) {
    when (item.type) {
        DesktopWidgetType.STORAGE -> StorageCard(
            card = data.storage ?: OverviewCard.Storage(hasFullAccess = false, usedBytes = null, totalBytes = null, usage = null, scanning = false),
            modifier = modifier,
            onRefreshUsage = data.onRefreshStorage,
            onGrantFullAccess = callbacks.onGrantFullAccess,
            onFindLargeFiles = callbacks.onOpenLargeFiles,
            onCleanUpDuplicates = callbacks.onPickDuplicatesFolder,
        )

        DesktopWidgetType.QUICK_ACCESS -> QuickAccessCard(
            card = quickAccess?.card ?: OverviewCard.QuickAccess(root = null, hasFullAccess = true, entryCount = null, thumbnails = emptyList()),
            modifier = modifier,
            onOpen = quickAccess?.onOpen ?: {},
            onOpenFile = { entry -> callbacks.onOpenFileShortcut(entry.uri) },
            onGrantFullAccess = callbacks.onGrantFullAccess,
            onPickFolder = { callbacks.onPickQuickAccessFolder(item.id) },
        )

        DesktopWidgetType.RECYCLE_BIN -> DeletedFilesCard(
            card = data.recycleBin ?: OverviewCard.DeletedFiles(0, 0L, 0, ""),
            modifier = modifier,
            onSeeFiles = callbacks.onOpenTrash,
        )

        DesktopWidgetType.TAGS -> TagsCard(
            card = data.tags ?: OverviewCard.Tags(emptyList()),
            modifier = modifier,
            onOpenTag = callbacks.onOpenTag,
        )

        DesktopWidgetType.PINNED -> PinnedCard(
            card = data.pinned ?: OverviewCard.Pinned(emptyList()),
            modifier = modifier,
            onOpenFavorite = callbacks.onOpenFavorite,
        )

        DesktopWidgetType.SHELF -> ShelfCard(
            count = data.shelfCount,
            previewNames = data.shelfPreviewNames,
            modifier = modifier,
            onOpenShelf = callbacks.onOpenShelf,
        )

        DesktopWidgetType.RECENTS -> RecentsCard(
            items = data.recents,
            nowMillis = data.nowMillis,
            modifier = modifier,
            onOpenFile = callbacks.onOpenFileShortcut,
        )

        DesktopWidgetType.SEARCH -> SearchPill(modifier = modifier, onFocusSearch = callbacks.onFocusSearch)

        DesktopWidgetType.QUICK_ACTIONS -> QuickActionsCard(
            modifier = modifier,
            onScan = callbacks.onScan,
            onFocusSearch = callbacks.onFocusSearch,
            onOpenShelf = callbacks.onOpenShelf,
            onOpenTrash = callbacks.onOpenTrash,
        )

        DesktopWidgetType.LARGE_FILES -> LargeFilesCard(
            usage = data.storage?.usage,
            scanning = data.storage?.scanning ?: false,
            modifier = modifier,
            onSeeAll = callbacks.onOpenLargeFiles,
        )
    }
}
