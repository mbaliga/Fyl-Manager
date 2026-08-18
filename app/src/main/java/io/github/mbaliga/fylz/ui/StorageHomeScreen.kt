package io.github.mbaliga.fylz.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.storage.StorageAccess
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.storage.StorageRootKind

/**
 * The launch surface that replaces the bare `Text("Open a folder to begin")` empty state.
 *
 * With "All files access" granted, every row here opens directly -- internal storage, removable
 * volumes and the standard folders -- because the File-backed provider serves them, and no picker
 * appears in the happy path. Without it, the rows are already-granted subtrees plus one-tap
 * `ACTION_OPEN_DOCUMENT_TREE` shortcuts pre-seeded with `EXTRA_INITIAL_URI`, so the user still
 * lands somewhere useful instead of on a blank screen.
 *
 * Rows are 56dp tall with 8dp vertical padding around a 40dp icon, clearing DESIGN.md's 48dp
 * minimum touch target, and every icon-only affordance carries a semantic label.
 *
 * Deliberately the one home surface with no selection: [StorageRootRow] renders a
 * [io.github.mbaliga.fylz.storage.StorageRoot] -- internal storage, a removable volume, a
 * standard folder shortcut, a remote -- never a [io.github.mbaliga.fylz.model.FileEntry]. A root
 * is a place you navigate into, not a member of a cut/copy/delete selection, so there is nothing
 * here for a long-press to add to one. [io.github.mbaliga.fylz.ui.canvas.SubjectList],
 * [io.github.mbaliga.fylz.ui.canvas.BentoMosaic] and [io.github.mbaliga.fylz.ui.canvas.SubjectCanvas]
 * carry the selection this build restores; this screen is upstream of all three and stays out of
 * it on purpose.
 */
@Composable
fun StorageHomeScreen(
    onOpenRoot: (StorageRoot) -> Unit,
    onPickFolder: (StorageRoot?) -> Unit,
    onOpenRemotes: () -> Unit,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
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

    // Null means "haven't heard back yet"; an empty list is a real, resolved answer -- collapsing
    // the two into one boolean is what used to spin the loading indicator forever on a location
    // that genuinely has nothing to show.
    val groups by produceState<List<StorageRootGroup>?>(
        initialValue = null,
        key1 = refreshKey,
        key2 = ready,
    ) {
        value = StorageAccess.available(context).flatMap { it.rootGroups(context) }
    }

    val loading = groups == null && ready
    val resolvedGroups = groups.orEmpty()

    Column(modifier.fillMaxSize()) {
        if (!ready) {
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

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(Modifier.width(160.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.storage_home_loading))
                }
            }
            return@Column
        }

        // contentPadding is fixed breathing room below the last row; it does not know about the
        // gesture nav bar's device-dependent inset, which is why the footer below also carries
        // navigationBarsPadding() -- without it the caption clips under the gesture bar.
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
            resolvedGroups.forEach { group ->
                item(key = "header:${group.title}") {
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
                items(group.roots, key = { "${group.title}:${it.id}" }) { root ->
                    StorageRootRow(
                        root = root,
                        onClick = { if (root.opensDirectly) onOpenRoot(root) else onPickFolder(root) },
                    )
                }
            }

            if (resolvedGroups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.storage_home_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }

            item(key = "footer-actions") {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The gate card's "Use picker" button is this exact action -- showing both
                    // is the same affordance twice on one screen.
                    if (ready) {
                        Button(onClick = { onPickFolder(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.storage_home_add_folder))
                        }
                    }
                    OutlinedButton(onClick = onOpenRemotes, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Cloud, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.storage_home_remotes))
                    }
                    Text(
                        stringResource(R.string.storage_home_access_note, StorageAccess.accessLabel(context)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
