package io.github.mbaliga.fylz.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Creates a visually preserved PDF with an almost-transparent OCR text layer and optional UTF-8
 * sidecar text. Recognition is performed on-device by the bundled ML Kit model.
 */
class SearchablePdfService(private val context: Context) {
    enum class Script { LATIN, DEVANAGARI }

    data class Result(
        val pageCount: Int,
        val recognizedCharacters: Int,
        val outputBytes: Long,
        val sidecarBytes: Long,
    )

    suspend fun convert(
        sourcePdfUri: Uri,
        destinationPdfUri: Uri,
        sidecarTextUri: Uri? = null,
        script: Script = Script.LATIN,
        maxPages: Int = 500,
        maxRenderedPixelsPerPage: Long = 16_000_000L,
    ): Result = withContext(Dispatchers.IO) {
        require(maxPages in 1..2_000)
        require(maxRenderedPixelsPerPage in 1_000_000L..64_000_000L)
        val descriptor = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
            ?: error("Unable to open the source PDF.")
        val recognizer = when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
        val outputDocument = PdfDocument()
        val extracted = StringBuilder()
        var characters = 0
        var pages = 0
        try {
            descriptor.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    require(renderer.pageCount <= maxPages) {
                        "PDF contains ${renderer.pageCount} pages; the configured OCR limit is $maxPages."
                    }
                    for (pageIndex in 0 until renderer.pageCount) {
                        coroutineContext.ensureActive()
                        renderer.openPage(pageIndex).use { page ->
                            val target = boundedDimensions(page.width, page.height, maxRenderedPixelsPerPage)
                            val bitmap = Bitmap.createBitmap(target.first, target.second, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val recognition = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                                val pageInfo = PdfDocument.PageInfo.Builder(target.first, target.second, pageIndex + 1).create()
                                val outputPage = outputDocument.startPage(pageInfo)
                                drawPage(outputPage.canvas, bitmap, recognition)
                                outputDocument.finishPage(outputPage)
                                val text = recognition.text
                                if (text.isNotBlank()) {
                                    if (extracted.isNotEmpty()) extracted.append("\n\n")
                                    extracted.append("--- Page ${pageIndex + 1} ---\n").append(text)
                                    characters += text.length
                                }
                                pages += 1
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }

            val pdfBytes = ByteArrayOutputStream().use { memory ->
                outputDocument.writeTo(memory)
                memory.toByteArray()
            }
            context.contentResolver.openOutputStream(destinationPdfUri, "w")?.use { output ->
                output.write(pdfBytes)
                output.flush()
            } ?: error("Unable to write the OCR-enhanced PDF.")

            var sidecarBytes = 0L
            if (sidecarTextUri != null) {
                val bytes = extracted.toString().toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(sidecarTextUri, "w")?.use { output ->
                    output.write(bytes)
                    output.flush()
                } ?: error("Unable to write the OCR sidecar text.")
                sidecarBytes = bytes.size.toLong()
            }
            Result(pages, characters, pdfBytes.size.toLong(), sidecarBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            outputDocument.close()
            recognizer.close()
        }
    }

    private fun drawPage(canvas: Canvas, bitmap: Bitmap, recognition: Text) {
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 1 // retained by PDF writers while remaining visually negligible
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        recognition.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    val box = element.boundingBox ?: return@forEach
                    if (element.text.isBlank() || box.width() <= 0 || box.height() <= 0) return@forEach
                    paint.textSize = max(4f, box.height().toFloat() * 0.82f)
                    val measured = paint.measureText(element.text).coerceAtLeast(1f)
                    val scaleX = box.width() / measured
                    canvas.save()
                    canvas.translate(box.left.toFloat(), box.bottom.toFloat())
                    canvas.scale(scaleX.coerceIn(0.1f, 10f), 1f)
                    canvas.drawText(element.text, 0f, 0f, paint)
                    canvas.restore()
                }
            }
        }
    }

    private fun boundedDimensions(width: Int, height: Int, maxPixels: Long): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return width to height
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { failure -> if (continuation.isActive) continuation.resumeWithException(failure) }
        addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
    }
}
