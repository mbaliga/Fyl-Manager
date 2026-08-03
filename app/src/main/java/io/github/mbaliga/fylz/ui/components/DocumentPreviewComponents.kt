package io.github.mbaliga.fylz.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.ArchiveInspection
import io.github.mbaliga.fylz.data.ArchiveService
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.FileFormatDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PdfPagePreview(val bitmap: ImageBitmap, val pageCount: Int)

@Composable
fun PdfDocumentPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pageIndex by remember(entry.uri) { mutableIntStateOf(0) }
    val result by produceState<Result<PdfPagePreview>?>(null, entry.uri, pageIndex) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { descriptorFd ->
                    PdfRenderer(descriptorFd).use { renderer ->
                        require(renderer.pageCount > 0) { "The PDF contains no pages." }
                        val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                        renderer.openPage(safeIndex).use { page ->
                            val maxDimension = 1_600f
                            val scale = minOf(maxDimension / page.width, maxDimension / page.height, 2f)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            PdfPagePreview(bitmap.asImageBitmap(), renderer.pageCount)
                        }
                    }
                } ?: error("The provider did not return a seekable PDF descriptor.")
            }
        }
    }
    when (val current = result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> current.fold(
            onSuccess = { preview ->
                Column(modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) }, enabled = pageIndex > 0) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous PDF page")
                        }
                        Text("Page ${pageIndex + 1} of ${preview.pageCount}", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { pageIndex = (pageIndex + 1).coerceAtMost(preview.pageCount - 1) }, enabled = pageIndex + 1 < preview.pageCount) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next PDF page")
                        }
                    }
                    HorizontalDivider()
                    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.TopCenter) {
                        Image(preview.bitmap, contentDescription = "Page ${pageIndex + 1} of ${entry.name}")
                    }
                }
            },
            onFailure = { UniversalInspectorPreview(entry, descriptor, modifier) },
        )
    }
}

@Composable
fun ZipArchivePreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val result by produceState<Result<ArchiveInspection>?>(null, entry.uri) {
        value = runCatching { ArchiveService(context.applicationContext).inspectZip(entry.uri) }
    }
    when (val current = result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> current.fold(
            onSuccess = { details ->
                Column(
                    modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(38.dp))
                        Column {
                            Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
                            Text("${details.entryCount} entries", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        color = if (details.extractionDecision.allowed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            details.extractionDecision.reason ?: if (details.extractionDecision.allowed) "Container paths and expansion metadata passed preflight." else "Extraction is blocked.",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                    details.visibleEntries.forEach { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (item.directory) Icons.Outlined.Archive else Icons.Outlined.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(item.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!item.directory && item.uncompressedBytes >= 0) Text(humanBytes(item.uncompressedBytes), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (details.entriesTruncated) Text("Additional entries are hidden by the preview limit.", style = MaterialTheme.typography.labelMedium)
                }
            },
            onFailure = { UniversalInspectorPreview(entry, descriptor, modifier) },
        )
    }
}

@Composable
fun ExternalOpenButton(entry: FileEntry) {
    val context = LocalContext.current
    Button(onClick = {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(entry.uri, entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "No external specialist viewer is installed.", Toast.LENGTH_SHORT).show()
        }
    }) {
        Icon(Icons.Outlined.OpenInNew, contentDescription = null)
        Text("Open", Modifier.padding(start = 6.dp))
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KiB"
    bytes < 1_073_741_824 -> "${bytes / 1_048_576} MiB"
    else -> "${bytes / 1_073_741_824} GiB"
}
