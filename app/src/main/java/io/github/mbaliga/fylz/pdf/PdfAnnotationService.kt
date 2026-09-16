package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One freehand stroke, in the same on-screen canvas space [io.github.mbaliga.fylz.data.AnnotationStroke]
 * (image annotation) already uses -- [PdfAnnotationService] maps it into the target page's own
 * point space itself, since that mapping depends on which page and the box it was drawn in. */
data class PdfInkStroke(val points: List<Offset>, val colorArgb: Int, val strokeWidthPx: Float)

/**
 * Burns freehand ink directly into a PDF page's own content stream -- real vector drawing
 * operators (`moveTo`/`lineTo`/`stroke`), appended after the page's existing content rather than
 * replacing it, so that page's original text/vectors/searchability survive untouched and every
 * other page in the document is not even opened for writing.
 *
 * This is a deliberately different trade-off from [PdfPageTools]/[PdfToolService]/
 * [SearchablePdfService], which all rasterize a full visual copy: pdfbox-android's port of Apache
 * PDFBox ships no `PDAnnotationInk` convenience class, so a real `/Annots` ink annotation with a
 * spec-correct appearance stream would have to be hand-built from raw COS structures -- fragile,
 * and not something this sandbox can visually verify in a real PDF viewer. Content-stream drawing
 * operators are the same PDFBox write API [PdfTextExtractorTest] already proves works correctly in
 * this exact library version.
 *
 * Always writes to a new [destinationUri], matching every other PDF operation in this app --
 * there is no in-place edit of an existing PDF's bytes anywhere in this codebase.
 */
object PdfAnnotationService {
    /** Single-page convenience over [annotatePages], for callers (and the existing test suite)
     * that only ever touch one page at a time. */
    suspend fun annotatePage(
        context: Context,
        sourceUri: Uri,
        pageIndex: Int,
        canvasSize: Size,
        strokes: List<PdfInkStroke>,
        destinationUri: Uri,
    ) = annotatePages(context, sourceUri, mapOf(pageIndex to strokes), mapOf(pageIndex to canvasSize), destinationUri)

    /**
     * Same operation, across every page the user actually drew on in one sitting -- one document
     * open and one [PDDocument.save], rather than the overlay calling [annotatePage] once per
     * page and re-opening (and re-encoding) the whole file each time. [canvasSizeByPage] carries
     * the on-screen box each page was measured at, since pages in the same PDF can have different
     * aspect ratios and the fit-to-canvas box differs per page as a result.
     */
    suspend fun annotatePages(
        context: Context,
        sourceUri: Uri,
        strokesByPage: Map<Int, List<PdfInkStroke>>,
        canvasSizeByPage: Map<Int, Size>,
        destinationUri: Uri,
    ) = withContext(Dispatchers.IO) {
        val pages = strokesByPage.filterValues { it.isNotEmpty() }
        require(pages.isNotEmpty()) { "At least one stroke is required." }
        require(pages.keys.all { canvasSizeByPage[it].let { size -> size != null && size.width > 0f && size.height > 0f } }) {
            "Every annotated page must have been measured."
        }
        val input = context.contentResolver.openInputStream(sourceUri) ?: error("The source PDF is unavailable.")
        val document = input.use { PDDocument.load(it) }
        try {
            require(!document.isEncrypted) { "This PDF is encrypted and cannot be annotated." }
            pages.forEach { (pageIndex, strokes) ->
                require(pageIndex in 0 until document.numberOfPages) { "PDF page index is out of range." }
                val canvasSize = canvasSizeByPage.getValue(pageIndex)
                val page = document.getPage(pageIndex)
                val mediaBox = page.mediaBox
                val scaleX = mediaBox.width / canvasSize.width
                val scaleY = mediaBox.height / canvasSize.height
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    strokes.forEach { stroke -> drawStroke(stream, stroke, mediaBox, scaleX, scaleY) }
                }
            }
            val output = context.contentResolver.openOutputStream(destinationUri, "w")
                ?: error("The destination is not writable.")
            output.use { document.save(it) }
        } finally {
            document.close()
        }
    }

    private fun drawStroke(
        stream: PDPageContentStream,
        stroke: PdfInkStroke,
        mediaBox: com.tom_roush.pdfbox.pdmodel.common.PDRectangle,
        scaleX: Float,
        scaleY: Float,
    ) {
        if (stroke.points.size < 2) return
        // The (Int, Int, Int) 0..255 overload is deprecated in this PDFBox version in favour of
        // this 0..1 float one; same RG operator either way.
        stream.setStrokingColor(
            Color.red(stroke.colorArgb) / 255f,
            Color.green(stroke.colorArgb) / 255f,
            Color.blue(stroke.colorArgb) / 255f,
        )
        stream.setLineWidth((stroke.strokeWidthPx * scaleX).coerceAtLeast(0.1f))
        stream.setLineCapStyle(ROUND_CAP_STYLE)
        stream.setLineJoinStyle(ROUND_JOIN_STYLE)
        stroke.points.forEachIndexed { index, point ->
            // PDF page space grows upward from the page's own lower-left corner; the canvas the
            // user drew on grows downward from its top-left -- every y needs both the scale and
            // the flip, not just the scale x gets.
            val x = mediaBox.lowerLeftX + point.x * scaleX
            val y = mediaBox.lowerLeftY + mediaBox.height - point.y * scaleY
            if (index == 0) stream.moveTo(x, y) else stream.lineTo(x, y)
        }
        stream.stroke()
    }

    /** PDF spec line cap/join style 1 -- round, matching the image annotation path's own
     * StrokeCap.Round/StrokeJoin.Round so ink looks the same regardless of which file it lands on. */
    private const val ROUND_CAP_STYLE = 1
    private const val ROUND_JOIN_STYLE = 1
}
