package io.github.mbaliga.fylz.preview

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun MeshWireframePreview(
    uri: Uri,
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val result by produceState<Result<MeshPreviewData>?>(initialValue = null, uri, fileName) {
        value = runCatching { loadMesh(context.applicationContext, uri, fileName) }
    }

    when (val state = result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> state.fold(
            onSuccess = { data -> MeshCanvas(data, modifier) },
            onFailure = { failure -> UniversalPreviewError("Unable to render 3D model", failure.message, modifier) },
        )
    }
}

@Composable
private fun MeshCanvas(data: MeshPreviewData, modifier: Modifier) {
    var rotationX by remember { mutableFloatStateOf(-0.45f) }
    var rotationY by remember { mutableFloatStateOf(0.65f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val normalized = remember(data) { normalize(data.vertices) }

    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(data.format, style = MaterialTheme.typography.labelMedium)
                Text("${data.vertices.size} vertices", style = MaterialTheme.typography.labelMedium)
                Text("${data.sourceFaces} faces", style = MaterialTheme.typography.labelMedium)
                if (data.truncated) Text("Preview limited", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        Box(
            Modifier
                .weight(1f)
                .pointerInput(data) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        rotationY += pan.x / 280f
                        rotationX += pan.y / 280f
                        zoom = (zoom * gestureZoom).coerceIn(0.35f, 8f)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val projected = normalized.map { point ->
                    val cy = cos(rotationY); val sy = sin(rotationY)
                    val cx = cos(rotationX); val sx = sin(rotationX)
                    val x1 = point.x * cy + point.z * sy
                    val z1 = -point.x * sy + point.z * cy
                    val y1 = point.y * cx - z1 * sx
                    val z2 = point.y * sx + z1 * cx
                    val perspective = 1f / (1.8f + z2 * 0.65f).coerceAtLeast(0.25f)
                    Offset(x1 * perspective, y1 * perspective)
                }
                val scale = min(size.width, size.height) * 0.78f * zoom
                val center = Offset(size.width / 2f, size.height / 2f)
                data.edges.forEach { edge ->
                    if (edge.from !in projected.indices || edge.to !in projected.indices) return@forEach
                    val a = projected[edge.from]
                    val b = projected[edge.to]
                    drawLine(
                        color = lineColor,
                        start = center + Offset(a.x * scale, -a.y * scale),
                        end = center + Offset(b.x * scale, -b.y * scale),
                        strokeWidth = 1.2f,
                    )
                }
                drawCircle(axisColor, radius = 3f, center = center, style = Stroke(width = 1f))
            }
        }
        Text(
            "Drag to rotate · pinch to zoom",
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun loadMesh(context: Context, uri: Uri, name: String): MeshPreviewData = withContext(Dispatchers.IO) {
    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    if (size != null && size > MeshPreviewParser.MAX_INPUT_BYTES) {
        error("3D model exceeds the ${MeshPreviewParser.MAX_INPUT_BYTES / 1024 / 1024} MiB preview limit.")
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MeshPreviewParser.MAX_INPUT_BYTES) { "3D model exceeds the preview limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Unable to read this 3D model.")
    MeshPreviewParser.parse(name, bytes)
}

private fun normalize(vertices: List<MeshPoint>): List<MeshPoint> {
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
    vertices.forEach {
        minX = min(minX, it.x); minY = min(minY, it.y); minZ = min(minZ, it.z)
        maxX = max(maxX, it.x); maxY = max(maxY, it.y); maxZ = max(maxZ, it.z)
    }
    val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f; val cz = (minZ + maxZ) / 2f
    val extent = max(max(maxX - minX, maxY - minY), maxZ - minZ).takeIf { it.isFinite() && it > 0f } ?: 1f
    return vertices.map { MeshPoint((it.x - cx) / extent, (it.y - cy) / extent, (it.z - cz) / extent) }
}

@Composable
internal fun UniversalPreviewError(title: String, detail: String?, modifier: Modifier = Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
