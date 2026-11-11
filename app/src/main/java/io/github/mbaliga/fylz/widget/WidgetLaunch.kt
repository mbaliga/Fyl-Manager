package io.github.mbaliga.fylz.widget

/**
 * Where a widget tap asks [io.github.mbaliga.fylz.MainActivity] to land.
 *
 * MainActivity is exported (it is the launcher activity), so any app can start it with any extras.
 * [WidgetLaunch.parse] therefore accepts only these shapes, a folder only from the standard list
 * and a volume only from the volumes mounted right now; anything else is ignored and the app opens
 * normally.
 */
sealed interface WidgetDestination {
    /** The storage home: volumes, standard folders and providers. */
    data object Home : WidgetDestination

    /** The Recovery room (operations, file history, backups, archives). */
    data object Recovery : WidgetDestination

    /** The Recycle Bin dialog. */
    data object RecycleBin : WidgetDestination

    /** A standard folder on the primary volume, by its `Environment.DIRECTORY_*` value. */
    data class Folder(val directory: String) : WidgetDestination

    /** The root of a mounted volume, by its FylzFilesDocumentsProvider root id. */
    data class Volume(val rootId: String) : WidgetDestination
}

object WidgetLaunch {
    const val EXTRA_DESTINATION = "io.github.mbaliga.fylz.widget.extra.DESTINATION"
    const val EXTRA_FOLDER = "io.github.mbaliga.fylz.widget.extra.FOLDER"
    const val EXTRA_VOLUME = "io.github.mbaliga.fylz.widget.extra.VOLUME"

    const val HOME = "home"
    const val RECOVERY = "recovery"
    const val RECYCLE_BIN = "recycle_bin"
    const val FOLDER = "folder"
    const val VOLUME = "volume"

    /** The extras that encode [destination]; the inverse of [parse]. */
    fun extras(destination: WidgetDestination): Map<String, String> = when (destination) {
        WidgetDestination.Home -> mapOf(EXTRA_DESTINATION to HOME)
        WidgetDestination.Recovery -> mapOf(EXTRA_DESTINATION to RECOVERY)
        WidgetDestination.RecycleBin -> mapOf(EXTRA_DESTINATION to RECYCLE_BIN)
        is WidgetDestination.Folder -> mapOf(EXTRA_DESTINATION to FOLDER, EXTRA_FOLDER to destination.directory)
        is WidgetDestination.Volume -> mapOf(EXTRA_DESTINATION to VOLUME, EXTRA_VOLUME to destination.rootId)
    }

    /**
     * @param allowedFolders the standard directories a tap may open.
     * @param mountedVolumes root ids of the volumes mounted now.
     * @return null when the extras do not describe a valid destination.
     */
    fun parse(
        destination: String?,
        folder: String?,
        volume: String?,
        allowedFolders: Set<String>,
        mountedVolumes: Set<String>,
    ): WidgetDestination? = when (destination) {
        HOME -> WidgetDestination.Home
        RECOVERY -> WidgetDestination.Recovery
        RECYCLE_BIN -> WidgetDestination.RecycleBin
        FOLDER -> folder?.takeIf { it in allowedFolders }?.let(WidgetDestination::Folder)
        VOLUME -> volume?.takeIf { it in mountedVolumes }?.let(WidgetDestination::Volume)
        else -> null
    }
}
