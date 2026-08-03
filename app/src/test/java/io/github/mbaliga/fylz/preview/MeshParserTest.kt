package io.github.mbaliga.fylz.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshParserTest {
    @Test
    fun objBuildsDeduplicatedWireframe() {
        val model = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3
        """.trimIndent().toByteArray()
        val result = MeshParser.parse("triangle.obj", model)
        assertEquals(3, result.vertices.size)
        assertEquals(3, result.edges.size)
        assertEquals(1, result.faceCount)
    }

    @Test
    fun asciiStlParsesTriangle() {
        val model = """
            solid triangle
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                  vertex 0 1 0
                endloop
              endfacet
            endsolid triangle
        """.trimIndent().toByteArray()
        val result = MeshParser.parse("triangle.stl", model)
        assertEquals(3, result.vertices.size)
        assertEquals(3, result.edges.size)
        assertEquals(1, result.faceCount)
    }

    @Test
    fun binaryStlParsesTriangle() {
        val bytes = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(80)
            putInt(1)
            repeat(3) { putFloat(0f) }
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach(::putFloat)
            putShort(0)
        }.array()
        val result = MeshParser.parse("triangle.stl", bytes)
        assertEquals(3, result.vertices.size)
        assertEquals(3, result.edges.size)
        assertEquals(1, result.faceCount)
    }

    @Test
    fun offParsesPolygon() {
        val result = MeshParser.parse(
            "quad.off",
            """
                OFF
                4 1 0
                0 0 0
                1 0 0
                1 1 0
                0 1 0
                4 0 1 2 3
            """.trimIndent().toByteArray(),
        )
        assertEquals(4, result.vertices.size)
        assertEquals(4, result.edges.size)
        assertTrue(!result.truncated)
    }
}
