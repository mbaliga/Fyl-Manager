package io.github.mbaliga.fylz

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mbaliga.fylz.model.BrowserTab
import io.github.mbaliga.fylz.model.BrowserUiState
import io.github.mbaliga.fylz.model.BrowserViewMode
import io.github.mbaliga.fylz.model.DetailDensity
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.model.PreviewKind
import io.github.mbaliga.fylz.model.PreviewState
import io.github.mbaliga.fylz.model.ShellMode
import io.github.mbaliga.fylz.model.SortDirection
import io.github.mbaliga.fylz.model.SortField
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.preview.PreviewClassifier
import io.github.mbaliga.fylz.storage.FileNamePolicy
import io.github.mbaliga.fylz.storage.FileSorter
import io.github.mbaliga.fylz.storage.SafFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val PREFS_NAME = "fylz_preferences"
private const val PREF_ROOT_URI = "root_uri"
private const val PREF_VIEW_MODE = "view_mode"
private const val PREF_DENSITY = "density"
private const val PREF_THEME = "theme"
private const val PREF_SHELL = "shell"
private const val PREF_NAV_PINNED = "nav_pinned"
private const val PREF_PREVIEW_VISIBLE = "preview_visible"

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SafFileRepository(application.contentResolver)
    private val preferences = application.getSharedPreferences(PREFS_NAME, 0)
    private val initialNavPinned = preferences.getBoolean(PREF_NAV_PINNED, true)
    private val _state = MutableStateFlow(
        BrowserUiState(
            viewMode = preferences.enumValue(PREF_VIEW_MODE, BrowserViewMode.LIST),
            density = preferences.enumValue(PREF_DENSITY, DetailDensity.COMFORTABLE),
            themeMode = preferences.enumValue(PREF_THEME, ThemeMode.SYSTEM),
            shellMode = preferences.enumValue(PREF_SHELL, ShellMode.TRADITIONAL),
            navPinned = initialNavPinned,
            navCollapsed = !initialNavPinned,
            previewVisible = preferences.getBoolean(PREF_PREVIEW_VISIBLE, true),
        ),
    )
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private var allEntries: List<FileEntry> = emptyList()
    private var rootRequestId: Long = 0
    private var folderRequestId: Long = 0

    init {
        preferences.getString(PREF_ROOT_URI, null)?.let { persisted ->
            openRoot(Uri.parse(persisted), remember = false)
        }
    }

    fun openRoot(uri: Uri, remember: Boolean = true) {
        if (!prepareToLeaveEditor()) return
        val requestId = ++rootRequestId
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.rootLocation(uri).fold(
                onSuccess = { location ->
                    if (requestId != rootRequestId) return@fold
                    val tab = BrowserTab(
                        id = UUID.randomUUID().toString(),
                        label = location.title,
                        current = location,
                    )
                    allEntries = emptyList()
                    _state.update { current ->
                        current.copy(
                            tabs = if (current.tabs.isEmpty()) listOf(tab) else current.tabs + tab,
                            activeTabId = tab.id,
                            entries = emptyList(),
                            selected = null,
                            preview = PreviewState(),
                            searchQuery = "",
                            isLoading = false,
                        )
                    }
                    if (remember) preferences.edit().putString(PREF_ROOT_URI, uri.toString()).apply()
                    loadActiveFolder()
                },
                onFailure = { failure ->
                    if (requestId != rootRequestId) return@fold
                    if (!remember) preferences.edit().remove(PREF_ROOT_URI).apply()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = failure.userMessage("Unable to open that location."),
                        )
                    }
                },
            )
        }
    }

    fun refresh() = loadActiveFolder()

    fun select(entry: FileEntry) {
        if (entry.isDirectory) {
            openFolder(entry)
            return
        }
        selectOnly(entry)
    }

    fun selectOnly(entry: FileEntry) {
        if (!prepareToLeaveEditor()) return
        val kind = if (entry.isDirectory) PreviewKind.DIRECTORY else PreviewClassifier.classify(entry)
        _state.update {
            it.copy(
                selected = entry,
                preview = PreviewState(kind = kind),
                isEditing = false,
                editorText = "",
                editorDirty = false,
            )
        }
        if (kind == PreviewKind.MARKDOWN || kind == PreviewKind.TEXT) loadTextPreview(entry, kind)
        collapseNavigationIfUnpinned()
    }

    fun clearSelection() {
        _state.update {
            it.copy(
                selected = null,
                preview = PreviewState(),
                isEditing = false,
                editorText = "",
                editorDirty = false,
            )
        }
    }

    fun openFolder(entry: FileEntry) {
        if (!entry.isDirectory || !prepareToLeaveEditor()) return
        val tab = _state.value.activeTab ?: return
        val next = FolderLocation(entry.treeUri, entry.documentId, entry.name, entry.flags)
        replaceTab(
            tab.copy(
                label = entry.name,
                current = next,
                backStack = tab.backStack + tab.current,
                forwardStack = emptyList(),
            ),
        )
        _state.update { it.copy(searchQuery = "") }
        clearSelection()
        collapseNavigationIfUnpinned()
        loadActiveFolder()
    }

    fun navigateBack() {
        if (!prepareToLeaveEditor()) return
        val tab = _state.value.activeTab ?: return
        val previous = tab.backStack.lastOrNull() ?: return
        replaceTab(
            tab.copy(
                label = previous.title,
                current = previous,
                backStack = tab.backStack.dropLast(1),
                forwardStack = tab.forwardStack + tab.current,
            ),
        )
        _state.update { it.copy(searchQuery = "") }
        clearSelection()
        collapseNavigationIfUnpinned()
        loadActiveFolder()
    }

    fun navigateForward() {
        if (!prepareToLeaveEditor()) return
        val tab = _state.value.activeTab ?: return
        val next = tab.forwardStack.lastOrNull() ?: return
        replaceTab(
            tab.copy(
                label = next.title,
                current = next,
                backStack = tab.backStack + tab.current,
                forwardStack = tab.forwardStack.dropLast(1),
            ),
        )
        _state.update { it.copy(searchQuery = "") }
        clearSelection()
        collapseNavigationIfUnpinned()
        loadActiveFolder()
    }

    fun newTab() {
        if (!prepareToLeaveEditor()) return
        val current = _state.value.activeTab ?: return
        val tab = current.copy(
            id = UUID.randomUUID().toString(),
            forwardStack = emptyList(),
        )
        _state.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
        clearSelection()
        loadActiveFolder()
    }

    fun navigateToBreadcrumb(index: Int) {
        if (!prepareToLeaveEditor()) return
        val tab = _state.value.activeTab ?: return
        val path = tab.backStack + tab.current
        if (index !in path.indices || index == path.lastIndex) return

        val target = path[index]
        val skippedParents = tab.backStack.drop(index + 1).reversed()
        replaceTab(
            tab.copy(
                label = target.title,
                current = target,
                backStack = tab.backStack.take(index),
                forwardStack = tab.forwardStack + tab.current + skippedParents,
            ),
        )
        _state.update { it.copy(searchQuery = "") }
        clearSelection()
        collapseNavigationIfUnpinned()
        loadActiveFolder()
    }

    fun switchTab(id: String) {
        if (!prepareToLeaveEditor()) return
        if (_state.value.activeTabId == id || _state.value.tabs.none { it.id == id }) return
        allEntries = emptyList()
        _state.update {
            it.copy(
                activeTabId = id,
                entries = emptyList(),
                selected = null,
                preview = PreviewState(),
                searchQuery = "",
                isEditing = false,
                editorText = "",
                editorDirty = false,
            )
        }
        loadActiveFolder()
    }

    fun closeTab(id: String) {
        val current = _state.value
        if (current.tabs.size <= 1) return
        val index = current.tabs.indexOfFirst { it.id == id }
        if (index < 0) return

        // Closing an inactive tab must not disturb the active tab's selection, preview, or editor.
        if (current.activeTabId != id) {
            _state.update { state -> state.copy(tabs = state.tabs.filterNot { it.id == id }) }
            return
        }

        if (!prepareToLeaveEditor()) return
        val remaining = current.tabs.filterNot { it.id == id }
        val nextId = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id
            ?: remaining.first().id
        allEntries = emptyList()
        _state.update {
            it.copy(
                tabs = remaining,
                activeTabId = nextId,
                entries = emptyList(),
                selected = null,
                preview = PreviewState(),
                searchQuery = "",
                isEditing = false,
                editorText = "",
                editorDirty = false,
            )
        }
        loadActiveFolder()
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        emitVisibleEntries()
    }

    fun setViewMode(mode: BrowserViewMode) {
        preferences.edit().putString(PREF_VIEW_MODE, mode.name).apply()
        _state.update { it.copy(viewMode = mode) }
    }

    fun setDensity(density: DetailDensity) {
        preferences.edit().putString(PREF_DENSITY, density.name).apply()
        _state.update { it.copy(density = density) }
    }

    fun setSort(field: SortField, direction: SortDirection = _state.value.sortDirection) {
        _state.update { it.copy(sortField = field, sortDirection = direction) }
        emitVisibleEntries()
    }

    fun toggleSortDirection() {
        val direction = if (_state.value.sortDirection == SortDirection.ASCENDING) {
            SortDirection.DESCENDING
        } else {
            SortDirection.ASCENDING
        }
        setSort(_state.value.sortField, direction)
    }

    fun setTheme(mode: ThemeMode) {
        preferences.edit().putString(PREF_THEME, mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    fun toggleShellMode() {
        val next = if (_state.value.shellMode == ShellMode.TRADITIONAL) {
            ShellMode.IMMERSIVE
        } else {
            ShellMode.TRADITIONAL
        }
        preferences.edit().putString(PREF_SHELL, next.name).apply()
        _state.update { it.copy(shellMode = next) }
    }

    fun toggleNavCollapsed() {
        _state.update { it.copy(navCollapsed = !it.navCollapsed) }
    }

    fun toggleNavPinned() {
        val next = !_state.value.navPinned
        preferences.edit().putBoolean(PREF_NAV_PINNED, next).apply()
        _state.update { it.copy(navPinned = next, navCollapsed = !next) }
    }

    fun togglePreviewVisible() {
        val next = !_state.value.previewVisible
        if (!next && !prepareToLeaveEditor()) return
        preferences.edit().putBoolean(PREF_PREVIEW_VISIBLE, next).apply()
        _state.update { it.copy(previewVisible = next, floatingPreview = false) }
    }

    fun toggleFloatingPreview() {
        _state.update {
            it.copy(
                floatingPreview = !it.floatingPreview,
                previewVisible = true,
            )
        }
    }

    fun startEditing() {
        val current = _state.value
        if (current.preview.kind !in setOf(PreviewKind.MARKDOWN, PreviewKind.TEXT)) return
        if (current.preview.text == null || current.preview.truncated) return
        val entry = current.selected ?: return
        if (entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0) return
        _state.update {
            it.copy(
                isEditing = true,
                editorText = current.preview.text.orEmpty(),
                editorDirty = false,
            )
        }
    }

    fun updateEditor(text: String) {
        _state.update {
            it.copy(
                editorText = text,
                editorDirty = text != it.preview.text.orEmpty(),
            )
        }
    }

    fun cancelEditing() {
        _state.update {
            it.copy(
                isEditing = false,
                editorText = "",
                editorDirty = false,
            )
        }
    }

    fun saveEditor() {
        val current = _state.value
        val entry = current.selected ?: return
        val text = current.editorText
        viewModelScope.launch {
            repository.writeText(entry, text).fold(
                onSuccess = {
                    _state.update { latest ->
                        val sameSelection = latest.selected?.let { selected ->
                            selected.treeUri == entry.treeUri &&
                                selected.documentId == entry.documentId
                        } == true
                        if (!sameSelection) {
                            latest.copy(error = null)
                        } else {
                            latest.copy(
                                preview = latest.preview.copy(
                                    text = text,
                                    truncated = false,
                                    error = null,
                                ),
                                isEditing = false,
                                editorText = "",
                                editorDirty = false,
                                error = null,
                            )
                        }
                    }
                    loadActiveFolder()
                },
                onFailure = { failure ->
                    _state.update { it.copy(error = failure.userMessage("Unable to save this file.")) }
                },
            )
        }
    }

    fun createFolder(name: String) = performInCurrentFolder(
        fallback = "Unable to create the folder.",
    ) { location ->
        requireFolderCreation(location)
        repository.createDirectory(location, FileNamePolicy.validate(name))
    }

    fun createTextFile(name: String) = performInCurrentFolder(
        fallback = "Unable to create the text file.",
    ) { location ->
        requireFolderCreation(location)
        repository.createTextFile(location, FileNamePolicy.validate(name))
    }

    fun renameSelected(newName: String) {
        if (!prepareToLeaveEditor()) return
        val entry = _state.value.selected ?: return
        if (entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME == 0) {
            _state.update { it.copy(error = "This document provider does not support renaming this item.") }
            return
        }
        viewModelScope.launch {
            runCatching { FileNamePolicy.validate(newName) }.fold(
                onSuccess = { safeName ->
                    repository.rename(entry, safeName).fold(
                        onSuccess = {
                            clearSelection()
                            loadActiveFolder()
                        },
                        onFailure = { failure ->
                            _state.update {
                                it.copy(error = failure.userMessage("Unable to rename this item."))
                            }
                        },
                    )
                },
                onFailure = { failure -> _state.update { it.copy(error = failure.message) } },
            )
        }
    }

    fun deleteSelected() {
        if (!prepareToLeaveEditor()) return
        val entry = _state.value.selected ?: return
        if (entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE == 0) {
            _state.update { it.copy(error = "This document provider does not support deleting this item.") }
            return
        }
        viewModelScope.launch {
            repository.delete(entry).fold(
                onSuccess = {
                    clearSelection()
                    loadActiveFolder()
                },
                onFailure = { failure ->
                    _state.update { it.copy(error = failure.userMessage("Unable to delete this item.")) }
                },
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun documentUri(entry: FileEntry): Uri = repository.documentUri(entry)

    private fun loadActiveFolder() {
        val tab = _state.value.activeTab ?: return
        val tabId = tab.id
        val location = tab.current
        val requestId = ++folderRequestId
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.listChildren(location).fold(
                onSuccess = { loaded ->
                    if (requestId != folderRequestId ||
                        _state.value.activeTabId != tabId ||
                        _state.value.activeTab?.current != location
                    ) return@fold
                    allEntries = loaded
                    _state.update { it.copy(isLoading = false) }
                    emitVisibleEntries()
                },
                onFailure = { failure ->
                    if (requestId != folderRequestId ||
                        _state.value.activeTabId != tabId ||
                        _state.value.activeTab?.current != location
                    ) return@fold
                    allEntries = emptyList()
                    _state.update {
                        it.copy(
                            entries = emptyList(),
                            isLoading = false,
                            error = failure.userMessage("Unable to read this folder."),
                        )
                    }
                },
            )
        }
    }

    private fun loadTextPreview(entry: FileEntry, kind: PreviewKind) {
        viewModelScope.launch {
            repository.readText(entry).fold(
                onSuccess = { payload ->
                    if (_state.value.selected != entry) return@fold
                    _state.update {
                        it.copy(
                            preview = PreviewState(
                                kind = kind,
                                text = payload.text,
                                truncated = payload.truncated,
                            ),
                        )
                    }
                },
                onFailure = { failure ->
                    if (_state.value.selected != entry) return@fold
                    _state.update {
                        it.copy(
                            preview = PreviewState(
                                kind = kind,
                                error = failure.userMessage("Unable to preview this file."),
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun performInCurrentFolder(
        fallback: String,
        operation: suspend (FolderLocation) -> Result<Unit>,
    ) {
        val location = _state.value.activeTab?.current ?: return
        viewModelScope.launch {
            runCatching { operation(location).getOrThrow() }.fold(
                onSuccess = { loadActiveFolder() },
                onFailure = { failure ->
                    _state.update { it.copy(error = failure.userMessage(fallback)) }
                },
            )
        }
    }

    private fun prepareToLeaveEditor(): Boolean {
        val current = _state.value
        if (!current.isEditing) return true
        if (current.editorDirty) {
            _state.update {
                it.copy(error = "Save or cancel the current edit before leaving this file.")
            }
            return false
        }
        cancelEditing()
        return true
    }

    private fun collapseNavigationIfUnpinned() {
        _state.update { current ->
            if (current.navPinned) current else current.copy(navCollapsed = true)
        }
    }

    private fun replaceTab(tab: BrowserTab) {
        _state.update { current ->
            current.copy(tabs = current.tabs.map { if (it.id == tab.id) tab else it })
        }
    }

    private fun emitVisibleEntries() {
        val current = _state.value
        val query = current.searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { it.name.contains(query, ignoreCase = true) }
        }
        _state.update {
            it.copy(
                entries = FileSorter.sort(filtered, current.sortField, current.sortDirection),
            )
        }
    }

    private fun requireFolderCreation(location: FolderLocation) {
        require(location.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
            "This document provider does not support creating items in this folder."
        }
    }
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enumValue(
    key: String,
    default: T,
): T = getString(key, null)?.let { stored ->
    runCatching { enumValueOf<T>(stored) }.getOrNull()
} ?: default

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
