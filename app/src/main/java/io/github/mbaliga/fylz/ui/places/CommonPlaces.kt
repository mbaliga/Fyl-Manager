package io.github.mbaliga.fylz.ui.places

import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootGroup
import io.github.mbaliga.fylz.storage.StorageRootKind

/** What a row in the left rail does when chosen. */
enum class PlaceKind {
    /** The storage home surface — not a folder, but a real destination. */
    HOME,

    /** A well-known folder that opens straight into a tab. */
    FOLDER,

    /** A saved search rather than a place: everything below the device root over [CommonPlaces.LARGE_FILES_QUERY]. */
    LARGE_FILES,

    /** The recycle bin, shown as the right-hand pane. */
    RECYCLE_BIN,

    /** Storage and recovery: operation history, file history, backups, archives. */
    RECOVERY,

    /** The app's own settings and tools. */
    SETTINGS,
}

/** One row of the left rail. [root] is set for [PlaceKind.FOLDER] only. */
data class CommonPlace(
    val id: String,
    val label: String,
    val kind: PlaceKind,
    val root: StorageRoot? = null,
)

/**
 * The left rail's contents: the common places, a few select smart entries, and the app's own
 * surfaces (recycle bin, storage & recovery, settings) — and nothing else.
 *
 * The rail used to list every open tab, so it grew with whatever the user had wandered into and
 * stopped being a map of the app. Where you currently are is now the file tree's job (the top
 * room); the rail is the short, stable list of places people actually start from.
 *
 * Pure so the shape is asserted in a test instead of eyeballed on a device.
 */
object CommonPlaces {

    const val HOME_ID = "__home__"
    const val LARGE_FILES_ID = "__large__"
    const val RECYCLE_BIN_ID = "__recycle__"
    const val RECOVERY_ID = "__recovery__"
    const val SETTINGS_ID = "__settings__"

    /** What "Large files" searches for. Parsed by [io.github.mbaliga.fylz.search.SearchQuery]. */
    const val LARGE_FILES_QUERY = "size:>100mb"

    /**
     * The standard folders worth a permanent row, in the order they read. Matched on the label
     * the storage providers already give them, so the SAF and File backends agree. Camera (DCIM)
     * is left out on purpose: Fylz is a file manager, and the photo-shaped places belong to
     * the gallery.
     */
    private val PINNED_FOLDERS = listOf("Downloads", "Documents", "Pictures", "Music", "Movies")

    fun build(groups: List<StorageRootGroup>): List<CommonPlace> {
        val roots = groups.flatMap { it.roots }
        return buildList {
            add(CommonPlace(HOME_ID, "Home", PlaceKind.HOME))
            for (label in PINNED_FOLDERS) {
                val root = roots.firstOrNull { it.title == label && it.opensDirectly } ?: continue
                add(CommonPlace(id = root.id, label = label, kind = PlaceKind.FOLDER, root = root))
            }
            // Large files needs somewhere to look, so it only appears once a device volume is
            // openable. Offering a smart entry that could only ever open an empty screen would
            // be a dead row.
            if (largeFilesRoot(groups) != null) {
                add(CommonPlace(LARGE_FILES_ID, "Large files", PlaceKind.LARGE_FILES))
            }
            // The app's own surfaces close the list. They are destinations like any other: moving
            // the wheel onto one shows it in the pane, which is why they live here and not in
            // a room of their own.
            add(CommonPlace(RECYCLE_BIN_ID, "Recycle bin", PlaceKind.RECYCLE_BIN))
            add(CommonPlace(RECOVERY_ID, "Storage & recovery", PlaceKind.RECOVERY))
            add(CommonPlace(SETTINGS_ID, "Settings", PlaceKind.SETTINGS))
        }
    }

    /** The row id of the pane a [PlaceKind] fills, or null for kinds that are not panes. */
    fun paneId(kind: PlaceKind): String? = when (kind) {
        PlaceKind.RECYCLE_BIN -> RECYCLE_BIN_ID
        PlaceKind.RECOVERY -> RECOVERY_ID
        PlaceKind.SETTINGS -> SETTINGS_ID
        else -> null
    }

    /** The volume "Large files" searches: the first openable internal volume. */
    fun largeFilesRoot(groups: List<StorageRootGroup>): StorageRoot? =
        groups.flatMap { it.roots }.firstOrNull { it.kind == StorageRootKind.INTERNAL && it.opensDirectly }

    /**
     * Which row is focused. A non-null [activePaneId] (the recycle bin, recovery or settings
     * pane) wins outright, since that pane replaces the file view. [largeFilesActive] wins over
     * the folder it is searching in, because that search is opened *inside* the device root and
     * would otherwise light the wrong row.
     */
    fun selectedId(
        places: List<CommonPlace>,
        activeTreeUri: String?,
        largeFilesActive: Boolean,
        activePaneId: String? = null,
    ): String {
        if (activePaneId != null) return activePaneId
        if (activeTreeUri == null) return HOME_ID
        if (largeFilesActive) return LARGE_FILES_ID
        return places.firstOrNull { it.root?.treeUri?.toString() == activeTreeUri }?.id ?: HOME_ID
    }
}
