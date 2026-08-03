package io.github.mbaliga.fylz.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.FileFormatDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RenderedPdfPage(
    val bitmap: ImageBitmap,
    val pageCount: Int,
    val pageIndex: Int,
)

@Composable
fun PdfPagerPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pageIndex by remember(entry.uri) { mutableIntStateOf(0) }
    val page by produceState<Result<RenderedPdfPage>?>(null, entry.uri, pageIndex) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        require(renderer.pageCount > 0) { "This PDF has no pages." }
                        val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                        renderer.openPage(safeIndex).use { source ->
                            val scale = minOf(
                                MAX_RENDER_WIDTH.toFloat() / source.width.coerceAtLeast(1),
                                MAX_RENDER_HEIGHT.toFloat() / source.height.coerceAtLeast(1),
                            ).coerceAtMost(2f)
                            val width = (source.width * scale).toInt().coerceAtLeast(1)
                            val height = (source.height * scale).toInt().coerceAtLeast(1)
                            require(width.toLong() * height <= MAX_RENDER_PIXELS) { "PDF page exceeds the render safety limit." }
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            RenderedPdfPage(bitmap.asImageBitmap(), renderer.pageCount, safeIndex)
                        }
                    }
                } ?: error("The provider did not return a seekable PDF descriptor.")
            }
        }
    }

    when (val result = page) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { rendered ->
                if (pageIndex != rendered.pageIndex) pageIndex = rendered.pageIndex
                Column(modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { pageIndex -= 1 }, enabled = rendered.pageIndex > 0) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous PDF page")
                        }
                        Text(
                            "Page ${rendered.pageIndex + 1} of ${rendered.pageCount}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        IconButton(onClick = { pageIndex += 1 }, enabled = rendered.pageIndex + 1 < rendered.pageCount) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next PDF page")
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Image(rendered.bitmap, contentDescription = "Page ${rendered.pageIndex + 1} of ${entry.name}")
                    }
                }
            },
            onFailure = {
                UniversalInspectorPreview(entry, descriptor, modifier, it.message ?: "Unable to render this PDF page.")
            },
        )
    }
}

private const val MAX_RENDER_WIDTH = 2_048
private const val MAX_RENDER_HEIGHT = 2_048
private const val MAX_RENDER_PIXELS = 4_194_304L
