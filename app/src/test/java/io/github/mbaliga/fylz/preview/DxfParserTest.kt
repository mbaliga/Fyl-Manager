package io.github.mbaliga.fylz.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfParserTest {
    @Test
    fun parsesCommonAsciiEntities() {
        val drawing = """
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            10
            0
            20
            0
            11
            10
            21
            5
            0
            CIRCLE
            10
            4
            20
            4
            40
            2
            0
            ARC
            10
            8
            20
            8
            40
            3
            50
            0
            51
            90
            0
            LWPOLYLINE
            70
            1
            10
            0
            20
            0
            10
            1
            20
            0
            10
            1
            20
            1
            0
            ENDSEC
            0
            EOF
        """.trimIndent().toByteArray()
        val result = DxfParser.parse(drawing)
        assertEquals(4, result.segments.size)
        assertEquals(1, result.circles.size)
        assertEquals(1, result.arcs.size)
        assertTrue(!result.truncated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBinaryDxfForVectorRenderer() {
        DxfParser.parse("AutoCAD Binary DXF\r\n\u001a\u0000".toByteArray(Charsets.US_ASCII))
    }
}
