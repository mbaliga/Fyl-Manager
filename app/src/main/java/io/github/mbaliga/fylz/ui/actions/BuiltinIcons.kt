package io.github.mbaliga.fylz.ui.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * `IconRef.Builtin.name` -> the exact `Icons.Outlined.*` vector the old code drew for that
 * built-in (design MC.0b/§2.6). Scoped to the names the renderers built so far actually look up;
 * a handful of icons stay state-dependent (the clipboard chip's Cut/Copy, the view toggle's
 * List/GridView, the sort menu's direction arrows) and are resolved straight off
 * [io.github.mbaliga.fylz.actions.BrowserState] by their own renderer instead, since one static
 * [io.github.mbaliga.fylz.actions.IconRef] can't represent them. Rooms, Recovery, the Archive
 * Tools menu and the gesture-only room-open actions extend this table in MC.0d/e, once they have
 * a renderer of their own.
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
    )

    fun icon(name: String): ImageVector = icons[name] ?: error("No builtin icon mapped for '$name'")
}
