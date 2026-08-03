package io.github.mbaliga.fylz.preview

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val MAX_GEOMETRY_BYTES = 32 * 1024 * 1024

@Composable
fun MeshFilePreview(name: String, uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<MeshPreviewData>?>(null, uri, name) {
        value = withContext(Dispatchers.IO) {
            runCatching { MeshParser.parse(name, readBounded(context, uri, MAX_GEOMETRY_BYTES)) }
        }
    }
    when (val current = result) {
        null -> LoadingGeometry(modifier)
        else -> current.fold(
            onSuccess = { MeshCanvas(it, modifier) },
            onFailure = { GeometryFailure(it.message ?: "Unable to render this model.", modifier) },
        )
    }
}

@Composable
fun DxfFilePreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<DrawingPreviewData>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { DxfParser.parse(readBounded(context, uri, DxfParser.MAX_INPUT_BYTES)) }
        }
    }
    when (val current = result) {
        null -> LoadingGeometry(modifier)
        else -> current.fold(
            onSuccess = { DrawingCanvas(it, modifier) },
            onFailure = { GeometryFailure(it.message ?: "Unable to render this drawing.", modifier) },
        )
    }
}

@Composable
private fun MeshCanvas(data: MeshPreviewData, modifier: Modifier) {
    var yaw by remember { mutableFloatStateOf(-28f) }
    var pitch by remember { mutableFloatStateOf(18f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val lineColor = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxSize()) {
        GeometryHeader(
            title = data.formatLabel,
            detail = "${data.vertices.size} vertices · ${data.edges.size} edges · ${data.faceCount} faces${if (data.truncated) " · limited" else ""}",
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(data) {
                    detectTransformGestures { _, panChange, zoomChange, rotation ->
                        pan += panChange
                        zoom = (zoom * zoomChange).coerceIn(0.2f, 12f)
                        yaw += rotation
                    }
                }
                .pointerInput(data) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        yaw += drag.x * 0.35f
                        pitch = (pitch - drag.y * 0.35f).coerceIn(-89f, 89f)
                    }
                },
        ) {
            if (data.vertices.isEmpty()) return@Canvas
            val center = data.bounds.center
            val scale = min(size.width, size.height) * 0.42f / data.bounds.span * zoom
            val yawRadians = yaw / 180f * PI.toFloat()
            val pitchRadians = pitch / 180f * PI.toFloat()
            val cy = cos(yawRadians); val sy = sin(yawRadians)
            val cp = cos(pitchRadians); val sp = sin(pitchRadians)
            val projected = data.vertices.map { point ->
                val x = point.x - center.x
                val y = point.y - center.y
                val z = point.z - center.z
                val rx = x * cy - z * sy
                val rz = x * sy + z * cy
                val ry = y * cp - rz * sp
                Offset(size.width / 2f + pan.x + rx * scale, size.height / 2f + pan.y - ry * scale)
            }
            data.edges.forEach { edge ->
                val start = projected.getOrNull(edge.start) ?: return@forEach
                val end = projected.getOrNull(edge.end) ?: return@forEach
                drawLine(lineColor, start, end, strokeWidth = 1.2f)
            }
        }
    }
}

@Composable
private fun DrawingCanvas(data: DrawingPreviewData, modifier: Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val lineColor = MaterialTheme.colorScheme.primary
    val points = remember(data) { data.points().filter { it.x.isFinite() && it.y.isFinite() }.toList() }
    Column(modifier.fillMaxSize()) {
        GeometryHeader(
            title = data.formatLabel,
            detail = "${data.segments.size} segments · ${data.circles.size} circles · ${data.arcs.size} arcs${if (data.truncated) " · limited" else ""}",
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(data) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        pan += panChange
                        zoom = (zoom * zoomChange).coerceIn(0.1f, 30f)
                    }
                },
        ) {
            if (points.isEmpty()) return@Canvas
            val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
            val spanX = max(maxX - minX, 0.001f)
            val spanY = max(maxY - minY, 0.001f)
            val scale = min(size.width / spanX, size.height / spanY) * 0.9f * zoom
            val centerX = (minX + maxX) / 2f
            val centerY = (minY + maxY) / 2f
            fun map(point: Point2) = Offset(
                size.width / 2f + pan.x + (point.x - centerX) * scale,
                size.height / 2f + pan.y - (point.y - centerY) * scale,
            )
            data.segments.forEach { drawLine(lineColor, map(it.start), map(it.end), strokeWidth = 1.3f) }
            data.circles.forEach {
                drawCircle(lineColor, radius = it.radius * scale, center = map(it.center), style = Stroke(1.3f))
            }
            data.arcs.forEach {
                val topLeft = map(Point2(it.center.x - it.radius, it.center.y + it.radius))
                val diameter = it.radius * 2f * scale
                var sweep = it.endDegrees - it.startDegrees
                if (sweep <= 0f) sweep += 360f
                drawArc(
                    color = lineColor,
                    startAngle = -it.endDegrees,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(1.3f),
                )
            }
        }
    }
}

@Composable
private fun GeometryHeader(title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Drag to rotate · pinch to zoom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingGeometry(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun GeometryFailure(message: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun readBounded(context: Context, uri: Uri, maxBytes: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this file.")
    return input.use { stream ->
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "File exceeds the in-app geometry preview limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
