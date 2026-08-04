package io.github.mbaliga.fylz.pdf

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Uri.parse() below needs a real android.net.Uri implementation, which the plain-JVM unit-test
// stub jar cannot provide (every stub method throws "not mocked"). Robolectric supplies real
// framework shadows; sdk is pinned to this module's targetSdk rather than left to Robolectric's
// default so this doesn't silently start testing against a different API level on a bump.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
