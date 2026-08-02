package io.github.mbaliga.fylz.ui

import android.content.Intent
import android.content.res.Configuration
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DensityMedium
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.DensityMode
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ShellMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.ui.components.FloatingPreviewPane
import io.github.mbaliga.fylz.ui.components.PreviewPane
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun FylzApp() {
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var accentPreset by remember { mutableStateOf(AccentPreset.MOSS) }
    var dynamicColor by remember { mutableStateOf(false) }

    FylzTheme(
        themeMode = themeMode,
        accentPreset = accentPreset,
        dynamicColor = dynamicColor,
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FylzWorkspace(
                themeMode = themeMode,
                onThemeModeChange = { themeMode = it },
                accentPreset = accentPreset,
                onAccentPresetChange = { accentPreset = it },
                dynamicColor = dynamicColor,
                onDynamicColorChange = { dynamicColor = it },
            )
        }
    }
}

@Composable
private fun FylzWorkspace(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accentPreset: AccentPreset,
    onAccentPresetChange: (AccentPreset) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val repository = remember(context) { DocumentRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val tabs = remember { mutableStateListOf<FolderTab>() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var entriesLoading by remember { mutableStateOf(false) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewTruncated by remember { mutableStateOf(false) }
    var previewLoading by remember { mutableStateOf(false) }
    var editorValue by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var shellMode by remember { mutableStateOf(ShellMode.TRADITIONAL) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var densityMode by remember { mutableStateOf(DensityMode.COMFORTABLE) }
    var previewMode by remember {
        mutableStateOf(
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                PreviewMode.DOCKED
            } else {
                PreviewMode.HIDDEN
            },
        )
    }
    var navCollapsed by remember { mutableStateOf(false) }
    var navOverlayOpen by remember { mutableStateOf(false) }

    val activeTab = tabs.firstOrNull { it.id == activeTabId }
    val filteredEntries = remember(entries, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) entries else entries.filter { it.name.contains(query, ignoreCase = true) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            repository.persistTreePermission(uri)
            scope.launch {
                runCatching { repository.rootLocation(uri) }
                    .onSuccess { root ->
                        val existing = tabs.firstOrNull { it.treeUri == uri }
                        if (existing != null) {
                            activeTabId = existing.id
                        } else {
                            val tab = FolderTab(treeUri = uri, locations = listOf(root))
                            tabs += tab
                            activeTabId = tab.id
                        }
                    }
                    .onFailure {
                        Toast.makeText(context, it.message ?: "Unable to open folder", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    LaunchedEffect(Unit) {
        context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri }
            .distinct()
            .take(6)
            .forEach { uri ->
                runCatching { repository.rootLocation(uri) }.getOrNull()?.let { root ->
                    val tab = FolderTab(treeUri = uri, locations = listOf(root))
                    tabs += tab
                    if (activeTabId == null) activeTabId = tab.id
                }
            }
    }

    LaunchedEffect(activeTab?.current?.uri, refreshNonce) {
        selectedEntry = null
        previewText = null
        editorValue = ""
        if (activeTab == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        entriesLoading = true
        runCatching { repository.listChildren(activeTab.treeUri, activeTab.current.uri) }
            .onSuccess { entries = it }
            .onFailure {
                entries = emptyList()
                Toast.makeText(context, it.message ?: "Unable to list this folder", Toast.LENGTH_LONG).show()
            }
        entriesLoading = false
    }

    LaunchedEffect(selectedEntry?.uri) {
        previewText = null
        previewTruncated = false
        editorValue = ""
        val entry = selectedEntry ?: return@LaunchedEffect
        if (!FileType.isTextPreviewable(entry.kind)) return@LaunchedEffect
        previewLoading = true
        runCatching { repository.readText(entry.uri) }
            .onSuccess {
                previewText = it.value
                previewTruncated = it.truncated
                editorValue = it.value
            }
            .onFailure {
                previewText = "Unable to preview this file.\n\n${it.message.orEmpty()}"
                editorValue = previewText.orEmpty()
            }
        previewLoading = false
    }

    fun updateActiveTab(transform: (FolderTab) -> FolderTab) {
        val index = tabs.indexOfFirst { it.id == activeTabId }
        if (index >= 0) tabs[index] = transform(tabs[index])
    }

    fun navigateToFolder(entry: FileEntry) {
        if (!entry.isDirectory) return
        updateActiveTab { tab ->
            tab.copy(locations = tab.locations + FolderLocation(entry.uri, entry.name))
        }
        searchQuery = ""
    }

    fun openFolderInNewTab(entry: FileEntry) {
        val source = activeTab ?: return
        if (!entry.isDirectory) return
        val tab = FolderTab(
            treeUri = source.treeUri,
            locations = source.locations + FolderLocation(entry.uri, entry.name),
        )
        tabs += tab
        activeTabId = tab.id
        searchQuery = ""
    }

    fun selectEntry(entry: FileEntry, wideLayout: Boolean) {
        selectedEntry = entry
        if (previewMode == PreviewMode.HIDDEN) {
            previewMode = if (wideLayout) PreviewMode.DOCKED else PreviewMode.FLOATING
        }
    }

    fun openExternal(entry: FileEntry) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(entry.uri, entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show()
            }
    }

    fun closeTab(tab: FolderTab) {
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (activeTabId == tab.id) {
            activeTabId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id
                ?: tabs.lastOrNull()?.id
        }
    }

    val saveEditor: () -> Unit = {
        val entry = selectedEntry
        if (entry != null && FileType.isEditable(entry.kind)) {
            scope.launch {
                runCatching { repository.writeText(entry.uri, editorValue) }
                    .onSuccess {
                        previewText = editorValue
                        previewTruncated = false
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(context, it.message ?: "Unable to save", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wideLayout = maxWidth >= 840.dp
        val showFixedNav = wideLayout && shellMode == ShellMode.TRADITIONAL
        val showDockedPreview = wideLayout && previewMode == PreviewMode.DOCKED
        val showFloatingPreview = previewMode == PreviewMode.FLOATING ||
            (!wideLayout && previewMode == PreviewMode.DOCKED)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (shellMode == ShellMode.IMMERSIVE) 52.dp else 0.dp),
        ) {
            if (shellMode == ShellMode.TRADITIONAL) {
                FylzTopBar(
                    onOpenFolder = { folderPicker.launch(null) },
                    onToggleNavigation = { navOverlayOpen = !navOverlayOpen },
                    showNavigationToggle = !showFixedNav,
                    shellMode = shellMode,
                    onShellModeChange = { shellMode = it },
                    viewMode = viewMode,
                    onViewModeChange = { viewMode = it },
                    densityMode = densityMode,
                    onDensityModeChange = { densityMode = it },
                    previewMode = previewMode,
                    onPreviewModeChange = { previewMode = it },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    accentPreset = accentPreset,
                    onAccentPresetChange = onAccentPresetChange,
                    dynamicColor = dynamicColor,
                    onDynamicColorChange = onDynamicColorChange,
                )
            }

            FolderTabs(
                tabs = tabs,
                activeTabId = activeTabId,
                onActivate = { activeTabId = it.id },
                onClose = ::closeTab,
                onAdd = { folderPicker.launch(null) },
            )

            Row(Modifier.weight(1f)) {
                if (showFixedNav) {
                    NavigationPane(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        collapsed = navCollapsed,
                        onToggleCollapsed = { navCollapsed = !navCollapsed },
                        onActivate = { activeTabId = it.id },
                        onOpenFolder = { folderPicker.launch(null) },
                        modifier = Modifier
                            .width(if (navCollapsed) 68.dp else 220.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                }

                FileWorkspace(
                    activeTab = activeTab,
                    entries = filteredEntries,
                    loading = entriesLoading,
                    selectedEntry = selectedEntry,
                    viewMode = viewMode,
                    densityMode = densityMode,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onNavigateToBreadcrumb = { index ->
                        updateActiveTab { it.copy(locations = it.locations.take(index + 1)) }
                    },
                    onNavigateUp = {
                        updateActiveTab { tab ->
                            if (tab.locations.size > 1) tab.copy(locations = tab.locations.dropLast(1)) else tab
                        }
                    },
                    onRefresh = { refreshNonce += 1 },
                    onSelect = { selectEntry(it, wideLayout) },
                    onOpenFolder = ::navigateToFolder,
                    onOpenFolderInNewTab = ::openFolderInNewTab,
                    onOpenExternal = ::openExternal,
                    onOpenFolderPicker = { folderPicker.launch(null) },
                    modifier = Modifier.weight(1f),
                )

                if (showDockedPreview) {
                    VerticalDivider()
                    PreviewPane(
                        entry = selectedEntry,
                        textContent = previewText,
                        textTruncated = previewTruncated,
                        loading = previewLoading,
                        editorValue = editorValue,
                        onEditorValueChange = { editorValue = it },
                        onSave = saveEditor,
                        modifier = Modifier
                            .width(380.dp)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        if (shellMode == ShellMode.IMMERSIVE) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .shadow(6.dp, MaterialTheme.shapes.large)
                    .zIndex(4f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 4.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navOverlayOpen = !navOverlayOpen }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Open navigation")
                    }
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Open folder")
                    }
                    IconButton(onClick = { shellMode = ShellMode.TRADITIONAL }) {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = "Traditional interface")
                    }
                    IconButton(onClick = {
                        previewMode = when (previewMode) {
                            PreviewMode.HIDDEN -> if (wideLayout) PreviewMode.DOCKED else PreviewMode.FLOATING
                            PreviewMode.DOCKED -> PreviewMode.FLOATING
                            PreviewMode.FLOATING -> PreviewMode.HIDDEN
                        }
                    }) {
                        Icon(Icons.Outlined.ViewSidebar, contentDescription = "Change preview mode")
                    }
                }
            }
        }

        if (navOverlayOpen && !showFixedNav) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
                    .clickable { navOverlayOpen = false }
                    .zIndex(6f),
            ) {
                NavigationPane(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    collapsed = false,
                    onToggleCollapsed = { navOverlayOpen = false },
                    onActivate = {
                        activeTabId = it.id
                        navOverlayOpen = false
                    },
                    onOpenFolder = {
                        navOverlayOpen = false
                        folderPicker.launch(null)
                    },
                    modifier = Modifier
                        .width(244.dp)
                        .fillMaxHeight()
                        .clickable(enabled = false) {},
                )
            }
        }

        if (showFloatingPreview) {
            FloatingPreviewPane(
                onDock = { previewMode = if (wideLayout) PreviewMode.DOCKED else PreviewMode.HIDDEN },
                onClose = { previewMode = PreviewMode.HIDDEN },
                modifier = Modifier.zIndex(8f),
            ) {
                PreviewPane(
                    entry = selectedEntry,
                    textContent = previewText,
                    textTruncated = previewTruncated,
                    loading = previewLoading,
                    editorValue = editorValue,
                    onEditorValueChange = { editorValue = it },
                    onSave = saveEditor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FylzTopBar(
    onOpenFolder: () -> Unit,
    onToggleNavigation: () -> Unit,
    showNavigationToggle: Boolean,
    shellMode: ShellMode,
    onShellModeChange: (ShellMode) -> Unit,
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    densityMode: DensityMode,
    onDensityModeChange: (DensityMode) -> Unit,
    previewMode: PreviewMode,
    onPreviewModeChange: (PreviewMode) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accentPreset: AccentPreset,
    onAccentPresetChange: (AccentPreset) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNavigationToggle) {
                IconButton(onClick = onToggleNavigation) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Open navigation")
                }
            }
            Text(
                "Fylz",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenFolder) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = "Open folder")
            }
            ViewModeMenu(viewMode, onViewModeChange)
            DensityMenu(densityMode, onDensityModeChange)
            PreviewMenu(previewMode, onPreviewModeChange)
            ThemeMenu(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                accentPreset = accentPreset,
                onAccentPresetChange = onAccentPresetChange,
                dynamicColor = dynamicColor,
                onDynamicColorChange = onDynamicColorChange,
            )
            IconButton(
                onClick = {
                    onShellModeChange(
                        if (shellMode == ShellMode.TRADITIONAL) ShellMode.IMMERSIVE
                        else ShellMode.TRADITIONAL,
                    )
                },
            ) {
                Icon(
                    if (shellMode == ShellMode.TRADITIONAL) Icons.Outlined.Fullscreen
                    else Icons.Outlined.FullscreenExit,
                    contentDescription = "Toggle immersive interface",
                )
            }
        }
    }
}

@Composable
private fun FolderTabs(
    tabs: List<FolderTab>,
    activeTabId: String?,
    onActivate: (FolderTab) -> Unit,
    onClose: (FolderTab) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab ->
                val active = tab.id == activeTabId
                Surface(
                    modifier = Modifier
                        .height(34.dp)
                        .widthIn(min = 116.dp, max = 220.dp)
                        .clickable { onActivate(tab) },
                    color = if (active) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = if (active) 3.dp else 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            tab.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 7.dp),
                        )
                        IconButton(onClick = { onClose(tab) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close ${tab.title}", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            IconButton(onClick = onAdd, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = "Open folder in new tab")
            }
        }
    }
}

@Composable
private fun NavigationPane(
    tabs: List<FolderTab>,
    activeTabId: String?,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onActivate: (FolderTab) -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!collapsed) {
                    Text("Workspace", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = onToggleCollapsed) {
                    Icon(Icons.Outlined.ViewSidebar, contentDescription = "Collapse navigation")
                }
            }
            Button(
                onClick = onOpenFolder,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                contentPadding = if (collapsed) PaddingValues(10.dp) else PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                if (!collapsed) Text("Open folder", Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            if (!collapsed) {
                Text(
                    "OPEN ROOTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            LazyColumn {
                items(tabs.distinctBy { it.treeUri }) { tab ->
                    val selected = tab.id == activeTabId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onActivate(tab) }
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            )
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                        if (!collapsed) {
                            Text(
                                tab.locations.first().name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileWorkspace(
    activeTab: FolderTab?,
    entries: List<FileEntry>,
    loading: Boolean,
    selectedEntry: FileEntry?,
    viewMode: ViewMode,
    densityMode: DensityMode,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (FileEntry) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFolderInNewTab: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onOpenFolderPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activeTab == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(52.dp))
                Text("Your files, without the noise.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "Open a folder to start a private, tabbed workspace.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                )
                Button(onClick = onOpenFolderPicker) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Text("Open folder", Modifier.padding(start = 8.dp))
                }
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Breadcrumbs(activeTab, onNavigateToBreadcrumb)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onNavigateUp, enabled = activeTab.locations.size > 1) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Parent folder")
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Filter this folder") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
            Text(
                "${entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        HorizontalDivider()

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Reading folder…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isBlank()) "This folder is empty." else "No matching files.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            when (viewMode) {
                ViewMode.LIST -> FileList(
                    entries = entries,
                    selectedEntry = selectedEntry,
                    densityMode = densityMode,
                    onSelect = onSelect,
                    onOpenFolder = onOpenFolder,
                    onOpenFolderInNewTab = onOpenFolderInNewTab,
                    onOpenExternal = onOpenExternal,
                )
                ViewMode.GRID -> FileGrid(
                    entries = entries,
                    selectedEntry = selectedEntry,
                    onSelect = onSelect,
                    onOpenFolder = onOpenFolder,
                    onOpenFolderInNewTab = onOpenFolderInNewTab,
                    onOpenExternal = onOpenExternal,
                )
                ViewMode.DETAILS -> FileDetails(
                    entries = entries,
                    selectedEntry = selectedEntry,
                    onSelect = onSelect,
                    onOpenFolder = onOpenFolder,
                    onOpenFolderInNewTab = onOpenFolderInNewTab,
                    onOpenExternal = onOpenExternal,
                )
            }
        }
    }
}

@Composable
private fun Breadcrumbs(tab: FolderTab, onNavigate: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tab.locations.forEachIndexed { index, location ->
            if (index > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 5.dp))
            Text(
                location.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (index == tab.locations.lastIndex) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onNavigate(index) }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun FileList(
    entries: List<FileEntry>,
    selectedEntry: FileEntry?,
    densityMode: DensityMode,
    onSelect: (FileEntry) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFolderInNewTab: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
) {
    val rowHeight = when (densityMode) {
        DensityMode.COMPACT -> 44.dp
        DensityMode.COMFORTABLE -> 58.dp
        DensityMode.DETAILED -> 72.dp
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        items(entries, key = { it.uri.toString() }) { entry ->
            FileRow(
                entry = entry,
                selected = entry.uri == selectedEntry?.uri,
                rowHeight = rowHeight,
                showMetadata = densityMode != DensityMode.COMPACT,
                onSelect = onSelect,
                onOpenFolder = onOpenFolder,
                onOpenFolderInNewTab = onOpenFolderInNewTab,
                onOpenExternal = onOpenExternal,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    rowHeight: androidx.compose.ui.unit.Dp,
    showMetadata: Boolean,
    onSelect: (FileEntry) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFolderInNewTab: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .combinedClickable(
                onClick = { if (entry.isDirectory) onOpenFolder(entry) else onSelect(entry) },
                onDoubleClick = { if (entry.isDirectory) onOpenFolder(entry) else onOpenExternal(entry) },
                onLongClick = { onSelect(entry) },
            )
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                else Color.Transparent,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entryIcon(entry.kind), contentDescription = null, tint = entryIconTint(entry.kind))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showMetadata) {
                Text(
                    metadata(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (entry.isDirectory) {
            IconButton(onClick = { onOpenFolderInNewTab(entry) }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = "Open ${entry.name} in a new tab")
            }
        }
    }
}

@Composable
private fun FileGrid(
    entries: List<FileEntry>,
    selectedEntry: FileEntry?,
    onSelect: (FileEntry) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFolderInNewTab: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.uri.toString() }) { entry ->
            val selected = entry.uri == selectedEntry?.uri
            Surface(
                modifier = Modifier
                    .height(122.dp)
                    .combinedClickable(
                        onClick = { if (entry.isDirectory) onOpenFolder(entry) else onSelect(entry) },
                        onDoubleClick = { if (entry.isDirectory) onOpenFolderInNewTab(entry) else onOpenExternal(entry) },
                        onLongClick = { onSelect(entry) },
                    )
                    .then(
                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                        else Modifier,
                    ),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(entryIcon(entry.kind), contentDescription = null, modifier = Modifier.size(34.dp), tint = entryIconTint(entry.kind))
                    Column {
                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        entry.sizeBytes?.let {
                            Text(formatBytes(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDetails(
    entries: List<FileEntry>,
    selectedEntry: FileEntry?,
    onSelect: (FileEntry) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFolderInNewTab: (FileEntry) -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Name", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Modified", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(132.dp))
            Text("Size", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(78.dp))
        }
        HorizontalDivider()
        LazyColumn {
            items(entries, key = { it.uri.toString() }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { if (entry.isDirectory) onOpenFolder(entry) else onSelect(entry) }
                        .background(
                            if (entry.uri == selectedEntry?.uri) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                            else Color.Transparent,
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(entryIcon(entry.kind), contentDescription = null, modifier = Modifier.size(20.dp), tint = entryIconTint(entry.kind))
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(formatDate(entry.lastModifiedMillis), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(132.dp))
                    Text(entry.sizeBytes?.let(::formatBytes).orEmpty(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(78.dp))
                    if (entry.isDirectory) {
                        IconButton(onClick = { onOpenFolderInNewTab(entry) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = "Open in new tab", modifier = Modifier.size(17.dp))
                        }
                    } else {
                        IconButton(onClick = { onOpenExternal(entry) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = "Open file", modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewModeMenu(value: ViewMode, onChange: (ViewMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                when (value) {
                    ViewMode.LIST -> Icons.Outlined.List
                    ViewMode.GRID -> Icons.Outlined.GridView
                    ViewMode.DETAILS -> Icons.Outlined.TableRows
                },
                contentDescription = "File view",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    onClick = {
                        onChange(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            when (mode) {
                                ViewMode.LIST -> Icons.Outlined.List
                                ViewMode.GRID -> Icons.Outlined.GridView
                                ViewMode.DETAILS -> Icons.Outlined.TableRows
                            },
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DensityMenu(value: DensityMode, onChange: (DensityMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.DensityMedium, contentDescription = "Information density")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DensityMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    onClick = {
                        onChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewMenu(value: PreviewMode, onChange: (PreviewMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.ViewSidebar, contentDescription = "Preview placement")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PreviewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    onClick = {
                        onChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeMenu(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accentPreset: AccentPreset,
    onAccentPresetChange: (AccentPreset) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Palette, contentDescription = "Theme")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text("APPEARANCE", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    onClick = { onThemeModeChange(mode) },
                    leadingIcon = {
                        Icon(
                            when (mode) {
                                ThemeMode.SYSTEM -> Icons.Outlined.DarkMode
                                ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                ThemeMode.DARK -> Icons.Outlined.DarkMode
                            },
                            contentDescription = null,
                        )
                    },
                    trailingIcon = { if (mode == themeMode) Text("✓") },
                )
            }
            HorizontalDivider()
            Text("ACCENT", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            AccentPreset.entries.forEach { accent ->
                DropdownMenuItem(
                    text = { Text(accent.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    onClick = { onAccentPresetChange(accent) },
                    trailingIcon = { if (accent == accentPreset) Text("✓") },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Use system colors") },
                onClick = { onDynamicColorChange(!dynamicColor) },
                trailingIcon = { Checkbox(dynamicColor, onCheckedChange = null) },
            )
        }
    }
}

private fun entryIcon(kind: EntryKind): ImageVector = when (kind) {
    EntryKind.DIRECTORY -> Icons.Outlined.Folder
    EntryKind.MARKDOWN -> Icons.Outlined.Article
    EntryKind.TEXT -> Icons.Outlined.Code
    EntryKind.IMAGE -> Icons.Outlined.Image
    EntryKind.PDF -> Icons.Outlined.PictureAsPdf
    EntryKind.ARCHIVE -> Icons.Outlined.Archive
    EntryKind.AUDIO -> Icons.Outlined.AudioFile
    EntryKind.VIDEO -> Icons.Outlined.VideoFile
    EntryKind.OTHER -> Icons.Outlined.InsertDriveFile
}

@Composable
private fun entryIconTint(kind: EntryKind): Color = when (kind) {
    EntryKind.DIRECTORY -> MaterialTheme.colorScheme.primary
    EntryKind.MARKDOWN, EntryKind.TEXT -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun metadata(entry: FileEntry): String = buildList {
    if (!entry.isDirectory) entry.sizeBytes?.let { add(formatBytes(it)) }
    entry.lastModifiedMillis?.let { add(formatDate(it)) }
    if (isEmpty()) add(entry.mimeType)
}.joinToString(" · ")

private fun formatDate(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
}.orEmpty()

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}
