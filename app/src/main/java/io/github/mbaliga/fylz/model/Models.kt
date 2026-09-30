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

/** Owner request: what a two-finger pinch-in on the browse surface does. [GO_UP] (the default)
 * reuses `fylz.navigate.up`'s own handler; [DETAIL_LEVEL] steps the grid/list detail ladder
 * instead (`io.github.mbaliga.fylz.ui.actions.DetailLevelLadder`), and in that mode pinch-out is
 * also live -- it is a no-op under [GO_UP]. Persisted the same shape as
 * [io.github.mbaliga.fylz.operations.VerifySettings] (see `PinchSettingsStore`). */
enum class PinchInBehavior {
    GO_UP,
    DETAIL_LEVEL,
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
