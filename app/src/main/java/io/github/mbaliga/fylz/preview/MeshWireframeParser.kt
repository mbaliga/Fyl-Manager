package io.github.mbaliga.fylz.preview

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class MeshPoint3(val x: Float, val y: Float, val z: Float)
data class MeshEdge(val from: Int, val to: Int)

data class MeshWireframe(
    val vertices: List<MeshPoint3>,
    val edges: List<MeshEdge>,
    val faceCount: Int,
    val sourceFormat: String,
    val truncated: Boolean,
) {
    init {
        require(vertices.isNotEmpty())
        require(edges.all { it.from in vertices.indices && it.to in vertices.indices })
    }
}

/** Lightweight, bounded parsers for common interchange meshes. */
object MeshWireframeParser {
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    const val MAX_VERTICES = 250_000
    const val MAX_EDGES = 500_000
    const val MAX_FACES = 250_000
    private const val MAX_LINE_CHARS = 64 * 1024

    fun parse(extension: String, bytes: ByteArray): MeshWireframe {
        require(bytes.size <= MAX_INPUT_BYTES) { "3D preview input exceeds the 32 MiB safety limit." }
        return when (extension.lowercase()) {
            "obj" -> parseObj(bytes.inputStream())
            "stl" -> parseStl(bytes)
            "ply" -> parsePly(bytes.inputStream())
            "off" -> parseOff(bytes.inputStream())
            else -> error("No built-in mesh parser for .$extension")
        }
    }

    fun parseObj(input: InputStream): MeshWireframe {
        val vertices = mutableListOf<MeshPoint3>()
        val edges = linkedSetOf<Long>()
        var faces = 0
        var truncated = false
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                require(raw.length <= MAX_LINE_CHARS) { "OBJ line exceeds the preview safety limit." }
                val line = raw.trim()
                when {
                    line.startsWith("v ") -> {
                        if (vertices.size >= MAX_VERTICES) { truncated = true; return@forEach }
                        val values = line.substring(2).trim().split(Regex("\\s+")).take(3)
                        if (values.size == 3) {
                            val p = values.mapNotNull(String::toFloatOrNull)
                            if (p.size == 3 && p.all(Float::isFinite)) vertices += MeshPoint3(p[0], p[1], p[2])
                        }
                    }
                    line.startsWith("f ") || line.startsWith("l ") -> {
                        if (faces >= MAX_FACES || edges.size >= MAX_EDGES) { truncated = true; return@forEach }
                        val indices = line.substring(2).trim().split(Regex("\\s+"))
                            .mapNotNull { token ->
                                val rawIndex = token.substringBefore('/').toIntOrNull() ?: return@mapNotNull null
                                when {
                                    rawIndex > 0 -> rawIndex - 1
                                    rawIndex < 0 -> vertices.size + rawIndex
                                    else -> null
                                }
                            }
                            .filter { it in vertices.indices }
                        if (indices.size >= 2) {
                            for (i in 0 until indices.lastIndex) addEdge(edges, indices[i], indices[i + 1])
                            if (line.startsWith("f ") && indices.size > 2) addEdge(edges, indices.last(), indices.first())
                            faces += if (line.startsWith("f ")) 1 else 0
                        }
                    }
                }
            }
        }
        return build(vertices, edges, faces, "Wavefront OBJ", truncated)
    }

    fun parseStl(bytes: ByteArray): MeshWireframe {
        if (looksLikeBinaryStl(bytes)) return parseBinaryStl(bytes)
        return parseAsciiStl(bytes.inputStream())
    }

    private fun parseAsciiStl(input: InputStream): MeshWireframe {
        val vertices = mutableListOf<MeshPoint3>()
        val edges = linkedSetOf<Long>()
        val lookup = HashMap<MeshPoint3, Int>()
        val triangle = ArrayList<Int>(3)
        var faces = 0
        var truncated = false
        input.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            lines.forEach { raw ->
                require(raw.length <= MAX_LINE_CHARS) { "STL line exceeds the preview safety limit." }
                val line = raw.trim()
                if (!line.startsWith("vertex ")) return@forEach
                if (faces >= MAX_FACES || edges.size >= MAX_EDGES || vertices.size >= MAX_VERTICES) {
                    truncated = true
                    return@forEach
                }
                val values = line.removePrefix("vertex ").trim().split(Regex("\\s+")).take(3)
                    .mapNotNull(String::toFloatOrNull)
                if (values.size != 3 || values.any { !it.isFinite() }) return@forEach
                val point = MeshPoint3(values[0], values[1], values[2])
                val index = lookup.getOrPut(point) {
                    vertices.add(point)
                    vertices.lastIndex
                }
                triangle += index
                if (triangle.size == 3) {
                    addEdge(edges, triangle[0], triangle[1])
                    addEdge(edges, triangle[1], triangle[2])
                    addEdge(edges, triangle[2], triangle[0])
                    triangle.clear()
                    faces += 1
                }
            }
        }
        return build(vertices, edges, faces, "ASCII STL", truncated)
    }

    private fun parseBinaryStl(bytes: ByteArray): MeshWireframe {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 84) { "Invalid binary STL." }
        buffer.position(80)
        val declared = buffer.int.toLong() and 0xffffffffL
        val available = ((bytes.size - 84) / 50).toLong()
        val count = min(min(declared, available), MAX_FACES.toLong()).toInt()
        val vertices = mutableListOf<MeshPoint3>()
        val edges = linkedSetOf<Long>()
        val lookup = HashMap<MeshPoint3, Int>()
        var faces = 0
        repeat(count) {
            if (buffer.remaining() < 50 || vertices.size >= MAX_VERTICES || edges.size >= MAX_EDGES) return@repeat
            buffer.position(buffer.position() + 12) // normal
            val indices = IntArray(3)
            for (i in 0..2) {
                val point = MeshPoint3(buffer.float, buffer.float, buffer.float)
                require(point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) { "Invalid STL coordinate." }
                indices[i] = lookup.getOrPut(point) {
                    vertices.add(point)
                    vertices.lastIndex
                }
            }
            buffer.short
            addEdge(edges, indices[0], indices[1])
            addEdge(edges, indices[1], indices[2])
            addEdge(edges, indices[2], indices[0])
            faces += 1
        }
        return build(vertices, edges, faces, "Binary STL", declared > count || available > count)
    }

    fun parsePly(input: InputStream): MeshWireframe {
        val reader = input.bufferedReader(Charsets.US_ASCII)
        require(reader.readLine()?.trim() == "ply") { "Invalid PLY header." }
        var format = ""
        var vertexCount = 0
        var faceCount = 0
        var headerLines = 1
        while (true) {
            val line = reader.readLine() ?: error("Incomplete PLY header.")
            headerLines += 1
            require(headerLines <= 10_000 && line.length <= MAX_LINE_CHARS) { "PLY header exceeds safety limits." }
            val parts = line.trim().split(Regex("\\s+"))
            when {
                parts.firstOrNull() == "format" -> format = parts.getOrNull(1).orEmpty()
                parts.take(2) == listOf("element", "vertex") -> vertexCount = parts.getOrNull(2)?.toIntOrNull() ?: 0
                parts.take(2) == listOf("element", "face") -> faceCount = parts.getOrNull(2)?.toIntOrNull() ?: 0
                line.trim() == "end_header" -> break
            }
        }
        require(format == "ascii") { "Only ASCII PLY is rendered in-app; binary PLY remains inspectable." }
        require(vertexCount in 1..MAX_VERTICES) { "PLY vertex count exceeds preview limits." }
        val vertices = ArrayList<MeshPoint3>(vertexCount)
        repeat(vertexCount) {
            val line = reader.readLine() ?: error("PLY ended before all vertices were read.")
            require(line.length <= MAX_LINE_CHARS)
            val p = line.trim().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
            require(p.size == 3 && p.all(Float::isFinite)) { "Invalid PLY vertex." }
            vertices += MeshPoint3(p[0], p[1], p[2])
        }
        val edges = linkedSetOf<Long>()
        var parsedFaces = 0
        var truncated = faceCount > MAX_FACES
        repeat(min(faceCount, MAX_FACES)) {
            val line = reader.readLine() ?: return@repeat
            require(line.length <= MAX_LINE_CHARS)
            val tokens = line.trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
            val count = tokens.firstOrNull() ?: return@repeat
            val indices = tokens.drop(1).take(count).filter { it in vertices.indices }
            if (indices.size >= 2) {
                indices.indices.forEach { i -> addEdge(edges, indices[i], indices[(i + 1) % indices.size]) }
                parsedFaces += 1
            }
            if (edges.size >= MAX_EDGES) truncated = true
        }
        return build(vertices, edges, parsedFaces, "ASCII PLY", truncated)
    }

    fun parseOff(input: InputStream): MeshWireframe {
        val lines = input.bufferedReader(Charsets.US_ASCII).lineSequence()
            .map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.iterator()
        require(lines.hasNext() && lines.next() in setOf("OFF", "COFF")) { "Invalid OFF header." }
        require(lines.hasNext()) { "Missing OFF counts." }
        val counts = lines.next().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        require(counts.size >= 2)
        val vertexCount = counts[0]
        val faceCount = counts[1]
        require(vertexCount in 1..MAX_VERTICES)
        val vertices = ArrayList<MeshPoint3>(vertexCount)
        repeat(vertexCount) {
            require(lines.hasNext()) { "OFF ended before all vertices were read." }
            val p = lines.next().split(Regex("\\s+")).take(3).mapNotNull(String::toFloatOrNull)
            require(p.size == 3 && p.all(Float::isFinite))
            vertices += MeshPoint3(p[0], p[1], p[2])
        }
        val edges = linkedSetOf<Long>()
        var parsedFaces = 0
        var truncated = faceCount > MAX_FACES
        repeat(min(faceCount, MAX_FACES)) {
            if (!lines.hasNext()) return@repeat
            val tokens = lines.next().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
            val count = tokens.firstOrNull() ?: return@repeat
            val indices = tokens.drop(1).take(count).filter { it in vertices.indices }
            if (indices.size >= 2) {
                indices.indices.forEach { i -> addEdge(edges, indices[i], indices[(i + 1) % indices.size]) }
                parsedFaces += 1
            }
            if (edges.size >= MAX_EDGES) truncated = true
        }
        return build(vertices, edges, parsedFaces, "OFF mesh", truncated)
    }

    private fun looksLikeBinaryStl(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return false
        val count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        return 84L + count * 50L <= bytes.size.toLong()
    }

    private fun addEdge(edges: MutableSet<Long>, first: Int, second: Int) {
        if (first == second || edges.size >= MAX_EDGES) return
        val low = min(first, second)
        val high = max(first, second)
        edges += (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
    }

    private fun build(
        vertices: List<MeshPoint3>,
        encodedEdges: Set<Long>,
        faces: Int,
        format: String,
        truncated: Boolean,
    ): MeshWireframe {
        require(vertices.isNotEmpty()) { "$format contains no previewable vertices." }
        val edges = encodedEdges.take(MAX_EDGES).map { encoded ->
            MeshEdge((encoded ushr 32).toInt(), encoded.toInt())
        }
        require(edges.isNotEmpty()) { "$format contains no previewable edges." }
        return MeshWireframe(vertices, edges, faces, format, truncated || encodedEdges.size > MAX_EDGES)
    }
}
