package io.github.mbaliga.fylz.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.library.FavoriteLocation
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.ui.theme.FylzTheme

/**
 * Configuration screen for [FolderShortcutWidgetProvider], launched by the platform via
 * `APPWIDGET_CONFIGURE` right after the widget is placed (and reachable again from the widget's
 * own unconfigured face, per that provider's `buildViews`).
 *
 * Two pickable lists, per the plan: (a) [LibraryStore.favorites] -- the folders the user has
 * already marked as favourites elsewhere in the app -- and (b) every tree this app currently
 * holds a persisted read grant for, i.e. every folder reachable without another SAF picker
 * round-trip. Picking either writes this widget's config and pushes its first real render before
 * finishing, so the home screen never shows the "tap to configure" face for longer than it takes
 * to pick.
 */
class FolderWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Standard app widget config contract: default to CANCELED so backing out (or the
        // process dying before a pick) leaves the host free to drop the half-placed widget.
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            FylzTheme(
                themeMode = ThemeMode.SYSTEM,
                accentPreset = AccentPreset.MOSS,
                dynamicColor = true,
            ) {
                FolderWidgetConfigScreen(
                    favorites = LibraryStore(applicationContext).favorites(),
                    grantedRoots = grantedTreeRoots(applicationContext),
                    onPick = { treeUri, folderUri, label -> finishWithPick(appWidgetId, treeUri, folderUri, label) },
                )
            }
        }
    }

    private fun finishWithPick(appWidgetId: Int, treeUri: Uri, folderUri: Uri, label: String) {
        FolderShortcutWidgetProvider.writeConfig(applicationContext, appWidgetId, treeUri, folderUri, label)
        AppWidgetManager.getInstance(applicationContext)
            .updateAppWidget(appWidgetId, FolderShortcutWidgetProvider.buildViews(applicationContext, appWidgetId))
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

/** One row this config screen can hand back to [FolderWidgetConfigActivity.finishWithPick]. */
private data class PickableFolder(val treeUri: Uri, val folderUri: Uri, val label: String)

/**
 * Every tree this app currently holds a persisted READ grant for, named from the tree's own root
 * document -- [io.github.mbaliga.fylz.ui.FylzV1App.openFavorite]'s "recover the tree from a
 * persisted grant sharing the same authority" trick run in the other direction: here the grant
 * itself is the starting point, not something to recover.
 */
private fun grantedTreeRoots(context: Context): List<PickableFolder> =
    context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .mapNotNull { permission ->
            val treeUri = permission.uri
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@mapNotNull null
            val folderUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            }.getOrNull() ?: return@mapNotNull null
            val label = displayNameFor(context, folderUri)
                ?: documentId.substringAfterLast('/').ifBlank { "Folder" }
            PickableFolder(treeUri, folderUri, label)
        }

/** Favourites recovered against the app's own persisted grants, exactly like
 *  [io.github.mbaliga.fylz.ui.FylzV1App.openFavorite] -- a favourite the current grant set can no
 *  longer reach (its permission was revoked) is silently dropped from this list rather than
 *  offered as a pick that would fail the moment the widget is tapped. */
private fun reachableFavorites(context: Context, favorites: List<FavoriteLocation>): List<PickableFolder> {
    val readGrants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
    return favorites.mapNotNull { favorite ->
        val treeUri = readGrants.firstOrNull { it.uri.authority == favorite.uri.authority }?.uri
            ?: return@mapNotNull null
        PickableFolder(treeUri, favorite.uri, favorite.name)
    }
}

private fun displayNameFor(context: Context, documentUri: Uri): String? = runCatching {
    context.contentResolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

@Composable
private fun FolderWidgetConfigScreen(
    favorites: List<FavoriteLocation>,
    grantedRoots: List<PickableFolder>,
    onPick: (treeUri: Uri, folderUri: Uri, label: String) -> Unit,
) {
    val context = LocalContext.current
    val pickableFavorites = reachableFavorites(context, favorites)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) }) },
    ) { padding ->
        if (pickableFavorites.isEmpty() && grantedRoots.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.widget_config_empty), modifier = Modifier.padding(24.dp))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (pickableFavorites.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.widget_config_favorites)) }
                items(pickableFavorites) { folder ->
                    FolderRow(folder, icon = Icons.Outlined.Star, onPick = onPick)
                }
            }
            if (grantedRoots.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.widget_config_folders)) }
                items(grantedRoots) { folder ->
                    FolderRow(folder, icon = Icons.Outlined.Folder, onPick = onPick)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FolderRow(
    folder: PickableFolder,
    icon: ImageVector,
    onPick: (treeUri: Uri, folderUri: Uri, label: String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(folder.treeUri, folder.folderUri, folder.label) }
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(folder.label, style = MaterialTheme.typography.bodyLarge)
    }
}
