package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import io.github.mbaliga.fylz.model.FileEntry

/** SVG, GIF, animated WebP/HEIF and bounded raster preview. */
@Composable
fun RichImagePreview(
    entry: FileEntry,
    modifier: Modifier = Modifier,
    onIntrinsicAspect: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    // The application's loader already registers the SVG and animated decoders this needs; a
    // second one here would decode the same file into a second memory cache.
    val request = remember(entry.uri) {
        ImageRequest.Builder(context)
            .data(entry.uri)
            .size(Size(2_048, 2_048))
            .crossfade(true)
            .build()
    }

    // No padding: the card is sized to the image's own aspect once onIntrinsicAspect reports it,
    // so Fit no longer needs a margin to keep the picture off a mismatched frame's edges.
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = "Preview of ${entry.name}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    val size = state.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f) {
                        onIntrinsicAspect?.invoke(size.width / size.height)
                    }
                }
            },
        )
    }
}
