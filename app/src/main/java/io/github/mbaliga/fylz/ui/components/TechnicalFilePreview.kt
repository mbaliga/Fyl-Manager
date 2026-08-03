package io.github.mbaliga.fylz.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.DrawingPoint
import io.github.mbaliga.fylz.preview.DxfPreviewData
import io.github.mbaliga.fylz.preview.DxfPreviewParser
import io.github.mbaliga.fylz.preview.FileFormatDescriptor
import io.github.mbaliga.fylz.preview.MeshPoint
import io.github.mbaliga.fylz.preview.MeshPreviewData
import io.github.mbaliga.fylz.preview.MeshPreviewParser
import io.github.mbaliga.fylz.preview.PreviewFamily
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
fun TechnicalFilePreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    when (descriptor.rendererId) {
        "mesh-wireframe" -> MeshWireframePreview(entry, modifier)
        "dxf" -> DxfDrawingPreview(entry, modifier)
        else -> UniversalInspectionPreview(entry, descriptor, modifier)
    }
}

@Composable
private fun MeshWireframePreview(entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<MeshPreviewData>?>(null, entry.uri, entry.name) {
        value = withContext(Dispatchers.IO) {
            runCatching { MeshPreviewParser.parse(entry.name, readBounded(context, entry.uri, MeshPreviewParser.MAX_INPUT_BYTES)) }
        }
    }
    PreviewResult(result, modifier) { mesh ->
        val lineColor = MaterialTheme.colorScheme.primary
        Column(modifier.fillMaxSize()) {
            PreviewMetrics(listOf("Format" to mesh.format, "Vertices" to mesh.vertices.size.toString(), "Edges" to mesh.edges.size.toString(), "Faces" to mesh.sourceFaces.toString()), mesh.truncated)
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                if (mesh.vertices.isEmpty() || mesh.edges.isEmpty()) return@Canvas
                val projected = mesh.vertices.map(::projectMesh)
                val bounds = Bounds2.from(projected)
                val scale = bounds.fitScale(size.width, size.height, 28f)
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                mesh.edges.forEach { edge ->
                    val from = projected.getOrNull(edge.from) ?: return@forEach
                    val to = projected.getOrNull(edge.to) ?: return@forEach
                    drawLine(lineColor, Offset(centerX + (from.x - bounds.centerX) * scale, centerY - (from.y - bounds.centerY) * scale), Offset(centerX + (to.x - bounds.centerX) * scale, centerY - (to.y - bounds.centerY) * scale), 1.2f, cap = StrokeCap.Round, alpha = 0.78f)
                }
            }
        }
    }
}

@Composable
private fun DxfDrawingPreview(entry: FileEntry, modifier: Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<DxfPreviewData>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) { runCatching { DxfPreviewParser.parse(readBounded(context, entry.uri, DxfPreviewParser.MAX_INPUT_BYTES)) } }
    }
    PreviewResult(result, modifier) { drawing ->
        val lineColor = MaterialTheme.colorScheme.primary
        Column(modifier.fillMaxSize()) {
            PreviewMetrics(listOf("Entities" to drawing.parsedEntities.toString(), "Segments" to drawing.segments.size.toString(), "Ignored" to drawing.ignoredEntities.toString()), drawing.truncated)
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                if (drawing.segments.isEmpty()) return@Canvas
                val bounds = Bounds2.from(drawing.segments.flatMap { listOf(it.start, it.end) })
                val scale = bounds.fitScale(size.width, size.height, 24f)
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                drawing.segments.forEach { segment ->
                    drawLine(lineColor, Offset(centerX + (segment.start.x - bounds.centerX) * scale, centerY - (segment.start.y - bounds.centerY) * scale), Offset(centerX + (segment.end.x - bounds.centerX) * scale, centerY - (segment.end.y - bounds.centerY) * scale), 1.25f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun UniversalInspectionPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<UniversalInspection>?>(null, entry.uri) {
        value = runCatching { UniversalFileInspector(context.applicationContext).inspect(entry.uri) }
    }
    PreviewResult(result, modifier) { inspection ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Outlined.DataObject, null)
                Column {
                    Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
                    Text(if (descriptor.family == PreviewFamily.BINARY) "Universal binary inspection" else "Structured fallback inspection", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            descriptor.notes?.let { Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
            PreviewMetrics(listOf("Sample" to formatBytes(inspection.sampledBytes.toLong()), "Entropy" to "%.2f bits/byte".format(inspection.entropyBitsPerByte), "Text-like" to if (inspection.probableText) "Yes" else "No", "Signature" to (inspection.detectedSignature ?: "Unknown")), inspection.truncated)
            HorizontalDivider()
            LabelValue("Sample SHA-256", inspection.sha256OfSample)
            LabelValue("Leading bytes", inspection.signatureHex.ifBlank { "Empty file" })
            if (inspection.printableStrings.isNotEmpty()) {
                Text("Extractable strings", style = MaterialTheme.typography.titleSmall)
                Text(inspection.printableStrings.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text("Hex preview", style = MaterialTheme.typography.titleSmall)
            Text(inspection.hexLines.joinToString("\n").ifBlank { "No bytes" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OpenExternalButton(entry)
        }
    }
}

@Composable
private fun <T> PreviewResult(result: Result<T>?, modifier: Modifier, content: @Composable (T) -> Unit) {
    when {
        result == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        result.isSuccess -> content(result.getOrThrow())
        else -> Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(Icons.Outlined.WarningAmber, null)
                Text("Built-in preview unavailable", style = MaterialTheme.typography.titleMedium)
                Text(result.exceptionOrNull()?.message ?: "The file is malformed, too large, or uses an unsupported variant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreviewMetrics(values: List<Pair<String, String>>, truncated: Boolean) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { (label, value) -> Column(Modifier.weight(1f)) { Text(value, style = MaterialTheme.typography.titleSmall); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        if (truncated) Text("Preview was bounded for safety.", color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun LabelValue(label: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }

@Composable
private fun OpenExternalButton(entry: FileEntry) {
    val context = LocalContext.current
    Button(onClick = {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri, entry.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }.onFailure { Toast.makeText(context, "No external viewer is installed for this format.", Toast.LENGTH_SHORT).show() }
    }) { androidx.compose.material3.Icon(Icons.Outlined.OpenInNew, null); Text("Open with another app", Modifier.padding(start = 6.dp)) }
}

private suspend fun readBounded(context: android.content.Context, uri: Uri, maxBytes: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this file.")
    input.use { stream ->
        val output = ByteArrayOutputStream(min(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(total + count <= maxBytes) { "File exceeds the built-in preview limit." }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }
}

private fun projectMesh(point: MeshPoint): DrawingPoint {
    val yaw = 35f / 180f * Math.PI.toFloat()
    val pitch = 25f / 180f * Math.PI.toFloat()
    val x1 = point.x * cos(yaw) - point.z * sin(yaw)
    val z1 = point.x * sin(yaw) + point.z * cos(yaw)
    return DrawingPoint(x1, point.y * cos(pitch) - z1 * sin(pitch))
}

private data class Bounds2(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
    fun fitScale(width: Float, height: Float, padding: Float): Float = min(max(1f, width - padding * 2f) / max(0.0001f, maxX - minX), max(1f, height - padding * 2f) / max(0.0001f, maxY - minY))
    companion object { fun from(points: List<DrawingPoint>) = Bounds2(points.minOfOrNull(DrawingPoint::x) ?: 0f, points.maxOfOrNull(DrawingPoint::x) ?: 1f, points.minOfOrNull(DrawingPoint::y) ?: 0f, points.maxOfOrNull(DrawingPoint::y) ?: 1f) }
}

private fun formatBytes(bytes: Long): String = when { bytes < 1024L -> "$bytes B"; bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"; else -> "${bytes / (1024L * 1024L)} MiB" }
