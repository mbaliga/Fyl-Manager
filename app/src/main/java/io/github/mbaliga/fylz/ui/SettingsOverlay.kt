package io.github.mbaliga.fylz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.model.ThemeMode

/**
 * The app's own tools and settings, reached from the left room rather than a swipe-in edge.
 *
 * This used to be the right room — a fourth spatial surface for a screen most sessions never
 * open. A room earns its edge by being touched often; Settings is touched rarely and by nobody
 * mid-task, so it moved to a plain entry point instead of holding gesture real estate hostage.
 * What is left of the room pattern is only the full-screen, in-theme presentation (the [Dialog]
 * setup below is the same one [io.github.mbaliga.fylz.ui.picker.FylzPicker] uses) — Fylz's own
 * chrome, not the bare `MaterialTheme` the destination activities used to paint under.
 */
@Composable
internal fun SettingsOverlay(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    showHidden: Boolean,
    onShowHiddenChange: (Boolean) -> Unit,
    onOpenRecycleBin: () -> Unit,
    onOpenRemotes: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenIndexManager: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close settings")
                    }
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                RoomHeading("Appearance")
                // selectableGroup + selectable(role = RadioButton) so a screen reader announces
                // this as one choice among three rather than three independent taps.
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = mode == themeMode
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    onClick = { onThemeModeChange(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // A filled square for the chosen mode rather than a RadioButton: the
                            // same marker the old tools room used, so this surface still reads
                            // as the same app as the rooms either side of it.
                            Box(Modifier.size(width = 20.dp, height = 10.dp), contentAlignment = Alignment.CenterStart) {
                                if (selected) {
                                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary))
                                }
                            }
                            Text(
                                mode.readableLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.6f),
                            )
                        }
                    }
                }

                Spacer(Modifier.size(20.dp))
                RoomHeading("Files")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onShowHiddenChange(!showHidden) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show hidden files", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Files and folders whose name starts with a dot.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = showHidden, onCheckedChange = onShowHiddenChange)
                }

                Spacer(Modifier.size(20.dp))
                RoomHeading("Storage & tools")
                SettingsToolRow(Icons.Outlined.RestoreFromTrash, "Recycle Bin", onOpenRecycleBin)
                SettingsToolRow(Icons.Outlined.Cloud, "Remotes", onOpenRemotes)
                SettingsToolRow(Icons.Outlined.Language, "Quick WebDAV listing", onOpenWebDav)
                SettingsToolRow(Icons.Outlined.Build, "Tools", onOpenTools)
                SettingsToolRow(Icons.Outlined.Search, "Index manager", onOpenIndexManager)
            }
        }
    }
}

@Composable
private fun SettingsToolRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}
