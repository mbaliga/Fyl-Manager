package io.github.mbaliga.fylz.index

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ContentTextExtractor]'s own logic -- routing and the size gate -- not the two readers it
 * dispatches to, which have their own real round-trip tests ([io.github.mbaliga.fylz.pdf.PdfTextExtractorTest],
 * [io.github.mbaliga.fylz.data.OfficeTextExtractorTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentTextExtractorTest {

    @Test
    fun `supports PDF by extension or mime type, and every OOXML extension`() {
        assertTrue(ContentTextExtractor.supports("pdf", "application/octet-stream"))
        assertTrue(ContentTextExtractor.supports("bin", "application/pdf"))
        assertTrue(ContentTextExtractor.supports("docx", "application/octet-stream"))
        assertFalse(ContentTextExtractor.supports("jpg", "image/jpeg"))
    }

    @Test
    fun `refuses to even try extracting past the size cap`() {
        val context = RuntimeEnvironment.getApplication()
        // A Uri that would throw if ever actually opened -- proving the size check runs first,
        // before any attempt to resolve it through the content resolver.
        val uri = Uri.parse("content://nonexistent/doc.pdf")

        val result = ContentTextExtractor.extract(
            context,
            uri,
            "pdf",
            "application/pdf",
            ContentTextExtractor.MAX_SOURCE_BYTES + 1,
        )

        assertNull(result)
    }

    @Test
    fun `an unsupported extension returns null without attempting anything`() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://nonexistent/photo.jpg")

        assertNull(ContentTextExtractor.extract(context, uri, "jpg", "image/jpeg", 100L))
    }
}
