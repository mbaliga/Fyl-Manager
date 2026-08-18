package io.github.mbaliga.fylz.ui.components

import android.content.ContentResolver
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.cluster.MAX_CARDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The shared photo-print card: a real thumbnail (or, absent one, the type icon) behind a thin
 * white print border -- the iPad-Photos look the cluster cards, the deck, the tray and a
 * folder's peek band all draw from now.
 *
 * [rotation] is applied by this composable's own layer, for callers with no live transform of
 * their own (a resting stack, a folder's peek band). Callers that already own a per-frame
 * transform -- the cluster drag physics, the deck's riffle -- animate their own outer
 * `graphicsLayer` instead and leave this at its default 0f, so the two layers never fight over
 * the same rotation.
 *
 * [entry]'s face reads [ThumbnailCache] directly and only ever paints an already-warm bitmap --
 * it never falls through to [EntryThumbnail]'s own fetch-on-miss `produceState`, because this
 * card is drawn on the cluster drag layer among other places, which recomposes on every finger
 * move: a card still cold when the drag starts shows [StackCardTypeIcon] instead, exactly as if
 * no [entry] had been given at all, and stays that way until [warmThumbnails] (or an ordinary
 * grid/list row, which shares the same cache) lands a bitmap under the same key -- the drag layer
 * itself never triggers IO. Without a real [entry] (a card whose file hasn't resolved yet, or was
 * never given one), [fallbackName] and [kind] are enough to pick a type icon -- no synthetic
 * [FileEntry] or placeholder URI needed to get there.
 *
 * [liveContent] is the opt-out from that discipline, for surfaces that recompose calmly rather
 * than per-frame: the face becomes a full [EntryThumbnail], which may fetch on a miss and lets a
 * video card keep its motion under the auto-animate setting. The deck riffle wants that; the
 * drag layer must never ask for it.
 */
@Composable
fun StackCard(
    entry: FileEntry?,
    fallbackName: String,
    kind: EntryKind,
    size: Dp,
    rotation: Float = 0f,
    liveContent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation },
    ) {
        val faceSize = size - PRINT_BORDER * 2
        // Same cache key EntryThumbnail/warmThumbnails use for the default thumbnail size --
        // a bare URI string, never re-derived from a cold fetch here.
        val cached = entry?.let { ThumbnailCache.get(it.uri.toString()) }
        Box(Modifier.padding(PRINT_BORDER), contentAlignment = Alignment.Center) {
            if (liveContent && entry != null) {
                EntryThumbnail(entry, size = faceSize)
            } else if (cached != null) {
                Image(
                    bitmap = cached,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(faceSize).clip(MaterialTheme.shapes.small),
                )
            } else {
                StackCardTypeIcon(fallbackName, kind, faceSize)
            }
        }
    }
}

/**
 * The no-[FileEntry] fallback: the same asset lookup [EntryThumbnail]'s own type-icon branch
 * uses, reached without needing a real entry (and its URI) to get there.
 */
@Composable
private fun StackCardTypeIcon(name: String, kind: EntryKind, size: Dp) {
    val style = LocalIconStyle.current
    val asset = remember(name, kind, style) {
        val descriptor = FileFormatRegistry.describe(name, "", kind)
        "file:///android_asset/" + FileTypeIcons.assetPath(descriptor.extension, descriptor.family, style)
    }
    AsyncImage(
        model = asset,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size * ICON_SCALE),
    )
}

/** The white print margin's width, on every edge. */
private val PRINT_BORDER = 3.dp

/**
 * One card's content for [StackedThumbs] -- the same trio [StackCard] itself takes, bundled so a
 * caller building a stack from a plain listing doesn't need three parallel lists.
 */
data class StackSpec(val entry: FileEntry?, val fallbackName: String, val kind: EntryKind)

/**
 * The iPad-Photos at-rest stack: up to three [StackCard]s cascading down and to the right, front
 * card first and drawn on top -- the look a folder's peek band, a tray card and the landing
 * hero's fanned folders all share.
 */
@Composable
fun StackedThumbs(items: List<StackSpec>, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier) {
        items.take(3).forEachIndexed { index, spec ->
            StackCard(
                entry = spec.entry,
                fallbackName = spec.fallbackName,
                kind = spec.kind,
                size = size,
                rotation = STACK_TILTS[index],
                modifier = Modifier
                    .offset(x = (index * 6).dp, y = (index * 4).dp)
                    .zIndex((3 - index).toFloat()),
            )
        }
    }
}

/** Alternating tilt, front card first -- the same "fanned by hand" read the deck's own fan uses. */
private val STACK_TILTS = floatArrayOf(-6f, 5f, -3f)

/**
 * Primes [ThumbnailCache] for the cards a cluster drag is about to draw, so the per-frame physics
 * loop never has to. Fire-and-forget on purpose: the drag can start, run and end well before a
 * slow provider answers, and a late bitmap just lands in the cache for whichever card asks next
 * -- this drag's, or a later one over the same files.
 */
fun warmThumbnails(entries: List<FileEntry>, resolver: ContentResolver) {
    entries.take(MAX_CARDS).forEach { entry ->
        if (entry.isDirectory || (entry.kind != EntryKind.IMAGE && entry.kind != EntryKind.VIDEO)) return@forEach
        val cacheKey = entry.uri.toString()
        if (ThumbnailCache.get(cacheKey) != null) return@forEach
        warmScope.launch {
            loadProviderThumbnail(resolver, entry.uri, THUMBNAIL_PIXELS)?.asImageBitmap()?.let {
                ThumbnailCache.put(cacheKey, it)
            }
        }
    }
}

/** Process-lifetime, matching [ThumbnailCache]'s own scope -- warming outlives any one drag. */
private val warmScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
