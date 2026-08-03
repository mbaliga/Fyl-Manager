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
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class DxfPoint(val x: Float, val y: Float)

sealed interface DxfPrimitive {
    data class Line(val start: DxfPoint, val end: DxfPoint) : DxfPrimitive
    data class Polyline(val points: List<DxfPoint>, val closed: Boolean) : DxfPrimitive
    data class Circle(val center: DxfPoint, val radius: Float) : DxfPrimitive
    data class Arc(val center: DxfPoint, val radius: Float, val startDegrees: Float, val endDegrees: Float) : DxfPrimitive
    data class Point(val point: DxfPoint) : DxfPrimitive
}

data class DxfPreviewData(
    val primitives: List<DxfPrimitive>,
    val skippedEntities: Int,
    val truncated: Boolean,
)

object DxfPreviewParser {
    const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    const val MAX_ENTITIES = 100_000
    private const val MAX_POLYLINE_POINTS = 200_000

    fun parse(bytes: ByteArray): DxfPreviewData {
        require(bytes.size <= MAX_INPUT_BYTES) { "DXF preview exceeds 24 MiB." }
        val prefix = bytes.take(32).toByteArray().toString(Charsets.US_ASCII)
        require(!prefix.startsWith("AutoCAD Binary DXF")) { "Binary DXF is inspectable but not rendered by the built-in vector viewer." }
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().toList()
        require(lines.size >= 2) { "DXF file is empty." }
        val pairs = ArrayList<Pair<Int, String>>(min(lines.size / 2, MAX_ENTITIES * 16))
        var index = 0
        while (index + 1 < lines.size && pairs.size < MAX_ENTITIES * 32) {
            val code = lines[index].trim().toIntOrNull()
            if (code != null) pairs += code to lines[index + 1].trim()
            index += 2
        }
        val primitives = mutableListOf<DxfPrimitive>()
        var skipped = 0
        var inEntities = false
        var current = 0
        while (current < pairs.size && primitives.size < MAX_ENTITIES) {
            val (code, value) = pairs[current]
            if (code == 0 && value.equals("SECTION", true) && pairs.getOrNull(current + 1) == (2 to "ENTITIES")) {
                inEntities = true
                current += 2
                continue
            }
            if (code == 0 && value.equals("ENDSEC", true) && inEntities) break
            if (!inEntities || code != 0) {
                current += 1
                continue
            }
            val next = generateSequence(current + 1) { it + 1 }.firstOrNull { it >= pairs.size || pairs[it].first == 0 } ?: pairs.size
            val fields = pairs.subList(current + 1, next)
            val primitive = when (value.uppercase()) {
                "LINE" -> parseLine(fields)
                "LWPOLYLINE" -> parseLightPolyline(fields)
                "CIRCLE" -> parseCircle(fields)
                "ARC" -> parseArc(fields)
                "POINT" -> parsePoint(fields)
                else -> null
            }
            if (primitive != null) primitives += primitive else skipped += 1
            current = next
        }
        require(primitives.isNotEmpty()) { "No supported DXF drawing entities were found." }
        return DxfPreviewData(primitives, skipped, primitives.size >= MAX_ENTITIES || pairs.size >= MAX_ENTITIES * 32)
    }

    private fun parseLine(fields: List<Pair<Int, String>>): DxfPrimitive.Line? {
        val x1 = number(fields, 10) ?: return null; val y1 = number(fields, 20) ?: return null
        val x2 = number(fields, 11) ?: return null; val y2 = number(fields, 21) ?: return null
        return DxfPrimitive.Line(DxfPoint(x1, y1), DxfPoint(x2, y2))
    }

    private fun parseLightPolyline(fields: List<Pair<Int, String>>): DxfPrimitive.Polyline? {
        val points = mutableListOf<DxfPoint>()
        var pendingX: Float? = null
        fields.forEach { (code, value) ->
            when (code) {
                10 -> pendingX = value.toFloatOrNull()
                20 -> pendingX?.let { x -> value.toFloatOrNull()?.let { y -> if (points.size < MAX_POLYLINE_POINTS) points += DxfPoint(x, y) } }.also { pendingX = null }
            }
        }
        if (points.size < 2) return null
        val flags = fields.firstOrNull { it.first == 70 }?.second?.toIntOrNull() ?: 0
        return DxfPrimitive.Polyline(points, flags and 1 == 1)
    }

    private fun parseCircle(fields: List<Pair<Int, String>>): DxfPrimitive.Circle? {
        val x = number(fields, 10) ?: return null; val y = number(fields, 20) ?: return null
        val radius = number(fields, 40)?.takeIf { it > 0f } ?: return null
        return DxfPrimitive.Circle(DxfPoint(x, y), radius)
    }

    private fun parseArc(fields: List<Pair<Int, String>>): DxfPrimitive.Arc? {
        val x = number(fields, 10) ?: return null; val y = number(fields, 20) ?: return null
        val radius = number(fields, 40)?.takeIf { it > 0f } ?: return null
        return DxfPrimitive.Arc(DxfPoint(x, y), radius, number(fields, 50) ?: 0f, number(fields, 51) ?: 360f)
    }

    private fun parsePoint(fields: List<Pair<Int, String>>): DxfPrimitive.Point? {
        val x = number(fields, 10) ?: return null; val y = number(fields, 20) ?: return null
        return DxfPrimitive.Point(DxfPoint(x, y))
    }

    private fun number(fields: List<Pair<Int, String>>, code: Int): Float? =
        fields.firstOrNull { it.first == code }?.second?.toFloatOrNull()?.takeIf(Float::isFinite)
}

@Composable
fun DxfPreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val result by produceState<Result<DxfPreviewData>?>(initialValue = null, uri) {
        value = runCatching { loadDxf(context.applicationContext, uri) }
    }
    when (val state = result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> state.fold(
            onSuccess = { data -> DxfCanvas(data, modifier) },
            onFailure = { failure -> UniversalPreviewError("Unable to render DXF drawing", failure.message, modifier) },
        )
    }
}

@Composable
private fun DxfCanvas(data: DxfPreviewData, modifier: Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val drawingColor = MaterialTheme.colorScheme.primary
    val bounds = remember(data) { bounds(data.primitives) }
    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("ASCII DXF", style = MaterialTheme.typography.labelMedium)
                Text("${data.primitives.size} entities", style = MaterialTheme.typography.labelMedium)
                if (data.skippedEntities > 0) Text("${data.skippedEntities} unsupported", style = MaterialTheme.typography.labelMedium)
                if (data.truncated) Text("Preview limited", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        Box(
            Modifier.weight(1f).pointerInput(data) {
                detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                    pan += gesturePan
                    zoom = (zoom * gestureZoom).coerceIn(0.25f, 20f)
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val width = (bounds.maxX - bounds.minX).takeIf { it > 0f } ?: 1f
                val height = (bounds.maxY - bounds.minY).takeIf { it > 0f } ?: 1f
                val baseScale = min(size.width / width, size.height / height) * 0.9f
                val scale = baseScale * zoom
                val centerModel = DxfPoint((bounds.minX + bounds.maxX) / 2f, (bounds.minY + bounds.maxY) / 2f)
                fun map(point: DxfPoint) = Offset(
                    size.width / 2f + (point.x - centerModel.x) * scale + pan.x,
                    size.height / 2f - (point.y - centerModel.y) * scale + pan.y,
                )
                data.primitives.forEach { primitive ->
                    when (primitive) {
                        is DxfPrimitive.Line -> drawLine(drawingColor, map(primitive.start), map(primitive.end), 1.3f)
                        is DxfPrimitive.Polyline -> {
                            primitive.points.zipWithNext().forEach { (a, b) -> drawLine(drawingColor, map(a), map(b), 1.3f) }
                            if (primitive.closed) drawLine(drawingColor, map(primitive.points.last()), map(primitive.points.first()), 1.3f)
                        }
                        is DxfPrimitive.Circle -> drawCircle(drawingColor, primitive.radius * scale, map(primitive.center), style = Stroke(1.3f))
                        is DxfPrimitive.Arc -> {
                            var sweep = primitive.endDegrees - primitive.startDegrees
                            if (sweep <= 0f) sweep += 360f
                            val topLeft = map(DxfPoint(primitive.center.x - primitive.radius, primitive.center.y + primitive.radius))
                            drawArc(
                                color = drawingColor,
                                startAngle = -primitive.startDegrees,
                                sweepAngle = -sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = androidx.compose.ui.geometry.Size(primitive.radius * 2f * scale, primitive.radius * 2f * scale),
                                style = Stroke(1.3f),
                            )
                        }
                        is DxfPrimitive.Point -> drawCircle(drawingColor, 2.5f, map(primitive.point))
                    }
                }
            }
        }
        Text(
            "Drag to pan · pinch to zoom",
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun loadDxf(context: Context, uri: Uri): DxfPreviewData = withContext(Dispatchers.IO) {
    val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    if (length != null && length > DxfPreviewParser.MAX_INPUT_BYTES) error("DXF exceeds the 24 MiB preview limit.")
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= DxfPreviewParser.MAX_INPUT_BYTES) { "DXF exceeds the preview limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Unable to read this DXF drawing.")
    DxfPreviewParser.parse(bytes)
}

private data class DxfBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

private fun bounds(primitives: List<DxfPrimitive>): DxfBounds {
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    fun include(point: DxfPoint) {
        minX = min(minX, point.x); minY = min(minY, point.y); maxX = max(maxX, point.x); maxY = max(maxY, point.y)
    }
    primitives.forEach {
        when (it) {
            is DxfPrimitive.Line -> { include(it.start); include(it.end) }
            is DxfPrimitive.Polyline -> it.points.forEach(::include)
            is DxfPrimitive.Circle -> { include(DxfPoint(it.center.x - it.radius, it.center.y - it.radius)); include(DxfPoint(it.center.x + it.radius, it.center.y + it.radius)) }
            is DxfPrimitive.Arc -> {
                include(DxfPoint(it.center.x - it.radius, it.center.y - it.radius)); include(DxfPoint(it.center.x + it.radius, it.center.y + it.radius))
            }
            is DxfPrimitive.Point -> include(it.point)
        }
    }
    if (!minX.isFinite()) return DxfBounds(-1f, -1f, 1f, 1f)
    return DxfBounds(minX, minY, maxX, maxY)
}
