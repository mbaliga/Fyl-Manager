package io.github.mbaliga.fylz.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.github.mbaliga.fylz.data.AnnotationStroke
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.data.ImageAnnotationRenderer
import io.github.mbaliga.fylz.data.ImageExportFormat
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val SWATCHES = listOf(Color.Red, Color(0xFFFFC107), Color(0xFF2196F3), Color.Black, Color.White)
internal val STROKE_WIDTHS = listOf(6f to "Thin", 16f to "Thick")

/**
 * Freehand pen markup for a single image, burned into the file's own bytes -- not a Fylz-only
 * sidecar, so the result opens anywhere. One canvas, one undo stack; multi-layer or shape/text
 * tools are a later step, not this one.
 *
 * @param entry the single selected image; the caller (`SelectionActionPolicy.annotate` plus an
 *   [ImageExportFormat.SUPPORTED_MIME_TYPES] check) has already established there is exactly one
 *   and it is a format this can save back.
 */
@Composable
fun AnnotateOverlay(entry: FileEntry, repository: DocumentRepository, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(entry.uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var color by remember { mutableStateOf(SWATCHES.first()) }
    var strokeWidthPx by remember { mutableFloatStateOf(STROKE_WIDTHS.first().first) }
    val strokes = remember { mutableStateListOf<AnnotationStroke>() }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(entry.uri) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(entry.uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        if (decoded == null) loadFailed = true else bitmap = decoded
    }

    fun save(destinationUri: Uri, invalidateCache: Boolean) {
        val source = bitmap ?: return
        scope.launch {
            busy = true
            val format = ImageExportFormat.fromMimeType(entry.mimeType) ?: ImageExportFormat.JPEG
            val burned = withContext(Dispatchers.Default) {
                ImageAnnotationRenderer.burnIn(source, strokes.toList(), canvasSize)
            }
            runCatching { repository.writeBitmap(destinationUri, burned, format.compressFormat, format.quality) }
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

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(entry.mimeType),
    ) { destination ->
        if (destination != null) save(destination, invalidateCache = false)
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
                    Text("Annotate", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty() && !busy) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo last stroke")
                    }
                    IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty() && !busy) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear all strokes")
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
                        else -> DrawingSurface(
                            bitmap = bitmap!!,
                            strokes = strokes,
                            activePoints = activePoints,
                            color = color,
                            strokeWidthPx = strokeWidthPx,
                            onCanvasSizeChanged = { canvasSize = it },
                            onStrokeStart = { activePoints = listOf(it) },
                            onStrokeContinue = { activePoints = activePoints + it },
                            onStrokeEnd = {
                                if (activePoints.size >= 2) strokes.add(AnnotationStroke(activePoints, color, strokeWidthPx))
                                activePoints = emptyList()
                            },
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(SWATCHES) { swatch ->
                            ColorSwatch(swatch, selected = swatch == color, onClick = { color = swatch })
                        }
                    }
                    STROKE_WIDTHS.forEach { (width, label) ->
                        TactileOptionRow(text = label, selected = strokeWidthPx == width, onClick = { strokeWidthPx = width })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TactileButton(
                            text = "Save",
                            onClick = { save(entry.uri, invalidateCache = true) },
                            enabled = !busy && bitmap != null,
                            fillWidth = true,
                            modifier = Modifier.weight(1f),
                        )
                        TactileButton(
                            text = "Save as…",
                            onClick = { saveAsLauncher.launch("annotated_${entry.name}") },
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
private fun DrawingSurface(
    bitmap: Bitmap,
    strokes: List<AnnotationStroke>,
    activePoints: List<Offset>,
    color: Color,
    strokeWidthPx: Float,
    onCanvasSizeChanged: (Size) -> Unit,
    onStrokeStart: (Offset) -> Unit,
    onStrokeContinue: (Offset) -> Unit,
    onStrokeEnd: () -> Unit,
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
                        onDragStart = { offset -> onStrokeStart(offset) },
                        onDrag = { change, _ -> change.consume(); onStrokeContinue(change.position) },
                        onDragEnd = onStrokeEnd,
                        onDragCancel = onStrokeEnd,
                    )
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = "Image being annotated",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.fillMaxSize()) {
                val live = if (activePoints.size >= 2) strokes + AnnotationStroke(activePoints, color, strokeWidthPx) else strokes
                live.forEach { stroke ->
                    if (stroke.points.size < 2) return@forEach
                    val path = Path()
                    stroke.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(
                        path,
                        color = stroke.color,
                        style = Stroke(
                            width = stroke.strokeWidthPx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

