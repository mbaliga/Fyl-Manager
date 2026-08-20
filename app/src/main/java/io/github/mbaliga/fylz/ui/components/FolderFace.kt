package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.appearance.FolderAppearance
import io.github.mbaliga.fylz.appearance.FolderPalette
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.FolderPeek
import io.github.mbaliga.fylz.ui.theme.FolderMaterial
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ShadowLevel
import io.github.mbaliga.fylz.ui.theme.softShadow
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
 *
 * @param showLabel whether the SOLID/FROSTED/ICONIC registers draw their own internal name+count
 *   band over the face. Defaults true -- [io.github.mbaliga.fylz.ui.canvas.BentoMosaic] and every
 *   other caller that hands this composable a bare cell still wants the label drawn inline,
 *   exactly as before. Callers that
 *   present the name and count themselves outside the face (`FolderGridCell`'s own centered
 *   [CountChip], [FolderHero]'s name/count beneath the hero face) pass `false` so the two don't
 *   double up.
 */
@Composable
internal fun FolderFace(
    entry: FileEntry,
    peek: FolderPeek,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val shownName = displayName(entry.name, entry.isDirectory, LocalShowExtensions.current)
    when (LocalThemeStyle.current.folderMaterial) {
        FolderMaterial.SOLID -> SolidFolderFace(entry, shownName, peek, modifier, showLabel)
        FolderMaterial.FROSTED -> FrostedFolderFace(entry, shownName, peek, modifier, showLabel)
        FolderMaterial.ICONIC -> QuietFolderFace(entry, shownName, peek.itemCount, modifier, showLabel)
        FolderMaterial.TEXT -> Box(modifier)
    }
}

/**
 * A folder's chosen icon/colour/stickers, looked up by uri -- null when nothing was ever set, in
 * which case every register below draws exactly what it always drew. A function rather than a
 * plain map so the composition root can back it with whatever cache and invalidation (an
 * `appearanceVersion` counter, the `tagsVersion` idiom) it needs without every reader here caring
 * how; called straight from composition rather than behind a `remember`, so a change becomes
 * visible the same recomposition the counter bumps. Defaults to "nothing is ever customised" so a
 * preview or a test that never provides one still draws the theme's plain default.
 */
val LocalFolderAppearance: ProvidableCompositionLocal<(Uri) -> FolderAppearance?> =
    compositionLocalOf { { _: Uri -> null } }

/** True once the active [MaterialTheme] colour scheme reads as a dark one -- how [FolderPalette] picks its tone. */
@Composable
private fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** [FolderAppearance.colorSlug] resolved against the live scheme, or [fallback] absent an override or an unrecognised slug. */
@Composable
private fun folderTone(appearance: FolderAppearance?, fallback: Color): Color {
    val dark = isDarkSurface()
    return appearance?.colorSlug?.let { FolderPalette.colorFor(it, dark) } ?: fallback
}

/** "1 item" vs "12 items" -- shared by every register that still shows a count. */
private fun itemCountLabel(itemCount: Int): String = if (itemCount == 1) "1 item" else "$itemCount items"

/**
 * The dark minimal register: nothing but the file-type icon and the name -- [FolderMaterial.ICONIC]
 * (Vintage/Retro, where the pixel-art folder mark is meant to carry the whole read) and, before the
 * theme system existed, every folder with nothing thumbnailable in it (still loading, or truly
 * empty of photos and clips). Identical to what `FileCard` drew inline for every non-peek
 * directory before this file existed.
 *
 * [entry] also resolves [LocalFolderAppearance]'s colour override: this register has no surface
 * of its own to tint, so a chosen colour draws as a disc behind the icon instead -- undrawn
 * entirely absent an override, so a folder nobody customised looks exactly as it always has.
 */
@Composable
private fun QuietFolderFace(
    entry: FileEntry,
    name: String,
    itemCount: Int,
    modifier: Modifier,
    showLabel: Boolean,
) {
    val dark = isDarkSurface()
    val tone = LocalFolderAppearance.current(entry.uri)?.colorSlug?.let { FolderPalette.colorFor(it, dark) }
    val icon = @Composable {
        Box(contentAlignment = Alignment.Center) {
            if (tone != null) {
                Box(Modifier.size(64.dp).background(tone, CircleShape))
            }
            EntryThumbnail(entry, size = 56.dp)
        }
    }
    if (showLabel) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            icon()
            Column {
                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    itemCountLabel(itemCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon() }
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
 *
 * [entry] is read only for [LocalFolderAppearance]'s colour override -- [SolidFolderFace] never
 * had a way to see which folder it was drawing before, so every folder painted the same
 * `primaryContainer` tone regardless of what its own record (if any) asked for.
 */
@Composable
private fun SolidFolderFace(entry: FileEntry, name: String, peek: FolderPeek, modifier: Modifier, showLabel: Boolean) {
    val appearance = LocalFolderAppearance.current(entry.uri)
    val tone = folderTone(appearance, MaterialTheme.colorScheme.primaryContainer)
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
        if (showLabel) {
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

/** Size each real thumbnail draws at in the peek band. */
private val PEEK_THUMB_SIZE = 40.dp

/**
 * Where the peek cascade's front card sits, negative so it visibly overflows the silhouette's own
 * top edge instead of nesting inside it -- Build 11.5's "peeks break the silhouette" language
 * (frames 1/3), replacing the old positive [Dp] that tucked the stack's top card just inside the
 * pane.
 */
private val PEEK_BREAK_Y = (-10).dp

/** Per-card fan -- frames 1/3's own -8/+6/-4deg cascade, each card nudged a little further right and down than the last. */
private val PEEK_TILTS = floatArrayOf(-8f, 6f, -4f)
private val PEEK_OFFSETS_X = listOf((-8).dp, 0.dp, 8.dp)
private val PEEK_OFFSETS_Y = listOf(0.dp, 3.dp, 6.dp)

/** Matches [StackCard]'s own hardcoded corner radius, so the soft shadow wrapped around each peek traces the same silhouette the card itself draws. */
private val PEEK_CARD_SHAPE = RoundedCornerShape(10.dp)

/**
 * The frosted-glass register: a die-cut sticker -- the folder's own tab+body silhouette
 * ([FolderSilhouetteShape], the same proportions [SolidFolderFace] draws), white-outlined and
 * floating on a soft [ShadowLevel.MD] shadow, filled with the folder's own first media thumb
 * blurred full-bleed behind a translucent wash. Up to three of the folder's own real thumbnails
 * cascade above the silhouette's top edge, fanned and visibly overflowing it rather than sitting
 * inside -- frames 1/3's "content peek breaks the folder's silhouette" read; if the folder also
 * holds non-photographic files, one blank [DocumentSheet] joins as the rearmost leaf of that same
 * cascade.
 *
 * Generalised to every folder, not just media-bearing ones: a folder with nothing to blur still
 * gets the glass silhouette, just without a photo behind it -- a folder in this theme is a window,
 * and an empty room still has a window.
 *
 * [entry] is read for [LocalFolderAppearance]'s own record -- before it reached this branch too,
 * a Fylz folder could never wear the colour or stickers it was given, no matter what the picker
 * in Settings wrote for it. [FolderAppearance.colorSlug] tints the wash itself (both the plain
 * empty silhouette and the layer sitting over a real photo), which is what "a much wider range of
 * colours" on a *translucent* folder actually means -- a flat tint under Neo, a tinted pane of
 * glass here. Stickers draw above that wash and below the name panel, so they read as sitting on
 * the glass rather than under it.
 */
@Composable
private fun FrostedFolderFace(
    entry: FileEntry,
    name: String,
    peek: FolderPeek,
    modifier: Modifier,
    showLabel: Boolean,
) {
    // peek.hasNonMedia comes from the same folder listing that filled peek.thumbs, so it stays
    // accurate past the three-thumb cap -- a folder of ten photos and zero documents must never
    // draw a document sheet, which comparing itemCount against the capped thumb list can't tell
    // apart from a folder of three photos and seven documents.
    val hasDocuments = peek.hasNonMedia
    val specs = peek.thumbs.map { StackSpec(it, it.name, it.kind) }
    val appearance = LocalFolderAppearance.current(entry.uri)
    val washTone = folderTone(appearance, MaterialTheme.colorScheme.surfaceContainerHigh)
    val stickerOutline = if (isDarkSurface()) MaterialTheme.colorScheme.surfaceBright else Color.White
    val silhouette = remember { FolderSilhouetteShape() }

    Box(modifier.fillMaxSize()) {
        if (hasDocuments) {
            // One slot further back than the cascade's own last card -- same offset step, so it
            // reads as one more leaf in the stack rather than a second, competing motif.
            DocumentSheet(
                rotation = -4f,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = (specs.size * 6).dp - 6.dp, y = PEEK_BREAK_Y + (specs.size * 3).dp)
                    .zIndex(-1f),
            )
        }
        // Drawn directly rather than through StackedThumbs: each card here wants its own
        // softShadow wrapper for the "tiny shadow" sticker look, which StackedThumbs has no
        // per-card modifier hook to add.
        specs.take(3).forEachIndexed { index, spec ->
            StackCard(
                entry = spec.entry,
                fallbackName = spec.fallbackName,
                kind = spec.kind,
                size = PEEK_THUMB_SIZE,
                rotation = PEEK_TILTS.getOrElse(index) { 0f },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = PEEK_OFFSETS_X.getOrElse(index) { 0.dp }, y = PEEK_BREAK_Y + PEEK_OFFSETS_Y.getOrElse(index) { 0.dp })
                    .zIndex((3 - index).toFloat())
                    .softShadow(ShadowLevel.SM, PEEK_CARD_SHAPE),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .softShadow(ShadowLevel.MD, silhouette)
                .clip(silhouette)
                .border(3.dp, stickerOutline, silhouette),
        ) {
            // peek.thumbs can be empty here (a folder with no media, or none loaded yet) now that
            // every folder reaches this register under Fylz -- fall back to a plain tinted pane
            // instead of `.first()`ing an empty list.
            if (peek.thumbs.isNotEmpty()) {
                FrostedBackdrop(peek.thumbs.first(), Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(washTone))
            }
            Box(Modifier.fillMaxSize().background(washTone.copy(alpha = 0.55f)))
            if (appearance != null && appearance.stickers.isNotEmpty()) {
                FolderStickerLayer(appearance.stickers, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            if (showLabel) {
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
}

/**
 * The folder's own tab+body silhouette as one outline -- the exact two rectangles
 * [SolidFolderFace] draws as separate `Surface`s ([FOLDER_TAB_WIDTH_FRACTION] /
 * [FOLDER_TAB_HEIGHT_FRACTION] / [FOLDER_BODY_HEIGHT_FRACTION]), unioned into a single [Path] so
 * [FrostedFolderFace] can clip, stroke and shadow one silhouette instead of two independently
 * bordered rectangles. The tab's own height fraction already runs past where the body starts
 * ([FOLDER_TAB_HEIGHT_FRACTION] 0.18 vs. the body's own top at `1 - FOLDER_BODY_HEIGHT_FRACTION`
 * = 0.15), so the union has no seam to paper over.
 */
private class FolderSilhouetteShape(
    private val tabWidthFraction: Float = FOLDER_TAB_WIDTH_FRACTION,
    private val tabHeightFraction: Float = FOLDER_TAB_HEIGHT_FRACTION,
    private val bodyHeightFraction: Float = FOLDER_BODY_HEIGHT_FRACTION,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        with(density) {
            val bodyRadius = FylzGeometry.RadiusLg.toPx()
            val tabRadius = FylzGeometry.RadiusSm.toPx()
            val bodyTop = size.height * (1f - bodyHeightFraction)
            val body = Path().apply {
                addRoundRect(RoundRect(Rect(0f, bodyTop, size.width, size.height), CornerRadius(bodyRadius)))
            }
            val tabWidth = size.width * tabWidthFraction
            val tabHeight = size.height * tabHeightFraction
            val tab = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(0f, 0f, tabWidth, tabHeight),
                        topLeft = CornerRadius(tabRadius),
                        topRight = CornerRadius(bodyRadius),
                        bottomRight = CornerRadius.Zero,
                        bottomLeft = CornerRadius.Zero,
                    ),
                )
            }
            val union = Path().apply { op(body, tab, PathOperation.Union) }
            Outline.Generic(union)
        }
}

/** How many of a folder's stickers actually draw -- past this the cascade would crowd the name panel below it. */
private const val STICKERS_DRAWN = 3
private val STICKER_SIZE = 22.dp
private val STICKER_BORDER_WIDTH = 2.dp

/**
 * The chosen sticker set, drawn as a small overlapping row -- SVGs from `assets/stickers/`, never
 * emoji (see [io.github.mbaliga.fylz.appearance.FolderStickers]). Only [FrostedFolderFace] ever
 * calls this: it is the one register with a pane of glass for a sticker to sit on.
 *
 * Each sticker gets the same 2dp white (surfaceBright in dark) die-cut border the peek thumbnails
 * wear -- Build 11.5's sticker treatment applied uniformly, so a folder's front-facing stickers and
 * its peeking photos read as the same kind of object rather than two different affordances.
 */
@Composable
private fun FolderStickerLayer(stickers: List<String>, modifier: Modifier) {
    val borderColor = if (isDarkSurface()) MaterialTheme.colorScheme.surfaceBright else Color.White
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        stickers.take(STICKERS_DRAWN).forEach { key ->
            Box(
                Modifier
                    .size(STICKER_SIZE)
                    .softShadow(ShadowLevel.SM, CircleShape)
                    .clip(CircleShape)
                    .background(borderColor)
                    .border(STICKER_BORDER_WIDTH, borderColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = "file:///android_asset/stickers/$key.svg",
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(STICKER_SIZE - STICKER_BORDER_WIDTH * 2),
                )
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
