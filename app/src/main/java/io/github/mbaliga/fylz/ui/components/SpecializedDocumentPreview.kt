package io.github.mbaliga.fylz.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.components.preview.ArchiveContentPreview
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PdfPagePreview(
    val pageCount: Int,
    val pageIndex: Int,
    val page: ImageBitmap,
    val thumbnails: List<Pair<Int, ImageBitmap>>,
)

@Composable
fun PdfDocumentPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var requestedPage by remember(entry.uri) { mutableIntStateOf(0) }
    val result by produceState<Result<PdfPagePreview>?>(initialValue = null, entry.uri, requestedPage) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { descriptorFd ->
                    PdfRenderer(descriptorFd).use { renderer ->
                        require(renderer.pageCount > 0) { "PDF contains no pages." }
                        val pageIndex = requestedPage.coerceIn(0, renderer.pageCount - 1)
                        val page = renderPdfPage(renderer, pageIndex, 1200)
                        val thumbnailIndexes = thumbnailIndexes(renderer.pageCount, pageIndex)
                        val thumbnails = thumbnailIndexes.map { index -> index to renderPdfPage(renderer, index, 180) }
                        PdfPagePreview(renderer.pageCount, pageIndex, page, thumbnails)
                    }
                } ?: error("The provider did not expose a seekable PDF file descriptor.")
            }
        }
    }

    when (val current = result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> current.fold(
            onSuccess = { preview ->
                Column(modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TactileIconKey(
                                icon = Icons.Outlined.ChevronLeft,
                                contentDescription = "Previous PDF page",
                                enabled = preview.pageIndex > 0,
                                onClick = { requestedPage = preview.pageIndex - 1 },
                            )
                            Text(
                                "Page ${preview.pageIndex + 1} of ${preview.pageCount}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TactileIconKey(
                                icon = Icons.Outlined.ChevronRight,
                                contentDescription = "Next PDF page",
                                enabled = preview.pageIndex + 1 < preview.pageCount,
                                onClick = { requestedPage = preview.pageIndex + 1 },
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        preview.thumbnails.forEach { (index, thumbnail) ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (index == preview.pageIndex) 2.dp else 1.dp,
                                    if (index == preview.pageIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.clickable { requestedPage = index },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = "PDF page ${index + 1}",
                                        modifier = Modifier.height(92.dp).padding(3.dp),
                                        contentScale = ContentScale.Fit,
                                    )
                                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopCenter) {
                        Image(
                            bitmap = preview.page,
                            contentDescription = "Page ${preview.pageIndex + 1} of ${entry.name}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            },
            onFailure = {
                UniversalInspectorPreview(
                    entry,
                    descriptor,
                    modifier,
                    it.message ?: "The PDF page renderer could not open this provider stream.",
                )
            },
        )
    }
}

private fun renderPdfPage(renderer: PdfRenderer, index: Int, targetWidth: Int): ImageBitmap =
    renderer.openPage(index).use { page ->
        val height = (targetWidth * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }.asImageBitmap()
    }

private fun thumbnailIndexes(pageCount: Int, selected: Int): List<Int> {
    if (pageCount <= 12) return (0 until pageCount).toList()
    val indexes = linkedSetOf(0, pageCount - 1)
    for (index in (selected - 4)..(selected + 4)) if (index in 0 until pageCount) indexes += index
    return indexes.sorted()
}

/**
 * ZIP-family containers, as a browsable tree rather than a flat wall of names.
 *
 * The owner's ask was to "preview files within them, not just see the filenames", so this now
 * delegates to [ArchiveContentPreview]: descend into folders inside the archive, come back out,
 * and open one entry -- extracted alone, bounded, into a self-pruning cache file -- in the same
 * renderers a file on disk gets. Everything the old flat listing stated (entry counts, the
 * declared expanded size, password protection, a blocked extraction preflight) is still stated,
 * in that browser's own header, and still comes from `ArchiveService.inspectZip`.
 */
@Composable
fun ZipArchivePreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    ArchiveContentPreview(entry, descriptor, modifier)
}
