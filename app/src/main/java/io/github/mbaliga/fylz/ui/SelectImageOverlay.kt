package io.github.mbaliga.fylz.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.data.ImageExportFormat
import io.github.mbaliga.fylz.data.ImageSelectionRenderer
import io.github.mbaliga.fylz.data.SelectionMask
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lasso-select a region of a single image, then crop, copy, or cut it out -- the first of three
 * planned selection tools (magic wand and magnetic lasso are separate, later slices) sharing one
 * [SelectionMask] representation and [ImageSelectionRenderer]'s two output actions.
 *
 * @param entry the single selected image; the caller (`SelectionActionPolicy.selectImage` plus an
 *   [ImageExportFormat.SUPPORTED_MIME_TYPES] check) has already established there is exactly one
 *   and it is a format this can decode and re-encode.
 */
@Composable
fun SelectImageOverlay(entry: FileEntry, repository: DocumentRepository, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(entry.uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var lassoPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(entry.uri) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(entry.uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        if (decoded == null) loadFailed = true else bitmap = decoded
    }

    fun buildMask(): SelectionMask? {
        val bmp = bitmap ?: return null
        if (lassoPoints.size < 3 || canvasSize.width <= 0f || canvasSize.height <= 0f) return null
        val scaleX = bmp.width / canvasSize.width
        val scaleY = bmp.height / canvasSize.height
        val path = android.graphics.Path()
        lassoPoints.forEachIndexed { index, point ->
            val x = point.x * scaleX
            val y = point.y * scaleY
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return SelectionMask.fromPath(path, bmp.width, bmp.height)
    }

    fun writeResult(destinationUri: Uri, result: Bitmap, format: ImageExportFormat, invalidateCache: Boolean) {
        scope.launch {
            busy = true
            runCatching { repository.writeBitmap(destinationUri, result, format.compressFormat, format.quality) }
                .onSuccess {
                    if (invalidateCache) {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                    }
                    Toast.makeText(context, "Saved", Toast.LENGTH_LONG).show()
                    onSaved()
                }
                .onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "Unable to save the image.", Toast.LENGTH_LONG).show()
                }
            busy = false
        }
    }

    fun withMask(onNoSelection: () -> Unit = {}, action: (Bitmap, SelectionMask) -> Unit) {
        val bmp = bitmap
        val mask = buildMask()
        if (bmp == null || mask == null || mask.boundingBox() == null) {
            Toast.makeText(context, "Draw a selection first.", Toast.LENGTH_SHORT).show()
            onNoSelection()
            return
        }
        action(bmp, mask)
    }

    val copyAsNewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(entry.mimeType),
    ) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        withMask { bmp, mask ->
            val cropped = ImageSelectionRenderer.cropToBoundingBox(bmp, mask) ?: return@withMask
            val format = ImageExportFormat.fromMimeType(entry.mimeType) ?: ImageExportFormat.JPEG
            writeResult(destination, cropped, format, invalidateCache = false)
        }
    }

    val cutoutLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ImageExportFormat.PNG.mimeType),
    ) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        withMask { bmp, mask ->
            val cutout = ImageSelectionRenderer.cutoutWithTransparency(bmp, mask) ?: return@withMask
            writeResult(destination, cutout, ImageExportFormat.PNG, invalidateCache = false)
        }
    }

    fun performCrop() = withMask { bmp, mask ->
        val cropped = ImageSelectionRenderer.cropToBoundingBox(bmp, mask) ?: return@withMask
        val format = ImageExportFormat.fromMimeType(entry.mimeType) ?: ImageExportFormat.JPEG
        writeResult(entry.uri, cropped, format, invalidateCache = true)
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (!busy) onDismiss() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Cancel")
                    }
                    Text("Select", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { lassoPoints = emptyList(); activePoints = emptyList() },
                        enabled = (lassoPoints.isNotEmpty() || activePoints.isNotEmpty()) && !busy,
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear selection")
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        loadFailed -> Text(
                            "Unable to open this image.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        bitmap == null -> CircularProgressIndicator()
                        else -> LassoSurface(
                            bitmap = bitmap!!,
                            lassoPoints = lassoPoints,
                            activePoints = activePoints,
                            onCanvasSizeChanged = { canvasSize = it },
                            onDragStart = { lassoPoints = emptyList(); activePoints = listOf(it) },
                            onDragContinue = { activePoints = activePoints + it },
                            onDragEnd = {
                                if (activePoints.size >= 3) lassoPoints = activePoints
                                activePoints = emptyList()
                            },
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Drag to draw a lasso, then choose what to do with it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TactileButton(
                        text = "Crop to selection",
                        onClick = { performCrop() },
                        enabled = !busy && bitmap != null,
                        fillWidth = true,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TactileButton(
                            text = "Copy as new image",
                            onClick = { withMask { _, _ -> copyAsNewLauncher.launch("copy_${entry.name}") } },
                            style = TactileButtonStyle.SECONDARY,
                            enabled = !busy && bitmap != null,
                            fillWidth = true,
                            modifier = Modifier.weight(1f),
                        )
                        TactileButton(
                            text = "Cut out (transparent)",
                            onClick = {
                                withMask { _, _ ->
                                    val base = entry.name.substringBeforeLast('.', missingDelimiterValue = entry.name)
                                    cutoutLauncher.launch("$base.png")
                                }
                            },
                            style = TactileButtonStyle.SECONDARY,
                            enabled = !busy && bitmap != null,
                            fillWidth = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LassoSurface(
    bitmap: Bitmap,
    lassoPoints: List<Offset>,
    activePoints: List<Offset>,
    onCanvasSizeChanged: (Size) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragContinue: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxAspect = maxWidth.value / maxHeight.value
        val fittedWidth = if (bitmapAspect > boxAspect) maxWidth else maxHeight * bitmapAspect
        val fittedHeight = if (bitmapAspect > boxAspect) maxWidth / bitmapAspect else maxHeight

        Box(
            Modifier
                .size(fittedWidth, fittedHeight)
                .onSizeChanged { onCanvasSizeChanged(Size(it.width.toFloat(), it.height.toFloat())) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> onDragStart(offset) },
                        onDrag = { change, _ -> change.consume(); onDragContinue(change.position) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = "Image being selected",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.fillMaxSize()) {
                val shown = if (activePoints.size >= 2) activePoints else lassoPoints
                if (shown.size >= 2) {
                    val path = Path()
                    shown.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    if (shown === lassoPoints) path.close()
                    drawPath(
                        path,
                        color = Color(0xFF7C4DFF),
                        style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}
