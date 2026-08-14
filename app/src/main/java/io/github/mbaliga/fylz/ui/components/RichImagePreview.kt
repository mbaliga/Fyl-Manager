package io.github.mbaliga.fylz.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import coil3.svg.SvgDecoder
import io.github.mbaliga.fylz.model.FileEntry

/** SVG, GIF, animated WebP/HEIF and bounded raster preview. */
@Composable
fun RichImagePreview(entry: FileEntry, modifier: Modifier = Modifier) {
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

    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = "Preview of ${entry.name}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                // State is rendered by AsyncImage; explicit callback keeps lifecycle ownership local.
                if (state is AsyncImagePainter.State.Error) Unit
            },
        )
    }
}
