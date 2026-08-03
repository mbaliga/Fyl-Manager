package io.github.mbaliga.fylz.pdf

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfPagePlanPolicyTest {
    @Test
    fun acceptsBoundedPagePlan() {
        val pages = listOf(
            PdfPageReference(Uri.parse("content://example/one"), 0),
            PdfPageReference(Uri.parse("content://example/one"), 1, 1),
            PdfPageReference(Uri.parse("content://example/two"), 0, -1),
        )
        assertNull(PdfPagePlanPolicy.validate(pages))
    }

    @Test
    fun rejectsEmptyPlan() {
        assertEquals("Choose at least one page.", PdfPagePlanPolicy.validate(emptyList()))
    }

    @Test
    fun rejectsExcessPages() {
        val pages = List(PdfPagePlanPolicy.MAX_PAGES_PER_EXPORT + 1) {
            PdfPageReference(Uri.parse("content://example/document"), it)
        }
        assertEquals(
            "The export exceeds the ${PdfPagePlanPolicy.MAX_PAGES_PER_EXPORT}-page safety limit.",
            PdfPagePlanPolicy.validate(pages),
        )
    }

    @Test
    fun rejectsTooManyInputDocuments() {
        val pages = List(PdfPagePlanPolicy.MAX_INPUT_DOCUMENTS + 1) {
            PdfPageReference(Uri.parse("content://example/$it"), 0)
        }
        assertEquals("The export contains too many source documents.", PdfPagePlanPolicy.validate(pages))
    }
}
