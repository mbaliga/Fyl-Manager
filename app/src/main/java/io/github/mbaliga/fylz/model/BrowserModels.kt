package io.github.mbaliga.fylz.model

enum class BrowserViewMode { LIST, GRID }
enum class DetailDensity { COMPACT, COMFORTABLE, GENEROUS }
enum class SortField { NAME, MODIFIED, SIZE, TYPE }
enum class SortDirection { ASCENDING, DESCENDING }
enum class ShellMode { TRADITIONAL, IMMERSIVE }
enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED, MONO }

enum class PreviewKind {
    NONE,
    DIRECTORY,
    MARKDOWN,
    TEXT,
    IMAGE,
    PDF,
    AUDIO,
    VIDEO,
    ARCHIVE,
    UNSUPPORTED,
}

data class FolderLocation(
    val treeUri: String,
    val documentId: String,
    val title: String,
    val flags: Int = 0,
)

data class BrowserTab(
    val id: String,
    val label: String,
    val current: FolderLocation,
    val backStack: List<FolderLocation> = emptyList(),
    val forwardStack: List<FolderLocation> = emptyList(),
)

data class FileEntry(
    val treeUri: String,
    val documentId: String,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedAt: Long?,
    val flags: Int,
)

data class PreviewState(
    val kind: PreviewKind = PreviewKind.NONE,
    val text: String? = null,
    val truncated: Boolean = false,
    val error: String? = null,
)

data class BrowserUiState(
    val tabs: List<BrowserTab> = emptyList(),
    val activeTabId: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val selected: FileEntry? = null,
    val preview: PreviewState = PreviewState(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val viewMode: BrowserViewMode = BrowserViewMode.LIST,
    val density: DetailDensity = DetailDensity.COMFORTABLE,
    val sortField: SortField = SortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val shellMode: ShellMode = ShellMode.TRADITIONAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val navPinned: Boolean = true,
    val navCollapsed: Boolean = false,
    val previewVisible: Boolean = true,
    val floatingPreview: Boolean = false,
    val isEditing: Boolean = false,
    val editorText: String = "",
    val editorDirty: Boolean = false,
) {
    val activeTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == activeTabId }
}
