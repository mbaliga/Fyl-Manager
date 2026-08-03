package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class OcrScript {
    LATIN,
    DEVANAGARI,
}

data class SearchablePdfOptions(
    val script: OcrScript = OcrScript.LATIN,
    val renderDpi: Int = 144,
    val maximumPages: Int = 500,
    val maximumPixelsPerPage: Long = 24_000_000L,
) {
    init {
        require(renderDpi in 96..300)
        require(maximumPages in 1..2_000)
        require(maximumPixelsPerPage in 1_000_000L..100_000_000L)
    }
}

data class SearchablePdfResult(
    val pagesWritten: Int,
    val pagesWithText: Int,
    val recognizedCharacters: Long,
    val warning: String = "The searchable copy is rasterized. Forms, links, annotations, layers and original metadata may not be preserved.",
)

/**
 * Creates a visual copy of a PDF and adds a transparent best-effort text layer from on-device OCR.
 * The output is intentionally written to a distinct destination URI and never modifies the source.
 */
class SearchablePdfService(private val context: Context) {
    suspend fun export(
        sourceUri: Uri,
        destinationUri: Uri,
        options: SearchablePdfOptions = SearchablePdfOptions(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): SearchablePdfResult = withContext(Dispatchers.IO) {
        require(sourceUri != destinationUri) { "Searchable PDF output must use a different destination." }
        val descriptor = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?: error("The source PDF is unavailable.")
        val output = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: error("The destination is not writable.")
        val renderer = PdfRenderer(descriptor)
        require(renderer.pageCount in 1..options.maximumPages) {
            "The PDF has ${renderer.pageCount} pages; the configured OCR limit is ${options.maximumPages}."
        }
        val document = PdfDocument()
        val recognizer = recognizer(options.script)
        var pagesWithText = 0
        var recognizedCharacters = 0L
        try {
            repeat(renderer.pageCount) { pageIndex ->
                coroutineContext.ensureActive()
                renderer.openPage(pageIndex).use { sourcePage ->
                    val dimensions = renderDimensions(sourcePage.width, sourcePage.height, options)
                    val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val recognized = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            sourcePage.width.coerceAtLeast(1),
                            sourcePage.height.coerceAtLeast(1),
                            pageIndex + 1,
                        ).create()
                        val target = document.startPage(pageInfo)
                        try {
                            val pageRect = RectF(0f, 0f, pageInfo.pageWidth.toFloat(), pageInfo.pageHeight.toFloat())
                            target.canvas.drawBitmap(bitmap, null, pageRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                            val stats = drawTextLayer(
                                canvas = target.canvas,
                                text = recognized,
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height,
                                pageWidth = pageInfo.pageWidth,
                                pageHeight = pageInfo.pageHeight,
                            )
                            if (stats.first > 0) pagesWithText += 1
                            recognizedCharacters += stats.second
                        } finally {
                            document.finishPage(target)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                onProgress(pageIndex + 1, renderer.pageCount)
            }
            output.use(document::writeTo)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            runCatching(recognizer::close)
            runCatching(document::close)
            runCatching(renderer::close)
            runCatching(descriptor::close)
            runCatching(output::close)
        }
        SearchablePdfResult(renderer.pageCount, pagesWithText, recognizedCharacters)
    }

    private fun recognizer(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.DEVANAGARI -> TextRecognition.getClient(
            DevanagariTextRecognizerOptions.Builder().build(),
        )
    }

    private fun drawTextLayer(
        canvas: android.graphics.Canvas,
        text: Text,
        imageWidth: Int,
        imageHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
    ): Pair<Int, Long> {
        if (imageWidth <= 0 || imageHeight <= 0) return 0 to 0L
        val scaleX = pageWidth.toFloat() / imageWidth
        val scaleY = pageHeight.toFloat() / imageHeight
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            alpha = 0
        }
        var elements = 0
        var characters = 0L
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    val box = element.boundingBox ?: return@forEach
                    val value = element.text.takeIf(String::isNotBlank) ?: return@forEach
                    paint.textSize = max(1f, box.height() * scaleY * 0.82f)
                    val desiredWidth = max(1f, box.width() * scaleX)
                    val measured = max(1f, paint.measureText(value))
                    canvas.save()
                    canvas.translate(box.left * scaleX, box.bottom * scaleY)
                    canvas.scale((desiredWidth / measured).coerceIn(0.1f, 10f), 1f)
                    canvas.drawText(value, 0f, 0f, paint)
                    canvas.restore()
                    elements += 1
                    characters += value.length
                }
            }
        }
        return elements to characters
    }

    private fun renderDimensions(widthPoints: Int, heightPoints: Int, options: SearchablePdfOptions): Pair<Int, Int> {
        val scale = options.renderDpi / 72.0
        var width = (widthPoints * scale).roundToInt().coerceAtLeast(1)
        var height = (heightPoints * scale).roundToInt().coerceAtLeast(1)
        val pixels = width.toLong() * height.toLong()
        if (pixels > options.maximumPixelsPerPage) {
            val reduction = kotlin.math.sqrt(options.maximumPixelsPerPage.toDouble() / pixels)
            width = (width * reduction).roundToInt().coerceAtLeast(1)
            height = (height * reduction).roundToInt().coerceAtLeast(1)
        }
        return width to height
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
