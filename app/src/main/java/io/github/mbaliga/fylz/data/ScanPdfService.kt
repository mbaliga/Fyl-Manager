package io.github.mbaliga.fylz.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Offline multipage image-to-PDF exporter used by the scan review journey.
 *
 * Pages are decoded with bounds and sampling, never at unrestricted camera resolution. No page
 * pixels leave the device. OCR is intentionally separate so a PDF export never depends on a model.
 */
class ScanPdfService(private val context: Context) {
    data class Page(
        val imageUri: Uri,
        val rotationDegrees: Int = 0,
    )

    data class Options(
        val pageWidthPoints: Int = 595,
        val pageHeightPoints: Int = 842,
        val marginPoints: Int = 24,
        val maxDecodeDimension: Int = 3_000,
        val jpegQuality: Int = 88,
    )

    suspend fun export(
        pages: List<Page>,
        destinationUri: Uri,
        options: Options = Options(),
    ) = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty()) { "Add at least one page." }
        require(options.pageWidthPoints > 0 && options.pageHeightPoints > 0)
        require(options.marginPoints >= 0)
        require(options.maxDecodeDimension in 512..8_192)
        require(options.jpegQuality in 40..100)

        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                val bitmap = decodeBounded(page.imageUri, options.maxDecodeDimension)
                val oriented = rotate(bitmap, page.rotationDegrees)
                if (oriented !== bitmap) bitmap.recycle()
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        options.pageWidthPoints,
                        options.pageHeightPoints,
                        index + 1,
                    ).create()
                    val pdfPage = document.startPage(pageInfo)
                    drawPage(pdfPage.canvas, oriented, options)
                    document.finishPage(pdfPage)
                } finally {
                    oriented.recycle()
                }
            }

            val output = context.contentResolver.openOutputStream(destinationUri, "w")
                ?: error("Unable to write the PDF destination.")
            output.use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun decodeBounded(uri: Uri, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: error("Unable to read a scan page.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported scan image." }

        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Unable to decode a scan page.")
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        require(normalized == 90 || normalized == 180 || normalized == 270) {
            "Page rotation must be 0, 90, 180, or 270 degrees."
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(normalized.toFloat()) },
            true,
        )
    }

    private fun drawPage(canvas: Canvas, bitmap: Bitmap, options: Options) {
        canvas.drawColor(Color.WHITE)
        val contentWidth = options.pageWidthPoints - options.marginPoints * 2
        val contentHeight = options.pageHeightPoints - options.marginPoints * 2
        require(contentWidth > 0 && contentHeight > 0) { "PDF margins leave no page content area." }

        val scale = min(
            contentWidth.toFloat() / bitmap.width.toFloat(),
            contentHeight.toFloat() / bitmap.height.toFloat(),
        )
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (options.pageWidthPoints - drawWidth) / 2f
        val top = (options.pageHeightPoints - drawHeight) / 2f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}
