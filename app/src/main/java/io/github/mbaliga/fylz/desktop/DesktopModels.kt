package io.github.mbaliga.fylz.desktop

import android.net.Uri
import io.github.mbaliga.fylz.canvas.TilePlacement
import java.util.UUID

/**
 * One widget kind the desktop can host. Persisted by [id] rather than the enum's own name -- the
 * same by-id idiom [io.github.mbaliga.fylz.storage.StorageKind] and friends use for a persisted
 * key -- so a Kotlin constant rename never breaks a value already sitting in a user's desktop.
 */
enum class DesktopWidgetType(val id: String) {
    STORAGE("storage"),
    QUICK_ACCESS("quick_access"),
    RECYCLE_BIN("recycle_bin"),
    TAGS("tags"),
    PINNED("pinned"),
    SHELF("shelf"),
    RECENTS("recents"),
    SEARCH("search"),
    QUICK_ACTIONS("quick_actions"),
    LARGE_FILES("large_files"),
    ;

    companion object {
        fun fromId(id: String): DesktopWidgetType? = entries.firstOrNull { it.id == id }
    }
}

/** A widget's footprint on the desktop grid -- how much room it asks for, not a pixel size. */
enum class DesktopItemSize { SMALL, MEDIUM, LARGE }

/**
 * One thing placed on the desktop: a shortcut to a folder, a shortcut to a file, or a widget.
 *
 * [id] is an opaque, stable string minted once when the item is created and never recomputed from
 * its content -- [DesktopStore] keys every operation off it, so renaming a shortcut's
 * [FolderShortcut.displayName] or reconfiguring a [Widget] is never mistaken for a different item.
 * Every implementation defaults [id] to a fresh [UUID], the same default-constructor idiom
 * [io.github.mbaliga.fylz.model.FileEntry] and [io.github.mbaliga.fylz.library.LibraryStore]'s own
 * favorites use -- a test that needs a deterministic id passes one explicitly by name.
 *
 * [placement] reuses [TilePlacement] from the freeform canvas rather than inventing a second
 * geometry type; [DesktopPolicy] is this package's analog of [io.github.mbaliga.fylz.canvas.CanvasLayoutPolicy]
 * for that shared placement type.
 */
sealed interface DesktopItem {
    val id: String
    val placement: TilePlacement

    data class FolderShortcut(
        override val id: String = UUID.randomUUID().toString(),
        val treeUri: Uri,
        val folderUri: Uri,
        val displayName: String,
        override val placement: TilePlacement,
    ) : DesktopItem

    data class FileShortcut(
        override val id: String = UUID.randomUUID().toString(),
        val uri: Uri,
        val treeUri: Uri?,
        val displayName: String,
        override val placement: TilePlacement,
    ) : DesktopItem

    /**
     * [config] is a flat string map, e.g. QUICK_ACCESS's `"target"` key holding either
     * `"downloads"` (auto-resolved the way [io.github.mbaliga.fylz.ui.overview.OverviewScreen]
     * resolves its own Downloads root) or `"tree:<treeUri>|folder:<folderUri>"` for a picked
     * folder, with an optional `"label"` display override. [DesktopStore.migrateUri] parses and
     * rewrites that particular shape; any other widget type's [config] is opaque to the store.
     */
    data class Widget(
        override val id: String = UUID.randomUUID().toString(),
        val type: DesktopWidgetType,
        val size: DesktopItemSize,
        val config: Map<String, String> = emptyMap(),
        override val placement: TilePlacement,
    ) : DesktopItem
}
