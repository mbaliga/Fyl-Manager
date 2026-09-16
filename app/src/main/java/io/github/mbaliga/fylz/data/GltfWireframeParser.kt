package io.github.mbaliga.fylz.data

import io.github.mbaliga.fylz.preview.Edge3
import io.github.mbaliga.fylz.preview.GeometryPreview
import io.github.mbaliga.fylz.preview.GeometryPreviewParser
import io.github.mbaliga.fylz.preview.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * A wireframe out of a glTF 2.0 scene, hand-parsed, because Fylz bundles no 3D engine.
 *
 * The format registry has advertised a "gltf" renderer for `.gltf`/`.glb` at RENDERED depth for
 * some time with nothing behind it -- those files fell through to the byte inspector. This is that
 * renderer's missing half: the JSON (or the GLB's JSON chunk), the binary buffer, the accessors
 * that address it, and the scene's node transforms, resolved into positions and edges.
 *
 * Node transforms are applied rather than ignored. A glTF scene places each mesh with a node
 * matrix or a translation/rotation/scale triple, and a reader that skips them piles every part of
 * a multi-part model onto the origin -- a picture of the file's geometry that is not a picture of
 * the model.
 *
 * The honest limits, which the caller surfaces rather than hides:
 *  - A `.gltf` whose buffers live in a separate `.bin` file cannot be drawn. A preview is handed
 *    one file and cannot reach a sibling, so that case fails with a message saying exactly that
 *    instead of rendering a partial model. GLB (one self-contained file) and base64 `data:`
 *    buffers both work.
 *  - Positions must be float VEC3. Quantized meshes (the KHR_mesh_quantization extension) are
 *    counted as skipped, never silently drawn at the wrong scale.
 *  - Triangles, triangle strips/fans and line primitives are converted to edges. Point clouds and
 *    anything else are counted as skipped.
 */
object GltfWireframeParser {
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_NODES = 20_000
    private const val MAX_DEPTH = 64
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private const val COMPONENT_BYTE = 5120
    private const val COMPONENT_UNSIGNED_BYTE = 5121
    private const val COMPONENT_SHORT = 5122
    private const val COMPONENT_UNSIGNED_SHORT = 5123
    private const val COMPONENT_UNSIGNED_INT = 5125
    private const val COMPONENT_FLOAT = 5126

    private const val MODE_LINES = 1
    private const val MODE_LINE_LOOP = 2
    private const val MODE_LINE_STRIP = 3
    private const val MODE_TRIANGLES = 4
    private const val MODE_TRIANGLE_STRIP = 5
    private const val MODE_TRIANGLE_FAN = 6

    /** True for the two spellings this parser handles. */
    fun handles(extension: String): Boolean = extension == "gltf" || extension == "glb"

    fun parse(bytes: ByteArray): GeometryPreview {
        require(bytes.size <= MAX_INPUT_BYTES) { "This 3D scene exceeds the 32 MiB preview limit." }
        val container = readContainer(bytes)
        val root = JSONObject(container.json)
        val buffers = resolveBuffers(root.optJSONArray("buffers"), container.binary)
        val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        require(meshes.length() > 0) { "This glTF scene declares no meshes." }

        val vertices = mutableListOf<Vec3>()
        val edges = LinkedHashSet<Long>()
        var primitives = 0
        var skipped = 0
        var truncated = false

        for (placement in placements(root)) {
            val mesh = meshes.optJSONObject(placement.mesh) ?: continue
            val list = mesh.optJSONArray("primitives") ?: continue
            for (index in 0 until list.length()) {
                if (truncated) break
                val primitive = list.optJSONObject(index) ?: continue
                primitives += 1
                val positionAccessor = primitive.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                val positions = readPositions(positionAccessor, accessors, bufferViews, buffers)
                if (positions == null) {
                    skipped += 1
                    continue
                }
                val indices = primitive.optInt("indices", -1)
                    .takeIf { it >= 0 }
                    ?.let { readIndices(it, accessors, bufferViews, buffers) }
                    ?: IntArray(positions.size / 3) { it }
                val mode = primitive.optInt("mode", MODE_TRIANGLES)
                val pairs = edgePairs(mode, indices)
                if (pairs == null) {
                    skipped += 1
                    continue
                }
                val base = vertices.size
                if (base + positions.size / 3 > GeometryPreviewParser.MAX_VERTICES) {
                    truncated = true
                    break
                }
                var offset = 0
                while (offset + 2 < positions.size) {
                    val point = placement.matrix.transform(positions[offset], positions[offset + 1], positions[offset + 2])
                    vertices += Vec3(point[0], point[1], point[2])
                    offset += 3
                }
                val vertexCount = vertices.size - base
                var pair = 0
                while (pair + 1 < pairs.size) {
                    val from = pairs[pair]
                    val to = pairs[pair + 1]
                    pair += 2
                    if (from == to || from !in 0 until vertexCount || to !in 0 until vertexCount) continue
                    val low = minOf(from, to) + base
                    val high = maxOf(from, to) + base
                    edges += (low.toLong() shl 32) or high.toLong()
                    if (edges.size >= GeometryPreviewParser.MAX_EDGES) {
                        truncated = true
                        break
                    }
                }
            }
        }
        require(vertices.isNotEmpty() && edges.isNotEmpty()) {
            if (skipped > 0) {
                "This glTF scene's geometry uses a primitive or accessor type Fylz cannot read."
            } else {
                "This glTF scene contains no drawable geometry."
            }
        }
        return GeometryPreview(
            vertices = vertices,
            edges = edges.map { Edge3((it ushr 32).toInt(), (it and 0xFFFFFFFFL).toInt()) },
            sourcePrimitiveCount = primitives,
            truncated = truncated || skipped > 0,
            formatLabel = if (container.binary != null) "glTF binary scene" else "glTF scene",
        )
    }

    private class Container(val json: String, val binary: ByteArray?)

    private fun readContainer(bytes: ByteArray): Container {
        if (bytes.size < 12) error("This file is too short to be a glTF scene.")
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (header.getInt(0) != GLB_MAGIC) {
            // A plain .gltf is JSON; the JSON parser rejects anything that is not.
            return Container(String(bytes, Charsets.UTF_8), null)
        }
        val declared = header.getInt(8)
        val limit = if (declared in 12..bytes.size) declared else bytes.size
        var offset = 12
        var json: String? = null
        var binary: ByteArray? = null
        while (offset + 8 <= limit) {
            val length = header.getInt(offset)
            val type = header.getInt(offset + 4)
            val start = offset + 8
            if (length < 0 || start + length > limit) break
            when (type) {
                CHUNK_JSON -> if (json == null) json = String(bytes, start, length, Charsets.UTF_8)
                CHUNK_BIN -> if (binary == null) binary = bytes.copyOfRange(start, start + length)
            }
            // Chunks are four-byte aligned; an unaligned length would otherwise walk into padding.
            offset = start + length + ((4 - (length % 4)) % 4)
        }
        return Container(json ?: error("This GLB file has no JSON chunk."), binary)
    }

    private fun resolveBuffers(declared: JSONArray?, binary: ByteArray?): List<ByteArray?> {
        val list = declared ?: JSONArray()
        return (0 until list.length()).map { index ->
            val buffer = list.optJSONObject(index)
            val uri = buffer?.optString("uri").orEmpty()
            when {
                // The GLB binary chunk is buffer 0 and carries no uri, by specification.
                uri.isEmpty() -> if (index == 0) binary else null
                uri.startsWith("data:") -> uri.substringAfter(";base64,", "")
                    .takeIf { it.isNotEmpty() }
                    ?.let { encoded -> runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() }
                // An external .bin sibling: a single-file preview has no way to reach it.
                else -> null
            }
        }
    }

    private class Placement(val mesh: Int, val matrix: FloatArray)

    /**
     * Every mesh in the default scene, paired with the world matrix its node chain gives it.
     * A scene with no node graph at all falls back to drawing each declared mesh at the origin.
     */
    private fun placements(root: JSONObject): List<Placement> {
        val nodes = root.optJSONArray("nodes")
        val meshCount = root.optJSONArray("meshes")?.length() ?: 0
        if (nodes == null || nodes.length() == 0) {
            return (0 until meshCount).map { Placement(it, identity()) }
        }
        val scenes = root.optJSONArray("scenes")
        val sceneIndex = root.optInt("scene", 0)
        val roots = scenes?.optJSONObject(sceneIndex)?.optJSONArray("nodes")
            ?: JSONArray((0 until nodes.length()).toList())
        val out = mutableListOf<Placement>()
        val visited = HashSet<Int>()
        for (index in 0 until roots.length()) {
            walk(nodes, roots.optInt(index, -1), identity(), out, visited, 0)
        }
        return out
    }

    private fun walk(
        nodes: JSONArray,
        index: Int,
        parent: FloatArray,
        out: MutableList<Placement>,
        visited: MutableSet<Int>,
        depth: Int,
    ) {
        if (index < 0 || index >= nodes.length() || depth > MAX_DEPTH || out.size > MAX_NODES) return
        // A malformed file can point a child back at an ancestor; visiting each node once keeps
        // that from becoming an infinite descent.
        if (!visited.add(index)) return
        val node = nodes.optJSONObject(index) ?: return
        val world = parent.multiply(localMatrix(node))
        val mesh = node.optInt("mesh", -1)
        if (mesh >= 0) out += Placement(mesh, world)
        val children = node.optJSONArray("children") ?: return
        for (child in 0 until children.length()) {
            walk(nodes, children.optInt(child, -1), world, out, visited, depth + 1)
        }
    }

    private fun localMatrix(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.let { declared ->
            if (declared.length() == 16) {
                return FloatArray(16) { declared.optDouble(it, 0.0).toFloat() }
            }
        }
        val translation = node.optJSONArray("translation")
        val rotation = node.optJSONArray("rotation")
        val scale = node.optJSONArray("scale")
        val sx = scale?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = scale?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = scale?.optDouble(2, 1.0)?.toFloat() ?: 1f
        val qx = rotation?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = rotation?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = rotation?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = rotation?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
        // Column-major, matching glTF's own convention: M = T * R * S.
        return floatArrayOf(
            (1 - 2 * (yy + zz)) * sx, (2 * (xy + wz)) * sx, (2 * (xz - wy)) * sx, 0f,
            (2 * (xy - wz)) * sy, (1 - 2 * (xx + zz)) * sy, (2 * (yz + wx)) * sy, 0f,
            (2 * (xz + wy)) * sz, (2 * (yz - wx)) * sz, (1 - 2 * (xx + yy)) * sz, 0f,
            translation?.optDouble(0, 0.0)?.toFloat() ?: 0f,
            translation?.optDouble(1, 0.0)?.toFloat() ?: 0f,
            translation?.optDouble(2, 0.0)?.toFloat() ?: 0f,
            1f,
        )
    }

    private fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private fun FloatArray.multiply(other: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var total = 0f
                for (k in 0 until 4) total += this[k * 4 + row] * other[column * 4 + k]
                result[column * 4 + row] = total
            }
        }
        return result
    }

    private fun FloatArray.transform(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        this[0] * x + this[4] * y + this[8] * z + this[12],
        this[1] * x + this[5] * y + this[9] * z + this[13],
        this[2] * x + this[6] * y + this[10] * z + this[14],
    )

    private fun readPositions(
        accessorIndex: Int,
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray?>,
    ): FloatArray? {
        val accessor = accessors.optJSONObject(accessorIndex) ?: return null
        if (accessor.optString("type") != "VEC3") return null
        // Only float positions: a quantized mesh read as float would be drawn at a wrong scale,
        // which is worse than declining to draw it.
        if (accessor.optInt("componentType") != COMPONENT_FLOAT) return null
        val count = accessor.optInt("count", 0)
        if (count <= 0 || count > GeometryPreviewParser.MAX_VERTICES) return null
        val view = viewOf(accessor, bufferViews, buffers) ?: return null
        val stride = if (view.stride > 0) view.stride else 12
        val out = FloatArray(count * 3)
        for (element in 0 until count) {
            val base = view.offset + element * stride
            if (base + 12 > view.bytes.size) return null
            val buffer = ByteBuffer.wrap(view.bytes, base, 12).order(ByteOrder.LITTLE_ENDIAN)
            out[element * 3] = buffer.float
            out[element * 3 + 1] = buffer.float
            out[element * 3 + 2] = buffer.float
        }
        return out
    }

    private fun readIndices(
        accessorIndex: Int,
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray?>,
    ): IntArray? {
        val accessor = accessors.optJSONObject(accessorIndex) ?: return null
        if (accessor.optString("type") != "SCALAR") return null
        val componentType = accessor.optInt("componentType")
        val width = when (componentType) {
            COMPONENT_BYTE, COMPONENT_UNSIGNED_BYTE -> 1
            COMPONENT_SHORT, COMPONENT_UNSIGNED_SHORT -> 2
            COMPONENT_UNSIGNED_INT -> 4
            else -> return null
        }
        val count = accessor.optInt("count", 0)
        if (count <= 0 || count > GeometryPreviewParser.MAX_EDGES * 3) return null
        val view = viewOf(accessor, bufferViews, buffers) ?: return null
        val stride = if (view.stride > 0) view.stride else width
        val out = IntArray(count)
        for (element in 0 until count) {
            val base = view.offset + element * stride
            if (base + width > view.bytes.size) return null
            out[element] = when (width) {
                1 -> view.bytes[base].toInt() and 0xFF
                2 -> (view.bytes[base].toInt() and 0xFF) or ((view.bytes[base + 1].toInt() and 0xFF) shl 8)
                else -> ByteBuffer.wrap(view.bytes, base, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
        }
        return out
    }

    private class View(val bytes: ByteArray, val offset: Int, val stride: Int)

    private fun viewOf(accessor: JSONObject, bufferViews: JSONArray, buffers: List<ByteArray?>): View? {
        val viewIndex = accessor.optInt("bufferView", -1)
        if (viewIndex < 0) return null
        val view = bufferViews.optJSONObject(viewIndex) ?: return null
        val bytes = buffers.getOrNull(view.optInt("buffer", -1)) ?: return null
        val offset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
        if (offset < 0 || offset >= bytes.size) return null
        return View(bytes, offset, view.optInt("byteStride", 0))
    }

    /** Index pairs for one primitive's edges, or null when the mode draws no lines Fylz can show. */
    private fun edgePairs(mode: Int, indices: IntArray): IntArray? = when (mode) {
        MODE_TRIANGLES -> IntArray(indices.size / 3 * 6).also { out ->
            var triangle = 0
            while (triangle * 3 + 2 < indices.size) {
                val a = indices[triangle * 3]
                val b = indices[triangle * 3 + 1]
                val c = indices[triangle * 3 + 2]
                out[triangle * 6] = a; out[triangle * 6 + 1] = b
                out[triangle * 6 + 2] = b; out[triangle * 6 + 3] = c
                out[triangle * 6 + 4] = c; out[triangle * 6 + 5] = a
                triangle += 1
            }
        }
        MODE_TRIANGLE_STRIP -> buildPairs(indices.size - 2) { step ->
            intArrayOf(indices[step], indices[step + 1], indices[step + 1], indices[step + 2], indices[step + 2], indices[step])
        }
        MODE_TRIANGLE_FAN -> buildPairs(indices.size - 2) { step ->
            intArrayOf(indices[0], indices[step + 1], indices[step + 1], indices[step + 2], indices[step + 2], indices[0])
        }
        MODE_LINES -> IntArray(indices.size / 2 * 2) { indices[it] }
        MODE_LINE_STRIP, MODE_LINE_LOOP -> buildPairs(indices.size - 1) { step ->
            intArrayOf(indices[step], indices[step + 1])
        }
        else -> null
    }

    private fun buildPairs(steps: Int, pairsAt: (Int) -> IntArray): IntArray {
        if (steps <= 0) return IntArray(0)
        val collected = ArrayList<Int>(steps * 2)
        for (step in 0 until steps) collected.addAll(pairsAt(step).toList())
        return collected.toIntArray()
    }
}
