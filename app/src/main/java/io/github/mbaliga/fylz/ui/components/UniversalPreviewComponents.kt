package io.github.mbaliga.fylz.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
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
            onSuccess = { inspection -> InspectorContent(entry, descriptor, inspection, modifier) },
            onFailure = { failure -> PreviewFailure(entry, failure.message ?: "Unable to inspect this file.", modifier) },
        )
    }
}

@Composable
fun MeshWireframePreview(entry: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<MeshPreviewData>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readBounded(context.contentResolver, entry.uri, MeshPreviewParser.MAX_INPUT_BYTES)
                MeshPreviewParser.parse(entry.name, bytes)
            }
        }
    }
    when (val current = result) {
        null -> LoadingPreview(modifier)
        else -> current.fold(
            onSuccess = { mesh -> MeshCanvas(mesh, modifier) },
            onFailure = { failure -> PreviewFailure(entry, failure.message ?: "Unable to parse this 3D model.", modifier) },
        )
    }
}

@Composable
fun DxfDrawingPreview(entry: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<DxfPreviewData>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readBounded(context.contentResolver, entry.uri, DxfPreviewParser.MAX_INPUT_BYTES)
                DxfPreviewParser.parse(bytes)
            }
        }
    }
    when (val current = result) {
        null -> LoadingPreview(modifier)
        else -> current.fold(
            onSuccess = { drawing -> DxfCanvas(drawing, modifier) },
            onFailure = { failure -> PreviewFailure(entry, failure.message ?: "Unable to parse this DXF drawing.", modifier) },
        )
    }
}

@Composable
private fun MeshCanvas(mesh: MeshPreviewData, modifier: Modifier) {
    var yaw by remember { mutableFloatStateOf(-0.65f) }
    var pitch by remember { mutableFloatStateOf(0.45f) }
    val lineColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer

    Column(modifier.fillMaxSize()) {
        Surface(color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.ViewInAr, contentDescription = null)
                Text(
                    "${mesh.format} · ${mesh.vertices.size} vertices · ${mesh.sourceFaces} faces",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.RotateRight, contentDescription = null)
                Text("Drag to rotate", style = MaterialTheme.typography.labelSmall)
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(mesh) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        yaw += delta.x / 240f
                        pitch = (pitch + delta.y / 240f).coerceIn(-1.45f, 1.45f)
                    }
                },
        ) {
            if (mesh.vertices.isEmpty()) return@Canvas
            val projected = mesh.vertices.map { point -> project(point, yaw, pitch) }
            val minX = projected.minOf { it.x }
            val maxX = projected.maxOf { it.x }
            val minY = projected.minOf { it.y }
            val maxY = projected.maxOf { it.y }
            val spanX = (maxX - minX).coerceAtLeast(0.0001f)
            val spanY = (maxY - minY).coerceAtLeast(0.0001f)
            val scale = min(size.width * 0.9f / spanX, size.height * 0.9f / spanY)
            val offsetX = (size.width - spanX * scale) / 2f - minX * scale
            val offsetY = (size.height - spanY * scale) / 2f + maxY * scale
            mesh.edges.forEach { edge ->
                val first = projected.getOrNull(edge.from) ?: return@forEach
                val second = projected.getOrNull(edge.to) ?: return@forEach
                drawLine(
                    color = lineColor,
                    start = Offset(first.x * scale + offsetX, offsetY - first.y * scale),
                    end = Offset(second.x * scale + offsetX, offsetY - second.y * scale),
                    strokeWidth = 1.2f,
                )
            }
            if (mesh.edges.isEmpty()) {
                projected.take(50_000).forEach { point ->
                    drawCircle(lineColor, radius = 1.5f, center = Offset(point.x * scale + offsetX, offsetY - point.y * scale))
                }
            }
        }
        if (mesh.truncated) {
            Text(
                "Preview simplified at safety limits; the original file was not modified.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun DxfCanvas(drawing: DxfPreviewData, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Text(
                "DXF · ${drawing.parsedEntities} rendered entities · ${drawing.ignoredEntities} unsupported entities",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            if (drawing.segments.isEmpty()) return@Canvas
            val minX = drawing.segments.minOf { min(it.start.x, it.end.x) }
            val maxX = drawing.segments.maxOf { max(it.start.x, it.end.x) }
            val minY = drawing.segments.minOf { min(it.start.y, it.end.y) }
            val maxY = drawing.segments.maxOf { max(it.start.y, it.end.y) }
            val spanX = (maxX - minX).coerceAtLeast(0.0001f)
            val spanY = (maxY - minY).coerceAtLeast(0.0001f)
            val scale = min(size.width / spanX, size.height / spanY) * 0.94f
            val offsetX = (size.width - spanX * scale) / 2f - minX * scale
            val offsetY = (size.height - spanY * scale) / 2f + maxY * scale
            drawing.segments.forEach { segment ->
                drawLine(
                    lineColor,
                    Offset(segment.start.x * scale + offsetX, offsetY - segment.start.y * scale),
                    Offset(segment.end.x * scale + offsetX, offsetY - segment.end.y * scale),
                    strokeWidth = 1.3f,
                )
            }
        }
        if (drawing.truncated) {
            Text("Drawing was simplified at preview limits.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
private fun InspectorContent(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    inspection: UniversalInspection,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(34.dp))
            Column(Modifier.weight(1f)) {
                Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    descriptor.extension.takeIf(String::isNotBlank)?.let { ".$it · ${entry.mimeType}" } ?: entry.mimeType,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        descriptor.notes?.let {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }
        InspectorRow("Detected signature", inspection.detectedSignature ?: "No known signature in sample")
        InspectorRow("Sample", "${inspection.sampledBytes} bytes${if (inspection.truncated) " (bounded)" else ""}")
        InspectorRow("Sample SHA-256", inspection.sha256OfSample)
        InspectorRow("Entropy", "%.3f bits/byte".format(inspection.entropyBitsPerByte))
        InspectorRow("Text likelihood", if (inspection.probableText) "Likely text" else "Binary or encoded")
        HorizontalDivider()
        Text("Header", style = MaterialTheme.typography.titleSmall)
        Text(inspection.signatureHex.ifBlank { "Empty file" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (inspection.printableStrings.isNotEmpty()) {
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DataObject, contentDescription = null)
                Text("Extractable strings", style = MaterialTheme.typography.titleSmall)
            }
            inspection.printableStrings.take(80).forEach {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Code, contentDescription = null)
            Text("Hex preview", style = MaterialTheme.typography.titleSmall)
        }
        Text(inspection.hexLines.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        ExternalOpenButton(entry)
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (label.contains("SHA")) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun PreviewFailure(entry: FileEntry, message: String, modifier: Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            ExternalOpenButton(entry)
        }
    }
}

@Composable
private fun ExternalOpenButton(entry: FileEntry) {
    val context = LocalContext.current
    Button(onClick = {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(entry.uri, entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "No installed app handles this format.", Toast.LENGTH_SHORT).show() }
    }) {
        Icon(Icons.Outlined.OpenInNew, contentDescription = null)
        Text("Open with another app", Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun LoadingPreview(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private fun project(point: MeshPoint, yaw: Float, pitch: Float): Offset {
    val cy = cos(yaw)
    val sy = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)
    val rotatedX = point.x * cy - point.z * sy
    val rotatedZ = point.x * sy + point.z * cy
    val rotatedY = point.y * cp - rotatedZ * sp
    return Offset(rotatedX, rotatedY)
}

private fun readBounded(resolver: android.content.ContentResolver, uri: Uri, maxBytes: Int): ByteArray {
    resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "File exceeds the built-in preview limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    error("Unable to read this file.")
}
