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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
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
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which input method is currently building the selection mask. */
internal enum class SelectionTool(val label: String) {
    LASSO("Lasso"),
    WAND("Magic wand"),
}

/** Magic wand tolerance presets -- a per-channel Chebyshev distance, see [SelectionMask.floodFill]. */
private val WAND_TOLERANCES = listOf(12 to "Strict", 32 to "Normal", 64 to "Loose")

private const val PREVIEW_TINT_ALPHA = 0x66

/**
 * Select a region of a single image with a lasso or the magic wand, then crop, copy, or cut it
 * out -- the first two of three planned selection tools (magnetic lasso is a separate, later
 * slice) sharing one [SelectionMask] representation and [ImageSelectionRenderer]'s output actions.
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
    var tool by remember { mutableStateOf(SelectionTool.LASSO) }
    var tolerance by remember { mutableIntStateOf(WAND_TOLERANCES[1].first) }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var currentMask by remember { mutableStateOf<SelectionMask?>(null) }

    fun clearSelection() {
        activePoints = emptyList()
        currentMask = null
    }

    LaunchedEffect(entry.uri) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(entry.uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        if (decoded == null) loadFailed = true else bitmap = decoded
    }

    fun screenToBitmap(point: Offset, bmp: Bitmap): Offset {
        val scaleX = bmp.width / canvasSize.width
        val scaleY = bmp.height / canvasSize.height
        return Offset(point.x * scaleX, point.y * scaleY)
    }

    fun finishLasso() {
        val bmp = bitmap
        if (bmp == null || activePoints.size < 3 || canvasSize.width <= 0f || canvasSize.height <= 0f) {
            activePoints = emptyList()
            return
        }
        val path = android.graphics.Path()
        activePoints.forEachIndexed { index, point ->
            val (x, y) = screenToBitmap(point, bmp)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        currentMask = SelectionMask.fromPath(path, bmp.width, bmp.height)
        activePoints = emptyList()
    }

    fun pickWandSeed(point: Offset) {
        val bmp = bitmap ?: return
        if (canvasSize.width <= 0f || canvasSize.height <= 0f) return
        val (x, y) = screenToBitmap(point, bmp)
        val seedX = x.toInt().coerceIn(0, bmp.width - 1)
        val seedY = y.toInt().coerceIn(0, bmp.height - 1)
        currentMask = SelectionMask.floodFill(bmp, seedX, seedY, tolerance)
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

    fun withMask(action: (Bitmap, SelectionMask) -> Unit) {
        val bmp = bitmap
        val mask = currentMask
        if (bmp == null || mask == null || mask.boundingBox() == null) {
            Toast.makeText(context, "Make a selection first.", Toast.LENGTH_SHORT).show()
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
                        onClick = { clearSelection() },
                        enabled = (currentMask != null || activePoints.isNotEmpty()) && !busy,
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
                        else -> SelectionSurface(
                            bitmap = bitmap!!,
                            tool = tool,
                            activePoints = activePoints,
                            currentMask = currentMask,
                            onCanvasSizeChanged = { canvasSize = it },
                            onLassoStart = { currentMask = null; activePoints = listOf(it) },
                            onLassoContinue = { activePoints = activePoints + it },
                            onLassoEnd = { finishLasso() },
                            onWandTap = { pickWandSeed(it) },
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionTool.entries.forEach { candidate ->
                        TactileOptionRow(
                            text = candidate.label,
                            selected = tool == candidate,
                            onClick = { tool = candidate; clearSelection() },
                        )
                    }
                    if (tool == SelectionTool.WAND) {
                        Text(
                            "Tolerance",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        WAND_TOLERANCES.forEach { (value, label) ->
                            TactileOptionRow(text = label, selected = tolerance == value, onClick = { tolerance = value })
                        }
                    }
                    Text(
                        if (tool == SelectionTool.LASSO) {
                            "Drag to draw a lasso, then choose what to do with it."
                        } else {
                            "Tap a spot to select pixels of a similar color."
                        },
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
private fun SelectionSurface(
    bitmap: Bitmap,
    tool: SelectionTool,
    activePoints: List<Offset>,
    currentMask: SelectionMask?,
    onCanvasSizeChanged: (Size) -> Unit,
    onLassoStart: (Offset) -> Unit,
    onLassoContinue: (Offset) -> Unit,
    onLassoEnd: () -> Unit,
    onWandTap: (Offset) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val tintColor = MaterialTheme.colorScheme.primary
    val previewOverlay: ImageBitmap? = remember(currentMask, tintColor) {
        currentMask?.let { mask ->
            val tint = (PREVIEW_TINT_ALPHA shl 24) or (tintColor.toArgb() and 0x00FFFFFF)
            ImageSelectionRenderer.maskPreviewOverlay(mask, tint).asImageBitmap()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxAspect = maxWidth.value / maxHeight.value
        val fittedWidth = if (bitmapAspect > boxAspect) maxWidth else maxHeight * bitmapAspect
        val fittedHeight = if (bitmapAspect > boxAspect) maxWidth / bitmapAspect else maxHeight

        Box(
            Modifier
                .size(fittedWidth, fittedHeight)
                .onSizeChanged { onCanvasSizeChanged(Size(it.width.toFloat(), it.height.toFloat())) }
                .pointerInput(tool) {
                    if (tool == SelectionTool.LASSO) {
                        detectDragGestures(
                            onDragStart = { offset -> onLassoStart(offset) },
                            onDrag = { change, _ -> change.consume(); onLassoContinue(change.position) },
                            onDragEnd = onLassoEnd,
                            onDragCancel = onLassoEnd,
                        )
                    } else {
                        detectTapGestures(onTap = onWandTap)
                    }
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = "Image being selected",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            previewOverlay?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (tool == SelectionTool.LASSO && activePoints.size >= 2) {
                Canvas(Modifier.fillMaxSize()) {
                    val path = Path()
                    activePoints.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
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
