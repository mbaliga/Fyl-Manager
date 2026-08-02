package io.github.mbaliga.fylz.ui

import android.content.ClipData
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mbaliga.fylz.BrowserViewModel
import io.github.mbaliga.fylz.model.BrowserTab
import io.github.mbaliga.fylz.model.BrowserUiState
import io.github.mbaliga.fylz.model.BrowserViewMode
import io.github.mbaliga.fylz.model.DetailDensity
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.PreviewKind
import io.github.mbaliga.fylz.model.ShellMode
import io.github.mbaliga.fylz.model.SortDirection
import io.github.mbaliga.fylz.model.SortField
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.preview.PreviewClassifier
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

enum class NameDialogMode { NONE, FOLDER, TEXT_FILE, RENAME }

@Composable
fun FylzApp(
    viewModel: BrowserViewModel,
    onPickFolder: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.tabs.isNotEmpty()) {
        if (state.tabs.isNotEmpty()) {
            state.error?.let { message ->
                snackbarHostState.showSnackbar(message)
                viewModel.clearError()
            }
        }
    }

    val openExternal: (FileEntry) -> Unit = { entry ->
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(viewModel.documentUri(entry), entry.mimeType)
            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0) {
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                } else {
                    0
                }
            addFlags(grantFlags)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Open ${entry.name}"))
        }.onFailure {
            Toast.makeText(context, "No compatible app is installed.", Toast.LENGTH_SHORT).show()
        }
    }

    val shareExternal: (FileEntry) -> Unit = { entry ->
        val uri = viewModel.documentUri(entry)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entry.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(entry.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Share ${entry.name}"))
        }.onFailure {
            Toast.makeText(context, "No compatible share target is installed.", Toast.LENGTH_SHORT).show()
        }
    }

    FylzTheme(state.themeMode) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.tabs.isEmpty()) {
                WelcomeScreen(
                    isLoading = state.isLoading,
                    error = state.error,
                    onPickFolder = onPickFolder,
                )
            } else {
                Workspace(
                    state = state,
                    viewModel = viewModel,
                    onPickFolder = onPickFolder,
                    openExternal = openExternal,
                    shareExternal = shareExternal,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    isLoading: Boolean,
    error: String?,
    onPickFolder: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .padding(28.dp),
        ) {
            Text(
                text = "FYLZ",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "A minimal, local-first file workspace.",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Choose a folder once. Android will remember the permission, and Fylz will browse it without requesting blanket access to every file on the device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPickFolder, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                } else {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                }
                Text(if (isLoading) "Opening..." else "Choose location")
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "No telemetry. No account. No network permission in this build.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Workspace(
    state: BrowserUiState,
    viewModel: BrowserViewModel,
    onPickFolder: () -> Unit,
    openExternal: (FileEntry) -> Unit,
    shareExternal: (FileEntry) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    var nameDialog by rememberSaveable { mutableStateOf(NameDialogMode.NONE) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pendingDiscardAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun runGuarded(action: () -> Unit) {
        if (state.editorDirty) pendingDiscardAction = action else action()
    }

    BackHandler(
        enabled = state.isEditing ||
            (state.previewVisible && state.selected != null) ||
            state.activeTab?.backStack?.isNotEmpty() == true,
    ) {
        when {
            state.isEditing -> {
                if (state.editorDirty) pendingDiscardAction = viewModel::cancelEditing
                else viewModel.cancelEditing()
            }
            state.previewVisible && state.selected != null -> {
                runGuarded(viewModel::togglePreviewVisible)
            }
            else -> runGuarded(viewModel::navigateBack)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.shellMode == ShellMode.TRADITIONAL) {
                WorkspaceTopBar(
                    state = state,
                    onBack = { runGuarded(viewModel::navigateBack) },
                    onForward = { runGuarded(viewModel::navigateForward) },
                    onRefresh = viewModel::refresh,
                    onPickFolder = { runGuarded(onPickFolder) },
                    onToggleShell = viewModel::toggleShellMode,
                    onTheme = viewModel::setTheme,
                )
            }
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val wide = maxWidth >= 720.dp
            val showDockedPreview = wide && state.previewVisible && !state.floatingPreview
            val showNavigation = state.shellMode == ShellMode.TRADITIONAL && maxWidth >= 600.dp
            val forceCompactNavigation = maxWidth < 900.dp
            val previewPaneWidth = when {
                maxWidth < 840.dp -> 280.dp
                maxWidth < 1_000.dp -> 320.dp
                else -> 390.dp
            }

            Column(Modifier.fillMaxSize()) {
                TabsBar(
                    tabs = state.tabs,
                    activeId = state.activeTabId,
                    onSelect = { id -> runGuarded { viewModel.switchTab(id) } },
                    onClose = { id ->
                        if (id == state.activeTabId) runGuarded { viewModel.closeTab(id) }
                        else viewModel.closeTab(id)
                    },
                    onAdd = { runGuarded(viewModel::newTab) },
                )
                BreadcrumbBar(
                    tab = state.activeTab,
                    onNavigate = { index -> runGuarded { viewModel.navigateToBreadcrumb(index) } },
                )
                HorizontalDivider()
                Row(Modifier.weight(1f)) {
                    if (showNavigation) {
                        NavigationPane(
                            state = state,
                            forceCompact = forceCompactNavigation,
                            onPickFolder = { runGuarded(onPickFolder) },
                            onToggleCollapsed = viewModel::toggleNavCollapsed,
                            onTogglePinned = viewModel::toggleNavPinned,
                            onTogglePreview = { runGuarded(viewModel::togglePreviewVisible) },
                            onToggleFloating = viewModel::toggleFloatingPreview,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        VerticalDivider(Modifier.fillMaxHeight())
                    }

                    FilePane(
                        state = state,
                        onSearch = viewModel::setSearchQuery,
                        onSelect = { entry -> runGuarded { viewModel.select(entry) } },
                        onSelectOnly = { entry -> runGuarded { viewModel.selectOnly(entry) } },
                        onBack = { runGuarded(viewModel::navigateBack) },
                        onForward = { runGuarded(viewModel::navigateForward) },
                        onRefresh = viewModel::refresh,
                        onViewMode = viewModel::setViewMode,
                        onDensity = viewModel::setDensity,
                        onSort = viewModel::setSort,
                        onToggleSortDirection = viewModel::toggleSortDirection,
                        onNewFolder = { nameDialog = NameDialogMode.FOLDER },
                        onNewTextFile = { nameDialog = NameDialogMode.TEXT_FILE },
                        onRename = { runGuarded { nameDialog = NameDialogMode.RENAME } },
                        onDelete = { runGuarded { confirmDelete = true } },
                        onToggleShell = viewModel::toggleShellMode,
                        onTogglePreview = { runGuarded(viewModel::togglePreviewVisible) },
                        onToggleFloating = viewModel::toggleFloatingPreview,
                        onOpenExternal = openExternal,
                        onShareExternal = shareExternal,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )

                    if (showDockedPreview) {
                        VerticalDivider(Modifier.fillMaxHeight())
                        PreviewPane(
                            state = state,
                            documentUri = viewModel::documentUri,
                            onClose = { runGuarded(viewModel::togglePreviewVisible) },
                            onToggleFloating = viewModel::toggleFloatingPreview,
                            onEdit = viewModel::startEditing,
                            onEditorChange = viewModel::updateEditor,
                            onSave = viewModel::saveEditor,
                            onCancelEdit = { runGuarded(viewModel::cancelEditing) },
                            onOpenExternal = openExternal,
                            modifier = Modifier
                                .width(previewPaneWidth)
                                .fillMaxHeight()
                                .padding(10.dp),
                        )
                    }
                }
            }

            if (state.previewVisible && state.selected != null && (!wide || state.floatingPreview)) {
                FloatingPreview(
                    state = state,
                    documentUri = viewModel::documentUri,
                    onClose = { runGuarded(viewModel::togglePreviewVisible) },
                    onToggleFloating = viewModel::toggleFloatingPreview,
                    onEdit = viewModel::startEditing,
                    onEditorChange = viewModel::updateEditor,
                    onSave = viewModel::saveEditor,
                    onCancelEdit = { runGuarded(viewModel::cancelEditing) },
                    onOpenExternal = openExternal,
                    maxWidthPx = constraints.maxWidth.toFloat(),
                    maxHeightPx = constraints.maxHeight.toFloat(),
                )
            }
        }
    }

    NameDialog(
        mode = nameDialog,
        initialName = if (nameDialog == NameDialogMode.RENAME) state.selected?.name.orEmpty() else "",
        onDismiss = { nameDialog = NameDialogMode.NONE },
        onConfirm = { value ->
            when (nameDialog) {
                NameDialogMode.FOLDER -> viewModel.createFolder(value)
                NameDialogMode.TEXT_FILE -> viewModel.createTextFile(value)
                NameDialogMode.RENAME -> viewModel.renameSelected(value)
                NameDialogMode.NONE -> Unit
            }
            nameDialog = NameDialogMode.NONE
        },
    )

    if (pendingDiscardAction != null) {
        AlertDialog(
            onDismissRequest = { pendingDiscardAction = null },
            title = { Text("Discard unsaved changes?") },
            text = { Text("The text editor contains changes that have not been saved.") },
            confirmButton = {
                Button(
                    onClick = {
                        val action = pendingDiscardAction
                        pendingDiscardAction = null
                        viewModel.cancelEditing()
                        action?.invoke()
                    },
                ) { Text("Discard and continue") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscardAction = null }) { Text("Keep editing") }
            },
        )
    }

    if (confirmDelete && state.selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${state.selected.name}?") },
            text = {
                Text("This asks the current document provider to delete the item. Some providers may not offer a recycle bin.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSelected()
                        confirmDelete = false
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    state: BrowserUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onPickFolder: () -> Unit,
    onToggleShell: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    val tab = state.activeTab

    TopAppBar(
        title = {
            Column {
                Text(
                    text = tab?.current?.title ?: "Fylz",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.entries.size} visible items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            Row {
                IconButton(
                    onClick = onBack,
                    enabled = tab?.backStack?.isNotEmpty() == true,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                IconButton(
                    onClick = onForward,
                    enabled = tab?.forwardStack?.isNotEmpty() == true,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Forward")
                }
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
            Box {
                IconButton(onClick = { themeExpanded = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Theme")
                }
                DropdownMenu(
                    expanded = themeExpanded,
                    onDismissRequest = { themeExpanded = false },
                ) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            onClick = {
                                onTheme(mode)
                                themeExpanded = false
                            },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Open location in new tab") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPickFolder()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Enter immersive mode") },
                        leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onToggleShell()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun TabsBar(
    tabs: List<BrowserTab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == activeId
            Surface(
                color = if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(tab.id) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    if (tabs.size > 1) {
                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close tab",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.size(7.dp))
                    }
                }
            }
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = "New tab")
        }
    }
}

@Composable
private fun BreadcrumbBar(
    tab: BrowserTab?,
    onNavigate: (Int) -> Unit,
) {
    if (tab == null) return
    val path = tab.backStack + tab.current
    val scrollState = rememberScrollState()
    LaunchedEffect(tab.current.documentId, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        path.forEachIndexed { index, location ->
            TextButton(
                onClick = { onNavigate(index) },
                enabled = index < path.lastIndex,
            ) {
                Text(
                    text = location.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index < path.lastIndex) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NavigationPane(
    state: BrowserUiState,
    forceCompact: Boolean,
    onPickFolder: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onTogglePinned: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleFloating: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = forceCompact || state.navCollapsed
    Column(
        modifier = modifier
            .width(if (compact) 70.dp else 224.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = onToggleCollapsed,
                enabled = !forceCompact,
            ) {
                Icon(Icons.Outlined.Menu, contentDescription = "Collapse navigation")
            }
            if (!compact) {
                Text(
                    "FYLZ",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "Pin navigation",
                        tint = if (state.navPinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NavigationAction(
            icon = Icons.Outlined.Folder,
            label = "Location",
            compact = compact,
            selected = true,
            onClick = onPickFolder,
        )
        NavigationAction(
            icon = Icons.Outlined.Splitscreen,
            label = if (state.previewVisible) "Hide preview" else "Show preview",
            compact = compact,
            onClick = onTogglePreview,
        )
        NavigationAction(
            icon = Icons.Outlined.OpenInNew,
            label = "Float preview",
            compact = compact,
            selected = state.floatingPreview,
            onClick = onToggleFloating,
        )
        if (!compact) {
            Spacer(Modifier.height(18.dp))
            Text(
                "CURRENT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.activeTab?.current?.title.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Local-first foundation\nNo network permission",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun NavigationAction(
    icon: ImageVector,
    label: String,
    compact: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Icon(
            icon,
            contentDescription = if (compact) label else null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!compact) {
            Spacer(Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FilePane(
    state: BrowserUiState,
    onSearch: (String) -> Unit,
    onSelect: (FileEntry) -> Unit,
    onSelectOnly: (FileEntry) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onViewMode: (BrowserViewMode) -> Unit,
    onDensity: (DetailDensity) -> Unit,
    onSort: (SortField, SortDirection) -> Unit,
    onToggleSortDirection: () -> Unit,
    onNewFolder: () -> Unit,
    onNewTextFile: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleShell: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleFloating: () -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    onShareExternal: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FileToolbar(
            state = state,
            onSearch = onSearch,
            onBack = onBack,
            onForward = onForward,
            onRefresh = onRefresh,
            onViewMode = onViewMode,
            onDensity = onDensity,
            onSort = onSort,
            onToggleSortDirection = onToggleSortDirection,
            onNewFolder = onNewFolder,
            onNewTextFile = onNewTextFile,
            onToggleShell = onToggleShell,
            onTogglePreview = onTogglePreview,
            onToggleFloating = onToggleFloating,
        )
        val selectedEntry = state.selected
        if (selectedEntry != null) {
            SelectionBar(
                entry = selectedEntry,
                onRename = onRename,
                onDelete = onDelete,
                onOpen = { onOpenExternal(selectedEntry) },
                onShare = { onShareExternal(selectedEntry) },
            )
        }
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())

        when {
            state.entries.isEmpty() && state.isLoading -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) { CircularProgressIndicator() }
            state.entries.isEmpty() -> EmptyFolder(
                searchQuery = state.searchQuery,
                modifier = Modifier.fillMaxSize(),
            )
            state.viewMode == BrowserViewMode.LIST -> FileList(
                entries = state.entries,
                selected = state.selected,
                density = state.density,
                onSelect = onSelect,
                onLongSelect = onSelectOnly,
                modifier = Modifier.fillMaxSize(),
            )
            else -> FileGrid(
                entries = state.entries,
                selected = state.selected,
                density = state.density,
                onSelect = onSelect,
                onLongSelect = onSelectOnly,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FileToolbar(
    state: BrowserUiState,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onViewMode: (BrowserViewMode) -> Unit,
    onDensity: (DetailDensity) -> Unit,
    onSort: (SortField, SortDirection) -> Unit,
    onToggleSortDirection: () -> Unit,
    onNewFolder: () -> Unit,
    onNewTextFile: () -> Unit,
    onToggleShell: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleFloating: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                FolderSearchField(
                    query = state.searchQuery,
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (state.shellMode == ShellMode.IMMERSIVE) {
                        ImmersiveNavigationControls(
                            state = state,
                            onBack = onBack,
                            onForward = onForward,
                            onRefresh = onRefresh,
                        )
                    }
                    FileActionButtons(
                        state = state,
                        onViewMode = onViewMode,
                        onDensity = onDensity,
                        onSort = onSort,
                        onToggleSortDirection = onToggleSortDirection,
                        onNewFolder = onNewFolder,
                        onNewTextFile = onNewTextFile,
                        onToggleShell = onToggleShell,
                        onTogglePreview = onTogglePreview,
                        onToggleFloating = onToggleFloating,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (state.shellMode == ShellMode.IMMERSIVE) {
                    ImmersiveNavigationControls(
                        state = state,
                        onBack = onBack,
                        onForward = onForward,
                        onRefresh = onRefresh,
                    )
                }
                FolderSearchField(
                    query = state.searchQuery,
                    onSearch = onSearch,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(6.dp))
                FileActionButtons(
                    state = state,
                    onViewMode = onViewMode,
                    onDensity = onDensity,
                    onSort = onSort,
                    onToggleSortDirection = onToggleSortDirection,
                    onNewFolder = onNewFolder,
                    onNewTextFile = onNewTextFile,
                    onToggleShell = onToggleShell,
                    onTogglePreview = onTogglePreview,
                    onToggleFloating = onToggleFloating,
                )
            }
        }
    }
}

@Composable
private fun ImmersiveNavigationControls(
    state: BrowserUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
) {
    IconButton(
        onClick = onBack,
        enabled = state.activeTab?.backStack?.isNotEmpty() == true,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
    }
    IconButton(
        onClick = onForward,
        enabled = state.activeTab?.forwardStack?.isNotEmpty() == true,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Forward")
    }
    IconButton(onClick = onRefresh) {
        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
    }
}

@Composable
private fun FolderSearchField(
    query: String,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onSearch,
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onSearch("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear filter")
                }
            }
        } else {
            null
        },
        placeholder = { Text("Filter this folder") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        modifier = modifier,
    )
}

@Composable
private fun FileActionButtons(
    state: BrowserUiState,
    onViewMode: (BrowserViewMode) -> Unit,
    onDensity: (DetailDensity) -> Unit,
    onSort: (SortField, SortDirection) -> Unit,
    onToggleSortDirection: () -> Unit,
    onNewFolder: () -> Unit,
    onNewTextFile: () -> Unit,
    onToggleShell: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleFloating: () -> Unit,
) {
    var newMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var densityMenu by remember { mutableStateOf(false) }
    var paneMenu by remember { mutableStateOf(false) }
    val folderFlags = state.activeTab?.current?.flags ?: 0
    val canCreate = folderFlags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { newMenu = true }, enabled = canCreate) {
                Icon(Icons.Outlined.Add, contentDescription = "Create")
            }
            DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                DropdownMenuItem(
                    text = { Text("New folder") },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        newMenu = false
                        onNewFolder()
                    },
                )
                DropdownMenuItem(
                    text = { Text("New text or Markdown file") },
                    leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                    onClick = {
                        newMenu = false
                        onNewTextFile()
                    },
                )
            }
        }
        IconButton(
            onClick = {
                onViewMode(
                    if (state.viewMode == BrowserViewMode.LIST) BrowserViewMode.GRID
                    else BrowserViewMode.LIST,
                )
            },
        ) {
            Icon(
                if (state.viewMode == BrowserViewMode.LIST) Icons.Outlined.GridView else Icons.Outlined.List,
                contentDescription = "Change view",
            )
        }
        Box {
            IconButton(onClick = { sortMenu = true }) {
                Icon(Icons.Outlined.Sort, contentDescription = "Sort")
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortField.entries.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        trailingIcon = {
                            if (field == state.sortField) Text(
                                if (state.sortDirection == SortDirection.ASCENDING) "ASC" else "DESC",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        onClick = {
                            if (field == state.sortField) onToggleSortDirection()
                            else onSort(field, SortDirection.ASCENDING)
                            sortMenu = false
                        },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { densityMenu = true }) {
                Icon(Icons.Outlined.Tune, contentDescription = "Detail density")
            }
            DropdownMenu(expanded = densityMenu, onDismissRequest = { densityMenu = false }) {
                DetailDensity.entries.forEach { density ->
                    DropdownMenuItem(
                        text = { Text(density.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        onClick = {
                            onDensity(density)
                            densityMenu = false
                        },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { paneMenu = true }) {
                Icon(Icons.Outlined.Splitscreen, contentDescription = "Panes")
            }
            DropdownMenu(expanded = paneMenu, onDismissRequest = { paneMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (state.previewVisible) "Hide preview" else "Show preview") },
                    onClick = {
                        onTogglePreview()
                        paneMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (state.floatingPreview) "Dock preview" else "Float preview") },
                    onClick = {
                        onToggleFloating()
                        paneMenu = false
                    },
                )
                if (state.shellMode == ShellMode.IMMERSIVE) {
                    DropdownMenuItem(
                        text = { Text("Return to traditional mode") },
                        onClick = {
                            onToggleShell()
                            paneMenu = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    entry: FileEntry,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    val canRename = entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
    val canDelete = entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory) {
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "Share")
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = "Open with")
            }
        }
        IconButton(onClick = onRename, enabled = canRename) {
            Icon(Icons.Outlined.Description, contentDescription = "Rename")
        }
        IconButton(onClick = onDelete, enabled = canDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    entries: List<FileEntry>,
    selected: FileEntry?,
    density: DetailDensity,
    onSelect: (FileEntry) -> Unit,
    onLongSelect: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowHeight = when (density) {
        DetailDensity.COMPACT -> 48.dp
        DetailDensity.COMFORTABLE -> 64.dp
        DetailDensity.GENEROUS -> 82.dp
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier,
    ) {
        items(entries, key = { it.documentId }) { entry ->
            val isSelected = selected?.documentId == entry.documentId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.background,
                    )
                    .combinedClickable(
                        onClick = { onSelect(entry) },
                        onLongClick = { onLongSelect(entry) },
                        onDoubleClick = { onSelect(entry) },
                    )
                    .padding(horizontal = 12.dp),
            ) {
                Icon(
                    fileIcon(entry),
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (density == DetailDensity.GENEROUS) 30.dp else 24.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = if (density == DetailDensity.COMPACT) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (density != DetailDensity.COMPACT) {
                        Text(
                            text = entryDetails(entry, includeDate = density == DetailDensity.GENEROUS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (density == DetailDensity.COMPACT) {
                    Text(
                        text = entry.size?.let(::formatBytes).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (density == DetailDensity.GENEROUS) {
                    Text(
                        text = entry.modifiedAt?.let {
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
                        }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(
    entries: List<FileEntry>,
    selected: FileEntry?,
    density: DetailDensity,
    onSelect: (FileEntry) -> Unit,
    onLongSelect: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minCell = when (density) {
        DetailDensity.COMPACT -> 112.dp
        DetailDensity.COMFORTABLE -> 142.dp
        DetailDensity.GENEROUS -> 176.dp
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCell),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(entries, key = { it.documentId }) { entry ->
            val isSelected = selected?.documentId == entry.documentId
            Surface(
                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                tonalElevation = if (isSelected) 4.dp else 1.dp,
                modifier = Modifier
                    .height(if (density == DetailDensity.GENEROUS) 170.dp else 140.dp)
                    .combinedClickable(
                        onClick = { onSelect(entry) },
                        onLongClick = { onLongSelect(entry) },
                        onDoubleClick = { onSelect(entry) },
                    ),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Icon(
                        fileIcon(entry),
                        contentDescription = null,
                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (density == DetailDensity.GENEROUS) 54.dp else 42.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (density != DetailDensity.COMPACT) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            entry.size?.let(::formatBytes).orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFolder(searchQuery: String, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (searchQuery.isEmpty()) Icons.Outlined.Folder else Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (searchQuery.isEmpty()) "This folder is empty" else "No matching files",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun NameDialog(
    mode: NameDialogMode,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (mode == NameDialogMode.NONE) return
    var value by remember(mode, initialName) { mutableStateOf(initialName) }
    val title = when (mode) {
        NameDialogMode.FOLDER -> "New folder"
        NameDialogMode.TEXT_FILE -> "New text file"
        NameDialogMode.RENAME -> "Rename item"
        NameDialogMode.NONE -> ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") },
                supportingText = {
                    if (mode == NameDialogMode.TEXT_FILE) {
                        Text("Use .md for rendered Markdown preview.")
                    }
                },
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = value.trim().isNotEmpty()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FloatingPreview(
    state: BrowserUiState,
    documentUri: (FileEntry) -> android.net.Uri,
    onClose: () -> Unit,
    onToggleFloating: () -> Unit,
    onEdit: () -> Unit,
    onEditorChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    maxWidthPx: Float,
    maxHeightPx: Float,
) {
    val density = LocalDensity.current
    val minWidthPx = with(density) { 280.dp.toPx() }
    val minHeightPx = with(density) { 260.dp.toPx() }
    val initialOffsetPx = with(density) { 24.dp.toPx() }
    val initialWidthPx = with(density) { 420.dp.toPx() }
    val initialHeightPx = with(density) { 560.dp.toPx() }
    var x by rememberSaveable { mutableFloatStateOf(initialOffsetPx) }
    var y by rememberSaveable { mutableFloatStateOf(initialOffsetPx) }
    var widthPx by rememberSaveable { mutableFloatStateOf(initialWidthPx) }
    var heightPx by rememberSaveable { mutableFloatStateOf(initialHeightPx) }

    val effectiveMinWidthPx = minOf(minWidthPx, maxWidthPx.coerceAtLeast(1f))
    val effectiveMinHeightPx = minOf(minHeightPx, maxHeightPx.coerceAtLeast(1f))

    LaunchedEffect(maxWidthPx, maxHeightPx, effectiveMinWidthPx, effectiveMinHeightPx) {
        widthPx = widthPx.coerceIn(effectiveMinWidthPx, maxWidthPx.coerceAtLeast(effectiveMinWidthPx))
        heightPx = heightPx.coerceIn(effectiveMinHeightPx, maxHeightPx.coerceAtLeast(effectiveMinHeightPx))
        x = x.coerceIn(0f, (maxWidthPx - widthPx).coerceAtLeast(0f))
        y = y.coerceIn(0f, (maxHeightPx - heightPx).coerceAtLeast(0f))
    }

    val widthDp = with(density) { widthPx.toDp() }
    val heightDp = with(density) { heightPx.toDp() }

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(widthDp)
            .height(heightDp),
    ) {
        PreviewPane(
            state = state,
            documentUri = documentUri,
            onClose = onClose,
            onToggleFloating = onToggleFloating,
            onEdit = onEdit,
            onEditorChange = onEditorChange,
            onSave = onSave,
            onCancelEdit = onCancelEdit,
            onOpenExternal = onOpenExternal,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(88.dp)
                .height(22.dp)
                .padding(top = 5.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                .pointerInput(maxWidthPx, maxHeightPx, widthPx, heightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        x = (x + dragAmount.x).coerceIn(0f, (maxWidthPx - widthPx).coerceAtLeast(0f))
                        y = (y + dragAmount.y).coerceIn(0f, (maxHeightPx - heightPx).coerceAtLeast(0f))
                    }
                },
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(32.dp)
                .clip(RoundedCornerShape(topStart = 14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                .pointerInput(maxWidthPx, maxHeightPx, x, y) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        widthPx = (widthPx + dragAmount.x).coerceIn(
                            effectiveMinWidthPx,
                            (maxWidthPx - x).coerceAtLeast(effectiveMinWidthPx),
                        )
                        heightPx = (heightPx + dragAmount.y).coerceIn(
                            effectiveMinHeightPx,
                            (maxHeightPx - y).coerceAtLeast(effectiveMinHeightPx),
                        )
                    }
                },
        )
    }
}

private fun fileIcon(entry: FileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Outlined.Folder
    return when (PreviewClassifier.classify(entry)) {
        PreviewKind.MARKDOWN -> Icons.Outlined.Description
        PreviewKind.TEXT -> Icons.Outlined.TextSnippet
        PreviewKind.IMAGE -> Icons.Outlined.Image
        PreviewKind.PDF -> Icons.Outlined.PictureAsPdf
        PreviewKind.AUDIO -> Icons.Outlined.AudioFile
        PreviewKind.VIDEO -> Icons.Outlined.VideoFile
        PreviewKind.ARCHIVE -> Icons.Outlined.Archive
        else -> Icons.Outlined.InsertDriveFile
    }
}

private fun entryDetails(entry: FileEntry, includeDate: Boolean): String = buildString {
    append(if (entry.isDirectory) "Folder" else entry.mimeType.substringAfter('/').uppercase())
    entry.size?.let { append("  |  ${formatBytes(it)}") }
    if (includeDate) {
        entry.modifiedAt?.let {
            append("  |  ")
            append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)))
        }
    }
}
