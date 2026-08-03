package io.github.mbaliga.fylz.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object MeshParser {
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    const val MAX_VERTICES = 250_000
    const val MAX_EDGES = 750_000
    const val MAX_FACES = 250_000

    fun parse(name: String, bytes: ByteArray): MeshPreviewData {
        require(bytes.size <= MAX_INPUT_BYTES) { "Model exceeds the in-app preview limit." }
        return when (FileFormatRegistry.compoundExtension(name)) {
            "obj" -> parseObj(bytes.toString(Charsets.UTF_8))
            "stl" -> parseStl(bytes)
            "ply" -> parsePly(bytes.toString(Charsets.UTF_8))
            "off" -> parseOff(bytes.toString(Charsets.UTF_8))
            else -> error("No built-in mesh parser for this format.")
        }
    }

    private fun parseObj(text: String): MeshPreviewData {
        val vertices = mutableListOf<Point3>()
        val edges = linkedSetOf<Long>()
        var faces = 0
        var truncated = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("v ") -> {
                    if (vertices.size >= MAX_VERTICES) { truncated = true; return@forEach }
                    val p = line.split(Regex("\\s+")).drop(1).take(3).mapNotNull(String::toFloatOrNull)
                    if (p.size == 3) vertices += Point3(p[0], p[1], p[2])
                }
                line.startsWith("f ") || line.startsWith("l ") -> {
                    val indices = line.split(Regex("\\s+")).drop(1).mapNotNull { token ->
                        token.substringBefore('/').toIntOrNull()?.let { index ->
                            if (index > 0) index - 1 else vertices.size + index
                        }
                    }.filter { it in vertices.indices }
                    if (indices.size >= 2) {
                        if (line.startsWith("f ")) faces += 1
                        indices.zipWithNext().forEach { addEdge(edges, it.first, it.second) }
                        if (line.startsWith("f ") && indices.size > 2) addEdge(edges, indices.last(), indices.first())
                    }
                    if (edges.size >= MAX_EDGES || faces >= MAX_FACES) truncated = true
                }
            }
        }
        return result(vertices, edges, faces, "Wavefront OBJ", truncated)
    }

    private fun parseStl(bytes: ByteArray): MeshPreviewData {
        val binaryCount = if (bytes.size >= 84) ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL else -1L
        val binarySize = if (binaryCount >= 0) 84L + binaryCount * 50L else -1L
        return if (binarySize in 84..bytes.size.toLong()) parseBinaryStl(bytes, binaryCount.toInt())
        else parseAsciiStl(bytes.toString(Charsets.US_ASCII))
    }

    private fun parseBinaryStl(bytes: ByteArray, declaredTriangles: Int): MeshPreviewData {
        val vertices = mutableListOf<Point3>()
        val vertexIndex = hashMapOf<Point3, Int>()
        val edges = linkedSetOf<Long>()
        val triangles = minOf(declaredTriangles, MAX_FACES, (bytes.size - 84) / 50)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(84) }
        repeat(triangles) {
            buffer.position(buffer.position() + 12)
            val ids = IntArray(3)
            repeat(3) { index ->
                val point = Point3(buffer.float, buffer.float, buffer.float)
                ids[index] = vertexIndex.getOrPut(point) {
                    require(vertices.size < MAX_VERTICES) { "STL contains too many unique vertices." }
                    vertices.add(point); vertices.lastIndex
                }
            }
            buffer.short
            addEdge(edges, ids[0], ids[1]); addEdge(edges, ids[1], ids[2]); addEdge(edges, ids[2], ids[0])
        }
        return result(vertices, edges, triangles, "Binary STL", triangles < declaredTriangles)
    }

    private fun parseAsciiStl(text: String): MeshPreviewData {
        val vertices = mutableListOf<Point3>()
        val vertexIndex = hashMapOf<Point3, Int>()
        val edges = linkedSetOf<Long>()
        val triangle = mutableListOf<Int>()
        var faces = 0
        var truncated = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim().lowercase(Locale.ROOT)
            if (line.startsWith("vertex ")) {
                val p = line.split(Regex("\\s+")).drop(1).take(3).mapNotNull(String::toFloatOrNull)
                if (p.size == 3 && vertices.size < MAX_VERTICES) {
                    val point = Point3(p[0], p[1], p[2])
                    triangle += vertexIndex.getOrPut(point) { vertices.add(point); vertices.lastIndex }
                }
            }
            if (line == "endfacet" && triangle.size >= 3) {
                val ids = triangle.takeLast(3)
                addEdge(edges, ids[0], ids[1]); addEdge(edges, ids[1], ids[2]); addEdge(edges, ids[2], ids[0])
                faces += 1
                triangle.clear()
                if (faces >= MAX_FACES || edges.size >= MAX_EDGES) truncated = true
            }
        }
        return result(vertices, edges, faces, "ASCII STL", truncated)
    }

    private fun parsePly(text: String): MeshPreviewData {
        val lines = text.lineSequence().iterator()
        require(lines.hasNext() && lines.next().trim() == "ply") { "Not a PLY file." }
        var vertexCount = 0
        var faceCount = 0
        var ascii = false
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (line == "format ascii 1.0") ascii = true
            if (line.startsWith("element vertex ")) vertexCount = line.substringAfterLast(' ').toInt()
            if (line.startsWith("element face ")) faceCount = line.substringAfterLast(' ').toInt()
            if (line == "end_header") break
        }
        require(ascii) { "Binary PLY is inspected but not rendered by the built-in viewer." }
        val vertices = mutableListOf<Point3>()
        repeat(minOf(vertexCount, MAX_VERTICES)) {
            require(lines.hasNext()) { "Truncated PLY vertex data." }
            val p = lines.next().trim().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
            if (p.size == 3) vertices += Point3(p[0], p[1], p[2])
        }
        repeat((vertexCount - vertices.size).coerceAtLeast(0)) { if (lines.hasNext()) lines.next() }
        val edges = linkedSetOf<Long>()
        var parsedFaces = 0
        repeat(minOf(faceCount, MAX_FACES)) {
            if (!lines.hasNext()) return@repeat
            val values = lines.next().trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
            val count = values.firstOrNull() ?: 0
            val ids = values.drop(1).take(count).filter { it in vertices.indices }
            ids.zipWithNext().forEach { addEdge(edges, it.first, it.second) }
            if (ids.size > 2) addEdge(edges, ids.last(), ids.first())
            parsedFaces += 1
        }
        return result(vertices, edges, parsedFaces, "ASCII PLY", vertexCount > MAX_VERTICES || faceCount > MAX_FACES)
    }

    private fun parseOff(text: String): MeshPreviewData {
        val lines = text.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.iterator()
        require(lines.hasNext() && lines.next().uppercase(Locale.ROOT).endsWith("OFF")) { "Not an OFF model." }
        val counts = lines.next().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        require(counts.size >= 2)
        val vertexCount = counts[0]
        val faceCount = counts[1]
        val vertices = mutableListOf<Point3>()
        repeat(minOf(vertexCount, MAX_VERTICES)) {
            val p = lines.next().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
            if (p.size == 3) vertices += Point3(p[0], p[1], p[2])
        }
        repeat((vertexCount - vertices.size).coerceAtLeast(0)) { if (lines.hasNext()) lines.next() }
        val edges = linkedSetOf<Long>()
        var parsed = 0
        repeat(minOf(faceCount, MAX_FACES)) {
            if (!lines.hasNext()) return@repeat
            val values = lines.next().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
            val ids = values.drop(1).take(values.firstOrNull() ?: 0).filter { it in vertices.indices }
            ids.zipWithNext().forEach { addEdge(edges, it.first, it.second) }
            if (ids.size > 2) addEdge(edges, ids.last(), ids.first())
            parsed += 1
        }
        return result(vertices, edges, parsed, "OFF model", vertexCount > MAX_VERTICES || faceCount > MAX_FACES)
    }

    private fun result(vertices: List<Point3>, encodedEdges: Set<Long>, faces: Int, label: String, truncated: Boolean) =
        MeshPreviewData(vertices, encodedEdges.take(MAX_EDGES).map { Edge3((it ushr 32).toInt(), it.toInt()) }, faces, label, truncated || encodedEdges.size > MAX_EDGES)

    private fun addEdge(edges: MutableSet<Long>, first: Int, second: Int) {
        if (first == second || edges.size >= MAX_EDGES) return
        val a = minOf(first, second); val b = maxOf(first, second)
        edges += (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
    }
}
