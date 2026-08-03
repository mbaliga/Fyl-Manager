package io.github.mbaliga.fylz.preview

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class DrawingPoint(val x: Float, val y: Float)

data class DrawingSegment(val start: DrawingPoint, val end: DrawingPoint, val layer: String)

data class DxfPreviewData(
    val segments: List<DrawingSegment>,
    val parsedEntities: Int,
    val ignoredEntities: Int,
    val truncated: Boolean,
)

/** Bounded ASCII DXF preview for common linework. */
object DxfPreviewParser {
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    const val MAX_ENTITIES = 200_000
    const val MAX_SEGMENTS = 600_000
    private const val ARC_SEGMENTS = 48

    fun parse(bytes: ByteArray): DxfPreviewData {
        require(bytes.size <= MAX_INPUT_BYTES) { "DXF preview input exceeds 32 MiB." }
        require(bytes.none { it == 0.toByte() }) { "Binary DXF is not supported by the built-in preview." }
        val rawLines = bytes.toString(Charsets.ISO_8859_1).lineSequence().map(String::trimEnd).toList()
        require(rawLines.size >= 2) { "DXF content is incomplete." }
        val pairs = ArrayList<Pair<Int, String>>(rawLines.size / 2)
        var line = 0
        while (line + 1 < rawLines.size) {
            val code = rawLines[line].trim().toIntOrNull()
            if (code != null) pairs += code to rawLines[line + 1].trim()
            line += 2
        }

        val segments = ArrayList<DrawingSegment>()
        var parsed = 0
        var ignored = 0
        var truncated = false
        var inEntities = false
        var index = 0
        var pendingPolyline: MutableList<DrawingPoint>? = null
        var pendingLayer = "0"
        var pendingClosed = false

        fun flushPolyline() {
            val points = pendingPolyline ?: return
            addPolyline(segments, points, pendingLayer, pendingClosed)
            pendingPolyline = null
            pendingClosed = false
        }

        while (index < pairs.size) {
            val (code, value) = pairs[index]
            if (code == 0 && value == "SECTION" && pairs.getOrNull(index + 1) == (2 to "ENTITIES")) {
                inEntities = true
                index += 2
                continue
            }
            if (inEntities && code == 0 && value == "ENDSEC") {
                flushPolyline()
                break
            }
            if (!inEntities || code != 0) {
                index += 1
                continue
            }
            if (parsed + ignored >= MAX_ENTITIES || segments.size >= MAX_SEGMENTS) {
                truncated = true
                break
            }

            val type = value.uppercase()
            if (type != "VERTEX" && type != "SEQEND") flushPolyline()
            var end = index + 1
            while (end < pairs.size && pairs[end].first != 0) end += 1
            val attributes = pairs.subList(index + 1, end)
            when (type) {
                "LINE" -> {
                    val layer = string(attributes, 8) ?: "0"
                    val x1 = number(attributes, 10)
                    val y1 = number(attributes, 20)
                    val x2 = number(attributes, 11)
                    val y2 = number(attributes, 21)
                    if (allFinite(x1, y1, x2, y2)) {
                        addSegment(segments, DrawingPoint(x1!!, y1!!), DrawingPoint(x2!!, y2!!), layer)
                        parsed += 1
                    } else ignored += 1
                }
                "LWPOLYLINE" -> {
                    val layer = string(attributes, 8) ?: "0"
                    val closed = ((integer(attributes, 70) ?: 0) and 1) != 0
                    val points = mutableListOf<DrawingPoint>()
                    var x: Float? = null
                    attributes.forEach { (attributeCode, attributeValue) ->
                        when (attributeCode) {
                            10 -> {
                                if (x != null) x = null
                                x = attributeValue.toFloatOrNull()
                            }
                            20 -> {
                                val y = attributeValue.toFloatOrNull()
                                if (x != null && y != null && x!!.isFinite() && y.isFinite()) {
                                    points += DrawingPoint(x!!, y)
                                }
                                x = null
                            }
                        }
                    }
                    if (points.size >= 2) {
                        addPolyline(segments, points, layer, closed)
                        parsed += 1
                    } else ignored += 1
                }
                "POLYLINE" -> {
                    pendingPolyline = mutableListOf()
                    pendingLayer = string(attributes, 8) ?: "0"
                    pendingClosed = ((integer(attributes, 70) ?: 0) and 1) != 0
                }
                "VERTEX" -> {
                    val x = number(attributes, 10)
                    val y = number(attributes, 20)
                    if (pendingPolyline != null && allFinite(x, y)) pendingPolyline!!.add(DrawingPoint(x!!, y!!))
                    else ignored += 1
                }
                "SEQEND" -> {
                    if (pendingPolyline?.size.orZero() >= 2) parsed += 1 else ignored += 1
                    flushPolyline()
                }
                "CIRCLE" -> {
                    val layer = string(attributes, 8) ?: "0"
                    val cx = number(attributes, 10)
                    val cy = number(attributes, 20)
                    val radius = number(attributes, 40)
                    if (allFinite(cx, cy, radius) && radius!! > 0f) {
                        addArc(segments, DrawingPoint(cx!!, cy!!), radius, 0f, 360f, layer)
                        parsed += 1
                    } else ignored += 1
                }
                "ARC" -> {
                    val layer = string(attributes, 8) ?: "0"
                    val cx = number(attributes, 10)
                    val cy = number(attributes, 20)
                    val radius = number(attributes, 40)
                    val start = number(attributes, 50)
                    val finish = number(attributes, 51)
                    if (allFinite(cx, cy, radius, start, finish) && radius!! > 0f) {
                        addArc(segments, DrawingPoint(cx!!, cy!!), radius, start!!, finish!!, layer)
                        parsed += 1
                    } else ignored += 1
                }
                "POINT" -> {
                    val layer = string(attributes, 8) ?: "0"
                    val x = number(attributes, 10)
                    val y = number(attributes, 20)
                    if (allFinite(x, y)) {
                        val size = 0.5f
                        addSegment(segments, DrawingPoint(x!! - size, y!!), DrawingPoint(x + size, y), layer)
                        addSegment(segments, DrawingPoint(x, y - size), DrawingPoint(x, y + size), layer)
                        parsed += 1
                    } else ignored += 1
                }
                "3DFACE", "SOLID", "TRACE" -> {
                    val layer = string(attributes, 8) ?: "0"
                    val points = (10..13).mapNotNull { codeX ->
                        val x = number(attributes, codeX)
                        val y = number(attributes, codeX + 10)
                        if (allFinite(x, y)) DrawingPoint(x!!, y!!) else null
                    }.distinct()
                    if (points.size >= 2) {
                        addPolyline(segments, points, layer, closed = true)
                        parsed += 1
                    } else ignored += 1
                }
                "TEXT", "MTEXT", "INSERT", "SPLINE", "ELLIPSE", "HATCH", "DIMENSION", "LEADER" -> ignored += 1
                "EOF" -> break
                else -> ignored += 1
            }
            if (segments.size >= MAX_SEGMENTS) truncated = true
            index = max(end, index + 1)
        }
        flushPolyline()
        return DxfPreviewData(segments.take(MAX_SEGMENTS), parsed, ignored, truncated || segments.size > MAX_SEGMENTS)
    }

    private fun addPolyline(
        output: MutableList<DrawingSegment>,
        points: List<DrawingPoint>,
        layer: String,
        closed: Boolean,
    ) {
        points.zipWithNext().forEach { (start, end) -> addSegment(output, start, end, layer) }
        if (closed && points.size > 2) addSegment(output, points.last(), points.first(), layer)
    }

    private fun addArc(
        output: MutableList<DrawingSegment>,
        center: DrawingPoint,
        radius: Float,
        startDegrees: Float,
        endDegrees: Float,
        layer: String,
    ) {
        var sweep = endDegrees - startDegrees
        while (sweep <= 0f) sweep += 360f
        val steps = max(4, min(ARC_SEGMENTS, (ARC_SEGMENTS * sweep / 360f).toInt()))
        var previous = polar(center, radius, startDegrees)
        repeat(steps) { step ->
            val current = polar(center, radius, startDegrees + sweep * (step + 1) / steps)
            addSegment(output, previous, current, layer)
            previous = current
        }
    }

    private fun polar(center: DrawingPoint, radius: Float, degrees: Float): DrawingPoint {
        val radians = degrees / 180f * PI.toFloat()
        return DrawingPoint(center.x + radius * cos(radians), center.y + radius * sin(radians))
    }

    private fun addSegment(output: MutableList<DrawingSegment>, start: DrawingPoint, end: DrawingPoint, layer: String) {
        if (output.size >= MAX_SEGMENTS) return
        if (!start.x.isFinite() || !start.y.isFinite() || !end.x.isFinite() || !end.y.isFinite()) return
        output += DrawingSegment(start, end, layer.take(128))
    }

    private fun number(values: List<Pair<Int, String>>, code: Int): Float? =
        values.firstOrNull { it.first == code }?.second?.toFloatOrNull()

    private fun integer(values: List<Pair<Int, String>>, code: Int): Int? =
        values.firstOrNull { it.first == code }?.second?.toIntOrNull()

    private fun string(values: List<Pair<Int, String>>, code: Int): String? =
        values.firstOrNull { it.first == code }?.second

    private fun allFinite(vararg values: Float?): Boolean = values.all { it != null && it.isFinite() }
    private fun Int?.orZero(): Int = this ?: 0
}
