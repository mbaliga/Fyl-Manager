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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Remove
import io.github.mbaliga.fylz.ui.components.NotchedCardShape
import io.github.mbaliga.fylz.ui.components.quickLookSlots
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.ui.components.FileTypeIcons
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.components.QuickAction

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
    iconStyle: IconStyle,
    onIconStyleChange: (IconStyle) -> Unit,
    quickActions: List<QuickAction>,
    onQuickActionsChange: (List<QuickAction>) -> Unit,
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
                RoomHeading("Preview actions")
                QuickActionEditor(
                    rail = quickActions,
                    iconStyle = iconStyle,
                    onChange = onQuickActionsChange,
                )

                Spacer(Modifier.size(20.dp))
                RoomHeading("File icons")
                Text(
                    "Every style is shown as it will actually be drawn \u2014 the four treatments differ " +
                        "enough that naming them tells you very little.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                IconStyle.entries.forEach { style ->
                    IconStyleRow(
                        style = style,
                        selected = style == iconStyle,
                        onSelect = { onIconStyleChange(style) },
                    )
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

/**
 * One selectable icon treatment, previewed with real artwork rather than described.
 *
 * The sample formats are picked to span the pack's range — a document, a spreadsheet, an image and
 * an archive — because the styles diverge most on the busiest marks; a row of four identical grey
 * document icons would make Filled and Gray look like the same choice.
 */
@Composable
private fun IconStyleRow(style: IconStyle, selected: Boolean, onSelect: () -> Unit) {
    val samples = remember(style) {
        listOf(
            "pdf" to PreviewFamily.PDF,
            "xlsx" to PreviewFamily.OFFICE,
            "png" to PreviewFamily.IMAGE,
            "zip" to PreviewFamily.ARCHIVE,
        ).map { (ext, family) ->
            "file:///android_asset/" + FileTypeIcons.assetPath(ext, family, style)
        }
    }
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(
                style.name.lowercase().replaceFirstChar(Char::titlecase),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 4.dp).width(88.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                samples.forEach { asset ->
                    AsyncImage(
                        model = asset,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

/**
 * Arranges the preview card's rail, showing the card itself rather than describing it.
 *
 * The miniature is drawn with the same [NotchedCardShape] the real card uses and re-renders as
 * actions are added or removed, so the thing that grows a notch here is the thing that grows a
 * notch there. A checkbox list would have left the user to imagine what "four pinned actions"
 * does to the silhouette — which is precisely the part worth seeing before committing to it.
 *
 * Pinned actions are capped at [QuickAction.MAX_PINNED]; the last slot always belongs to "more",
 * so the rail can never be arranged into a state that hides the actions it did not pin.
 */
@Composable
private fun QuickActionEditor(
    rail: List<QuickAction>,
    iconStyle: IconStyle,
    onChange: (List<QuickAction>) -> Unit,
) {
    val overflow = remember(rail) { QuickAction.overflowFor(rail) }
    val sample = remember(iconStyle) {
        "file:///android_asset/" + FileTypeIcons.assetPath("pdf", PreviewFamily.PDF, iconStyle)
    }

    Text(
        "Pinned actions sit in the card's notch. The rest live behind “more”. " +
            "Up to ${QuickAction.MAX_PINNED}, because the last slot is always “more”.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )

    // ── The card, as it will actually look ────────────────────────────────────────────
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(220.dp).height(132.dp)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxSize(),
            ) {}
            Surface(
                shape = NotchedCardShape(railSlots = quickLookSlots(rail.size + 1), slotSize = 34.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = sample,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }
            Row(Modifier.align(Alignment.TopStart).height(34.dp)) {
                rail.forEach { action ->
                    MiniSlot(action.icon)
                }
                MiniSlot(Icons.Outlined.MoreHoriz)
            }
            Box(Modifier.align(Alignment.BottomEnd)) { MiniSlot(Icons.Outlined.Close) }
        }
    }

    // ── Pinned, in rail order ─────────────────────────────────────────────────────────
    rail.forEachIndexed { index, action ->
        QuickActionEditorRow(
            action = action,
            pinned = true,
            canMoveUp = index > 0,
            onMoveUp = { onChange(rail.toMutableList().apply { add(index - 1, removeAt(index)) }) },
            // One pinned action is the floor. The notch is at least two slots wide, so an empty
            // rail would cut a notch with nothing but "more" sitting in half of it.
            onToggle = { if (rail.size > 1) onChange(rail - action) },
            enabled = rail.size > 1,
        )
    }
    if (overflow.isNotEmpty()) {
        Text(
            "Behind “more”",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
        overflow.forEach { action ->
            QuickActionEditorRow(
                action = action,
                pinned = false,
                canMoveUp = false,
                onMoveUp = {},
                // Silently refusing the tap at the cap would read as a broken button; the row is
                // disabled instead, so the ceiling is visible before it is hit.
                onToggle = { if (rail.size < QuickAction.MAX_PINNED) onChange(rail + action) },
                enabled = rail.size < QuickAction.MAX_PINNED,
            )
        }
    }
}

@Composable
private fun MiniSlot(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun QuickActionEditorRow(
    action: QuickAction,
    pinned: Boolean,
    canMoveUp: Boolean,
    onMoveUp: () -> Unit,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
        Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        if (pinned && canMoveUp) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move ${action.label} earlier")
            }
        }
        Icon(
            if (pinned) Icons.Outlined.Remove else Icons.Outlined.Add,
            contentDescription = if (pinned) "Unpin ${action.label}" else "Pin ${action.label}",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}
