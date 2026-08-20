package io.github.mbaliga.fylz.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
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
    onIntrinsicAspect: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    var pageIndex by remember(entry.uri) { mutableIntStateOf(0) }
    val page by produceState<Result<RenderedPdfPage>?>(null, entry.uri, pageIndex) {
        val result = withContext(Dispatchers.IO) {
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
        // Reported on the first page only: the card locks to that shape for the whole viewing
        // session, so paging through a document with mixed page sizes never resizes the card
        // out from under the reader's thumb.
        result.onSuccess { rendered ->
            if (rendered.pageIndex == 0) {
                onIntrinsicAspect?.invoke(rendered.bitmap.width.toFloat() / rendered.bitmap.height.toFloat())
            }
        }
        value = result
    }

    when (val result = page) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { rendered ->
                if (pageIndex != rendered.pageIndex) pageIndex = rendered.pageIndex
                Box(modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Image(
                            rendered.bitmap,
                            contentDescription = "Page ${rendered.pageIndex + 1} of ${entry.name}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PdfPagerPill(
                        pageIndex = rendered.pageIndex,
                        pageCount = rendered.pageCount,
                        onPrevious = { pageIndex -= 1 },
                        onNext = { pageIndex += 1 },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    )
                }
            },
            onFailure = {
                UniversalInspectorPreview(entry, descriptor, modifier, it.message ?: "Unable to render this PDF page.")
            },
        )
    }
}

/**
 * The prev/`n of m`/next chrome, floating over the page rather than claiming a header strip --
 * the card is sized to the page's own shape now, so a fixed band across the top would put a
 * grey bar exactly where content used to fill the card edge-to-edge.
 */
@Composable
private fun PdfPagerPill(
    pageIndex: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        // Was a 32dp compact IconButton pair -- TactileIconKey's own >=48dp touch target (the
        // kit's hard floor) makes this floating pill noticeably taller than before. Flag for the
        // render pass: verify it still reads as a floating pill rather than crowding the page.
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TactileIconKey(
                icon = Icons.Outlined.ChevronLeft,
                contentDescription = "Previous PDF page",
                onClick = onPrevious,
                enabled = pageIndex > 0,
            )
            Text(
                "${pageIndex + 1} of $pageCount",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TactileIconKey(
                icon = Icons.Outlined.ChevronRight,
                contentDescription = "Next PDF page",
                onClick = onNext,
                enabled = pageIndex + 1 < pageCount,
            )
        }
    }
}

private const val MAX_RENDER_WIDTH = 2_048
private const val MAX_RENDER_HEIGHT = 2_048
private const val MAX_RENDER_PIXELS = 4_194_304L
