package io.github.mbaliga.fylz.ui.components.preview

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.data.GltfWireframeParser
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.GeometryPreview
import io.github.mbaliga.fylz.preview.GeometryPreviewParser
import io.github.mbaliga.fylz.preview.Vec3
import io.github.mbaliga.fylz.ui.components.UniversalInspectorPreview
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A 3D model or CAD drawing on a Canvas, with rotate, pan and zoom.
 *
 * Fylz bundles no 3D engine and cannot fetch one, so this is a hand-projected wireframe: the mesh
 * parsers turn OBJ/STL/PLY/OFF/DXF into vertices and edges, [GltfWireframeParser] does the same
 * for glTF and GLB, and the projection and gesture arithmetic live here. That is the whole
 * mechanism, and the caption says as much rather than implying a render.
 *
 * The gesture split is the point of this file existing. Before it, a drag rotated and a pinch
 * zoomed, and there was no way to move the model within the frame at all -- so a zoomed-in model
 * put whatever it put at the centre and the rest was unreachable. Now:
 *  - one finger rotates (a 3D model) or slides (a flat CAD drawing, which has nothing to rotate);
 *  - two fingers pan, whatever the model's dimensionality;
 *  - a pinch zooms;
 *  - and a labelled key resets the view, so recovering from a lost model is not a gesture nobody
 *    can guess at.
 */
@Composable
fun ModelWireframePreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val parsed by produceState<Result<GeometryPreview>?>(null, entry.uri, entry.name) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadGeometry(context, entry.uri, entry.name, descriptor.extension) }
        }
    }
    when (val result = parsed) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { geometry ->
                WireframeCanvas(
                    geometry = geometry,
                    rotatable = descriptor.family == PreviewFamily.MODEL_3D,
                    name = entry.name,
                    modifier = modifier,
                )
            },
            onFailure = { failure ->
                UniversalInspectorPreview(
                    entry,
                    descriptor,
                    modifier,
                    failure.message ?: "This model could not be projected safely.",
                )
            },
        )
    }
}

@Composable
private fun WireframeCanvas(
    geometry: GeometryPreview,
    rotatable: Boolean,
    name: String,
    modifier: Modifier,
) {
    var rotationX by remember(geometry) { mutableFloatStateOf(if (rotatable) INITIAL_PITCH else 0f) }
    var rotationY by remember(geometry) { mutableFloatStateOf(if (rotatable) INITIAL_YAW else 0f) }
    var zoom by remember(geometry) { mutableFloatStateOf(1f) }
    var offset by remember(geometry) { mutableStateOf(Offset.Zero) }
    val lineColor = MaterialTheme.colorScheme.primary
    val projected = remember(geometry, rotationX, rotationY, rotatable) {
        geometry.vertices.map { project(it, rotationX, rotationY, rotatable) }
    }

    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(geometry.formatLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            append("${geometry.vertices.size} vertices · ${geometry.edges.size} edges")
                            if (geometry.truncated) append(" · partial")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TactileIconKey(
                    icon = Icons.Outlined.CenterFocusStrong,
                    contentDescription = "Reset the model view",
                    onClick = {
                        rotationX = if (rotatable) INITIAL_PITCH else 0f
                        rotationY = if (rotatable) INITIAL_YAW else 0f
                        zoom = 1f
                        offset = Offset.Zero
                    },
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .pointerInput(geometry, rotatable) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pointers = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            val pinch = event.calculateZoom()
                            when {
                                // Two fingers are unambiguously a pan-and-zoom, whether the model
                                // is a solid or a flat drawing.
                                pointers >= 2 -> {
                                    if (pinch != 1f) zoom = (zoom * pinch).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    offset += pan
                                }
                                // A flat CAD drawing has no third axis to turn, so its one-finger
                                // drag slides the sheet instead of spinning it edge-on.
                                rotatable -> {
                                    rotationY += pan.x / ROTATION_SCALE
                                    rotationX = (rotationX + pan.y / ROTATION_SCALE).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
                                }
                                else -> offset += pan
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .semantics {
                    contentDescription = buildString {
                        append("Wireframe of $name. ")
                        append("${geometry.vertices.size} vertices and ${geometry.edges.size} edges. ")
                        append(if (rotatable) "Drag to rotate, two fingers to pan, pinch to zoom." else "Drag to pan, pinch to zoom.")
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                if (projected.isEmpty()) return@Canvas
                var minX = Float.POSITIVE_INFINITY
                var maxX = Float.NEGATIVE_INFINITY
                var minY = Float.POSITIVE_INFINITY
                var maxY = Float.NEGATIVE_INFINITY
                projected.forEach {
                    minX = min(minX, it.x); maxX = max(maxX, it.x)
                    minY = min(minY, it.y); maxY = max(maxY, it.y)
                }
                val spanX = max(maxX - minX, MIN_SPAN)
                val spanY = max(maxY - minY, MIN_SPAN)
                val scale = min(size.width * FIT_FRACTION / spanX, size.height * FIT_FRACTION / spanY) * zoom
                if (!scale.isFinite() || scale <= 0f) return@Canvas
                val modelCenterX = (minX + maxX) / 2f
                val modelCenterY = (minY + maxY) / 2f
                val originX = size.width / 2f + offset.x
                val originY = size.height / 2f + offset.y
                geometry.edges.forEach { edge ->
                    val from = projected.getOrNull(edge.from) ?: return@forEach
                    val to = projected.getOrNull(edge.to) ?: return@forEach
                    drawLine(
                        color = lineColor,
                        start = Offset(
                            originX + (from.x - modelCenterX) * scale,
                            originY - (from.y - modelCenterY) * scale,
                        ),
                        end = Offset(
                            originX + (to.x - modelCenterX) * scale,
                            originY - (to.y - modelCenterY) * scale,
                        ),
                        strokeWidth = STROKE_WIDTH,
                    )
                }
            }
        }
        Text(
            buildString {
                append(if (rotatable) "Drag to rotate · two fingers to pan · pinch to zoom" else "Drag to pan · pinch to zoom")
                append(" · wireframe projection, not a shaded render")
                if (geometry.truncated) append(" · bounded to part of this model")
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Orthographic on purpose. A perspective divide makes a zoomed-in model slide and stretch as it
 * turns, which reads as a bug when the gesture was "zoom"; a parallel projection keeps the pan
 * offset meaning the same thing at every zoom level.
 */
private fun project(vertex: Vec3, rotationX: Float, rotationY: Float, rotatable: Boolean): Offset {
    if (!rotatable) return Offset(vertex.x, vertex.y)
    val cosYaw = cos(rotationY)
    val sinYaw = sin(rotationY)
    val cosPitch = cos(rotationX)
    val sinPitch = sin(rotationX)
    val x = vertex.x * cosYaw + vertex.z * sinYaw
    val depth = -vertex.x * sinYaw + vertex.z * cosYaw
    val y = vertex.y * cosPitch - depth * sinPitch
    return Offset(x, y)
}

private fun loadGeometry(context: Context, uri: Uri, name: String, extension: String): GeometryPreview {
    val limit = if (GltfWireframeParser.handles(extension)) {
        GltfWireframeParser.MAX_INPUT_BYTES
    } else {
        GeometryPreviewParser.MAX_INPUT_BYTES
    }
    val bytes = readBounded(context, uri, limit)
    return if (GltfWireframeParser.handles(extension)) {
        GltfWireframeParser.parse(bytes)
    } else {
        GeometryPreviewParser.parse(name, bytes)
    }
}

private fun readBounded(context: Context, uri: Uri, maxBytes: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this model.")
    return input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) {
                "This model exceeds the ${maxBytes / (1024 * 1024)} MiB preview limit."
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

private const val INITIAL_PITCH = -0.45f
private const val INITIAL_YAW = 0.65f
private const val PITCH_LIMIT = 1.5f
private const val ROTATION_SCALE = 260f
private const val MIN_ZOOM = 0.15f
private const val MAX_ZOOM = 24f
private const val MIN_SPAN = 0.0001f
private const val FIT_FRACTION = 0.86f
private const val STROKE_WIDTH = 1.2f
