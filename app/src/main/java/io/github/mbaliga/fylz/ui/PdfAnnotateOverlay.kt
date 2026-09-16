package io.github.mbaliga.fylz.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.pdf.PdfAnnotationService
import io.github.mbaliga.fylz.pdf.PdfInkStroke
import io.github.mbaliga.fylz.pdf.PdfToolService
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import kotlinx.coroutines.launch

/**
 * Freehand pen markup for a PDF, burned into each drawn-on page's own content stream as real
 * vector drawing operators -- not a rasterized copy. See [PdfAnnotationService]'s own KDoc for
 * why that is the right trade-off given what pdfbox-android's port actually ships.
 *
 * Unlike [AnnotateOverlay] (image annotation, which can overwrite the source), this always writes
 * to a new file: there is no in-place edit of a PDF's own bytes anywhere in this app. Strokes are
 * kept per page as the user moves between them -- switching pages no longer discards anything --
 * and one "Save as…" burns every marked-up page into one output PDF via
 * [PdfAnnotationService.annotatePages].
 *
 * @param entry the single selected PDF; the caller (`SelectionActionPolicy.annotatePdf`) has
 *   already established there is exactly one.
 */
@Composable
fun PdfAnnotateOverlay(entry: FileEntry, service: PdfToolService, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pageCount by remember(entry.uri) { mutableStateOf<Int?>(null) }
    var documentFailed by remember(entry.uri) { mutableStateOf(false) }
    var pageIndex by remember(entry.uri) { mutableIntStateOf(0) }
    var pageBitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    // Keyed by page too, unlike documentFailed: a render failure is about the one page being
    // shown, not the document as a whole, so it must not follow the user to a page that would
    // otherwise render fine -- turning one bad page into a poisoned overlay.
    var pageLoadFailed by remember(entry.uri, pageIndex) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(SWATCHES.first().first) }
    var strokeWidthPx by remember { mutableFloatStateOf(STROKE_WIDTHS.first().first) }
    // Per page, not one flat list: switching pages used to silently drop whatever had been
    // drawn on the page just left. canvasSizeByPage doesn't need to be observable -- it is only
    // ever read once, at save time.
    val strokesByPage = remember(entry.uri) { mutableStateMapOf<Int, List<PdfInkStroke>>() }
    val canvasSizeByPage = remember(entry.uri) { mutableMapOf<Int, Size>() }
    var activePoints by remember(pageIndex) { mutableStateOf<List<Offset>>(emptyList()) }
    val currentPageStrokes = strokesByPage[pageIndex].orEmpty()
    val hasAnyStrokes = strokesByPage.values.any { it.isNotEmpty() }

    LaunchedEffect(entry.uri) {
        runCatching { service.inspect(entry.uri) }
            .onSuccess { pageCount = it.pageCount }
            .onFailure { documentFailed = true }
    }

    LaunchedEffect(entry.uri, pageIndex) {
        if (pageCount == null) return@LaunchedEffect
        pageBitmap = null
        runCatching { service.renderPagePreview(entry.uri, pageIndex) }
            .onSuccess { pageBitmap = it }
            .onFailure { pageLoadFailed = true }
    }

    fun save(destinationUri: Uri) {
        val pages = strokesByPage.filterValues { it.isNotEmpty() }
        if (pages.isEmpty()) return
        scope.launch {
            busy = true
            runCatching {
                PdfAnnotationService.annotatePages(context, entry.uri, pages, canvasSizeByPage.toMap(), destinationUri)
            }
                .onSuccess {
                    Toast.makeText(context, "Saved", Toast.LENGTH_LONG).show()
                    onSaved()
                }
                .onFailure { failure ->
                    Toast.makeText(context, failure.message ?: "Unable to save the PDF.", Toast.LENGTH_LONG).show()
                }
            busy = false
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { destination ->
        if (destination != null) save(destination)
    }

    fun requestDismiss() {
        if (busy) return
        if (hasAnyStrokes) confirmDiscard = true else onDismiss()
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::requestDismiss, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Cancel")
                    }
                    Text("Annotate PDF", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { strokesByPage[pageIndex] = currentPageStrokes.dropLast(1) },
                        enabled = currentPageStrokes.isNotEmpty() && !busy,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo last stroke on this page")
                    }
                    IconButton(
                        onClick = { strokesByPage[pageIndex] = emptyList() },
                        enabled = currentPageStrokes.isNotEmpty() && !busy,
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear this page's strokes")
                    }
                }

                pageCount?.let { count ->
                    if (count > 1) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            IconButton(onClick = { pageIndex -= 1 }, enabled = pageIndex > 0 && !busy) {
                                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous page")
                            }
                            Text(
                                "Page ${pageIndex + 1} of $count" + if (strokesByPage[pageIndex]?.isNotEmpty() == true) " · marked up" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { pageIndex += 1 }, enabled = pageIndex < count - 1 && !busy) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = "Next page")
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        documentFailed -> Text(
                            "Unable to open this PDF.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        pageLoadFailed -> Text(
                            "Unable to open this page. Try another page, or come back to this one later.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        pageBitmap == null -> CircularProgressIndicator()
                        else -> PdfDrawingSurface(
                            bitmap = pageBitmap!!,
                            strokes = currentPageStrokes,
                            activePoints = activePoints,
                            color = color,
                            strokeWidthPx = strokeWidthPx,
                            onCanvasSizeChanged = { canvasSizeByPage[pageIndex] = it },
                            onStrokeStart = { activePoints = listOf(it) },
                            onStrokeContinue = { activePoints = activePoints + it },
                            onStrokeEnd = {
                                if (activePoints.size >= 2) {
                                    strokesByPage[pageIndex] =
                                        currentPageStrokes + PdfInkStroke(activePoints, color.toArgb(), strokeWidthPx)
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
                        "Saves every page you've drawn on into one new PDF -- the original is never overwritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TactileButton(
                        text = if (busy) "Saving…" else "Save as…",
                        onClick = { saveAsLauncher.launch("annotated_${entry.name}") },
                        enabled = !busy && hasAnyStrokes,
                        fillWidth = true,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your markup?") },
            text = { Text("The ink you've drawn hasn't been saved. Leaving now discards it.") },
            confirmButton = {
                TactileButton(
                    text = "Discard",
                    style = TactileButtonStyle.DESTRUCTIVE,
                    onClick = { confirmDiscard = false; onDismiss() },
                )
            },
            dismissButton = {
                TactileButton(text = "Keep drawing", style = TactileButtonStyle.SECONDARY, onClick = { confirmDiscard = false })
            },
        )
    }
}

@Composable
private fun PdfDrawingSurface(
    bitmap: Bitmap,
    strokes: List<PdfInkStroke>,
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
                contentDescription = "PDF page being annotated",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.fillMaxSize()) {
                val live = if (activePoints.size >= 2) {
                    strokes + PdfInkStroke(activePoints, color.toArgb(), strokeWidthPx)
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
