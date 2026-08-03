package io.github.mbaliga.fylz.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class PdfPageSelection(
    val sourceUri: Uri,
    val pageIndex: Int,
    val rotationDegrees: Int = 0,
) {
    init {
        require(pageIndex >= 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
    }
}

data class PdfSourceSummary(
    val uri: Uri,
    val pageCount: Int,
    val pages: List<PdfPageSummary>,
)

data class PdfPageSummary(
    val pageIndex: Int,
    val widthPoints: Int,
    val heightPoints: Int,
)

data class PdfTransformationResult(
    val pageCount: Int,
    val outputBytes: Long,
    val outputSha256: String,
    val rasterized: Boolean = true,
)

/**
 * Bounded page-level PDF tools using Android's platform renderer and generator.
 *
 * Output pages are rasterized by design. This preserves visible page appearance across merge,
 * split, reorder and rotation without claiming object-level editing of arbitrary PDF internals.
 */
class PdfPageTools(private val context: Context) {
    suspend fun inspect(sourceUri: Uri): PdfSourceSummary = withContext(Dispatchers.IO) {
        openRenderer(sourceUri).use { renderer ->
            require(renderer.pageCount <= MAX_SOURCE_PAGES) { "PDF contains too many pages for one operation." }
            val pages = List(renderer.pageCount) { index ->
                renderer.openPage(index).use { page ->
                    PdfPageSummary(index, page.width, page.height)
                }
            }
            PdfSourceSummary(sourceUri, renderer.pageCount, pages)
        }
    }

    suspend fun exportPages(
        selections: List<PdfPageSelection>,
        destinationUri: Uri,
        maxRenderDimension: Int = DEFAULT_RENDER_DIMENSION,
        onProgress: (completedPages: Int, totalPages: Int) -> Unit = { _, _ -> },
    ): PdfTransformationResult = withContext(Dispatchers.IO) {
        require(selections.isNotEmpty()) { "Select at least one PDF page." }
        require(selections.size <= MAX_OUTPUT_PAGES) { "Too many pages selected for one PDF." }
        require(maxRenderDimension in MIN_RENDER_DIMENSION..MAX_RENDER_DIMENSION)

        val temp = java.io.File(context.cacheDir, "pdf-tools-${java.util.UUID.randomUUID()}.pdf")
        val renderers = linkedMapOf<String, PdfRenderer>()
        val document = PdfDocument()
        try {
            selections.forEachIndexed { outputIndex, selection ->
                coroutineContext.ensureActive()
                val renderer = renderers.getOrPut(selection.sourceUri.toString()) {
                    openRenderer(selection.sourceUri)
                }
                require(selection.pageIndex < renderer.pageCount) {
                    "Page ${selection.pageIndex + 1} does not exist in ${selection.sourceUri}."
                }
                renderer.openPage(selection.pageIndex).use { sourcePage ->
                    val rotated = selection.rotationDegrees == 90 || selection.rotationDegrees == 270
                    val sourceWidth = sourcePage.width.coerceAtLeast(1)
                    val sourceHeight = sourcePage.height.coerceAtLeast(1)
                    val longest = maxOf(sourceWidth, sourceHeight)
                    val scale = minOf(1f, maxRenderDimension.toFloat() / longest)
                    val bitmapWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
                    val bitmapHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
                    require(bitmapWidth.toLong() * bitmapHeight.toLong() <= MAX_BITMAP_PIXELS) {
                        "PDF page exceeds the rendering memory limit."
                    }
                    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pageWidth = if (rotated) bitmapHeight else bitmapWidth
                        val pageHeight = if (rotated) bitmapWidth else bitmapHeight
                        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, outputIndex + 1).create()
                        val outputPage = document.startPage(info)
                        try {
                            drawRotated(outputPage.canvas, bitmap, selection.rotationDegrees, pageWidth, pageHeight)
                        } finally {
                            document.finishPage(outputPage)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                onProgress(outputIndex + 1, selections.size)
            }

            temp.outputStream().buffered().use { output -> document.writeTo(output) }
            require(temp.length() in 1..MAX_OUTPUT_BYTES) { "Generated PDF exceeds the output safety limit." }
            copyVerified(temp, destinationUri)
            PdfTransformationResult(
                pageCount = selections.size,
                outputBytes = temp.length(),
                outputSha256 = sha256(temp),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            runCatching { document.close() }
            renderers.values.forEach { runCatching { it.close() } }
            temp.delete()
        }
    }

    private fun drawRotated(canvas: Canvas, bitmap: Bitmap, degrees: Int, pageWidth: Int, pageHeight: Int) {
        val matrix = Matrix()
        when (degrees) {
            0 -> Unit
            90 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(pageWidth.toFloat(), 0f)
            }
            180 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(pageWidth.toFloat(), pageHeight.toFloat())
            }
            270 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, pageHeight.toFloat())
            }
        }
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun openRenderer(uri: Uri): PdfRenderer {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("The provider did not expose a readable PDF descriptor.")
        return try {
            PdfRenderer(descriptor)
        } catch (failure: Throwable) {
            descriptor.close()
            throw failure
        }
    }

    private fun copyVerified(source: java.io.File, destinationUri: Uri) {
        val expected = sha256(source)
        context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        } ?: error("The destination provider did not return a writable stream.")
        val actual = context.contentResolver.openInputStream(destinationUri)?.use(::sha256)
            ?: error("The generated PDF could not be verified.")
        check(actual == expected) { "The destination provider did not preserve the generated PDF bytes." }
    }

    private fun sha256(file: java.io.File): String = file.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val DEFAULT_RENDER_DIMENSION = 2_400
        const val MIN_RENDER_DIMENSION = 512
        const val MAX_RENDER_DIMENSION = 4_096
        const val MAX_SOURCE_PAGES = 10_000
        const val MAX_OUTPUT_PAGES = 2_000
        const val MAX_OUTPUT_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_BITMAP_PIXELS = 24_000_000L
    }
}
