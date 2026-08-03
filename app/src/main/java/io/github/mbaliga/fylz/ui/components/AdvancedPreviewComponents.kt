package io.github.mbaliga.fylz.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.FileFormatDescriptor
import io.github.mbaliga.fylz.preview.GeometryPreview
import io.github.mbaliga.fylz.preview.GeometryPreviewParser
import io.github.mbaliga.fylz.preview.UniversalFileInspector
import io.github.mbaliga.fylz.preview.UniversalInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun GeometryWireframePreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val result by produceState<Result<GeometryPreview>?>(initialValue = null, entry.uri, descriptor.extension) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val input = context.contentResolver.openInputStream(entry.uri) ?: error("Unable to read this file.")
                val bytes = input.use { stream ->
                    val limit = GeometryPreviewParser.MAX_INPUT_BYTES
                    val output = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(total + count <= limit) { "Geometry preview exceeds the 16 MiB parsing limit." }
                        output.write(buffer, 0, count)
                        total += count
                    }
                    output.toByteArray()
                }
                GeometryPreviewParser.parse(entry.name, bytes)
            }
        }
    }

    when (val current = result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> current.fold(
            onSuccess = { geometry -> WireframeCanvas(geometry, modifier) },
            onFailure = { failure -> UniversalInspectorPreview(entry, descriptor, failure.message, modifier) },
        )
    }
}

@Composable
private fun WireframeCanvas(geometry: GeometryPreview, modifier: Modifier = Modifier) {
    var zoom by remember(geometry) { mutableFloatStateOf(1f) }
    var pan by remember(geometry) { mutableStateOf(Offset.Zero) }
    var yaw by remember(geometry) { mutableFloatStateOf(0.55f) }
    var pitch by remember(geometry) { mutableFloatStateOf(-0.35f) }

    Column(modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(geometry.formatLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${geometry.vertices.size} vertices · ${geometry.edges.size} edges · ${geometry.sourcePrimitiveCount} primitives" +
                            if (geometry.truncated) " · preview limited" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    zoom = 1f
                    pan = Offset.Zero
                }) { Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Fit geometry") }
                IconButton(onClick = { yaw += 0.35f }) {
                    Icon(Icons.Outlined.Rotate90DegreesCcw, contentDescription = "Rotate geometry")
                }
            }
        }
        HorizontalDivider()
        val lineColor = MaterialTheme.colorScheme.primary
        val background = MaterialTheme.colorScheme.surface
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(geometry) {
                    detectTransformGestures { _, drag, scale, rotation ->
                        pan += drag
                        zoom = (zoom * scale).coerceIn(0.15f, 20f)
                        yaw += Math.toRadians(rotation.toDouble()).toFloat()
                    }
                },
        ) {
            drawRect(background)
            if (geometry.vertices.isEmpty() || geometry.edges.isEmpty()) return@Canvas
            val cy = cos(yaw)
            val sy = sin(yaw)
            val cp = cos(pitch)
            val sp = sin(pitch)
            val projected = geometry.vertices.map { vertex ->
                val x1 = vertex.x * cy + vertex.z * sy
                val z1 = -vertex.x * sy + vertex.z * cy
                val y1 = vertex.y * cp - z1 * sp
                Offset(x1, -y1)
            }
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            projected.forEach {
                minX = min(minX, it.x)
                maxX = max(maxX, it.x)
                minY = min(minY, it.y)
                maxY = max(maxY, it.y)
            }
            val width = (maxX - minX).coerceAtLeast(0.0001f)
            val height = (maxY - minY).coerceAtLeast(0.0001f)
            val fit = min(size.width / width, size.height / height) * 0.88f * zoom
            val cx = (minX + maxX) / 2f
            val cy2 = (minY + maxY) / 2f
            fun screen(point: Offset) = Offset(
                size.width / 2f + (point.x - cx) * fit + pan.x,
                size.height / 2f + (point.y - cy2) * fit + pan.y,
            )
            geometry.edges.forEach { edge ->
                val from = projected.getOrNull(edge.from) ?: return@forEach
                val to = projected.getOrNull(edge.to) ?: return@forEach
                drawLine(lineColor, screen(from), screen(to), strokeWidth = 1.2f)
            }
        }
    }
}

@Composable
fun UniversalInspectorPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    rendererFailure: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val inspection by produceState<Result<UniversalInspection>?>(initialValue = null, entry.uri) {
        value = runCatching { UniversalFileInspector(context.applicationContext).inspect(entry.uri) }
    }

    when (val current = inspection) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> current.fold(
            onSuccess = { details -> InspectorContent(entry, descriptor, details, rendererFailure, modifier) },
            onFailure = { failure ->
                GenericOpenFallback(entry, descriptor, failure.message ?: rendererFailure, modifier)
            },
        )
    }
}

@Composable
private fun InspectorContent(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    inspection: UniversalInspection,
    rendererFailure: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    SelectionContainer {
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    rendererFailure != null -> "The semantic renderer could not open this file. A safe bounded inspection is shown instead."
                    descriptor.notes != null -> descriptor.notes
                    else -> "Fylz does not execute this file. The view below is a bounded read-only inspection."
                }.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rendererFailure?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            DetailRow("MIME", entry.mimeType)
            DetailRow("Extension", descriptor.extension.ifBlank { "None" })
            DetailRow("Preview tier", descriptor.depth.name.lowercase().replaceFirstChar(Char::uppercase))
            DetailRow("Detected signature", inspection.detectedSignature ?: "Unknown")
            DetailRow("Sample", "${formatBytes(inspection.sampledBytes.toLong())}${if (inspection.truncated) " of a larger file" else ""}")
            DetailRow("Sample SHA-256", inspection.sha256OfSample)
            DetailRow("Entropy", "%.2f bits/byte".format(inspection.entropyBitsPerByte))
            DetailRow("Likely text", if (inspection.probableText) "Yes" else "No")

            Button(onClick = { openExternal(context, entry) }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                Text("Open with another app", Modifier.padding(start = 6.dp))
            }

            HorizontalDivider()
            Text("Signature bytes", style = MaterialTheme.typography.titleSmall)
            Text(inspection.signatureHex.ifBlank { "Empty file" }, fontFamily = FontFamily.Monospace)

            if (inspection.printableStrings.isNotEmpty()) {
                HorizontalDivider()
                Text("Extracted strings", style = MaterialTheme.typography.titleSmall)
                Text(inspection.printableStrings.joinToString("\n"), fontFamily = FontFamily.Monospace)
            }

            HorizontalDivider()
            Text("Hex preview", style = MaterialTheme.typography.titleSmall)
            Text(inspection.hexLines.joinToString("\n").ifBlank { "Empty file" }, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun GenericOpenFallback(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    message: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
            Text(message ?: "This provider did not expose readable bytes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { openExternal(context, entry) }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                Text("Open with another app", Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun openExternal(context: android.content.Context, entry: FileEntry) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(entry.uri, entry.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No installed app advertises this file type.", Toast.LENGTH_SHORT).show() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
