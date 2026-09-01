package io.github.mbaliga.fylz.model

import android.net.Uri
import io.github.mbaliga.fylz.core.model.EntryKind
import java.util.UUID

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
    STACKS,
    CANVAS,
}

/**
 * The S/M/L icon-size axis (COMPACT/COMFORTABLE/DETAILED), independent of [ViewMode] -- any
 * render branch can be asked to run denser or roomier without changing what it renders.
 * [io.github.mbaliga.fylz.browse.Density] turns a mode into the actual scale and sizes; nothing
 * here does that math so the enum stays a plain preference value, storable the same
 * enum-string way [ViewMode] already is.
 */
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

/**
 * What a deliberate shake of the device does. REFRESH is the constellation default
 * (cell-shell's `ShakeToRefresh` contract); the owner's direction is that the gesture's
 * *meaning* be the user's to configure, so the detector stays shared and only the dispatch
 * is a preference. OFF exists because a gesture some people trigger on a bumpy commute must
 * be refusable outright, not merely retargeted.
 */
enum class ShakeAction {
    REFRESH,
    GO_HOME,
    OFF,
}

enum class AccentPreset {
    MOSS,
    INK,
    CLAY,
    ELECTRIC,
}
