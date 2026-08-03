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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

@Composable
fun PdfDocumentPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val page by produceState<ImageBitmap?>(initialValue = null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { descriptorFd ->
                    PdfRenderer(descriptorFd).use { renderer ->
                        if (renderer.pageCount == 0) return@use null
                        renderer.openPage(0).use { pdfPage ->
                            val width = 900
                            val height = (width * pdfPage.height.toFloat() / pdfPage.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }.asImageBitmap()
                        }
                    }
                }
            }.getOrNull()
        }
    }
    if (page == null) {
        UniversalInspectorPreview(entry, descriptor, modifier, "The PDF page renderer could not open this provider stream.")
    } else {
        Box(modifier.padding(12.dp), contentAlignment = Alignment.TopCenter) {
            Image(bitmap = page!!, contentDescription = "First page of ${entry.name}")
        }
    }
}

@Composable
fun ZipArchivePreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val inspection by produceState<Result<ArchiveInspection>?>(initialValue = null, entry.uri) {
        value = runCatching { ArchiveService(context.applicationContext).inspectZip(entry.uri) }
    }
    when (val result = inspection) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { details -> ArchiveInspectionContent(details, modifier) },
            onFailure = {
                UniversalInspectorPreview(
                    entry,
                    descriptor,
                    modifier,
                    it.message ?: "This archive format is not handled by the ZIP inspector.",
                )
            },
        )
    }
}

@Composable
private fun ArchiveInspectionContent(details: ArchiveInspection, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(36.dp))
            Column {
                Text("ZIP-compatible archive", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${details.entryCount} entries · ${formatSpecializedBytes(details.archiveBytes)} compressed",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (details.encrypted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Text("Password protected")
            }
        }
        val decision = details.extractionDecision
        Surface(
            color = if (decision.allowed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(if (decision.allowed) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, contentDescription = null)
                Column {
                    Text(if (decision.allowed) "Extraction preflight passed" else "Extraction blocked", style = MaterialTheme.typography.titleSmall)
                    Text(decision.reason ?: "Paths and expansion metadata are within configured limits.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ArchiveMetric("Files", details.fileCount.toString())
            ArchiveMetric("Folders", details.directoryCount.toString())
            ArchiveMetric("Expanded", details.totalUncompressedBytes?.let(::formatSpecializedBytes) ?: "Unknown")
        }
        HorizontalDivider()
        Text("Contents", style = MaterialTheme.typography.titleSmall)
        details.visibleEntries.forEach { archiveEntry ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (archiveEntry.directory) Icons.Outlined.Archive else Icons.Outlined.InsertDriveFile, null, Modifier.size(20.dp))
                Text(archiveEntry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!archiveEntry.directory && archiveEntry.uncompressedBytes >= 0L) {
                    Text(formatSpecializedBytes(archiveEntry.uncompressedBytes), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (details.entriesTruncated) Text("Only the first ${details.visibleEntries.size} entries are shown.")
    }
}

@Composable
private fun ArchiveMetric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ExternalOpenButton(entry: FileEntry) {
    val context = LocalContext.current
    Button(onClick = {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(entry.uri, entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "No installed app advertises support for this type.", Toast.LENGTH_SHORT).show() }
    }) {
        Icon(Icons.Outlined.OpenInNew, contentDescription = null)
        Text("Open with…", Modifier.padding(start = 6.dp))
    }
}

private fun formatSpecializedBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do { value /= 1_024.0; unit += 1 } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}
