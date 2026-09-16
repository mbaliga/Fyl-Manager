package io.github.mbaliga.fylz.ui.components

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Whether previews are allowed to move on their own: video thumbnails cycle frames here, and
 * [io.github.mbaliga.fylz.ui.components.QuickLook] autoplays video/GIF content under the same
 * flag. Mirrors [LocalIconStyle] -- a display preference threaded from the settings store down to
 * every leaf that draws a thumbnail, rather than a parameter plumbed through the row, the grid
 * card and the folder-peek strip separately. Defaults to false so a preview or a test that does
 * not provide one still shows a still image.
 */
val LocalAutoAnimate: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Scopes [content] to whether previews auto-animate; the composition root provides the persisted preference. */
@Composable
fun ProvideAutoAnimate(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAutoAnimate provides enabled, content = content)
}

/** How far into the clip each cycled frame is sampled, as a fraction of duration. */
private val VIDEO_FRAME_FRACTIONS = listOf(0.125f, 0.375f, 0.625f, 0.875f)

/** How long each frame holds before the cycle advances. Slow enough to read as a hint, not a flip-book. */
private const val VIDEO_FRAME_CYCLE_MS = 600L

/**
 * Caps how many rows can be pulling frames from [MediaMetadataRetriever] at once. Extraction opens
 * a codec per call; a grid that scrolls past a few dozen videos in one fling would otherwise fire
 * that many decodes simultaneously and starve the device the moment auto-animate is turned on.
 */
private val VIDEO_FRAME_EXTRACTION_PERMITS = Semaphore(permits = 2)

/**
 * A video thumbnail that cycles four frames sampled across the clip instead of sitting on one.
 *
 * The static provider thumbnail (the same one [EntryThumbnail] would otherwise show) is what's on
 * screen the instant this composes and for as long as extraction takes, so turning auto-animate on
 * never produces a blank tile -- it upgrades a still that was already there into motion. A file
 * that fails to extract (corrupt container, unsupported codec, permission denial) is remembered
 * as an empty frame list and stays on that still permanently; the negative entry matters as much
 * as the positive one, because a row scrolled off-screen and back recomposes from scratch, and
 * without it every pass over a doomed file would burn an extraction permit that a decodable
 * neighbour was waiting on.
 */
@Composable
fun VideoMotionThumbnail(
    entry: FileEntry,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val staticFrame by produceState<ImageBitmap?>(initialValue = null, key1 = entry.uri) {
        val cacheKey = entry.uri.toString()
        value = ThumbnailCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            loadProviderThumbnail(context.contentResolver, entry.uri, THUMBNAIL_PIXELS)?.asImageBitmap()
        }?.also { ThumbnailCache.put(cacheKey, it) }
    }

    val motionFrames by produceState<List<ImageBitmap>?>(initialValue = null, key1 = entry.uri) {
        val cacheKey = entry.uri.toString()
        val cached = VideoFrameCache.get(cacheKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = VIDEO_FRAME_EXTRACTION_PERMITS.withPermit {
            withContext(Dispatchers.IO) { extractMotionFrames(context, entry.uri) }
        }.orEmpty().also { VideoFrameCache.put(cacheKey, it) }
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val frames = motionFrames
        val still = staticFrame
        when {
            !frames.isNullOrEmpty() -> {
                var index by remember(entry.uri) { mutableIntStateOf(0) }
                LaunchedEffect(entry.uri, frames) {
                    while (true) {
                        delay(VIDEO_FRAME_CYCLE_MS)
                        index = (index + 1) % frames.size
                    }
                }
                Image(
                    bitmap = frames[index],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(MaterialTheme.shapes.small),
                )
            }

            still != null -> Image(
                bitmap = still,
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
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(size * ICON_SCALE),
                )
            }
        }
    }
}

/**
 * Pulls four frames at [VIDEO_FRAME_FRACTIONS] of the clip's duration. Returns null -- never
 * throws -- so the caller's only job is to fall back to the static thumbnail; a duration Android
 * cannot report or a codec that refuses one of the four timestamps is exactly as unusable as a
 * file `MediaMetadataRetriever` cannot open at all.
 */
private fun extractMotionFrames(context: Context, uri: Uri): List<ImageBitmap>? = runCatching {
    // `.release()` rather than `.use {}`: MediaMetadataRetriever only picked up AutoCloseable in
    // API 29, and release() is the one cleanup path guaranteed across every minSdk this targets.
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        require(durationMs != null && durationMs > 0) { "No usable duration for $uri." }
        VIDEO_FRAME_FRACTIONS.map { fraction ->
            val timeUs = (durationMs * fraction * 1_000).toLong()
            val frame = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                THUMBNAIL_PIXELS,
                THUMBNAIL_PIXELS,
            )
            checkNotNull(frame) { "No frame at $fraction of $uri." }
            frame.asImageBitmap()
        }
    } finally {
        retriever.release()
    }
}.getOrNull()

/**
 * Process-wide LRU of extracted frame sets, keyed by URI string. Extraction is real decode work
 * behind [VIDEO_FRAME_EXTRACTION_PERMITS]'s two permits, so re-decoding the same clip every time
 * its row scrolls back into view would make the semaphore itself the bottleneck for the whole
 * grid rather than a guard against bursts.
 */
private object VideoFrameCache {
    private const val MAX_ENTRIES = 24
    private val cache = object : LinkedHashMap<String, List<ImageBitmap>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ImageBitmap>>) = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): List<ImageBitmap>? = cache[key]

    @Synchronized
    fun put(key: String, frames: List<ImageBitmap>) {
        cache[key] = frames
    }
}
