package io.github.mbaliga.fylz.index

import android.content.Context
import android.net.Uri
import io.github.mbaliga.fylz.data.OfficeTextExtractor
import io.github.mbaliga.fylz.pdf.PdfTextExtractor

/**
 * The single place [LocalIndexScheduler] asks "does this file have text worth sampling, and if so
 * what is it" -- so [RuleField.TEXT_CONTENT] has something real to compare against instead of
 * always failing closed (see [SmartCollectionEngine]'s own note on that). Kept out of the
 * scheduler itself so the size/extension gating and the two format-specific readers it dispatches
 * to ([PdfTextExtractor], [OfficeTextExtractor]) are each independently testable.
 */
object ContentTextExtractor {
    /** Skip extraction above this size outright -- a background walk over up to 500,000 files has
     * no business opening a multi-hundred-megabyte document just to sample its text. */
    const val MAX_SOURCE_BYTES = 25L * 1024L * 1024L

    fun supports(extension: String, mimeType: String): Boolean =
        isPdf(extension, mimeType) || OfficeTextExtractor.supports(extension)

    /** Null on anything from "unsupported format" or "too large" to "the document failed to
     * parse" -- a corrupt or password-protected file must not stop indexing the other files in
     * the same scope. */
    fun extract(context: Context, uri: Uri, extension: String, mimeType: String, sizeBytes: Long?): String? {
        if (sizeBytes != null && sizeBytes > MAX_SOURCE_BYTES) return null
        return runCatching {
            when {
                isPdf(extension, mimeType) -> PdfTextExtractor.extract(context, uri)
                OfficeTextExtractor.supports(extension) ->
                    OfficeTextExtractor.extract({ context.contentResolver.openInputStream(uri) ?: error("unreadable") }, extension)
                else -> null
            }
        }.getOrNull()
    }

    private fun isPdf(extension: String, mimeType: String): Boolean =
        extension.equals("pdf", ignoreCase = true) || mimeType == "application/pdf"
}
