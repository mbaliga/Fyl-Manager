package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.FolderPeek
import io.github.mbaliga.fylz.ui.theme.FolderMaterial
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The directory grid card's face -- replaces the old folder-peek header's role in `FileCard`.
 * Every resolved directory (a [peek], loading or empty alike) hands its [FolderPeek] here; which
 * register it draws is [LocalThemeStyle]'s [FolderMaterial], not the caller's or the peek's own
 * media-bearing-ness, so a folder that resolves from empty to media-bearing (or back, on a
 * rescan) never needs its call site to branch, and every folder in a theme draws the same way.
 *
 * Four registers, one per [FolderMaterial]: an opaque body+tab for [FolderMaterial.SOLID], a
 * frosted-glass preview of the folder's own first photo or clip (or, absent one, a plain glass
 * pane) for [FolderMaterial.FROSTED], the bare file-type icon for [FolderMaterial.ICONIC], and
 * nothing at all for [FolderMaterial.TEXT] -- that theme draws folders as listing rows instead.
 *
 * internal, not public: [FolderPeek] itself is internal (FylzV1App.kt owns it, scoped no wider
 * than the app module needs), and a public function cannot expose an internal parameter type.
 * FileCard is this composable's only caller today, and it lives in the same module.
 */
@Composable
internal fun FolderFace(
    entry: FileEntry,
    peek: FolderPeek,
    modifier: Modifier = Modifier,
) {
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    when (LocalThemeStyle.current.folderMaterial) {
        FolderMaterial.SOLID -> SolidFolderFace(shownName, peek, modifier)
        FolderMaterial.FROSTED -> FrostedFolderFace(shownName, peek, modifier)
        FolderMaterial.ICONIC -> QuietFolderFace(entry, shownName, peek.itemCount, modifier)
        FolderMaterial.TEXT -> Box(modifier)
    }
}

/** "1 item" vs "12 items" -- shared by every register that still shows a count. */
private fun itemCountLabel(itemCount: Int): String = if (itemCount == 1) "1 item" else "$itemCount items"

/**
 * The dark minimal register: nothing but the file-type icon and the name -- [FolderMaterial.ICONIC]
 * (Vintage/Retro, where the pixel-art folder mark is meant to carry the whole read) and, before the
 * theme system existed, every folder with nothing thumbnailable in it (still loading, or truly
 * empty of photos and clips). Identical to what `FileCard` drew inline for every non-peek
 * directory before this file existed.
 */
@Composable
private fun QuietFolderFace(
    entry: FileEntry,
    name: String,
    itemCount: Int,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        EntryThumbnail(entry, size = 56.dp)
        Column {
            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Text(
                itemCountLabel(itemCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How much of the card's top the tab claims and how wide it runs -- the landing hero fan folder's own proportions. */
private const val FOLDER_TAB_WIDTH_FRACTION = 0.44f
private const val FOLDER_TAB_HEIGHT_FRACTION = 0.18f

/** How much of the card the body claims, bottom-anchored -- the same 0.85 the landing hero's fan folder uses. */
private const val FOLDER_BODY_HEIGHT_FRACTION = 0.85f

/**
 * The opaque register: a folder is furniture here, not a window, so nothing inside it ever bleeds
 * through no matter what the peek found. Same two-Surface tab+body vocabulary as the landing
 * hero's fan folder, so a folder in the grid and a folder on the splash read as one object.
 */
@Composable
private fun SolidFolderFace(name: String, peek: FolderPeek, modifier: Modifier) {
    val tone = MaterialTheme.colorScheme.primaryContainer
    Box(modifier.fillMaxSize()) {
        Surface(
            color = tone,
            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(FOLDER_BODY_HEIGHT_FRACTION),
        ) {}
        Surface(
            color = tone,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 10.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(FOLDER_TAB_WIDTH_FRACTION)
                .fillMaxHeight(FOLDER_TAB_HEIGHT_FRACTION),
        ) {}
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    itemCountLabel(peek.itemCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How much of the card height the frosted pane claims, bottom-anchored; the rest is where the stack peeks. */
private const val FROSTED_PANE_HEIGHT_FRACTION = 0.7f

/** Size each real thumbnail draws at in the peek band -- the ~49dp of headroom the 0.7 fraction leaves in a 164dp card. */
private val PEEK_THUMB_SIZE = 40.dp

/** Where the stack's front card starts, so it straddles the pane's top edge instead of sitting wholly above or below it. */
private val PEEK_TOP = 20.dp

/**
 * The frosted-glass register: the folder's own first media thumb, blurred full-bleed behind a
 * translucent wash, with a sharp name panel over the bottom -- same copy and colors as the header
 * this replaces. Emerging from behind the pane's top edge, up to three of the folder's own real
 * thumbnails cascade like a small print stack; if the folder also holds non-photographic files,
 * one blank [DocumentSheet] joins as the rearmost leaf of that same cascade.
 *
 * Generalised to every folder, not just media-bearing ones: a folder with nothing to blur still
 * gets the glass pane, just without a photo behind it -- a folder in this theme is a window, and
 * an empty room still has a window.
 */
@Composable
private fun FrostedFolderFace(
    name: String,
    peek: FolderPeek,
    modifier: Modifier,
) {
    // peek.hasNonMedia comes from the same folder listing that filled peek.thumbs, so it stays
    // accurate past the three-thumb cap -- a folder of ten photos and zero documents must never
    // draw a document sheet, which comparing itemCount against the capped thumb list can't tell
    // apart from a folder of three photos and seven documents.
    val hasDocuments = peek.hasNonMedia
    val specs = peek.thumbs.map { StackSpec(it, it.name, it.kind) }

    Box(modifier.fillMaxSize()) {
        if (hasDocuments) {
            // One slot further back than the cascade's own last card -- same offset step, so it
            // reads as one more leaf in the stack rather than a second, competing motif.
            DocumentSheet(
                rotation = -4f,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = (specs.size * 6).dp - 6.dp, y = PEEK_TOP + (specs.size * 4).dp)
                    .zIndex(-1f),
            )
        }
        StackedThumbs(
            items = specs,
            size = PEEK_THUMB_SIZE,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = (-6).dp, y = PEEK_TOP),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(FROSTED_PANE_HEIGHT_FRACTION),
        ) {
            // peek.thumbs can be empty here (a folder with no media, or none loaded yet) now that
            // every folder reaches this register under Glass -- fall back to a plain tinted pane
            // instead of `.first()`ing an empty list.
            if (peek.thumbs.isNotEmpty()) {
                FrostedBackdrop(peek.thumbs.first(), Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(
                        itemCountLabel(peek.itemCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One drawn "sheet" peeking from behind the frosted pane -- a rounded rectangle only, no asset.
 * Stands in for a folder's non-photographic files as the rearmost leaf of the real-thumbnail
 * cascade [FrostedFolderFace] draws above it.
 */
@Composable
private fun DocumentSheet(rotation: Float, tonalElevation: Dp, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceBright,
        tonalElevation = tonalElevation,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier
            .size(width = 28.dp, height = 20.dp)
            .graphicsLayer {
                rotationZ = rotation
            },
    ) {}
}

/**
 * The frosted pane's own backdrop image: [entry]'s provider thumbnail (or, failing that, a direct
 * decode for images) blurred and cropped full-bleed across whatever size [modifier] gives it.
 *
 * Reuses [loadProviderThumbnail] and [ThumbnailCache] rather than [EntryThumbnail] itself: that
 * composable only ever renders a fixed square at a caller-chosen [androidx.compose.ui.unit.Dp], and
 * a card face needs to crop to an arbitrary rectangle -- but it shares the exact same cache and
 * pixel budget, so a folder's face and that same file's own row thumbnail cost one decode, not two.
 */
@Composable
private fun FrostedBackdrop(entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = entry.uri) {
        val cacheKey = entry.uri.toString()
        value = ThumbnailCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            loadProviderThumbnail(context.contentResolver, entry.uri, THUMBNAIL_PIXELS)?.asImageBitmap()
        }?.also { ThumbnailCache.put(cacheKey, it) }
    }

    // blur() leaves a transparent ring at its own edge; scaling the blurred layer up 6% pushes
    // that ring outside the card's bounds instead of clipping it, which would sharpen the crop
    // line right where the blur is supposed to fall off softly.
    val backdropModifier = modifier.blur(3.dp).graphicsLayer {
        scaleX = 1.06f
        scaleY = 1.06f
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = backdropModifier,
        )
        entry.kind == EntryKind.IMAGE -> AsyncImage(
            model = entry.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = backdropModifier,
        )
        // A video whose provider skipped FLAG_SUPPORTS_THUMBNAIL: no bitmap to blur, so the wash
        // above draws over plain surface instead of nothing.
        else -> Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}
