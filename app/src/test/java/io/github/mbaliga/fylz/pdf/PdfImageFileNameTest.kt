package io.github.mbaliga.fylz.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure string/int logic, no Android type anywhere -- see [pdfImageFileName]'s own KDoc. */
class PdfImageFileNameTest {

    @Test
    fun `pads to the page count's own digit width, floor of 2`() {
        assertEquals("invoice-01.png", pdfImageFileName("invoice", 0, 5, "png"))
        assertEquals("invoice-05.png", pdfImageFileName("invoice", 4, 5, "png"))
    }

    @Test
    fun `a single-page document still gets a 2-digit index`() {
        assertEquals("invoice-01.png", pdfImageFileName("invoice", 0, 1, "png"))
    }

    @Test
    fun `a three-digit page count sorts a 10-page and a 100-page name correctly`() {
        assertEquals("scan-010.jpg", pdfImageFileName("scan", 9, 120, "jpg"))
        assertEquals("scan-100.jpg", pdfImageFileName("scan", 99, 120, "jpg"))
    }
}
