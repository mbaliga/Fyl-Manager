package io.github.mbaliga.fylz.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.mbaliga.fylz.preview.FileFormatDescriptor
import io.github.mbaliga.fylz.preview.FileFormatRegistry
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coil can't gunzip on the fly, so a compressed SVG needs its bytes decompressed first. */
private const val SVGZ_MAX_BYTES = 8 * 1024 * 1024

/** SVG, SVGZ, GIF, animated WebP/HEIF and bounded raster preview. Falls back to
 * [UniversalInspectorPreview] on a decode failure (P0.9, defect 9) -- a blank pane is never
 * acceptable, and several formats this app lists as images (RAW, PSD, TIFF...) have no decoder
 * here at all. */
@Composable
fun RichImagePreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
            }
            .build()
    }

    val isSvgz = remember(entry.name) { FileFormatRegistry.compoundExtension(entry.name) == "svgz" }
    var svgzBytes by remember(entry.uri) { mutableStateOf<ByteArray?>(null) }
    var svgzFailed by remember(entry.uri) { mutableStateOf(false) }
    var decodeFailed by remember(entry.uri) { mutableStateOf(false) }

    LaunchedEffect(entry.uri, isSvgz) {
        if (!isSvgz) return@LaunchedEffect
        svgzBytes = null
        svgzFailed = false
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(entry.uri)?.use { input ->
                    GZIPInputStream(input).use { it.readBounded(SVGZ_MAX_BYTES) }
                }
            }.getOrNull()
        }
        if (bytes != null) svgzBytes = bytes else svgzFailed = true
    }

    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        when {
            isSvgz && svgzFailed -> UniversalInspectorPreview(entry, descriptor, Modifier.fillMaxSize())
            isSvgz && svgzBytes == null -> CircularProgressIndicator()
            decodeFailed -> UniversalInspectorPreview(entry, descriptor, Modifier.fillMaxSize())
            else -> {
                val requestData: Any = svgzBytes ?: entry.uri
                val request = remember(requestData) {
                    ImageRequest.Builder(context)
                        .data(requestData)
                        .size(Size(2_048, 2_048))
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = "Preview of ${entry.name}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state -> decodeFailed = state is AsyncImagePainter.State.Error },
                )
            }
        }
    }
}

/** Reads at most [maxBytes] from this stream -- a decompression bomb guard for [SVGZ_MAX_BYTES]. */
private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, 65_536))
    val chunk = ByteArray(8_192)
    var total = 0
    while (total < maxBytes) {
        val remaining = maxBytes - total
        val read = read(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) break
        buffer.write(chunk, 0, read)
        total += read
    }
    return buffer.toByteArray()
}
