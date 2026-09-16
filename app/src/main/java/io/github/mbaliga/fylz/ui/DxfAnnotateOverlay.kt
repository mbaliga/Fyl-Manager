package io.github.mbaliga.fylz.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.DxfAnnotationService
import io.github.mbaliga.fylz.preview.DxfInkStroke
import io.github.mbaliga.fylz.preview.GeometryPreview
import io.github.mbaliga.fylz.preview.Vec3
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import kotlinx.coroutines.launch

/**
 * Freehand pen markup for a DXF drawing, burned into its own ENTITIES section as a real POLYLINE
 * entity -- not a rasterized copy or a Fylz-only sidecar. See [DxfAnnotationService]'s own KDoc
 * for why a legacy POLYLINE was chosen over LWPOLYLINE.
 *
 * The drawing is shown as a hand-projected wireframe, the same technique
 * `io.github.mbaliga.fylz.ui.components.preview.ModelWireframePreview` uses for every CAD/mesh
 * preview since Fylz bundles no CAD engine -- but fixed, with no rotate/pan/zoom: like every other
 * annotate overlay in this app ([AnnotateOverlay], [PdfAnnotateOverlay]), the whole drawing is fit
 * to one unchanging canvas so a screen point maps to exactly one world point, with no separate
 * view/camera state [DxfAnnotationService] would otherwise have to be told about to save correctly.
 *
 * Always writes to a new file, matching every other annotate/edit path in this app.
 *
 * @param entry the single selected DXF; the caller (`SelectionActionPolicy.annotatesDxf`) has
 *   already established there is exactly one.
 */
@Composable
fun DxfAnnotateOverlay(entry: FileEntry, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var geometry by remember(entry.uri) { mutableStateOf<GeometryPreview?>(null) }
    var loadFailed by remember(entry.uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var color by remember { mutableStateOf(SWATCHES.first().first) }
    var strokeWidthPx by remember { mutableFloatStateOf(STROKE_WIDTHS.first().first) }
    val strokes = remember { mutableStateListOf<DxfInkStroke>() }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(entry.uri) {
        runCatching { DxfAnnotationService.loadGeometry(context, entry.uri, entry.name) }
            .onSuccess { geometry = it }
            .onFailure { loadFailed = true }
    }

    fun save(destinationUri: Uri) {
        val ink = strokes.toList()
        if (ink.isEmpty()) return
        scope.launch {
            busy = true
            runCatching {
                DxfAnnotationService.annotateDrawing(context, entry.uri, entry.name, canvasSize, ink, destinationUri)
            }
                .onSuccess {
                    Toast.makeText(context, "Saved", Toast.LENGTH_LONG).show()
                    onSaved()
                }
                .onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "Unable to save this drawing.", Toast.LENGTH_LONG).show()
                }
            busy = false
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/dxf"),
    ) { destination ->
        if (destination != null) save(destination)
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
                    Text("Annotate drawing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty() && !busy) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo last stroke")
                    }
                    IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty() && !busy) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear all strokes")
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val currentGeometry = geometry
                    when {
                        loadFailed -> Text(
                            "Unable to open this drawing.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        currentGeometry == null -> CircularProgressIndicator()
                        else -> DxfDrawingSurface(
                            geometry = currentGeometry,
                            strokes = strokes,
                            activePoints = activePoints,
                            color = color,
                            strokeWidthPx = strokeWidthPx,
                            onCanvasSizeChanged = { canvasSize = it },
                            onStrokeStart = { activePoints = listOf(it) },
                            onStrokeContinue = { activePoints = activePoints + it },
                            onStrokeEnd = {
                                if (activePoints.size >= 2) {
                                    strokes.add(DxfInkStroke(activePoints, color.toArgb(), strokeWidthPx))
                                }
                                activePoints = emptyList()
                            },
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(SWATCHES) { (swatch, label) ->
                            ColorSwatch(swatch, label, selected = swatch == color, onClick = { color = swatch })
                        }
                    }
                    STROKE_WIDTHS.forEach { (width, label) ->
                        TactileOptionRow(text = label, selected = strokeWidthPx == width, onClick = { strokeWidthPx = width })
                    }
                    Text(
                        "Saves as a new DXF -- the original is never overwritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TactileButton(
                        text = "Save as…",
                        onClick = { saveAsLauncher.launch("annotated_${entry.name}") },
                        enabled = !busy && strokes.isNotEmpty(),
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DxfDrawingSurface(
    geometry: GeometryPreview,
    strokes: List<DxfInkStroke>,
    activePoints: List<Offset>,
    color: Color,
    strokeWidthPx: Float,
    onCanvasSizeChanged: (Size) -> Unit,
    onStrokeStart: (Offset) -> Unit,
    onStrokeContinue: (Offset) -> Unit,
    onStrokeEnd: () -> Unit,
) {
    val bounds = remember(geometry) { DxfAnnotationService.boundsOf(geometry) }
    val drawingAspect = bounds.spanX / bounds.spanY
    val wireframeColor = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxAspect = maxWidth.value / maxHeight.value
        val fittedWidth = if (drawingAspect > boxAspect) maxWidth else maxHeight * drawingAspect
        val fittedHeight = if (drawingAspect > boxAspect) maxWidth / drawingAspect else maxHeight

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
            Canvas(Modifier.fillMaxSize()) {
                val scaleX = size.width / bounds.spanX
                val scaleY = size.height / bounds.spanY
                fun worldToCanvas(vertex: Vec3) = Offset(
                    (vertex.x - bounds.minX) * scaleX,
                    size.height - (vertex.y - bounds.minY) * scaleY,
                )
                geometry.edges.forEach { edge ->
                    val from = geometry.vertices.getOrNull(edge.from) ?: return@forEach
                    val to = geometry.vertices.getOrNull(edge.to) ?: return@forEach
                    drawLine(color = wireframeColor, start = worldToCanvas(from), end = worldToCanvas(to), strokeWidth = 1.2f)
                }
                val live = if (activePoints.size >= 2) {
                    strokes + DxfInkStroke(activePoints, color.toArgb(), strokeWidthPx)
                } else {
                    strokes
                }
                live.forEach { stroke ->
                    if (stroke.points.size < 2) return@forEach
                    val path = Path()
                    stroke.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(
                        path,
                        color = Color(stroke.colorArgb),
                        style = Stroke(width = stroke.strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}
