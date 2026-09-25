package io.github.mbaliga.fylz.ui.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * `IconRef.Builtin.name` -> the exact `Icons.Outlined.*` vector the old code drew for that
 * built-in (design MC.0b/c, §2.6), plus the two extra state-dependent vectors (`fylz.paste`'s
 * Cut/Copy and `fylz.view.toggle`'s List/GridView) whose renderer picks the *name* from
 * [io.github.mbaliga.fylz.actions.BrowserState] rather than from a single static `IconRef`, but
 * still resolves the vector through this same map. Scoped to what the renderers built so far
 * actually look up; Rooms, Recovery, the Archive Tools menu and the gesture-only room-open actions
 * extend this table in MC.0d/e, once they have a renderer of their own.
 */
object BuiltinIcons {
    private val icons: Map<String, ImageVector> = mapOf(
        "ContentCut" to Icons.Outlined.ContentCut,
        "ContentCopy" to Icons.Outlined.ContentCopy,
        "FolderCopy" to Icons.Outlined.FolderCopy,
        "DriveFileMove" to Icons.Outlined.DriveFileMove,
        "Delete" to Icons.Outlined.Delete,
        "Edit" to Icons.Outlined.Edit,
        "Tag" to Icons.Outlined.Tag,
        "Archive" to Icons.Outlined.Archive,
        "FolderOpen" to Icons.Outlined.FolderOpen,
        "TextSnippet" to Icons.Outlined.TextSnippet,
        "PictureAsPdf" to Icons.Outlined.PictureAsPdf,
        "Share" to Icons.Outlined.Share,
        "Close" to Icons.Outlined.Close,
        "Refresh" to Icons.Outlined.Refresh,
        "CreateNewFolder" to Icons.Outlined.CreateNewFolder,
        "ArrowBack" to Icons.Outlined.ArrowBack,
        "SelectAll" to Icons.Outlined.SelectAll,
        "GridView" to Icons.Outlined.GridView,
        "List" to Icons.Outlined.List,
    )

    fun icon(name: String): ImageVector = icons[name] ?: error("No builtin icon mapped for '$name'")
}
