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
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.ui.components.FileTypeIcons
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.components.QuickAction
import io.github.mbaliga.fylz.ui.landing.HomeMode
import io.github.mbaliga.fylz.ui.theme.FolderMaterial
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.ui.theme.ThemeStyle

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
    showExtensions: Boolean = true,
    onShowExtensionsChange: (Boolean) -> Unit = {},
    autoAnimate: Boolean = true,
    onAutoAnimateChange: (Boolean) -> Unit = {},
    iconStyle: IconStyle,
    onIconStyleChange: (IconStyle) -> Unit,
    themeStyle: ThemeStyle = ThemeStyle.NEO,
    onThemeStyleChange: (ThemeStyle) -> Unit = {},
    density: DensityMode = DensityMode.COMFORTABLE,
    onDensityChange: (DensityMode) -> Unit = {},
    quickActions: List<QuickAction>,
    onQuickActionsChange: (List<QuickAction>) -> Unit,
    homeMode: HomeMode = HomeMode.LOCATIONS,
    onHomeModeChange: (HomeMode) -> Unit = {},
    landingSubjectName: String? = null,
    onPickLandingSubject: () -> Unit = {},
    landingSplash: Boolean = true,
    onLandingSplashChange: (Boolean) -> Unit = {},
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onShowExtensionsChange(!showExtensions) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show file extensions", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Full names like report.pdf. Folders are never affected.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = showExtensions, onCheckedChange = onShowExtensionsChange)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onAutoAnimateChange(!autoAnimate) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-animate previews", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Video thumbnails and previews play quietly by themselves.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoAnimate, onCheckedChange = onAutoAnimateChange)
                }

                Spacer(Modifier.size(20.dp))
                RoomHeading("Landing")
                Text(
                    "What opens the app -- today's location list, or one folder arranged your way.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(Modifier.selectableGroup()) {
                    HomeMode.entries.forEach { mode ->
                        val selected = mode == homeMode
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    onClick = { onHomeModeChange(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                LandingSubjectRow(
                    label = "Landing folder",
                    valueText = landingSubjectName ?: "None",
                    onClick = onPickLandingSubject,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onLandingSplashChange(!landingSplash) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show the Fylz landing", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "A moment of brand when the app opens. Off goes straight to work.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = landingSplash, onCheckedChange = onLandingSplashChange)
                }

                Spacer(Modifier.size(20.dp))
                RoomHeading("Preview actions")
                QuickActionEditor(
                    rail = quickActions,
                    iconStyle = iconStyle,
                    onChange = onQuickActionsChange,
                )

                Spacer(Modifier.size(20.dp))
                RoomHeading("Theme")
                Text(
                    "Icons, folder material, and for three of these five, the whole colour scheme and " +
                        "type \u2014 shown as each will actually draw, not described.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ThemeStyle.entries.forEach { style ->
                    ThemeSwatchRow(
                        style = style,
                        selected = style == themeStyle,
                        onSelect = { onThemeStyleChange(style) },
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "Icon size",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Column(Modifier.selectableGroup()) {
                    DensityMode.entries.forEach { mode ->
                        val selected = mode == density
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .selectable(
                                    selected = selected,
                                    onClick = { onDensityChange(mode) },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                RoomHeading("File icons")
                Text(
                    "An advanced override, under whatever the theme above already set \u2014 every style " +
                        "is shown as it will actually be drawn, since naming the four tells you very little.",
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

private fun HomeMode.readableLabel(): String = when (this) {
    HomeMode.LOCATIONS -> "Locations list"
    HomeMode.LIST -> "List"
    HomeMode.BENTO -> "Bento"
    HomeMode.CANVAS -> "Canvas"
}

private fun DensityMode.readableLabel(): String = when (this) {
    DensityMode.COMPACT -> "Small"
    DensityMode.COMFORTABLE -> "Medium"
    DensityMode.DETAILED -> "Large"
}

private fun ThemeStyle.readableLabel(): String = when (this) {
    ThemeStyle.NEO -> "Neo"
    ThemeStyle.GLASS -> "Glass"
    ThemeStyle.VINTAGE -> "Vintage"
    ThemeStyle.RETRO -> "Retro"
    ThemeStyle.CLI -> "CLI"
}

/**
 * The "Landing folder" row: [SettingsToolRow]'s exact silhouette, plus a trailing value so the
 * current pick (or its absence) is legible without opening the picker to find out.
 */
@Composable
private fun LandingSubjectRow(label: String, valueText: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        Text(
            valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
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
 * One selectable theme, previewed as it will actually draw rather than as four sample glyphs: a
 * nested [FylzTheme] picks up the real colour scheme (dynamic wallpaper colour for Neo and Glass,
 * the fixed palette for the other three) and type around a miniature folder-then-file listing —
 * the same "don't describe it, draw it" answer [QuickActionEditor] gives the preview rail below.
 * A row of icons alone would have shown the icon pack and nothing else; folder material, and for
 * three of these five styles the whole palette, never show up in an icon.
 */
@Composable
private fun ThemeSwatchRow(style: ThemeStyle, selected: Boolean, onSelect: () -> Unit) {
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
            Modifier.heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(
                style.readableLabel(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 4.dp).width(64.dp),
            )
            ThemeMiniature(style, Modifier.weight(1f))
        }
    }
}

/**
 * A small folder and a small file, drawn under [style]'s own [FylzTheme] rather than the
 * screen's — every colour, the type, and which [FolderMaterial] register the folder draws in are
 * the real ones, not a description of them.
 */
@Composable
private fun ThemeMiniature(style: ThemeStyle, modifier: Modifier = Modifier) {
    // accentPreset is passed only to satisfy the signature -- dynamicColor = true wins outright
    // on every device this app runs on (minSdk 31), the same as the app's own four call sites, so
    // the swatch's Neo/Glass rows pick up this device's real wallpaper colour, not a stand-in.
    FylzTheme(themeMode = ThemeMode.SYSTEM, accentPreset = AccentPreset.MOSS, dynamicColor = true, themeStyle = style) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
            modifier = modifier.height(64.dp),
        ) {
            if (style.folderMaterial == FolderMaterial.TEXT) {
                // CLI draws no folder face at all -- the listing row itself, box-drawn, is the read.
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    Text("├─ Photos/", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("└─ sunset.heic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MiniatureFolder(style)
                    MiniatureFile(style)
                }
            }
        }
    }
}

/** The folder half of [ThemeMiniature]. Never called for [FolderMaterial.TEXT] -- that register draws no face, see the caller. */
@Composable
private fun MiniatureFolder(style: ThemeStyle) {
    when (style.folderMaterial) {
        FolderMaterial.SOLID -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 22.dp, height = 16.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(3.dp)),
            )
            Text("Photos", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
        }
        FolderMaterial.FROSTED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 22.dp, height = 16.dp)) {
                // A saturated tile standing in for whatever the folder holds, showing through the
                // translucent wash on top -- the same read as the real frosted face, at swatch scale.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(3.dp)))
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f), RoundedCornerShape(3.dp)),
                )
            }
            Text("Photos", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
        }
        FolderMaterial.ICONIC -> Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = "file:///android_asset/" + FileTypeIcons.assetPath("", PreviewFamily.DIRECTORY, style.iconStyle),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp),
            )
            Text("Photos", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
        }
        FolderMaterial.TEXT -> Unit
    }
}

/**
 * The file half of [ThemeMiniature] -- also stands in for [ThemeStyle.forcesExtensions], since
 * Vintage/Retro/CLI never hide one.
 *
 * The sample is deliberately an extension with no artwork of its own (`heic`, unlike `jpg`, is
 * not in [FileTypeIcons]'s exact set): every style resolves it through the generic family mark,
 * which is exactly where [IconStyle.VINTAGE] and [IconStyle.RETRO]'s pixel art actually lives —
 * a sample the pack has bespoke art for would fall back to Filled for both and show nothing
 * theme-specific at all.
 */
@Composable
private fun MiniatureFile(style: ThemeStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = "file:///android_asset/" + FileTypeIcons.assetPath("heic", PreviewFamily.IMAGE, style.iconStyle),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (style.forcesExtensions) "sunset.heic" else "sunset",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp),
        )
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
