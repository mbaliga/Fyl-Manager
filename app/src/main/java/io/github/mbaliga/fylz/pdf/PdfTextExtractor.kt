package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * A short text sample pulled from a PDF's own embedded text objects, for
 * [io.github.mbaliga.fylz.index.RuleField.TEXT_CONTENT] search matching -- distinct from (and much
 * faster than) [SearchablePdfService]'s on-device OCR, which reads pixels off a rendered page and
 * is deliberately opt-in/slow. This reads whatever text layer the PDF already has; a scanned,
 * image-only PDF has none, and comes back with an empty sample rather than a guess -- the same
 * "fails closed" choice [io.github.mbaliga.fylz.index.SmartCollectionEngine] already makes for a
 * content rule with nothing to check.
 *
 * Capped to the first [MAX_PAGES] pages: a search sample has no reason to read every page of a
 * 2,000-page book during a background index walk, and this app's own PdfRenderer-based tools
 * already cap themselves the same way for the same reason.
 */
object PdfTextExtractor {
    private const val MAX_PAGES = 20
    private const val MAX_SAMPLE_CHARS = 4_000

    @Volatile
    private var resourcesLoaded = false

    fun extract(context: Context, uri: Uri): String? {
        if (!resourcesLoaded) {
            synchronized(this) {
                if (!resourcesLoaded) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    resourcesLoaded = true
                }
            }
        }
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            PDDocument.load(input).use { document ->
                if (document.isEncrypted) return null
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = document.numberOfPages.coerceAtMost(MAX_PAGES)
                }
                stripper.getText(document)
            }
        }.trim().take(MAX_SAMPLE_CHARS).ifBlank { null }
    }
}
