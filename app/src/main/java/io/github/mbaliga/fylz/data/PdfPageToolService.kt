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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Platform-native PDF page operations. Pages are rendered and re-authored, which safely supports
 * merge/split/reorder/rotation across providers but intentionally flattens interactive/vector data.
 */
class PdfPageToolService(private val context: Context) {
    data class PageRef(
        val sourceUri: Uri,
        val pageIndex: Int,
        val rotationQuarterTurns: Int = 0,
    ) {
        init {
            require(pageIndex >= 0)
            require(rotationQuarterTurns in -3..3)
        }
    }

    data class ExportResult(
        val pageCount: Int,
        val outputBytes: Long,
        val flattened: Boolean = true,
    )

    suspend fun inspectPageCounts(sourceUris: List<Uri>): Map<Uri, Int> = withContext(Dispatchers.IO) {
        sourceUris.distinct().associateWith { uri ->
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
            } ?: error("Unable to open a source PDF.")
        }
    }

    suspend fun merge(
        sourceUris: List<Uri>,
        destinationUri: Uri,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxPixelsPerPage: Long = DEFAULT_MAX_PIXELS,
    ): ExportResult {
        require(sourceUris.isNotEmpty())
        val counts = inspectPageCounts(sourceUris)
        val pages = sourceUris.flatMap { uri -> List(counts.getValue(uri)) { index -> PageRef(uri, index) } }
        return export(pages, destinationUri, maxPages, maxPixelsPerPage)
    }

    suspend fun split(
        sourceUri: Uri,
        ranges: List<IntRange>,
        destinations: List<Uri>,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxPixelsPerPage: Long = DEFAULT_MAX_PIXELS,
    ): List<ExportResult> {
        require(ranges.isNotEmpty() && ranges.size == destinations.size)
        val pageCount = inspectPageCounts(listOf(sourceUri)).getValue(sourceUri)
        return ranges.zip(destinations).map { (range, destination) ->
            require(range.first >= 0 && range.last < pageCount) { "Split range is outside the source PDF." }
            export(range.map { PageRef(sourceUri, it) }, destination, maxPages, maxPixelsPerPage)
        }
    }

    suspend fun export(
        pages: List<PageRef>,
        destinationUri: Uri,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxPixelsPerPage: Long = DEFAULT_MAX_PIXELS,
    ): ExportResult = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty())
        require(pages.size <= maxPages) { "PDF operation exceeds the $maxPages page limit." }
        require(maxPixelsPerPage in 1_000_000L..64_000_000L)
        val grouped = pages.groupBy(PageRef::sourceUri)
        val descriptors = mutableMapOf<Uri, android.os.ParcelFileDescriptor>()
        val renderers = mutableMapOf<Uri, PdfRenderer>()
        val output = PdfDocument()
        try {
            grouped.keys.forEach { uri ->
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Unable to open a source PDF.")
                descriptors[uri] = descriptor
                renderers[uri] = PdfRenderer(descriptor)
            }
            pages.forEachIndexed { outputIndex, ref ->
                coroutineContext.ensureActive()
                val renderer = renderers.getValue(ref.sourceUri)
                require(ref.pageIndex < renderer.pageCount) { "Page ${ref.pageIndex + 1} is unavailable." }
                renderer.openPage(ref.pageIndex).use { sourcePage ->
                    val original = boundedDimensions(sourcePage.width, sourcePage.height, maxPixelsPerPage)
                    val turns = ((ref.rotationQuarterTurns % 4) + 4) % 4
                    val outputWidth = if (turns % 2 == 0) original.first else original.second
                    val outputHeight = if (turns % 2 == 0) original.second else original.first
                    val bitmap = Bitmap.createBitmap(original.first, original.second, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val info = PdfDocument.PageInfo.Builder(outputWidth, outputHeight, outputIndex + 1).create()
                        val page = output.startPage(info)
                        drawRotated(page.canvas, bitmap, turns)
                        output.finishPage(page)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val bytes = ByteArrayOutputStream().use { memory ->
                output.writeTo(memory)
                memory.toByteArray()
            }
            context.contentResolver.openOutputStream(destinationUri, "w")?.use { destination ->
                destination.write(bytes)
                destination.flush()
            } ?: error("Unable to write the destination PDF.")
            ExportResult(pages.size, bytes.size.toLong())
        } finally {
            output.close()
            renderers.values.forEach { runCatching { it.close() } }
            descriptors.values.forEach { runCatching { it.close() } }
        }
    }

    private fun drawRotated(canvas: Canvas, bitmap: Bitmap, turns: Int) {
        val matrix = Matrix()
        when (turns) {
            0 -> Unit
            1 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(bitmap.height.toFloat(), 0f)
            }
            2 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(bitmap.width.toFloat(), bitmap.height.toFloat())
            }
            3 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, bitmap.width.toFloat())
            }
        }
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun boundedDimensions(width: Int, height: Int, maxPixels: Long): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return width to height
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    companion object {
        const val DEFAULT_MAX_PAGES = 1_000
        const val DEFAULT_MAX_PIXELS = 20_000_000L
    }
}
