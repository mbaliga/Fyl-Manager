package io.github.mbaliga.fylz.preview

import io.github.mbaliga.fylz.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalPreviewTest {
    @Test
    fun registryRoutesCommonEngineeringFormats() {
        val obj = FileFormatRegistry.describe("part.obj", "application/octet-stream", EntryKind.OTHER)
        assertEquals(PreviewFamily.MODEL_3D, obj.family)
        assertEquals("mesh-wireframe", obj.rendererId)
        assertEquals(PreviewDepth.RENDERED, obj.depth)

        val dxf = FileFormatRegistry.describe("plan.dxf", "image/vnd.dxf", EntryKind.OTHER)
        assertEquals(PreviewFamily.CAD_2D, dxf.family)
        assertEquals("dxf", dxf.rendererId)

        val dwg = FileFormatRegistry.describe("plan.dwg", "application/acad", EntryKind.OTHER)
        assertEquals(PreviewFamily.CAD_2D, dwg.family)
        assertEquals(PreviewDepth.INSPECTED, dwg.depth)
        assertTrue(dwg.notes.orEmpty().contains("proprietary", ignoreCase = true))

        val step = FileFormatRegistry.describe("assembly.step", "model/step", EntryKind.OTHER)
        assertEquals(PreviewFamily.MODEL_3D, step.family)
        assertEquals(PreviewDepth.STRUCTURED, step.depth)

        val unknown = FileFormatRegistry.describe("payload.zzunknown", "application/octet-stream", EntryKind.OTHER)
        assertEquals(PreviewFamily.BINARY, unknown.family)
        assertEquals(PreviewDepth.INSPECTED, unknown.depth)
    }

    @Test
    fun parsesObjFacesAndNegativeIndexes() {
        val source = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f -4 -3 -2 -1
        """.trimIndent().toByteArray()
        val preview = GeometryPreviewParser.parse("quad.obj", source)
        assertEquals(4, preview.vertices.size)
        assertEquals(4, preview.edges.size)
        assertEquals(1, preview.sourcePrimitiveCount)
        assertFalse(preview.truncated)
    }

    @Test
    fun parsesAsciiStlTriangle() {
        val source = """
            solid triangle
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                  vertex 0 1 0
                endloop
              endfacet
            endsolid
        """.trimIndent().toByteArray()
        val preview = GeometryPreviewParser.parse("triangle.stl", source)
        assertEquals(3, preview.vertices.size)
        assertEquals(3, preview.edges.size)
        assertEquals(1, preview.sourcePrimitiveCount)
    }

    @Test
    fun parsesAsciiPly() {
        val source = """
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
        val preview = GeometryPreviewParser.parse("triangle.ply", source)
        assertEquals(3, preview.vertices.size)
        assertEquals(3, preview.edges.size)
    }

    @Test
    fun parsesOff() {
        val source = """
            OFF
            4 1 0
            0 0 0
            1 0 0
            1 1 0
            0 1 0
            4 0 1 2 3
        """.trimIndent().toByteArray()
        val preview = GeometryPreviewParser.parse("quad.off", source)
        assertEquals(4, preview.vertices.size)
        assertEquals(4, preview.edges.size)
    }

    @Test
    fun parsesBasicDxfEntities() {
        val source = listOf(
            "0", "SECTION", "2", "ENTITIES",
            "0", "LINE", "10", "0", "20", "0", "11", "10", "21", "10",
            "0", "CIRCLE", "10", "5", "20", "5", "40", "2",
            "0", "ENDSEC", "0", "EOF",
        ).joinToString("\n").toByteArray()
        val preview = GeometryPreviewParser.parse("drawing.dxf", source)
        assertTrue(preview.vertices.size >= 10)
        assertTrue(preview.edges.size >= 9)
        assertTrue(preview.sourcePrimitiveCount >= 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedGeometryInput() {
        GeometryPreviewParser.parse("huge.obj", ByteArray(GeometryPreviewParser.MAX_INPUT_BYTES + 1))
    }
}
