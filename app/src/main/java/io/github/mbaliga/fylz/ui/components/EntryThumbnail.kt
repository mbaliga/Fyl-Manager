package io.github.mbaliga.fylz.ui.components

import android.graphics.Bitmap
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
 * 3. The type icon.
 *
 * Every step is bounded: thumbnails are requested at [size], never full resolution, satisfying
 * docs/product/preview-and-recycle-bin-contract.md's "must never load an unbounded file into
 * memory" rule.
 *
 * Provider thumbnails (step 1) are cached in [ThumbnailCache] by URI, so a row that recomposes --
 * or a folder-grid card asking for the same three thumbnails on every peek -- re-queries the
 * resolver once, not on every recomposition.
 */
@Composable
fun EntryThumbnail(
    entry: FileEntry,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbnailable = !entry.isDirectory &&
        (entry.kind == EntryKind.IMAGE || entry.kind == EntryKind.VIDEO)

    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = entry.uri, key2 = thumbnailable) {
        if (!thumbnailable) {
            value = null
            return@produceState
        }
        val cacheKey = entry.uri.toString()
        val cached = ThumbnailCache.get(cacheKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            loadProviderThumbnail(context.contentResolver, entry.uri, THUMBNAIL_PIXELS)?.asImageBitmap()
        }?.also { ThumbnailCache.put(cacheKey, it) }
    }

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

            else -> {
                val style = LocalIconStyle.current
                val asset = remember(entry.name, entry.mimeType, entry.kind, style) {
                    val descriptor = FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
                    "file:///android_asset/" + FileTypeIcons.assetPath(descriptor.extension, descriptor.family, style)
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
 */
private fun loadProviderThumbnail(
    resolver: android.content.ContentResolver,
    uri: Uri,
    pixels: Int,
): Bitmap? = runCatching {
    resolver.loadThumbnail(uri, Size(pixels, pixels), CancellationSignal())
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
private object ThumbnailCache {
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

private const val THUMBNAIL_PIXELS = 192
private const val ICON_SCALE = 0.7f
