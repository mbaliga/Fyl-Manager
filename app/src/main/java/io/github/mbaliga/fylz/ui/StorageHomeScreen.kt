package io.github.mbaliga.fylz.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.library.RecentFolder
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.storage.StorageRootKind
import io.github.mbaliga.fylz.ui.places.LauncherModel

/**
 * The landing screen, drawn as a launcher rather than a directory listing.
 *
 * A phone's home screen puts the few things people reach for big and close and pushes the long
 * tail out of the way, and that is the shape here (the choices live in [LauncherModel]):
 *
 * 1. **Storage cards** — one soft-gradient card per volume with how much room is left. They answer
 *    the first question people have of a file manager, and they are the reference's rounded cards.
 * 2. **Quick access** — the standard folders as tiles, plus Large files and the Recycle bin.
 * 3. **Recent** — the folders opened lately, so getting back to work is one tap.
 * 4. **Locations** — add a folder, reach a network location, and any cloud providers.
 *
 * With "All files access" granted, every tile opens directly -- the File-backed provider serves
 * the volumes and standard folders -- and no picker appears in the happy path. Without it the
 * tiles are already-granted subtrees plus one-tap `ACTION_OPEN_DOCUMENT_TREE` shortcuts seeded
 * with `EXTRA_INITIAL_URI`, so the user still lands somewhere useful instead of on a blank
 * screen, and a card above says plainly what to grant.
 *
 * Tiles are at least 48dp to touch, and every icon-only affordance carries a semantic label.
 */
@Composable
fun StorageHomeScreen(
    onOpenRoot: (StorageRoot) -> Unit,
    onPickFolder: (StorageRoot?) -> Unit,
    onOpenRemotes: () -> Unit,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    recents: List<RecentFolder> = emptyList(),
    onOpenRecent: (RecentFolder) -> Unit = {},
    onOpenLargeFiles: () -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
) {
    val context = LocalContext.current
    var permissionRequested by remember { mutableStateOf(false) }
    val provider = StorageAccess.fileProvider

    // The grant round trip happens OUTSIDE this app: the user leaves for the system's
    // "All files access" screen, flips the toggle, and comes back. Nothing about that trip
    // changes any Compose state here, so nothing recomposes — the previous bare
    // `provider.isReady(context)` read in composition stayed stale forever, and the gate card
    // survived a successful grant (the exact on-device report: "did not get access to all
    // files even after giving it access"). ON_RESUME is the one signal that reliably fires on
    // the way back from Settings, so re-read the grant there and let state drive everything.
    val lifecycleOwner = LocalLifecycleOwner.current
    var ready by remember { mutableStateOf(provider.isReady(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ready = provider.isReady(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val groups by produceState(
        initialValue = emptyList<StorageRootGroup>(),
        key1 = refreshKey,
        key2 = ready,
    ) {
        value = StorageAccess.available(context).flatMap { it.rootGroups(context) }
    }

    val loading = groups.isEmpty() && ready
    val model = remember(groups) { LauncherModel.from(groups) }

    fun openRoot(root: StorageRoot) {
        if (root.opensDirectly) onOpenRoot(root) else onPickFolder(root)
    }

    if (loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(Modifier.width(160.dp))
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.storage_home_loading))
            }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (!ready) {
            item(key = "gate") {
                PermissionGateCard(
                    message = provider.readinessMessage(context)
                        ?: stringResource(R.string.storage_permission_generic),
                    requested = permissionRequested,
                    onGrant = {
                        permissionRequested = true
                        provider.permissionIntent(context)?.let(context::startPermissionActivity)
                    },
                    onUsePicker = { onPickFolder(null) },
                )
            }
        }

        item(key = "header") {
            val primary = model.storages.firstOrNull()
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("Files", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                val free = primary?.availableBytes
                Text(
                    if (free != null) "${formatStorageBytes(free)} free on ${primary?.title?.lowercase().orEmpty()}" else "Everything on this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (model.storages.isNotEmpty()) {
            item(key = "storages") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(model.storages, key = { it.id }) { root ->
                        val index = model.storages.indexOf(root)
                        StorageCard(root = root, tint = index, onClick = { openRoot(root) })
                    }
                }
            }
        }

        item(key = "quick") {
            SectionLabel("Quick access")
            val tiles = buildList {
                model.shortcuts.forEach { root ->
                    add(LauncherTile(LauncherModel.tileLabel(root), shortcutIcon(root.title), root.opensDirectly.not()) { openRoot(root) })
                }
                add(LauncherTile("Large files", Icons.Outlined.DataUsage, false, onOpenLargeFiles))
                add(LauncherTile("Recycle bin", Icons.Outlined.RestoreFromTrash, false, onOpenRecycleBin))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tiles.chunked(TILES_PER_ROW).forEach { rowTiles ->
                    Row(Modifier.fillMaxWidth()) {
                        rowTiles.forEach { tile -> TileCell(tile, Modifier.weight(1f)) }
                        // Keep the last row's tiles the same width as the rest.
                        repeat(TILES_PER_ROW - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        if (recents.isNotEmpty()) {
            item(key = "recent-label") { SectionLabel("Recent") }
            items(recents.take(MAX_RECENTS_SHOWN), key = { "recent:${it.folderUri}" }) { recent ->
                RecentRow(recent, onClick = { onOpenRecent(recent) })
            }
        }

        item(key = "locations") {
            SectionLabel("Locations")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WideTile("Add folder", Icons.Outlined.Add, Modifier.weight(1f)) { onPickFolder(null) }
                WideTile("Network", Icons.Outlined.Cloud, Modifier.weight(1f), onOpenRemotes)
            }
        }
        items(model.more, key = { "more:${it.id}" }) { root ->
            StorageRootRow(root = root, onClick = { openRoot(root) })
        }

        item(key = "footer") {
            Text(
                stringResource(R.string.storage_home_access_note, StorageAccess.accessLabel(context)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
        }
    }
}

private const val TILES_PER_ROW = 4
private const val MAX_RECENTS_SHOWN = 5

private data class LauncherTile(
    val label: String,
    val icon: ImageVector,
    /** True when opening it still needs a folder grant, which the tile marks with a lock. */
    val needsGrant: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** One volume: a rounded soft-gradient card with the room left on it. */
@Composable
private fun StorageCard(root: StorageRoot, tint: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Two gradients that alternate, so a phone with an SD card shows two distinct cards.
    val brush = if (tint % 2 == 0) {
        Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer))
    } else {
        Brush.linearGradient(listOf(scheme.secondaryContainer, scheme.primaryContainer))
    }
    val ink = scheme.onPrimaryContainer
    val used = LauncherModel.usedFraction(root)
    Box(
        Modifier
            .width(280.dp)
            .height(156.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(brush)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open ${root.title}" }
            .padding(20.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(root.kind), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(root.title, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column {
                val free = root.availableBytes
                val total = root.totalBytes
                if (free != null && total != null && total > 0) {
                    Text("${formatStorageBytes(free)} free", style = MaterialTheme.typography.headlineSmall, color = ink, fontWeight = FontWeight.SemiBold)
                    Text("of ${formatStorageBytes(total)}", style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
                } else {
                    Text("Open", style = MaterialTheme.typography.headlineSmall, color = ink, fontWeight = FontWeight.SemiBold)
                }
                if (used != null) {
                    // A plain bar: no M3 progress indicator, so it renders identically across
                    // the library versions this app builds against.
                    Box(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(ink.copy(alpha = 0.18f)),
                    ) {
                        Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(ink.copy(alpha = 0.75f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun TileCell(tile: LauncherTile, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = tile.onClick)
            .semantics { contentDescription = tile.label }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            if (tile.needsGrant) {
                // Colour must not carry state alone: the lock says "this one still needs a grant".
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp),
                )
            }
        }
        Text(
            tile.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun WideTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun RecentRow(recent: RecentFolder, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .semantics { contentDescription = "Open ${recent.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(recent.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun shortcutIcon(title: String): ImageVector = when {
    title.startsWith("Download") -> Icons.Outlined.Download
    title.startsWith("Document") -> Icons.Outlined.Description
    title.startsWith("Picture") -> Icons.Outlined.Image
    title.startsWith("Camera") -> Icons.Outlined.CameraAlt
    title.startsWith("Music") -> Icons.Outlined.MusicNote
    title.startsWith("Movie") -> Icons.Outlined.Movie
    else -> Icons.Outlined.Folder
}

@Composable
private fun StorageRootRow(root: StorageRoot, onClick: () -> Unit) {
    val subtitle = buildString {
        root.subtitle?.let(::append)
        if (root.availableBytes != null && root.totalBytes != null && root.totalBytes > 0) {
            if (isNotEmpty()) append(" · ")
            append(
                stringResourceFormatFree(
                    root.availableBytes,
                    root.totalBytes,
                ),
            )
        }
        if (root.readOnly) {
            if (isNotEmpty()) append(" · ")
            append("Read-only")
        }
    }
    val actionLabel = if (root.opensDirectly) {
        stringResource(R.string.storage_home_open_action, root.title)
    } else {
        stringResource(R.string.storage_home_grant_action, root.title)
    }

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = actionLabel },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = iconFor(root.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(root.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!root.opensDirectly) {
                // Colour alone must not carry state (DESIGN.md); the lock icon plus the semantic
                // label above both say "this one still needs a grant".
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionGateCard(
    message: String,
    requested: Boolean,
    onGrant: () -> Unit,
    onUsePicker: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.storage_permission_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (requested) {
                Text(
                    stringResource(R.string.storage_permission_return_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onGrant, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.storage_permission_grant))
                }
                Button(onClick = onUsePicker, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.storage_permission_use_picker))
                }
            }
        }
    }
}

private fun iconFor(kind: StorageRootKind) = when (kind) {
    StorageRootKind.INTERNAL -> Icons.Outlined.Smartphone
    StorageRootKind.REMOVABLE -> Icons.Outlined.SdCard
    StorageRootKind.STANDARD_DIRECTORY -> Icons.Outlined.Folder
    StorageRootKind.PROVIDER_ROOT -> Icons.Outlined.Cloud
    StorageRootKind.PICKER_SHORTCUT -> Icons.Outlined.FolderOpen
    StorageRootKind.REMOTE -> Icons.Outlined.Cloud
}

private fun stringResourceFormatFree(availableBytes: Long, totalBytes: Long): String =
    "${formatStorageBytes(availableBytes)} free of ${formatStorageBytes(totalBytes)}"

internal fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}

/**
 * Starts a system settings activity from a composable's context. Wrapped so the call site stays
 * readable and a missing settings screen degrades to a no-op instead of crashing.
 */
private fun Context.startPermissionActivity(intent: android.content.Intent) {
    runCatching {
        startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
