package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionId
import io.github.mbaliga.fylz.actions.ActionTarget
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.GestureId
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.theme.SpaceGrotesk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val SELECT_TOGGLE_ID = ActionId.parse("fylz.select.toggle")

/** Folder-card redesign: how many of a folder's children [rememberFolderPreview] will read before
 * giving up on an exact count, so a folder with 10,000 files never stalls the grid.
 * `DocumentRepository.listChildren` streams in its own batches; this just stops consuming that
 * stream once it has read this many entries (or the folder turns out smaller). */
internal const val MAX_PREVIEW_SCAN = 500

/** One folder's resolved live preview: [files] is [pickFolderPreviewFiles]'s pick (up to
 * [FOLDER_PREVIEW_LIMIT]); [totalFileCount] is the folder's own non-directory child count, or
 * `null` if [MAX_PREVIEW_SCAN] was reached before the listing finished -- an exact count isn't
 * knowable within the bound, so the card says "500+ Files" instead of guessing. */
data class FolderPreview(val files: List<FileEntry>, val totalFileCount: Int?)

/**
 * Fetches and caches [folderUri]'s live preview -- lazily (nothing runs until this card is
 * actually composed), asynchronously (off the main thread), and bounded ([MAX_PREVIEW_SCAN]).
 * [cache] is the caller's (`FileBrowser` in `FylzV1App.kt`), `remember`-scoped to the exact same
 * `(activeTab's folder, refreshKey)` pair the rest of this screen already keys its own folder
 * listing's refresh on -- so a fresh `remember` (and therefore an empty cache) happens exactly
 * when that effect would re-list the folder, mirroring its invalidation rather than inventing a
 * new one.
 */
@Composable
internal fun rememberFolderPreview(
    repository: DocumentRepository,
    treeUri: Uri,
    folderUri: Uri,
    cache: MutableMap<Uri, FolderPreview>,
): FolderPreview? {
    var preview by remember(folderUri) { mutableStateOf(cache[folderUri]) }
    LaunchedEffect(folderUri) {
        if (preview != null) return@LaunchedEffect
        val batch = withContext(Dispatchers.IO) {
            repository.listChildren(treeUri, folderUri)
                .first { it.complete || it.entries.size >= MAX_PREVIEW_SCAN }
        }
        val capped = !batch.complete && batch.entries.size >= MAX_PREVIEW_SCAN
        val resolved = FolderPreview(
            files = pickFolderPreviewFiles(batch.entries),
            totalFileCount = if (capped) null else batch.entries.count { !it.isDirectory },
        )
        cache[folderUri] = resolved
        preview = resolved
    }
    return preview
}

/** "Empty" / "1 File" / "N Files" / "500+ Files" -- `null` (still loading) reads as blank rather
 * than flashing "Empty" first. Counts non-directory children only, matching the word "File":
 * subfolders get their own card, so a folder that holds only subfolders reads as "Empty" here,
 * which is this task's own judgment call (documented in docs/agent/PROGRESS.md). */
internal fun folderSubtitle(preview: FolderPreview?): String = when {
    preview == null -> ""
    preview.totalFileCount == null -> "$MAX_PREVIEW_SCAN+ Files"
    preview.totalFileCount == 0 -> "Empty"
    preview.totalFileCount == 1 -> "1 File"
    else -> "${preview.totalFileCount} Files"
}

private fun isDarkSurface(color: Color): Boolean = color.luminance() < 0.5f

/**
 * One grid cell (design: the owner's Figma folder-card export, `docs/agent/PROGRESS.md`).
 * Directories get the full glass-panel treatment via [FolderCardVisual]; plain files keep the
 * simple thumbnail-and-name surface this composable always drew (no Figma spec covers a
 * non-folder card), just with the same selection glow swapped in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    entry: FileEntry,
    selected: Boolean,
    focused: Boolean,
    dispatcher: ActionDispatcher,
    state: BrowserState,
    ctx: ActionContext,
    repository: DocumentRepository,
    treeUri: Uri,
    previewCache: MutableMap<Uri, FolderPreview>,
) {
    val target = ActionTarget.Entry(entry)
    val onClick = { dispatcher.gesture(GestureId.ITEM_TAP, target, state, ctx) }
    val onDoubleClick = { dispatcher.gesture(GestureId.ITEM_DOUBLE_TAP, target, state, ctx) }
    val onLongClick = { dispatcher.gesture(GestureId.ITEM_LONG_PRESS, target, state, ctx) }
    val onToggle = { dispatcher.run(SELECT_TOGGLE_ID, state, target, ctx) }

    if (entry.isDirectory) {
        val palette = if (isDarkSurface(MaterialTheme.colorScheme.surface)) FolderCardPalettes.Dark else FolderCardPalettes.Light
        val preview = rememberFolderPreview(repository, treeUri, entry.uri, previewCache)
        FolderCardVisual(
            entry = entry,
            previewFiles = preview?.files.orEmpty(),
            subtitle = folderSubtitle(preview),
            selected = selected,
            focused = focused,
            palette = palette,
            onClick = onClick,
            onDoubleClick = onDoubleClick,
            onLongClick = onLongClick,
            onToggleSelected = onToggle,
        )
    } else {
        val glow = MaterialTheme.colorScheme.primary
        Surface(
            color = if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .height(140.dp)
                .let { if (selected) it.selectionGlow(glow, RoundedCornerShape(12.dp)) else it }
                .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick, onLongClick = onLongClick)
                .semantics { contentDescription = entry.name },
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    EntryThumbnail(entry, size = 56.dp)
                    Spacer(Modifier.weight(1f))
                    Checkbox(selected, onCheckedChange = { onToggle() })
                }
                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The folder card's own pure presentation: icon ([FolderGlassIcon], with [previewFiles] rendered
 * through it via [EntryThumbnail] -- real thumbnails, the same pipeline every other row/card in
 * this app already uses) + the name/[subtitle] text below it, Space Grotesk per the Figma export.
 * Takes already-resolved data and plain callbacks only (no [DocumentRepository], no action
 * registry), so it renders deterministically from seeded data -- see `FolderCardPaparazziTest`.
 *
 * Selection: a blue/violet glow outline (owner's mockup) replaces the old flat
 * `secondaryContainer` tint. Colour choice: `MaterialTheme.colorScheme.primary`, this app's own
 * theme accent token (`FylzTheme.kt`) -- the brief asked for an existing token rather than a new
 * unrelated colour, and `primary` is exactly what "the app's accent colour" already means here
 * (every `ThemePalette` sets it, light or dark, custom or built-in).
 *
 * The checkbox the old card always drew is NOT in the Figma export, but stays, in the icon's
 * top-start corner: it is the only way to add an item to an in-progress selection without
 * long-pressing it (`fylz.select.toggle` is wired only to `GestureId.ITEM_LONG_PRESS`; a plain tap
 * always opens the entry, selected or not), so dropping it would be a functional regression, not
 * just a visual one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCardVisual(
    entry: FileEntry,
    previewFiles: List<FileEntry>,
    subtitle: String,
    selected: Boolean,
    palette: FolderCardPalette,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelected: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
) {
    val glow = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .let { if (selected) it.selectionGlow(glow, shape) else it }
            // Keyboard focus is an accent-role state too: a softer accent ring than the selection glow.
            .let { if (focused && !selected) it.border(2.dp, glow.copy(alpha = 0.5f), shape) else it }
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick, onLongClick = onLongClick)
            .padding(10.dp)
            .semantics { contentDescription = "Folder ${entry.name}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            FolderGlassIcon(palette = palette, modifier = Modifier.fillMaxWidth()) { width, height ->
                if (previewFiles.isNotEmpty()) {
                    Row(
                        Modifier.size(width, height),
                        horizontalArrangement = Arrangement.spacedBy((-6).dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        previewFiles.forEach { file ->
                            EntryThumbnail(
                                file,
                                size = height * 0.72f,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }
            }
            Checkbox(selected, onCheckedChange = { onToggleSelected() }, modifier = Modifier.align(Alignment.TopStart))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.name,
            style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = palette.title, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            subtitle,
            style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = palette.subtitle, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A soft coloured glow (a blurred, tinted shadow) plus a crisp border, both [color] -- Compose's
 * own stand-in for the mockup's blue/violet outline glow; `Modifier.shadow`'s `ambientColor`/
 * `spotColor` (API 28+, well under this app's minSdk 31) draw a real soft-edged coloured shadow
 * rather than a flat ring. */
private fun Modifier.selectionGlow(color: Color, shape: RoundedCornerShape): Modifier = this
    .shadow(elevation = 14.dp, shape = shape, ambientColor = color, spotColor = color)
    .border(2.dp, color, shape)
