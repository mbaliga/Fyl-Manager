package io.github.mbaliga.fylz.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.mbaliga.fylz.ui.places.FolderIconSpec
import io.github.mbaliga.fylz.ui.places.LandingFacts
import io.github.mbaliga.fylz.ui.places.LandingFactsLoader
import io.github.mbaliga.fylz.ui.places.LandingText
import io.github.mbaliga.fylz.ui.places.LauncherCategory
import io.github.mbaliga.fylz.ui.places.LauncherModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    /** Opens a recursive search over the device volume; the category tiles use it. */
    onOpenSearch: (String) -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    recycleCount: Int = 0,
    onShredAll: () -> Unit = {},
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
    val facts by produceState(initialValue = LandingFacts(), key1 = refreshKey, key2 = ready) {
        value = withContext(Dispatchers.IO) { LandingFactsLoader.load(context) }
    }
    var confirmShred by remember { mutableStateOf(false) }

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

        item(key = "summary-row") {
            val internal = model.storages.firstOrNull()
            val total = internal?.totalBytes
            val used = if (total != null && internal.availableBytes != null) total - internal.availableBytes else null
            val downloads = model.shortcuts.firstOrNull { it.title.startsWith("Download") }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = LANDING_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(LANDING_GAP),
            ) {
                StorageSummaryCard(
                    usedBytes = used,
                    totalBytes = total,
                    breakdown = facts.breakdown,
                    onClick = { internal?.let(::openRoot) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                DownloadsCard(
                    count = facts.downloadsCount,
                    newestName = facts.downloadsNewest,
                    loaded = facts.loaded,
                    onClick = { downloads?.let(::openRoot) ?: onPickFolder(null) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        items(LauncherCategory.entries.chunked(2), key = { row -> row.first().name }) { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LANDING_GUTTER, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(LANDING_GAP),
            ) {
                row.forEach { category ->
                    FolderTile(
                        label = category.label,
                        subtitle = LandingText.fileCount(facts.breakdown.count(category.storage), facts.loaded),
                        cards = folderCards(category, facts),
                        onClick = { onOpenSearch(category.query) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        item(key = "deleted") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LANDING_GUTTER, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(LANDING_GAP),
            ) {
                DeletedFilesCard(
                    count = recycleCount,
                    onOpen = onOpenRecycleBin,
                    onShredAll = { confirmShred = true },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
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
    if (confirmShred) {
        AlertDialog(
            onDismissRequest = { confirmShred = false },
            title = { Text("Shred all deleted files?") },
            text = { Text("${LandingText.deletedCount(recycleCount)} files will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmShred = false; onShredAll() }) { Text("Shred all") }
            },
            dismissButton = { TextButton(onClick = { confirmShred = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The cards inside a category's folder: the newest photos for Images; for the rest, one page per
 * file up to three, so an empty folder is visibly empty and a full one shows a fan.
 */
private fun folderCards(category: LauncherCategory, facts: LandingFacts): List<FolderCardContent> {
    if (category == LauncherCategory.IMAGES) return facts.recentImages.take(3).map { FolderCardContent.Photo(it) }
    val count = FolderIconSpec.cardCount(facts.breakdown.count(category.storage))
    return listOf(FolderIconSpec.Slot.A, FolderIconSpec.Slot.B, FolderIconSpec.Slot.C).take(count).map(::sheetFor)
}

private val LANDING_GUTTER = 20.dp
private val LANDING_GAP = 16.dp

private const val MAX_RECENTS_SHOWN = 5

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
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
