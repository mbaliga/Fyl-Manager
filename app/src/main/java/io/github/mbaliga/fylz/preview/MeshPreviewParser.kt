package io.github.mbaliga.fylz.preview

import io.github.mbaliga.fylz.core.format.FileFormatRegistry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class MeshPoint(val x: Float, val y: Float, val z: Float)

data class MeshEdge(val from: Int, val to: Int)

data class MeshPreviewData(
    val vertices: List<MeshPoint>,
    val edges: List<MeshEdge>,
    val sourceFaces: Int,
    val truncated: Boolean,
    val format: String,
) {
    val empty: Boolean get() = vertices.isEmpty() || edges.isEmpty()
}

/** Lightweight preview parser, not a geometry-authoring importer. */
object MeshPreviewParser {
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    const val MAX_VERTICES = 200_000
    const val MAX_EDGES = 600_000
    private const val MAX_FACE_VERTICES = 10_000

    fun parse(fileName: String, bytes: ByteArray): MeshPreviewData {
        require(bytes.size <= MAX_INPUT_BYTES) { "3D preview input exceeds 32 MiB." }
        return when (FileFormatRegistry.compoundExtension(fileName)) {
            "obj" -> parseObj(bytes)
            "stl" -> parseStl(bytes)
            "ply" -> parsePly(bytes)
            "off" -> parseOff(bytes)
            else -> error("No built-in mesh parser for this format.")
        }
    }

    private fun parseObj(bytes: ByteArray): MeshPreviewData {
        val vertices = mutableListOf<MeshPoint>()
        val edges = linkedSetOf<Long>()
        var faces = 0
        var truncated = false
        bytes.toString(Charsets.UTF_8).lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("v ") && vertices.size < MAX_VERTICES -> {
                    val values = line.split(WHITESPACE).drop(1)
                    if (values.size >= 3) {
                        val x = values[0].toFloatOrNull()
                        val y = values[1].toFloatOrNull()
                        val z = values[2].toFloatOrNull()
                        if (x != null && y != null && z != null && x.isFinite() && y.isFinite() && z.isFinite()) {
                            vertices += MeshPoint(x, y, z)
                        }
                    }
                }
                line.startsWith("v ") -> truncated = true
                (line.startsWith("f ") || line.startsWith("l ")) && edges.size < MAX_EDGES -> {
                    val indexes = line.split(WHITESPACE).drop(1).take(MAX_FACE_VERTICES).mapNotNull { token ->
                        token.substringBefore('/').toIntOrNull()?.let { index ->
                            when {
                                index > 0 -> index - 1
                                index < 0 -> vertices.size + index
                                else -> -1
                            }
                        }?.takeIf { it in vertices.indices }
                    }
                    if (indexes.size >= 2) {
                        indexes.zipWithNext().forEach { (a, b) -> addEdge(edges, a, b) }
                        if (line.startsWith("f ") && indexes.size > 2) {
                            addEdge(edges, indexes.last(), indexes.first())
                            faces += 1
                        }
                    }
                }
                (line.startsWith("f ") || line.startsWith("l ")) -> truncated = true
            }
        }
        return result(vertices, edges, faces, truncated, "Wavefront OBJ")
    }

    private fun parseStl(bytes: ByteArray): MeshPreviewData {
        val triangleCount = if (bytes.size >= 84) {
            ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        } else 0L
        val expected = 84L + triangleCount * 50L
        return if (triangleCount > 0 && expected <= bytes.size.toLong()) parseBinaryStl(bytes, triangleCount)
        else parseAsciiStl(bytes)
    }

    private fun parseBinaryStl(bytes: ByteArray, declaredTriangles: Long): MeshPreviewData {
        val vertices = mutableListOf<MeshPoint>()
        val edges = linkedSetOf<Long>()
        val dedupe = HashMap<VertexKey, Int>()
        val count = min(declaredTriangles, MAX_EDGES.toLong() / 3L).toInt()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(84)
        var faces = 0
        repeat(count) {
            if (buffer.remaining() < 50) return@repeat
            buffer.position(buffer.position() + 12) // normal
            val face = IntArray(3)
            repeat(3) { corner ->
                val point = MeshPoint(buffer.float, buffer.float, buffer.float)
                if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return@repeat
                face[corner] = vertexIndex(point, vertices, dedupe)
            }
            buffer.short
            if (face.all { it >= 0 }) {
                addEdge(edges, face[0], face[1])
                addEdge(edges, face[1], face[2])
                addEdge(edges, face[2], face[0])
                faces += 1
            }
        }
        return result(vertices, edges, faces, declaredTriangles > count, "Binary STL")
    }

    private fun parseAsciiStl(bytes: ByteArray): MeshPreviewData {
        val vertices = mutableListOf<MeshPoint>()
        val edges = linkedSetOf<Long>()
        val dedupe = HashMap<VertexKey, Int>()
        val face = mutableListOf<Int>()
        var faces = 0
        var truncated = false
        bytes.toString(Charsets.US_ASCII).lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("vertex ")) {
                val values = line.split(WHITESPACE).drop(1)
                if (values.size >= 3) {
                    val point = MeshPoint(
                        values[0].toFloatOrNull() ?: return@forEach,
                        values[1].toFloatOrNull() ?: return@forEach,
                        values[2].toFloatOrNull() ?: return@forEach,
                    )
                    if (vertices.size >= MAX_VERTICES || edges.size >= MAX_EDGES) {
                        truncated = true
                    } else if (point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) {
                        face += vertexIndex(point, vertices, dedupe)
                    }
                }
            } else if (line == "endloop" && face.size >= 3) {
                face.windowed(2).forEach { addEdge(edges, it[0], it[1]) }
                addEdge(edges, face.last(), face.first())
                faces += 1
                face.clear()
            }
        }
        return result(vertices, edges, faces, truncated, "ASCII STL")
    }

    private fun parsePly(bytes: ByteArray): MeshPreviewData {
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().iterator()
        require(lines.hasNext() && lines.next().trim() == "ply") { "Invalid PLY header." }
        var ascii = false
        var vertexCount = 0
        var faceCount = 0
        var ended = false
        while (lines.hasNext()) {
            val line = lines.next().trim()
            when {
                line == "format ascii 1.0" -> ascii = true
                line.startsWith("element vertex ") -> vertexCount = line.substringAfterLast(' ').toIntOrNull() ?: 0
                line.startsWith("element face ") -> faceCount = line.substringAfterLast(' ').toIntOrNull() ?: 0
                line == "end_header" -> { ended = true; break }
            }
        }
        require(ascii && ended) { "Only ASCII PLY preview is currently supported." }
        val acceptedVertices = min(vertexCount, MAX_VERTICES)
        val vertices = ArrayList<MeshPoint>(acceptedVertices)
        repeat(vertexCount) { index ->
            if (!lines.hasNext()) return@repeat
            val values = lines.next().trim().split(WHITESPACE)
            if (index < acceptedVertices && values.size >= 3) {
                val point = MeshPoint(
                    values[0].toFloatOrNull() ?: 0f,
                    values[1].toFloatOrNull() ?: 0f,
                    values[2].toFloatOrNull() ?: 0f,
                )
                if (point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) vertices += point
            }
        }
        val edges = linkedSetOf<Long>()
        var faces = 0
        repeat(faceCount) {
            if (!lines.hasNext() || edges.size >= MAX_EDGES) return@repeat
            val values = lines.next().trim().split(WHITESPACE)
            val count = values.firstOrNull()?.toIntOrNull()?.coerceIn(0, MAX_FACE_VERTICES) ?: 0
            val indexes = values.drop(1).take(count).mapNotNull { it.toIntOrNull()?.takeIf(vertices.indices::contains) }
            if (indexes.size >= 2) {
                indexes.zipWithNext().forEach { addEdge(edges, it.first, it.second) }
                if (indexes.size > 2) addEdge(edges, indexes.last(), indexes.first())
                faces += 1
            }
        }
        return result(vertices, edges, faces, vertexCount > acceptedVertices || faceCount > faces, "ASCII PLY")
    }

    private fun parseOff(bytes: ByteArray): MeshPreviewData {
        val lines = bytes.toString(Charsets.UTF_8).lineSequence()
            .map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.iterator()
        require(lines.hasNext() && lines.next().uppercase() in setOf("OFF", "COFF", "NOFF")) { "Invalid OFF header." }
        require(lines.hasNext()) { "OFF counts are missing." }
        val counts = lines.next().split(WHITESPACE)
        val vertexCount = counts.getOrNull(0)?.toIntOrNull() ?: 0
        val faceCount = counts.getOrNull(1)?.toIntOrNull() ?: 0
        val accepted = min(vertexCount, MAX_VERTICES)
        val vertices = ArrayList<MeshPoint>(accepted)
        repeat(vertexCount) { index ->
            if (!lines.hasNext()) return@repeat
            val values = lines.next().split(WHITESPACE)
            if (index < accepted && values.size >= 3) {
                val point = MeshPoint(
                    values[0].toFloatOrNull() ?: 0f,
                    values[1].toFloatOrNull() ?: 0f,
                    values[2].toFloatOrNull() ?: 0f,
                )
                if (point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) vertices += point
            }
        }
        val edges = linkedSetOf<Long>()
        var faces = 0
        repeat(faceCount) {
            if (!lines.hasNext() || edges.size >= MAX_EDGES) return@repeat
            val values = lines.next().split(WHITESPACE)
            val count = values.firstOrNull()?.toIntOrNull()?.coerceIn(0, MAX_FACE_VERTICES) ?: 0
            val indexes = values.drop(1).take(count).mapNotNull { it.toIntOrNull()?.takeIf(vertices.indices::contains) }
            if (indexes.size >= 2) {
                indexes.zipWithNext().forEach { addEdge(edges, it.first, it.second) }
                if (indexes.size > 2) addEdge(edges, indexes.last(), indexes.first())
                faces += 1
            }
        }
        return result(vertices, edges, faces, vertexCount > accepted || faceCount > faces, "Object File Format")
    }

    private fun vertexIndex(point: MeshPoint, vertices: MutableList<MeshPoint>, dedupe: MutableMap<VertexKey, Int>): Int {
        val key = VertexKey(point.x.toRawBits(), point.y.toRawBits(), point.z.toRawBits())
        dedupe[key]?.let { return it }
        if (vertices.size >= MAX_VERTICES) return -1
        val index = vertices.size
        vertices += point
        dedupe[key] = index
        return index
    }

    private fun addEdge(edges: MutableSet<Long>, first: Int, second: Int) {
        if (first < 0 || second < 0 || first == second || edges.size >= MAX_EDGES) return
        val low = min(first, second)
        val high = max(first, second)
        edges += (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
    }

    private fun result(vertices: List<MeshPoint>, packed: Set<Long>, faces: Int, truncated: Boolean, format: String) =
        MeshPreviewData(
            vertices = vertices,
            edges = packed.map { MeshEdge((it ushr 32).toInt(), it.toInt()) },
            sourceFaces = faces,
            truncated = truncated || vertices.size >= MAX_VERTICES || packed.size >= MAX_EDGES,
            format = format,
        )

    private data class VertexKey(val x: Int, val y: Int, val z: Int)
    private val WHITESPACE = Regex("\\s+")
}
