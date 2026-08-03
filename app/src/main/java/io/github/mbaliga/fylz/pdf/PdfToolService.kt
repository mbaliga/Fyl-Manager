package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** A page reference used by merge, split, rotate and reorder operations. */
data class PdfPageRef(
    val sourceUri: Uri,
    val pageIndex: Int,
    val rotationDegrees: Int = 0,
) {
    init {
        require(pageIndex >= 0)
        require(rotationDegrees % 90 == 0)
    }
}

data class PdfInspection(
    val pageCount: Int,
    val pages: List<PdfPageInfo>,
)

data class PdfPageInfo(val index: Int, val widthPoints: Int, val heightPoints: Int)

data class PdfExportResult(
    val pageCount: Int,
    val outputBytes: Long,
    val rasterized: Boolean = true,
    val searchableTextAdded: Boolean = false,
)

class PdfToolService(private val context: Context) {
    suspend fun inspect(uri: Uri): PdfInspection = withContext(Dispatchers.IO) {
        openRenderer(uri) { renderer ->
            require(renderer.pageCount <= MAX_PAGES) { "PDF exceeds the $MAX_PAGES-page safety limit." }
            PdfInspection(
                pageCount = renderer.pageCount,
                pages = List(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page -> PdfPageInfo(index, page.width, page.height) }
                },
            )
        }
    }

    suspend fun exportPages(
        pages: List<PdfPageRef>,
        outputUri: Uri,
        searchableOcr: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): PdfExportResult = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty()) { "At least one page is required." }
        require(pages.size <= MAX_PAGES) { "Output exceeds the $MAX_PAGES-page safety limit." }
        val temp = File(context.cacheDir, "pdf-tools-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        val recognizer = if (searchableOcr) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
        try {
            pages.forEachIndexed { outputIndex, reference ->
                coroutineContext.ensureActive()
                val rendered = renderPage(reference.sourceUri, reference.pageIndex, reference.rotationDegrees)
                rendered.useBitmap { bitmap ->
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, outputIndex + 1).create()
                    val outputPage = document.startPage(pageInfo)
                    try {
                        outputPage.canvas.drawColor(Color.WHITE)
                        outputPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        if (recognizer != null) {
                            val recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            drawInvisibleSearchText(outputPage.canvas, recognized.textBlocks.flatMap { it.lines }, bitmap.width, bitmap.height)
                        }
                    } finally {
                        document.finishPage(outputPage)
                    }
                }
                onProgress(outputIndex + 1, pages.size)
            }
            FileOutputStream(temp).use { output -> document.writeTo(output); output.fd.sync() }
            val output = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("The selected provider did not return a writable stream.")
            output.use { target -> temp.inputStream().use { source -> source.copyTo(target); target.flush() } }
            PdfExportResult(pages.size, temp.length(), searchableTextAdded = searchableOcr)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            recognizer?.close()
            document.close()
            temp.delete()
        }
    }

    suspend fun merge(
        sources: List<Uri>,
        outputUri: Uri,
        searchableOcr: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): PdfExportResult {
        require(sources.isNotEmpty())
        val pages = mutableListOf<PdfPageRef>()
        for (source in sources) {
            val inspection = inspect(source)
            repeat(inspection.pageCount) { pages += PdfPageRef(source, it) }
            require(pages.size <= MAX_PAGES) { "Merged output exceeds the $MAX_PAGES-page safety limit." }
        }
        return exportPages(pages, outputUri, searchableOcr, onProgress)
    }

    suspend fun split(
        source: Uri,
        destinationUris: List<Uri>,
        searchableOcr: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PdfExportResult> {
        val inspection = inspect(source)
        require(destinationUris.size == inspection.pageCount) { "One destination is required for each source page." }
        return destinationUris.mapIndexed { index, target ->
            coroutineContext.ensureActive()
            exportPages(listOf(PdfPageRef(source, index)), target, searchableOcr)
                .also { onProgress(index + 1, destinationUris.size) }
        }
    }

    private fun renderPage(source: Uri, index: Int, rotationDegrees: Int): RenderedBitmap = openRenderer(source) { renderer ->
        require(index in 0 until renderer.pageCount) { "PDF page index is out of range." }
        renderer.openPage(index).use { page ->
            val scale = min(1f, MAX_RENDER_EDGE.toFloat() / max(page.width, page.height).coerceAtLeast(1))
            val width = max(1, (page.width * scale).toInt())
            val height = max(1, (page.height * scale).toInt())
            val sourceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            sourceBitmap.eraseColor(Color.WHITE)
            page.render(sourceBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val normalized = ((rotationDegrees % 360) + 360) % 360
            if (normalized == 0) return@use RenderedBitmap(sourceBitmap)
            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            val rotated = Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
            sourceBitmap.recycle()
            RenderedBitmap(rotated)
        }
    }

    private fun drawInvisibleSearchText(
        canvas: android.graphics.Canvas,
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        width: Int,
        height: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(1, 0, 0, 0)
        }
        lines.take(MAX_OCR_LINES_PER_PAGE).forEach { line ->
            val box = line.boundingBox ?: return@forEach
            if (box.width() <= 0 || box.height() <= 0 || line.text.isBlank()) return@forEach
            paint.textSize = box.height().toFloat().coerceAtLeast(4f)
            val measured = paint.measureText(line.text).coerceAtLeast(1f)
            val scaleX = box.width() / measured
            canvas.save()
            canvas.clipRect(RectF(0f, 0f, width.toFloat(), height.toFloat()))
            canvas.scale(scaleX, 1f, box.left.toFloat(), box.bottom.toFloat())
            canvas.drawText(line.text.take(MAX_OCR_LINE_CHARS), box.left.toFloat(), box.bottom.toFloat(), paint)
            canvas.restore()
        }
    }

    private inline fun <T> openRenderer(uri: Uri, block: (PdfRenderer) -> T): T {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("The selected provider did not return a seekable PDF descriptor.")
        return descriptor.use { PdfRenderer(it).use(block) }
    }

    private data class RenderedBitmap(val bitmap: Bitmap) {
        inline fun <T> useBitmap(block: (Bitmap) -> T): T = try { block(bitmap) } finally { bitmap.recycle() }
    }

    companion object {
        const val MAX_PAGES = 2_000
        const val MAX_RENDER_EDGE = 2_048
        const val MAX_OCR_LINES_PER_PAGE = 10_000
        const val MAX_OCR_LINE_CHARS = 2_000
    }
}
