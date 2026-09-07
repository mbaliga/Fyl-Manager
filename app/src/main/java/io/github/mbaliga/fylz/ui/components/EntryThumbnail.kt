package io.github.mbaliga.fylz.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.appearance.FolderFinish
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real thumbnails for list and grid rows, replacing the static vector icon that
 * `FileRowV1` and `FileCard` used for every single file regardless of type.
 *
 * Resolution order, cheapest first:
 *
 * 1. `ContentResolver.loadThumbnail` -- the provider's own thumbnail, which is what
 *    `DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL` advertises. The `full` flavor's
 *    `FylzFilesDocumentsProvider` implements `openDocumentThumbnail` for images and video, and
 *    `ExternalStorageProvider`/MediaStore do the same, so this covers both flavors and is the only
 *    path that produces a *video* frame.
 * 2. Coil for images the provider did not thumbnail. Coil decodes `content://` images directly and
 *    is already a dependency (previously used only by `RichImagePreview`).
 * 3. [loadPdfThumbnail] for PDFs the provider did not thumbnail -- most providers only advertise
 *    `FLAG_SUPPORTS_THUMBNAIL` for images and video, so this is the only path that produces a PDF
 *    thumbnail at all. Renders the first page with `PdfRenderer`, same as step 1's provider-side
 *    thumbnail in every other respect (bounded, cached, IO-dispatched).
 * 4. The type icon.
 *
 * Every step is bounded: thumbnails are requested at [size], never full resolution, satisfying
 * docs/product/preview-and-recycle-bin-contract.md's "must never load an unbounded file into
 * memory" rule.
 *
 * Provider thumbnails (step 1) are cached in [ThumbnailCache] by URI, so a row that recomposes --
 * or a folder-grid card asking for the same three thumbnails on every peek -- re-queries the
 * resolver once, not on every recomposition.
 *
 * A video entry hands off to [VideoMotionThumbnail] instead of steps 1-3 above when
 * [LocalAutoAnimate] is on -- that composable reuses this same provider-thumbnail path as its own
 * static fallback, so the two never disagree about what a still video thumbnail looks like.
 *
 * @param pixels the square side requested from [loadProviderThumbnail], [THUMBNAIL_PIXELS] by
 *   default. A caller drawing a tile well past that default's native size (the canvas and bento
 *   surfaces) can ask for more so the provider decodes at the size it is actually shown, instead
 *   of a 192px thumbnail stretched soft across a much larger tile. Folded into the cache key only
 *   when it differs from the default, so every existing call site keeps sharing the plain
 *   bare-URI cache entries it always has.
 */
@Composable
fun EntryThumbnail(
    entry: FileEntry,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    pixels: Int = THUMBNAIL_PIXELS,
) {
    if (entry.kind == EntryKind.VIDEO && !entry.isDirectory && LocalAutoAnimate.current) {
        VideoMotionThumbnail(entry, size, modifier)
        return
    }

    val context = LocalContext.current
    val thumbnailable = !entry.isDirectory &&
        (entry.kind == EntryKind.IMAGE || entry.kind == EntryKind.VIDEO)
    // PDFs get their own attempt, after the provider's own thumbnail and before the icon
    // fallback -- kept out of [thumbnailable] itself since that flag also drives the Coil
    // fallback a few lines down, and Coil has no PDF decoder to fall back to.
    val isPdf = !entry.isDirectory && entry.kind == EntryKind.PDF

    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = entry.uri,
        key2 = thumbnailable,
        key3 = pixels,
    ) {
        if (!thumbnailable && !isPdf) {
            value = null
            return@produceState
        }
        val cacheKey = if (pixels != THUMBNAIL_PIXELS) "${entry.uri}@$pixels" else entry.uri.toString()
        val cached = ThumbnailCache.get(cacheKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val provided = loadProviderThumbnail(context.contentResolver, entry.uri, pixels)
            (provided ?: if (isPdf) loadPdfThumbnail(context.contentResolver, entry.uri, pixels) else null)
                ?.asImageBitmap()
        }?.also { ThumbnailCache.put(cacheKey, it) }
    }

    // Resolved once for the whole `when` below: a folder's own record decides both which branch
    // runs (a chosen material outranks the icon) and what that branch paints.
    val folderAppearance = if (entry.isDirectory) LocalFolderAppearance.current(entry.uri) else null
    val folderFinish = FolderFinish.fromSlug(folderAppearance?.finishSlug)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(MaterialTheme.shapes.small),
            )

            thumbnailable && entry.kind == EntryKind.IMAGE -> AsyncImage(
                model = entry.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(MaterialTheme.shapes.small),
            )

            // A folder given a material draws AS that material here, not just on the grid card's
            // big face. This is the branch that decides what a folder looks like in a list row, a
            // details row, a picker and a breadcrumb -- everywhere but the two places FolderFace
            // reaches -- so a finish that stopped at FolderFace would be a setting the user could
            // choose and then not see anywhere they actually browse.
            folderFinish != null -> FolderPlane(
                finish = folderFinish,
                tone = folderTone(folderAppearance, MaterialTheme.colorScheme.primaryContainer),
                shape = remember { FolderSilhouetteShape() },
                dark = isDarkSurface(),
                // The same inset the drawn icons carry, so a finished folder sits on the row's
                // baseline exactly where the SVG it replaces did.
                modifier = Modifier.size(size * ICON_SCALE),
            )

            else -> {
                val style = LocalIconStyle.current
                // Only a directory can carry an icon override -- FolderAppearance is per-folder,
                // not per-file, so a plain file never looks this up.
                val overrideKey = folderAppearance?.iconKey
                val asset = remember(entry.name, entry.mimeType, entry.kind, style, overrideKey) {
                    val path = if (overrideKey != null) {
                        FileTypeIcons.assetPath(overrideKey, style)
                    } else {
                        val descriptor = FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
                        FileTypeIcons.assetPath(descriptor.extension, descriptor.family, style)
                    }
                    "file:///android_asset/$path"
                }
                AsyncImage(
                    model = asset,
                    contentDescription = null,
                    // Fit, not Crop: these are drawn marks with their own margins, and cropping
                    // one to a square eats the folded corner that distinguishes the format.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(size * ICON_SCALE),
                )
            }
        }
    }
}

/**
 * Asks the document provider for a bounded thumbnail. Returns null -- rather than throwing -- for
 * providers that do not implement `openDocumentThumbnail`, which is the common case for
 * third-party cloud providers.
 *
 * Internal rather than private: [VideoMotionThumbnail] reuses it verbatim for its own static
 * frame, so a provider that changes how it thumbnails a video changes both places at once.
 */
internal fun loadProviderThumbnail(
    resolver: android.content.ContentResolver,
    uri: Uri,
    pixels: Int,
): Bitmap? = runCatching {
    resolver.loadThumbnail(uri, Size(pixels, pixels), CancellationSignal())
}.getOrNull()

/**
 * Renders a PDF's first page to a bounded [Bitmap], for the entries [loadProviderThumbnail]
 * leaves empty -- most document providers advertise `FLAG_SUPPORTS_THUMBNAIL` for images and
 * video only, not PDFs.
 *
 * Sized to fit within a [pixels] square, preserving the page's own aspect ratio rather than
 * stretching it -- a page is usually taller than it is wide, so a square crop would otherwise cut
 * off its edges. The bitmap is filled white before rendering: [PdfRenderer] draws a page's content
 * onto whatever is already there, and a page's own background is transparent, not white.
 *
 * File descriptor, page and renderer are all opened and closed strictly in this one call via
 * nested [use] blocks, so a decode failure partway through never leaks any of the three.
 */
private fun loadPdfThumbnail(
    resolver: android.content.ContentResolver,
    uri: Uri,
    pixels: Int,
): Bitmap? = runCatching {
    resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF has no pages." }
            renderer.openPage(0).use { page ->
                val longSide = maxOf(page.width, page.height).coerceAtLeast(1)
                val scale = pixels.toFloat() / longSide
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }
}.getOrNull()

/**
 * Process-wide LRU of decoded provider thumbnails, keyed by URI string.
 *
 * Without this, `produceState` re-hits the content resolver on every recomposition -- and a
 * folder-grid card that peeks three children re-fires that query for the same three URIs every
 * time the grid recomposes. `LinkedHashMap`'s `accessOrder = true` constructor plus overriding
 * [LinkedHashMap.removeEldestEntry] is the standard bounded-LRU idiom; a plain `Map` would grow
 * without bound over a long browsing session.
 */
internal object ThumbnailCache {
    private const val MAX_ENTRIES = 64
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): ImageBitmap? = cache[key]

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        cache[key] = bitmap
    }
}

/**
 * A thumbnail for a bare [Uri], for callers that hold one and never see a [FileEntry] -- the
 * Activity overlay's preview fan, whose data comes up from the operations layer as opaque URIs.
 *
 * Deliberately the PROVIDER path only ([loadProviderThumbnail]), not the full [EntryThumbnail]
 * ladder: without an entry there is no `kind` to route a PDF render or a type icon by, and
 * guessing one from the URI is exactly the document-ID parsing the app forbids everywhere else.
 * Null means "nothing to show for this one" and the caller draws no tile rather than a
 * placeholder -- a document with no thumbnail behind it must not borrow someone else's picture.
 *
 * Shares [ThumbnailCache] with every listing row, so an operation over files the user was just
 * looking at re-reads nothing.
 */
@Composable
fun rememberUriThumbnail(uri: Uri, pixels: Int = THUMBNAIL_PIXELS): ImageBitmap? {
    val context = LocalContext.current
    val key = remember(uri, pixels) { if (pixels == THUMBNAIL_PIXELS) uri.toString() else "$uri@$pixels" }
    val cached = ThumbnailCache.get(key)
    val loaded by produceState<ImageBitmap?>(initialValue = cached, key) {
        if (cached != null) return@produceState
        value = withContext(Dispatchers.IO) {
            loadProviderThumbnail(context.contentResolver, uri, pixels)?.asImageBitmap()
        }?.also { ThumbnailCache.put(key, it) }
    }
    return loaded
}

/**
 * The icon treatment in force for this subtree.
 *
 * A composition local rather than a parameter because [EntryThumbnail] is reached from the list
 * row, the grid card, the folder-peek strip and the in-app picker; threading a display preference
 * through four unrelated call chains to reach one leaf is exactly what locals are for. Defaults to
 * [IconStyle.DEFAULT] so a preview or a test that does not provide one still draws something.
 */
val LocalIconStyle: ProvidableCompositionLocal<IconStyle> = compositionLocalOf { IconStyle.DEFAULT }

/** Scopes [content] to an icon [style]; the composition root provides the persisted preference. */
@Composable
fun ProvideIconStyle(style: IconStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIconStyle provides style, content = content)
}

internal const val THUMBNAIL_PIXELS = 192
internal const val ICON_SCALE = 0.7f
