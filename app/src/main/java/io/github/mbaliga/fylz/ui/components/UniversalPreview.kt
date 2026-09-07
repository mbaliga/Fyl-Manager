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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.preview.GeometryPreview
import io.github.mbaliga.fylz.preview.GeometryPreviewParser
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.preview.UniversalFileInspector
import io.github.mbaliga.fylz.preview.UniversalInspection
import io.github.mbaliga.fylz.preview.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun GeometryFilePreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val parsed by produceState<Result<GeometryPreview>?>(null, entry.uri, entry.name) {
        value = runCatching {
            val bytes = readBounded(context, entry.uri, GeometryPreviewParser.MAX_INPUT_BYTES)
            GeometryPreviewParser.parse(entry.name, bytes)
        }
    }
    when (val result = parsed) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { geometry -> GeometryCanvas(geometry, descriptor.family, modifier) },
            onFailure = {
                UniversalInspectorPreview(
                    entry = entry,
                    descriptor = descriptor,
                    modifier = modifier,
                    warning = it.message ?: "The geometry could not be rendered safely.",
                )
            },
        )
    }
}

@Composable
private fun GeometryCanvas(
    geometry: GeometryPreview,
    family: PreviewFamily,
    modifier: Modifier,
) {
    var zoom by remember(geometry) { mutableFloatStateOf(1f) }
    var rotationX by remember(geometry) { mutableFloatStateOf(if (family == PreviewFamily.MODEL_3D) -0.45f else 0f) }
    var rotationY by remember(geometry) { mutableFloatStateOf(if (family == PreviewFamily.MODEL_3D) 0.65f else 0f) }
    val projected = remember(geometry, rotationX, rotationY) {
        geometry.vertices.map { project(it, rotationX, rotationY, family == PreviewFamily.MODEL_3D) }
    }

    Column(modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (family == PreviewFamily.MODEL_3D) Icons.Outlined.DataObject else Icons.Outlined.GridOn,
                    contentDescription = null,
                )
                Text(geometry.formatLabel, style = MaterialTheme.typography.labelLarge)
                Text(
                    "${geometry.vertices.size} vertices · ${geometry.edges.size} edges",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (geometry.truncated) {
                    Text("Limited preview", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(geometry, family) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(0.15f, 20f)
                        if (family == PreviewFamily.MODEL_3D) {
                            rotationY += pan.x / 260f
                            rotationX = (rotationX + pan.y / 260f).coerceIn(-1.5f, 1.5f)
                        }
                    }
                },
        ) {
            if (projected.isEmpty()) return@Canvas
            val minX = projected.minOf { it.x }
            val maxX = projected.maxOf { it.x }
            val minY = projected.minOf { it.y }
            val maxY = projected.maxOf { it.y }
            val spanX = max(maxX - minX, 0.0001f)
            val spanY = max(maxY - minY, 0.0001f)
            val baseScale = min(size.width * 0.88f / spanX, size.height * 0.88f / spanY) * zoom
            val centerX = (minX + maxX) / 2f
            val centerY = (minY + maxY) / 2f
            fun screen(value: Offset) = Offset(
                size.width / 2f + (value.x - centerX) * baseScale,
                size.height / 2f - (value.y - centerY) * baseScale,
            )
            geometry.edges.forEach { edge ->
                val first = projected.getOrNull(edge.from) ?: return@forEach
                val second = projected.getOrNull(edge.to) ?: return@forEach
                drawLine(
                    color = Color.White.copy(alpha = 0.82f),
                    start = screen(first),
                    end = screen(second),
                    strokeWidth = if (family == PreviewFamily.MODEL_3D) 1.15f else 1.5f,
                )
            }
        }
    }
}

@Composable
fun UniversalInspectorPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
    warning: String? = null,
) {
    val context = LocalContext.current
    val inspection by produceState<Result<UniversalInspection>?>(null, entry.uri) {
        value = runCatching { UniversalFileInspector(context.applicationContext).inspect(entry.uri) }
    }
    when (val result = inspection) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { data -> InspectorContent(entry, descriptor, data, warning, modifier) },
            onFailure = { failure ->
                Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(42.dp))
                        Text("Unable to inspect this file", style = MaterialTheme.typography.titleMedium)
                        Text(failure.message ?: "The provider did not return readable data.")
                    }
                }
            },
        )
    }
}

@Composable
private fun InspectorContent(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    data: UniversalInspection,
    warning: String?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
        Text(
            buildString {
                append(entry.mimeType)
                if (descriptor.extension.isNotBlank()) append(" · .${descriptor.extension}")
                append(" · ${data.sampledBytes} bytes sampled")
                if (data.truncated) append(" · bounded")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (warning ?: descriptor.notes)?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        InspectorMetric("Detected signature", data.detectedSignature ?: "Unknown")
        InspectorMetric("Sample SHA-256", data.sha256OfSample)
        InspectorMetric("Entropy", "%.2f bits/byte".format(data.entropyBitsPerByte))
        InspectorMetric("Probable text", if (data.probableText) "Yes" else "No")
        InspectorMetric("Signature bytes", data.signatureHex.ifBlank { "Empty file" })

        if (data.printableStrings.isNotEmpty()) {
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Code, contentDescription = null)
                Text("Printable strings", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                data.printableStrings.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
        Text("Hex preview", style = MaterialTheme.typography.titleSmall)
        Text(
            data.hexLines.joinToString("\n").ifBlank { "No bytes" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InspectorMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun project(value: Vec3, xRotation: Float, yRotation: Float, rotate3d: Boolean): Offset {
    if (!rotate3d) return Offset(value.x, value.y)
    val cosY = cos(yRotation)
    val sinY = sin(yRotation)
    val x1 = value.x * cosY + value.z * sinY
    val z1 = -value.x * sinY + value.z * cosY
    val cosX = cos(xRotation)
    val sinX = sin(xRotation)
    val y1 = value.y * cosX - z1 * sinX
    return Offset(x1, y1)
}

private suspend fun readBounded(context: Context, uri: Uri, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read geometry.")
    input.use { stream ->
        val output = ByteArrayOutputStream(min(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Geometry preview exceeds the ${maxBytes / (1024 * 1024)} MiB limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
