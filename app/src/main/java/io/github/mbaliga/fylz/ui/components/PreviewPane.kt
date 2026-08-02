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
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PreviewPane(
    entry: FileEntry?,
    textContent: String?,
    textTruncated: Boolean,
    loading: Boolean,
    editorValue: String,
    onEditorValueChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(entry?.uri) { mutableStateOf(false) }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        if (entry == null) {
            EmptyPreview()
            return@Surface
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        text = entry.mimeType,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (FileType.isEditable(entry.kind)) {
                    FilledTonalButton(onClick = { editing = !editing }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Text(if (editing) "Preview" else "Edit", Modifier.padding(start = 6.dp))
                    }
                    if (editing) {
                        Button(onClick = onSave) {
                            Icon(Icons.Outlined.Save, contentDescription = null)
                            Text("Save", Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            HorizontalDivider()

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                editing && FileType.isEditable(entry.kind) -> OutlinedTextField(
                    value = editorValue,
                    onValueChange = onEditorValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    label = { Text("UTF-8 text") },
                )
                entry.kind == EntryKind.MARKDOWN && textContent != null -> Column(Modifier.fillMaxSize()) {
                    if (textTruncated) TruncationNotice()
                    MarkdownPreview(textContent, Modifier.fillMaxSize())
                }
                entry.kind == EntryKind.TEXT && textContent != null -> Column(Modifier.fillMaxSize()) {
                    if (textTruncated) TruncationNotice()
                    MonospaceTextPreview(textContent, Modifier.fillMaxSize())
                }
                entry.kind == EntryKind.IMAGE -> RichImagePreview(entry, Modifier.fillMaxSize())
                entry.kind == EntryKind.PDF -> PdfPreview(entry, Modifier.fillMaxSize())
                else -> GenericPreview(entry, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun EmptyPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Select a file to preview",
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TruncationNotice() {
    Text(
        text = "Large file preview is limited to 512 KiB.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    )
}

@Composable
private fun PdfPreview(entry: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val page by produceState<ImageBitmap?>(initialValue = null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount == 0) return@use null
                        renderer.openPage(0).use { pdfPage ->
                            val width = 900
                            val height = (width * pdfPage.height.toFloat() / pdfPage.width)
                                .toInt()
                                .coerceAtLeast(1)
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
        GenericPreview(entry, modifier)
    } else {
        Box(modifier.padding(12.dp), contentAlignment = Alignment.TopCenter) {
            Image(bitmap = page!!, contentDescription = "First page of ${entry.name}")
        }
    }
}

@Composable
private fun GenericPreview(entry: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon: ImageVector = when (entry.kind) {
        EntryKind.PDF -> Icons.Outlined.PictureAsPdf
        EntryKind.ARCHIVE -> Icons.Outlined.Archive
        EntryKind.AUDIO -> Icons.Outlined.AudioFile
        EntryKind.VIDEO -> Icons.Outlined.VideoFile
        else -> Icons.Outlined.InsertDriveFile
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(54.dp))
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            entry.sizeBytes?.let { Text(formatBytes(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(entry.uri, entry.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show()
                    }
            }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                Text("Open", Modifier.padding(start = 6.dp))
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}
