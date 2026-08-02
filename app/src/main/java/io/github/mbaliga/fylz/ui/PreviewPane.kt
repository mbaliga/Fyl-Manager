package io.github.mbaliga.fylz.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.BrowserUiState
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.PreviewKind
import io.github.mbaliga.fylz.preview.calculateBitmapSampleSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PreviewPane(
    state: BrowserUiState,
    documentUri: (FileEntry) -> Uri,
    onClose: () -> Unit,
    onToggleFloating: () -> Unit,
    onEdit: () -> Unit,
    onEditorChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    onOpenExternal: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.selected
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            PreviewHeader(
                entry = entry,
                kind = state.preview.kind,
                editing = state.isEditing,
                canEdit = entry != null &&
                    state.preview.kind in setOf(PreviewKind.MARKDOWN, PreviewKind.TEXT) &&
                    state.preview.text != null &&
                    !state.preview.truncated &&
                    entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
                onClose = onClose,
                onToggleFloating = onToggleFloating,
                onEdit = onEdit,
                onOpenExternal = { entry?.let(onOpenExternal) },
            )
            HorizontalDivider()

            if (entry == null) {
                EmptyPreview(Modifier.fillMaxSize())
            } else if (state.isEditing) {
                EditorPane(
                    text = state.editorText,
                    dirty = state.editorDirty,
                    onChange = onEditorChange,
                    onSave = onSave,
                    onCancel = onCancelEdit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PreviewBody(
                    state = state,
                    entry = entry,
                    uri = documentUri(entry),
                    onOpenExternal = { onOpenExternal(entry) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    entry: FileEntry?,
    kind: PreviewKind,
    editing: Boolean,
    canEdit: Boolean,
    onClose: () -> Unit,
    onToggleFloating: () -> Unit,
    onEdit: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry?.name ?: "Preview",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry != null) {
                Text(
                    text = previewLabel(kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!editing && canEdit) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit text")
            }
        }
        if (!editing && entry != null && !entry.isDirectory) {
            IconButton(onClick = onOpenExternal) {
                Icon(Icons.Outlined.Launch, contentDescription = "Open with another app")
            }
        }
        IconButton(onClick = onToggleFloating) {
            Icon(Icons.Outlined.OpenInFull, contentDescription = "Toggle floating preview")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "Close preview")
        }
    }
}

@Composable
private fun PreviewBody(
    state: BrowserUiState,
    entry: FileEntry,
    uri: Uri,
    onOpenExternal: () -> Unit,
    modifier: Modifier,
) {
    when (state.preview.kind) {
        PreviewKind.MARKDOWN -> ScrollablePreview(modifier) {
            PreviewNotices(state)
            state.preview.text?.let { MarkdownPreview(it) } ?: PreviewLoadingOrError(state)
        }
        PreviewKind.TEXT -> ScrollablePreview(modifier) {
            PreviewNotices(state)
            state.preview.text?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            } ?: PreviewLoadingOrError(state)
        }
        PreviewKind.IMAGE, PreviewKind.PDF -> BitmapPreview(
            uri = uri,
            kind = state.preview.kind,
            modifier = modifier,
        )
        PreviewKind.AUDIO, PreviewKind.VIDEO, PreviewKind.ARCHIVE,
        PreviewKind.UNSUPPORTED, PreviewKind.NONE, PreviewKind.DIRECTORY -> GenericPreview(
            entry = entry,
            kind = state.preview.kind,
            onOpenExternal = onOpenExternal,
            modifier = modifier,
        )
    }
}

@Composable
private fun ScrollablePreview(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PreviewNotices(state: BrowserUiState) {
    if (state.preview.truncated) {
        Text(
            text = "Preview capped at 400,000 characters. Editing is disabled here to prevent replacing the unread portion; open the file externally instead.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PreviewLoadingOrError(state: BrowserUiState) {
    if (state.preview.error != null) {
        Text(state.preview.error, color = MaterialTheme.colorScheme.error)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("Reading preview...", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BitmapPreview(
    uri: Uri,
    kind: PreviewKind,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val result by produceState<Result<Bitmap?>?>(null, uri, kind) {
        value = runCatching { loadBitmap(context, uri, kind) }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        when {
            result == null -> CircularProgressIndicator()
            result?.isFailure == true -> Text(
                text = result?.exceptionOrNull()?.message ?: "Preview unavailable.",
                color = MaterialTheme.colorScheme.error,
            )
            result?.getOrNull() == null -> Text("Preview unavailable for this file.")
            else -> Image(
                bitmap = requireNotNull(result?.getOrNull()).asImageBitmap(),
                contentDescription = "File preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun GenericPreview(
    entry: FileEntry,
    kind: PreviewKind,
    onOpenExternal: () -> Unit,
    modifier: Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(24.dp),
    ) {
        Text(
            text = previewGlyph(kind),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append(entry.mimeType)
                entry.size?.let { append("  |  ${formatBytes(it)}") }
                entry.modifiedAt?.let {
                    append("  |  ")
                    append(DateFormat.getDateTimeInstance().format(Date(it)))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (!entry.isDirectory) {
            Button(onClick = onOpenExternal) {
                Icon(Icons.Outlined.Launch, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Open with")
            }
        } else {
            Text(
                text = "Long-press selects a folder for inspection; tap it in the file pane to open it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyPreview(modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(24.dp),
    ) {
        Text("Select a file", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Markdown, agent notes, source text, images and the first page of PDFs preview here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditorPane(
    text: String,
    dirty: Boolean,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = { Text("Text editor") },
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onSave, enabled = dirty) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Save")
            }
        }
    }
}

private suspend fun loadBitmap(context: Context, uri: Uri, kind: PreviewKind): Bitmap? =
    withContext(Dispatchers.IO) {
        when (kind) {
            PreviewKind.IMAGE -> loadSampledImage(context, uri)
            PreviewKind.PDF -> context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use rendererUse@ { renderer ->
                    if (renderer.pageCount == 0) return@rendererUse null
                    renderer.openPage(0).use { page ->
                        val maxSide = 1_600f
                        val scale = minOf(1f, maxSide / maxOf(page.width, page.height).toFloat())
                        val bitmap = Bitmap.createBitmap(
                            maxOf(1, (page.width * scale).toInt()),
                            maxOf(1, (page.height * scale).toInt()),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
            else -> null
        }
    }

private fun loadSampledImage(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxSide = 2_048,
        )
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, decodeOptions)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1_024 && unitIndex < units.lastIndex) {
        value /= 1_024
        unitIndex++
    }
    return if (value >= 10) {
        String.format(Locale.getDefault(), "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}

private fun previewLabel(kind: PreviewKind): String = when (kind) {
    PreviewKind.MARKDOWN -> "Rendered Markdown"
    PreviewKind.TEXT -> "Text"
    PreviewKind.IMAGE -> "Image"
    PreviewKind.PDF -> "PDF first page"
    PreviewKind.AUDIO -> "Audio"
    PreviewKind.VIDEO -> "Video"
    PreviewKind.ARCHIVE -> "Archive"
    PreviewKind.UNSUPPORTED -> "File"
    PreviewKind.DIRECTORY -> "Folder"
    PreviewKind.NONE -> "Preview"
}

private fun previewGlyph(kind: PreviewKind): String = when (kind) {
    PreviewKind.AUDIO -> "AUDIO"
    PreviewKind.VIDEO -> "VIDEO"
    PreviewKind.ARCHIVE -> "ZIP"
    PreviewKind.PDF -> "PDF"
    PreviewKind.MARKDOWN -> "MD"
    PreviewKind.TEXT -> "TXT"
    PreviewKind.IMAGE -> "IMG"
    PreviewKind.DIRECTORY -> "DIR"
    PreviewKind.UNSUPPORTED, PreviewKind.NONE -> "FILE"
}
