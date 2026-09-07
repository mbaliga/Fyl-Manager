package io.github.mbaliga.fylz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The glTF reader, over containers assembled here byte by byte.
 *
 * A `.glb` is a binary envelope around a JSON document and a buffer, and every part of reading it
 * -- chunk walking, four-byte alignment, accessor addressing, node transforms -- is arithmetic
 * that either works or silently produces a plausible-looking wrong model. These pin it.
 */
class GltfWireframeParserTest {

    @Test
    fun `a glb triangle becomes three edges placed by its node transform`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val indices = intArrayOf(0, 1, 2)
        val json = """
            {
              "scene": 0,
              "scenes": [{"nodes": [0]}],
              "nodes": [{"mesh": 0, "translation": [10, 0, 0]}],
              "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "mode": 4}]}],
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
              ],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                {"buffer": 0, "byteOffset": 36, "byteLength": 6}
              ],
              "buffers": [{"byteLength": 42}]
            }
        """.trimIndent()

        val preview = GltfWireframeParser.parse(glb(json, binary(positions, indices)))

        assertEquals(3, preview.vertices.size)
        // The node's translation has to be applied, or every part of a multi-part scene lands on
        // top of every other one at the origin.
        assertEquals(10f, preview.vertices[0].x, 0.0001f)
        assertEquals(11f, preview.vertices[1].x, 0.0001f)
        assertEquals(10f, preview.vertices[2].x, 0.0001f)
        assertEquals(1f, preview.vertices[2].y, 0.0001f)
        assertEquals(3, preview.edges.size)
        assertEquals("glTF binary scene", preview.formatLabel)
    }

    @Test
    fun `a shared triangle edge is drawn once, not once per face`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f)
        // Two triangles sharing the 1-2 edge: five distinct edges, not six.
        val indices = intArrayOf(0, 1, 2, 1, 3, 2)
        val json = """
            {
              "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
                {"bufferView": 1, "componentType": 5123, "count": 6, "type": "SCALAR"}
              ],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 48},
                {"buffer": 0, "byteOffset": 48, "byteLength": 12}
              ],
              "buffers": [{"byteLength": 60}]
            }
        """.trimIndent()

        val preview = GltfWireframeParser.parse(glb(json, binary(positions, indices)))

        assertEquals(4, preview.vertices.size)
        assertEquals(5, preview.edges.size)
    }

    @Test
    fun `a gltf with a base64 data buffer is read without any sibling file`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val encoded = Base64.getEncoder().encodeToString(binary(positions, intArrayOf(0, 1, 2)))
        val json = """
            {
              "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
              ],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                {"buffer": 0, "byteOffset": 36, "byteLength": 6}
              ],
              "buffers": [{"byteLength": 42, "uri": "data:application/octet-stream;base64,$encoded"}]
            }
        """.trimIndent()

        val preview = GltfWireframeParser.parse(json.toByteArray(Charsets.UTF_8))

        assertEquals(3, preview.vertices.size)
        assertEquals("glTF scene", preview.formatLabel)
    }

    /**
     * The case that must fail loudly rather than draw something: a `.gltf` whose geometry is in a
     * sibling `.bin`. A preview is handed one file and cannot reach the neighbour, and half a
     * model rendered as if it were whole is exactly the kind of quiet lie this codebase has
     * already shipped once.
     */
    @Test
    fun `a gltf pointing at an external bin file is refused, not half-drawn`() {
        val json = """
            {
              "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
              ],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                {"buffer": 0, "byteOffset": 36, "byteLength": 6}
              ],
              "buffers": [{"byteLength": 42, "uri": "scene.bin"}]
            }
        """.trimIndent()

        val failure = runCatching { GltfWireframeParser.parse(json.toByteArray(Charsets.UTF_8)) }.exceptionOrNull()

        assertTrue("an unreachable buffer must fail", failure != null)
    }

    @Test
    fun `only the two glTF spellings are claimed`() {
        assertTrue(GltfWireframeParser.handles("glb"))
        assertTrue(GltfWireframeParser.handles("gltf"))
        assertTrue(!GltfWireframeParser.handles("fbx"))
        assertTrue(!GltfWireframeParser.handles("usdz"))
    }

    private fun binary(positions: FloatArray, indices: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(positions.size * 4 + indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        positions.forEach(buffer::putFloat)
        indices.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    private fun glb(json: String, binary: ByteArray): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadding = (4 - jsonBytes.size % 4) % 4
        val binaryPadding = (4 - binary.size % 4) % 4
        val body = ByteArrayOutputStream()
        fun putInt(value: Int) {
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }
        putInt(jsonBytes.size)
        putInt(0x4E4F534A)
        body.write(jsonBytes)
        repeat(jsonPadding) { body.write(0x20) }
        putInt(binary.size)
        putInt(0x004E4942)
        body.write(binary)
        repeat(binaryPadding) { body.write(0) }
        val chunks = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546C67).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(2).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(12 + chunks.size).array())
        out.write(chunks)
        return out.toByteArray()
    }
}
