package io.github.mbaliga.fylz.model

import android.net.Uri
import java.util.UUID

enum class EntryKind {
    DIRECTORY,
    MARKDOWN,
    TEXT,
    IMAGE,
    PDF,
    ARCHIVE,
    AUDIO,
    VIDEO,
    OTHER,
}

data class FileEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val lastModifiedMillis: Long?,
    val flags: Int,
    val kind: EntryKind,
) {
    val isDirectory: Boolean get() = kind == EntryKind.DIRECTORY
}

data class FolderLocation(
    val uri: Uri,
    val name: String,
)

data class FolderTab(
    val id: String = UUID.randomUUID().toString(),
    val treeUri: Uri,
    val locations: List<FolderLocation>,
) {
    val current: FolderLocation get() = locations.last()
    val title: String get() = current.name
}

enum class ViewMode {
    LIST,
    GRID,
    DETAILS,
}

enum class DensityMode {
    COMPACT,
    COMFORTABLE,
    DETAILED,
}

enum class ShellMode {
    TRADITIONAL,
    IMMERSIVE,
}

enum class PreviewMode {
    HIDDEN,
    DOCKED,
    FLOATING,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class AccentPreset {
    MOSS,
    INK,
    CLAY,
    ELECTRIC,
}

/** P1.8: whether a [FylzClipboard] resolves at Paste time to a move or a copy. */
enum class ClipboardMode {
    CUT,
    COPY,
}

/** What Cut/Copy put on the app's own in-memory clipboard (P1.8) -- [entries] is a snapshot taken
 * at Cut/Copy time, not a live reference to the current selection, so it survives the selection
 * being cleared or changed by navigating to another folder before Paste happens. */
data class FylzClipboard(
    val mode: ClipboardMode,
    val entries: List<FileEntry>,
)
