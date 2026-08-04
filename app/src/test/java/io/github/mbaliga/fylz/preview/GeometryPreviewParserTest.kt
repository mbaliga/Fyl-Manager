package io.github.mbaliga.fylz.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeometryPreviewParserTest {
    @Test fun parsesObjFacesAsWireframe() {
        val obj = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f 1 2 3 4
        """.trimIndent().toByteArray()
        val result = GeometryPreviewParser.parse("square.obj", obj)
        assertEquals(4, result.vertices.size)
        assertEquals(4, result.edges.size)
        assertEquals(1, result.sourcePrimitiveCount)
        assertFalse(result.truncated)
    }

    @Test fun parsesAsciiStlTriangle() {
        val stl = """
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
        val result = GeometryPreviewParser.parse("triangle.stl", stl)
        assertEquals(3, result.vertices.size)
        assertEquals(3, result.edges.size)
    }

    @Test fun parsesBinaryStlTriangle() {
        val bytes = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(80)
            putInt(1)
            putFloat(0f); putFloat(0f); putFloat(1f)
            putFloat(0f); putFloat(0f); putFloat(0f)
            putFloat(1f); putFloat(0f); putFloat(0f)
            putFloat(0f); putFloat(1f); putFloat(0f)
            putShort(0)
        }.array()
        val result = GeometryPreviewParser.parse("triangle.stl", bytes)
        assertEquals("Binary STL", result.formatLabel)
        assertEquals(3, result.edges.size)
    }

    @Test fun parsesAsciiPlyFace() {
        val ply = """
            ply
            format ascii 1.0
            element vertex 3
            property float x
            property float y
            property float z
            element face 1
            property list uchar int vertex_indices
            end_header
            0 0 0
            1 0 0
            0 1 0
            3 0 1 2
        """.trimIndent().toByteArray()
        val result = GeometryPreviewParser.parse("triangle.ply", ply)
        assertEquals(3, result.vertices.size)
        assertEquals(3, result.edges.size)
    }

    @Test fun parsesDxfLineCircleAndPolyline() {
        val dxf = listOf(
            "0", "SECTION", "2", "ENTITIES",
            "0", "LINE", "10", "0", "20", "0", "11", "10", "21", "5",
            "0", "CIRCLE", "10", "5", "20", "5", "40", "2",
            "0", "LWPOLYLINE", "70", "1", "10", "0", "20", "0", "10", "2", "20", "0", "10", "2", "20", "2",
            "0", "ENDSEC", "0", "EOF",
        ).joinToString("\n").toByteArray()
        val result = GeometryPreviewParser.parse("drawing.dxf", dxf)
        assertTrue(result.vertices.isNotEmpty())
        assertTrue(result.edges.size > 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedGeometryInput() {
        GeometryPreviewParser.parse("huge.obj", ByteArray(GeometryPreviewParser.MAX_INPUT_BYTES + 1))
    }
}
