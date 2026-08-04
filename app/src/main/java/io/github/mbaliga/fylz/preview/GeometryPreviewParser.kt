package io.github.mbaliga.fylz.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Vec3(val x: Float, val y: Float, val z: Float = 0f)
data class Edge3(val from: Int, val to: Int)

data class GeometryPreview(
    val vertices: List<Vec3>,
    val edges: List<Edge3>,
    val sourcePrimitiveCount: Int,
    val truncated: Boolean,
    val formatLabel: String,
) {
    init {
        require(vertices.size <= GeometryPreviewParser.MAX_VERTICES)
        require(edges.size <= GeometryPreviewParser.MAX_EDGES)
    }
}

object GeometryPreviewParser {
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    const val MAX_VERTICES = 100_000
    const val MAX_EDGES = 250_000
    private const val MAX_LINE_CHARS = 16_384

    fun parse(name: String, bytes: ByteArray): GeometryPreview {
        require(bytes.size <= MAX_INPUT_BYTES) { "Geometry preview exceeds the 16 MiB parsing limit." }
        return when (FileFormatRegistry.compoundExtension(name)) {
            "obj" -> parseObj(bytes)
            "stl" -> parseStl(bytes)
            "ply" -> parsePly(bytes)
            "off" -> parseOff(bytes)
            "dxf" -> parseDxf(bytes)
            else -> error("No built-in geometry parser is available for this format.")
        }
    }

    private fun parseObj(bytes: ByteArray): GeometryPreview {
        val vertices = mutableListOf<Vec3>()
        val edges = linkedSetOf<Long>()
        var faces = 0
        var truncated = false
        boundedLines(bytes).forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("v ") -> {
                    if (vertices.size >= MAX_VERTICES) {
                        truncated = true
                        return@forEach
                    }
                    val values = line.substring(2).trim().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
                    if (values.size == 3) vertices += Vec3(values[0], values[1], values[2])
                }
                line.startsWith("f ") || line.startsWith("l ") -> {
                    val indexes = line.substring(2).trim().split(Regex("\\s+"))
                        .mapNotNull { token ->
                            token.substringBefore('/').toIntOrNull()?.let { value ->
                                when {
                                    value > 0 -> value - 1
                                    value < 0 -> vertices.size + value
                                    else -> -1
                                }
                            }
                        }
                        .filter { it in vertices.indices }
                    if (indexes.size >= 2) {
                        val close = line.startsWith("f ") && indexes.size > 2
                        faces += if (close) 1 else 0
                        for (index in 0 until indexes.lastIndex) {
                            if (!addEdge(edges, indexes[index], indexes[index + 1])) truncated = true
                        }
                        if (close && !addEdge(edges, indexes.last(), indexes.first())) truncated = true
                    }
                }
            }
        }
        return geometry(vertices, edges, faces, truncated, "Wavefront OBJ")
    }

    private fun parseStl(bytes: ByteArray): GeometryPreview {
        val binaryTriangleCount = if (bytes.size >= 84) {
            ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
        } else 0L
        val expected = 84L + binaryTriangleCount * 50L
        val likelyBinary = binaryTriangleCount > 0 && expected <= bytes.size.toLong()
        return if (likelyBinary) parseBinaryStl(bytes, binaryTriangleCount.toInt()) else parseAsciiStl(bytes)
    }

    private fun parseBinaryStl(bytes: ByteArray, declaredTriangles: Int): GeometryPreview {
        val vertices = mutableListOf<Vec3>()
        val edges = linkedSetOf<Long>()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(84)
        val limit = minOf(declaredTriangles, (bytes.size - 84) / 50, MAX_EDGES / 3)
        repeat(limit) {
            if (buffer.remaining() < 50 || vertices.size + 3 > MAX_VERTICES) return@repeat
            buffer.position(buffer.position() + 12)
            val base = vertices.size
            repeat(3) { vertices += Vec3(buffer.float, buffer.float, buffer.float) }
            addEdge(edges, base, base + 1)
            addEdge(edges, base + 1, base + 2)
            addEdge(edges, base + 2, base)
            buffer.position(buffer.position() + 2)
        }
        return geometry(vertices, edges, limit, limit < declaredTriangles, "Binary STL")
    }

    private fun parseAsciiStl(bytes: ByteArray): GeometryPreview {
        val vertices = mutableListOf<Vec3>()
        val edges = linkedSetOf<Long>()
        val triangle = mutableListOf<Int>()
        var faces = 0
        var truncated = false
        boundedLines(bytes).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("vertex ", ignoreCase = true)) {
                if (vertices.size >= MAX_VERTICES) {
                    truncated = true
                    return@forEach
                }
                val values = line.substringAfter(' ').trim().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
                if (values.size == 3) {
                    triangle += vertices.size
                    vertices += Vec3(values[0], values[1], values[2])
                    if (triangle.size == 3) {
                        addEdge(edges, triangle[0], triangle[1])
                        addEdge(edges, triangle[1], triangle[2])
                        addEdge(edges, triangle[2], triangle[0])
                        triangle.clear()
                        faces += 1
                    }
                }
            }
        }
        return geometry(vertices, edges, faces, truncated, "ASCII STL")
    }

    private fun parsePly(bytes: ByteArray): GeometryPreview {
        val lines = boundedLines(bytes).iterator()
        require(lines.hasNext() && lines.next().trim() == "ply") { "Not a PLY file." }
        var ascii = false
        var vertexCount = 0
        var faceCount = 0
        while (lines.hasNext()) {
            val line = lines.next().trim()
            when {
                line == "format ascii 1.0" -> ascii = true
                line.startsWith("element vertex ") -> vertexCount = line.substringAfterLast(' ').toIntOrNull() ?: 0
                line.startsWith("element face ") -> faceCount = line.substringAfterLast(' ').toIntOrNull() ?: 0
                line == "end_header" -> break
            }
        }
        require(ascii) { "Only ASCII PLY is rendered; binary PLY remains available in the universal inspector." }
        val acceptedVertices = vertexCount.coerceAtMost(MAX_VERTICES)
        val vertices = ArrayList<Vec3>(acceptedVertices)
        repeat(vertexCount) {
            if (!lines.hasNext()) return@repeat
            val values = lines.next().trim().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
            if (vertices.size < acceptedVertices && values.size == 3) vertices += Vec3(values[0], values[1], values[2])
        }
        val edges = linkedSetOf<Long>()
        var acceptedFaces = 0
        repeat(faceCount) {
            if (!lines.hasNext() || edges.size >= MAX_EDGES) return@repeat
            val values = lines.next().trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
            val count = values.firstOrNull() ?: 0
            val indexes = values.drop(1).take(count).filter { it in vertices.indices }
            if (indexes.size >= 2) {
                indexes.indices.forEach { index -> addEdge(edges, indexes[index], indexes[(index + 1) % indexes.size]) }
                acceptedFaces += 1
            }
        }
        return geometry(vertices, edges, acceptedFaces, acceptedVertices < vertexCount || acceptedFaces < faceCount, "ASCII PLY")
    }

    private fun parseOff(bytes: ByteArray): GeometryPreview {
        val meaningful = boundedLines(bytes).map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.iterator()
        require(meaningful.hasNext()) { "Empty OFF file." }
        var header = meaningful.next()
        require(header == "OFF" || header.startsWith("OFF ")) { "Not an OFF file." }
        val counts = if (header == "OFF") {
            require(meaningful.hasNext()) { "OFF counts are missing." }
            meaningful.next().split(Regex("\\s+"))
        } else header.removePrefix("OFF").trim().split(Regex("\\s+"))
        val vertexCount = counts.getOrNull(0)?.toIntOrNull() ?: 0
        val faceCount = counts.getOrNull(1)?.toIntOrNull() ?: 0
        val vertices = mutableListOf<Vec3>()
        repeat(vertexCount) {
            if (!meaningful.hasNext()) return@repeat
            val values = meaningful.next().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
            if (vertices.size < MAX_VERTICES && values.size == 3) vertices += Vec3(values[0], values[1], values[2])
        }
        val edges = linkedSetOf<Long>()
        var acceptedFaces = 0
        repeat(faceCount) {
            if (!meaningful.hasNext() || edges.size >= MAX_EDGES) return@repeat
            val values = meaningful.next().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
            val indexes = values.drop(1).take(values.firstOrNull() ?: 0).filter { it in vertices.indices }
            if (indexes.size >= 2) {
                indexes.indices.forEach { index -> addEdge(edges, indexes[index], indexes[(index + 1) % indexes.size]) }
                acceptedFaces += 1
            }
        }
        return geometry(vertices, edges, acceptedFaces, vertices.size < vertexCount || acceptedFaces < faceCount, "OFF mesh")
    }

    private fun parseDxf(bytes: ByteArray): GeometryPreview {
        val raw = boundedLines(bytes).toList()
        require(raw.size >= 2) { "DXF file is empty." }
        val pairs = raw.chunked(2).mapNotNull { pair ->
            if (pair.size < 2) null else (pair[0].trim().toIntOrNull() ?: return@mapNotNull null) to pair[1].trim()
        }
        val vertices = mutableListOf<Vec3>()
        val edges = linkedSetOf<Long>()
        var entities = 0
        var index = 0
        var truncated = false
        while (index < pairs.size && vertices.size < MAX_VERTICES && edges.size < MAX_EDGES) {
            val (code, value) = pairs[index]
            if (code != 0) { index += 1; continue }
            val type = value.uppercase(Locale.ROOT)
            val attributes = mutableMapOf<Int, MutableList<String>>()
            index += 1
            while (index < pairs.size && pairs[index].first != 0) {
                attributes.getOrPut(pairs[index].first) { mutableListOf() } += pairs[index].second
                index += 1
            }
            when (type) {
                "LINE" -> {
                    val a = point(attributes, 10, 20, 30)
                    val b = point(attributes, 11, 21, 31)
                    if (a != null && b != null) addSegment(vertices, edges, a, b)
                    entities += 1
                }
                "LWPOLYLINE" -> {
                    val xs = attributes[10].orEmpty().mapNotNull(String::toFloatOrNull)
                    val ys = attributes[20].orEmpty().mapNotNull(String::toFloatOrNull)
                    val points = (0 until minOf(xs.size, ys.size)).map { Vec3(xs[it], ys[it]) }
                    addPolyline(vertices, edges, points, (attributes[70]?.firstOrNull()?.toIntOrNull() ?: 0) and 1 == 1)
                    entities += 1
                }
                "CIRCLE" -> {
                    val center = point(attributes, 10, 20, 30)
                    val radius = attributes[40]?.firstOrNull()?.toFloatOrNull()
                    if (center != null && radius != null && radius > 0f) addArc(vertices, edges, center, radius, 0f, 360f)
                    entities += 1
                }
                "ARC" -> {
                    val center = point(attributes, 10, 20, 30)
                    val radius = attributes[40]?.firstOrNull()?.toFloatOrNull()
                    val start = attributes[50]?.firstOrNull()?.toFloatOrNull()
                    val end = attributes[51]?.firstOrNull()?.toFloatOrNull()
                    if (center != null && radius != null && start != null && end != null && radius > 0f) addArc(vertices, edges, center, radius, start, end)
                    entities += 1
                }
            }
        }
        if (index < pairs.size) truncated = true
        return geometry(vertices, edges, entities, truncated, "ASCII DXF")
    }

    private fun point(values: Map<Int, List<String>>, xCode: Int, yCode: Int, zCode: Int): Vec3? {
        val x = values[xCode]?.firstOrNull()?.toFloatOrNull() ?: return null
        val y = values[yCode]?.firstOrNull()?.toFloatOrNull() ?: return null
        val z = values[zCode]?.firstOrNull()?.toFloatOrNull() ?: 0f
        return Vec3(x, y, z)
    }

    private fun addSegment(vertices: MutableList<Vec3>, edges: MutableSet<Long>, a: Vec3, b: Vec3) {
        if (vertices.size + 2 > MAX_VERTICES || edges.size >= MAX_EDGES) return
        val start = vertices.size
        vertices += a
        vertices += b
        addEdge(edges, start, start + 1)
    }

    private fun addPolyline(vertices: MutableList<Vec3>, edges: MutableSet<Long>, points: List<Vec3>, closed: Boolean) {
        if (points.size < 2) return
        val available = (MAX_VERTICES - vertices.size).coerceAtLeast(0)
        val accepted = points.take(available)
        val base = vertices.size
        vertices += accepted
        for (i in 0 until accepted.lastIndex) addEdge(edges, base + i, base + i + 1)
        if (closed && accepted.size == points.size) addEdge(edges, base + accepted.lastIndex, base)
    }

    private fun addArc(vertices: MutableList<Vec3>, edges: MutableSet<Long>, center: Vec3, radius: Float, startDegrees: Float, endDegrees: Float) {
        var sweep = endDegrees - startDegrees
        while (sweep <= 0f) sweep += 360f
        val segments = (sweep / 10f).toInt().coerceIn(8, 72)
        val points = (0..segments).map { index ->
            val degrees = startDegrees + sweep * index / segments
            val radians = degrees * PI.toFloat() / 180f
            Vec3(center.x + radius * cos(radians), center.y + radius * sin(radians), center.z)
        }
        addPolyline(vertices, edges, points, closed = false)
    }

    private fun geometry(vertices: List<Vec3>, encodedEdges: Set<Long>, primitives: Int, truncated: Boolean, label: String): GeometryPreview {
        require(vertices.isNotEmpty()) { "$label contains no renderable geometry." }
        val edges = encodedEdges.take(MAX_EDGES).map { value -> Edge3((value ushr 32).toInt(), value.toInt()) }
        require(edges.isNotEmpty()) { "$label contains no renderable edges." }
        return GeometryPreview(vertices.take(MAX_VERTICES), edges, primitives, truncated || encodedEdges.size > MAX_EDGES, label)
    }

    private fun addEdge(edges: MutableSet<Long>, first: Int, second: Int): Boolean {
        if (first == second || first < 0 || second < 0) return true
        if (edges.size >= MAX_EDGES) return false
        val low = minOf(first, second)
        val high = maxOf(first, second)
        edges += (low.toLong() shl 32) or (high.toLong() and 0xffff_ffffL)
        return true
    }

    private fun boundedLines(bytes: ByteArray): Sequence<String> = sequence {
        var start = 0
        var index = 0
        while (index <= bytes.size) {
            if (index == bytes.size || bytes[index] == '\n'.code.toByte()) {
                val length = (index - start).coerceAtMost(MAX_LINE_CHARS)
                yield(bytes.copyOfRange(start, start + length).toString(Charsets.UTF_8).trimEnd('\r'))
                start = index + 1
            }
            index += 1
        }
    }
}
