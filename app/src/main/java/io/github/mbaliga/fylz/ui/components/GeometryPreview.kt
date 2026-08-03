package io.github.mbaliga.fylz.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.preview.DxfPreviewData
import io.github.mbaliga.fylz.preview.DxfPreviewParser
import io.github.mbaliga.fylz.preview.DrawingPoint
import io.github.mbaliga.fylz.preview.MeshPoint
import io.github.mbaliga.fylz.preview.MeshPreviewData
import io.github.mbaliga.fylz.preview.MeshPreviewParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun MeshFilePreview(name: String, uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<MeshPreviewData>?>(null, uri, name) {
        value = runCatching {
            val bytes = readBounded(context, uri, MeshPreviewParser.MAX_INPUT_BYTES)
            MeshPreviewParser.parse(name, bytes)
        }
    }
    ResultFrame(result, modifier) { mesh -> MeshCanvas(mesh, Modifier.fillMaxSize()) }
}

@Composable
fun DxfFilePreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<DxfPreviewData>?>(null, uri) {
        value = runCatching {
            val bytes = readBounded(context, uri, DxfPreviewParser.MAX_INPUT_BYTES)
            DxfPreviewParser.parse(bytes)
        }
    }
    ResultFrame(result, modifier) { drawing -> DxfCanvas(drawing, Modifier.fillMaxSize()) }
}

@Composable
private fun <T> ResultFrame(
    result: Result<T>?,
    modifier: Modifier,
    content: @Composable (T) -> Unit,
) {
    when (result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = content,
            onFailure = { failure ->
                Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Preview unavailable", style = MaterialTheme.typography.titleMedium)
                        Text(
                            failure.message ?: "This file could not be safely rendered.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun MeshCanvas(mesh: MeshPreviewData, modifier: Modifier = Modifier) {
    var yaw by remember(mesh) { mutableFloatStateOf(0.5f) }
    var pitch by remember(mesh) { mutableFloatStateOf(-0.35f) }
    var zoom by remember(mesh) { mutableFloatStateOf(1f) }
    var pan by remember(mesh) { mutableStateOf(Offset.Zero) }
    val lineColor = MaterialTheme.colorScheme.primary
    val infoColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(mesh) {
                detectTransformGestures { _, panChange, zoomChange, rotation ->
                    pan += panChange
                    zoom = (zoom * zoomChange).coerceIn(0.1f, 20f)
                    yaw += rotation * 0.015f
                    pitch = (pitch + panChange.y * 0.004f).coerceIn(-1.5f, 1.5f)
                }
            },
        ) {
            if (mesh.vertices.isEmpty()) return@Canvas
            val rotated = mesh.vertices.map { rotate(it, yaw, pitch) }
            val minX = rotated.minOf { it.x }
            val maxX = rotated.maxOf { it.x }
            val minY = rotated.minOf { it.y }
            val maxY = rotated.maxOf { it.y }
            val width = max(maxX - minX, 0.0001f)
            val height = max(maxY - minY, 0.0001f)
            val scale = min(size.width / width, size.height / height) * 0.82f * zoom
            val centerX = (minX + maxX) / 2f
            val centerY = (minY + maxY) / 2f
            fun project(point: MeshPoint) = Offset(
                size.width / 2f + (point.x - centerX) * scale + pan.x,
                size.height / 2f - (point.y - centerY) * scale + pan.y,
            )
            mesh.edges.forEach { edge ->
                val start = rotated.getOrNull(edge.from) ?: return@forEach
                val end = rotated.getOrNull(edge.to) ?: return@forEach
                drawLine(lineColor, project(start), project(end), strokeWidth = 1.2f)
            }
        }
        Surface(
            color = infoColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(mesh.format, style = MaterialTheme.typography.labelMedium)
                Text("${mesh.vertices.size} vertices", style = MaterialTheme.typography.labelSmall)
                Text("${mesh.edges.size} edges", style = MaterialTheme.typography.labelSmall)
                if (mesh.truncated) Text("Preview limited", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DxfCanvas(drawing: DxfPreviewData, modifier: Modifier = Modifier) {
    var zoom by remember(drawing) { mutableFloatStateOf(1f) }
    var pan by remember(drawing) { mutableStateOf(Offset.Zero) }
    val lineColor = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(drawing) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    pan += panChange
                    zoom = (zoom * zoomChange).coerceIn(0.1f, 50f)
                }
            },
        ) {
            val points = drawing.segments.flatMap { listOf(it.start, it.end) }
            if (points.isEmpty()) return@Canvas
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val width = max(maxX - minX, 0.0001f)
            val height = max(maxY - minY, 0.0001f)
            val scale = min(size.width / width, size.height / height) * 0.88f * zoom
            val centerX = (minX + maxX) / 2f
            val centerY = (minY + maxY) / 2f
            fun project(point: DrawingPoint) = Offset(
                size.width / 2f + (point.x - centerX) * scale + pan.x,
                size.height / 2f - (point.y - centerY) * scale + pan.y,
            )
            drawing.segments.forEach { drawLine(lineColor, project(it.start), project(it.end), strokeWidth = 1.3f) }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DXF", style = MaterialTheme.typography.labelMedium)
                Text("${drawing.parsedEntities} entities", style = MaterialTheme.typography.labelSmall)
                if (drawing.ignoredEntities > 0) Text("${drawing.ignoredEntities} unsupported", style = MaterialTheme.typography.labelSmall)
                if (drawing.truncated) Text("Preview limited", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun rotate(point: MeshPoint, yaw: Float, pitch: Float): MeshPoint {
    val cy = cos(yaw)
    val sy = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)
    val x = point.x * cy + point.z * sy
    val z = -point.x * sy + point.z * cy
    return MeshPoint(x, point.y * cp - z * sp, point.y * sp + z * cp)
}

private suspend fun readBounded(context: Context, uri: Uri, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this file.")
    input.use { stream ->
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "File exceeds the built-in preview limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
