package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.DrawingPoint
import io.github.mbaliga.fylz.preview.DxfPreviewData
import io.github.mbaliga.fylz.preview.DxfPreviewParser
import io.github.mbaliga.fylz.preview.FileFormatDescriptor
import io.github.mbaliga.fylz.preview.MeshPoint
import io.github.mbaliga.fylz.preview.MeshPreviewData
import io.github.mbaliga.fylz.preview.MeshPreviewParser
import io.github.mbaliga.fylz.preview.UniversalFileInspector
import io.github.mbaliga.fylz.preview.UniversalInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun UniversalInspectorPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val result by produceState<Result<UniversalInspection>?>(null, entry.uri) {
        value = runCatching { UniversalFileInspector(context.applicationContext).inspect(entry.uri) }
    }
    when (val current = result) {
        null -> LoadingPreview(modifier)
        else -> current.fold(
            onSuccess = { inspection -> UniversalInspectionContent(entry, descriptor, inspection, modifier) },
            onFailure = { failure -> PreviewFailure("Unable to inspect file", failure.message, modifier) },
        )
    }
}

@Composable
private fun UniversalInspectionContent(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    inspection: UniversalInspection,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
        Text(
            buildString {
                append(entry.mimeType)
                descriptor.extension.takeIf(String::isNotBlank)?.let { append(" · .$it") }
                append(" · ${inspection.sampledBytes} bytes sampled")
                if (inspection.truncated) append(" · bounded")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        descriptor.notes?.let {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Metric("Detected", inspection.detectedSignature ?: "Unknown")
            Metric("Entropy", "%.2f bits/B".format(inspection.entropyBitsPerByte))
            Metric("Text-like", if (inspection.probableText) "Yes" else "No")
        }
        HorizontalDivider()
        Text("Signature", style = MaterialTheme.typography.titleSmall)
        MonospaceBlock(inspection.signatureHex)
        Text("Sample SHA-256", style = MaterialTheme.typography.titleSmall)
        MonospaceBlock(inspection.sha256OfSample)
        if (inspection.printableStrings.isNotEmpty()) {
            Text("Extractable strings", style = MaterialTheme.typography.titleSmall)
            MonospaceBlock(inspection.printableStrings.joinToString("\n"))
        }
        Text("Hex", style = MaterialTheme.typography.titleSmall)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            MonospaceBlock(inspection.hexLines.joinToString("\n"))
        }
    }
}

@Composable
fun MeshTechnicalPreview(entry: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<MeshPreviewData>?>(null, entry.uri) {
        value = runCatching {
            val bytes = withContext(Dispatchers.IO) { readBounded(context, entry.uri, MeshPreviewParser.MAX_INPUT_BYTES) }
            MeshPreviewParser.parse(entry.name, bytes)
        }
    }
    when (val current = result) {
        null -> LoadingPreview(modifier)
        else -> current.fold(
            onSuccess = { MeshWireframe(it, modifier) },
            onFailure = { failure -> PreviewFailure("Unable to render model", failure.message, modifier) },
        )
    }
}

@Composable
private fun MeshWireframe(data: MeshPreviewData, modifier: Modifier = Modifier) {
    var yaw by remember { mutableFloatStateOf(-0.55f) }
    var pitch by remember { mutableFloatStateOf(0.35f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(data.format, "${data.vertices.size} vertices")
            Metric("Edges", data.edges.size.toString())
            Metric("Faces", data.sourceFaces.toString())
            if (data.truncated) Metric("Preview", "Bounded")
        }
        HorizontalDivider()
        if (data.empty) {
            PreviewFailure("No renderable linework", "The format was recognized, but no bounded mesh edges were found.", Modifier.fillMaxSize())
            return@Column
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, rotation ->
                        pan += panChange
                        zoom = (zoom * zoomChange).coerceIn(0.1f, 20f)
                        yaw += rotation * 0.01f
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        yaw += drag.x * 0.008f
                        pitch = (pitch + drag.y * 0.008f).coerceIn(-1.55f, 1.55f)
                    }
                },
        ) {
            val projected = projectMesh(data.vertices, yaw, pitch)
            val bounds = bounds2(projected)
            val width = max(0.0001f, bounds.second.x - bounds.first.x)
            val height = max(0.0001f, bounds.second.y - bounds.first.y)
            val scale = min(size.width / width, size.height / height) * 0.82f * zoom
            val centre = Offset((bounds.first.x + bounds.second.x) / 2f, (bounds.first.y + bounds.second.y) / 2f)
            fun screen(point: Offset) = Offset(
                size.width / 2f + (point.x - centre.x) * scale + pan.x,
                size.height / 2f - (point.y - centre.y) * scale + pan.y,
            )
            data.edges.forEach { edge ->
                val first = projected.getOrNull(edge.from) ?: return@forEach
                val second = projected.getOrNull(edge.to) ?: return@forEach
                drawLine(MaterialTheme.colorScheme.primary, screen(first), screen(second), strokeWidth = 1.2f)
            }
        }
    }
}

@Composable
fun DxfTechnicalPreview(entry: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<DxfPreviewData>?>(null, entry.uri) {
        value = runCatching {
            val bytes = withContext(Dispatchers.IO) { readBounded(context, entry.uri, DxfPreviewParser.MAX_INPUT_BYTES) }
            DxfPreviewParser.parse(bytes)
        }
    }
    when (val current = result) {
        null -> LoadingPreview(modifier)
        else -> current.fold(
            onSuccess = { DxfCanvas(it, modifier) },
            onFailure = { failure -> PreviewFailure("Unable to render DXF", failure.message, modifier) },
        )
    }
}

@Composable
private fun DxfCanvas(data: DxfPreviewData, modifier: Modifier = Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric("Entities", data.parsedEntities.toString())
            Metric("Ignored", data.ignoredEntities.toString())
            Metric("Segments", data.segments.size.toString())
            if (data.truncated) Metric("Preview", "Bounded")
        }
        HorizontalDivider()
        if (data.segments.isEmpty()) {
            PreviewFailure("No supported linework", "The DXF opened, but its visible entities require a renderer beyond the built-in LINE/polyline/circle/arc subset.", Modifier.fillMaxSize())
            return@Column
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    pan += panChange
                    zoom = (zoom * zoomChange).coerceIn(0.05f, 100f)
                }
            },
        ) {
            val points = data.segments.flatMap { listOf(it.start, it.end) }
            val minX = points.minOf(DrawingPoint::x); val maxX = points.maxOf(DrawingPoint::x)
            val minY = points.minOf(DrawingPoint::y); val maxY = points.maxOf(DrawingPoint::y)
            val drawingWidth = max(0.0001f, maxX - minX); val drawingHeight = max(0.0001f, maxY - minY)
            val scale = min(size.width / drawingWidth, size.height / drawingHeight) * 0.88f * zoom
            val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
            fun screen(point: DrawingPoint) = Offset(
                size.width / 2f + (point.x - cx) * scale + pan.x,
                size.height / 2f - (point.y - cy) * scale + pan.y,
            )
            data.segments.forEach { segment ->
                drawLine(MaterialTheme.colorScheme.primary, screen(segment.start), screen(segment.end), strokeWidth = 1.1f)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonospaceBlock(value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Text(value, modifier = Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoadingPreview(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun PreviewFailure(title: String, detail: String?, modifier: Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun projectMesh(points: List<MeshPoint>, yaw: Float, pitch: Float): List<Offset> {
    val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
    return points.map { point ->
        val x1 = point.x * cy + point.z * sy
        val z1 = -point.x * sy + point.z * cy
        val y2 = point.y * cp - z1 * sp
        Offset(x1, y2)
    }
}

private fun bounds2(points: List<Offset>): Pair<Offset, Offset> {
    if (points.isEmpty()) return Offset.Zero to Offset(1f, 1f)
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    points.forEach { point ->
        minX = min(minX, point.x); minY = min(minY, point.y)
        maxX = max(maxX, point.x); maxY = max(maxY, point.y)
    }
    return Offset(minX, minY) to Offset(maxX, maxY)
}

private fun readBounded(context: android.content.Context, uri: android.net.Uri, limit: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read preview data.")
    input.use { stream ->
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() < limit) {
            val count = stream.read(buffer, 0, min(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        if (stream.read() >= 0) error("Preview input exceeds ${limit / (1024 * 1024)} MiB. Use the universal inspector or an external specialist viewer.")
        return output.toByteArray()
    }
}
