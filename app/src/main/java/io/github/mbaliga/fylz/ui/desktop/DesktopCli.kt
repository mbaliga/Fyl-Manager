package io.github.mbaliga.fylz.ui.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.desktop.DesktopItem
import io.github.mbaliga.fylz.desktop.DesktopStore
import io.github.mbaliga.fylz.desktop.DesktopWidgetType

/**
 * The desktop, under [io.github.mbaliga.fylz.ui.theme.ThemeStyle.CLI]: plain text rows, no
 * wallpaper -- the same "the indented tree IS the theme" rule
 * [io.github.mbaliga.fylz.ui.FylzV1App]'s own `FileBrowser` already applies to a folder listing
 * under CLI. Every shortcut opens on tap, same as [DesktopScreen]; a widget row names what it is
 * and, where one applies, opens the same destination its graphical card's own primary action
 * would -- deliberately not a rendering of the widget's own content (Storage's segmented bar,
 * Recents' list), since CLI's whole point is rows of text, not a second graphical surface in a
 * monospace font.
 *
 * See [DesktopScreen]'s own KDoc for why this file exists under Workstream W rather than C.
 */
@Composable
fun DesktopCli(
    store: DesktopStore,
    refreshKey: Int,
    callbacks: DesktopCallbacks,
    modifier: Modifier = Modifier,
) {
    var desktopItems by remember { mutableStateOf(store.items()) }
    LaunchedEffect(refreshKey) { desktopItems = store.items() }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(desktopItems, key = DesktopItem::id) { item ->
            val label = cliLabel(item)
            val onClick = cliAction(item, callbacks)
            // A row's own touch target must clear 48dp even in CLI mode, where the "row" is just
            // one line of monospace text -- heightIn(min) guarantees the target without padding
            // the text itself away from its neighbours.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .let { if (onClick != null) it.clickable(onClick = onClick) else it },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (desktopItems.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.integration_desktop_cli_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

private fun cliLabel(item: DesktopItem): String = when (item) {
    is DesktopItem.FolderShortcut -> "[/] ${item.displayName}"
    is DesktopItem.FileShortcut -> "[.] ${item.displayName}"
    is DesktopItem.Widget -> "[#] ${item.type.name.lowercase().replace('_', ' ')}"
}

private fun cliAction(item: DesktopItem, callbacks: DesktopCallbacks): (() -> Unit)? = when (item) {
    is DesktopItem.FolderShortcut -> ({ callbacks.onOpenFolderShortcut(item.treeUri, item.folderUri) })
    is DesktopItem.FileShortcut -> ({ callbacks.onOpenFileShortcut(item.uri) })
    is DesktopItem.Widget -> when (item.type) {
        DesktopWidgetType.RECYCLE_BIN -> callbacks.onOpenTrash
        DesktopWidgetType.SHELF -> callbacks.onOpenShelf
        DesktopWidgetType.SEARCH -> callbacks.onFocusSearch
        DesktopWidgetType.LARGE_FILES -> callbacks.onOpenLargeFiles
        DesktopWidgetType.QUICK_ACTIONS,
        DesktopWidgetType.STORAGE,
        DesktopWidgetType.QUICK_ACCESS,
        DesktopWidgetType.TAGS,
        DesktopWidgetType.PINNED,
        DesktopWidgetType.RECENTS,
        -> null
    }
}
