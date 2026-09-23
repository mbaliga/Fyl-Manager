package io.github.mbaliga.fylz.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class PdfPageInfo(
    val index: Int,
    val widthPoints: Int,
    val heightPoints: Int,
)

data class PdfDocumentInfo(
    val pageCount: Int,
    val pages: List<PdfPageInfo>,
    val encryptedOrUnreadable: Boolean = false,
)

data class PdfPageReference(
    val sourceUri: Uri,
    val pageIndex: Int,
    val rotationQuarterTurns: Int = 0,
) {
    init {
        require(pageIndex >= 0)
        require(rotationQuarterTurns in -3..3)
    }
}

data class PdfExportOptions(
    val renderDpi: Int = 144,
    val maximumOutputPixelsPerPage: Long = 24_000_000L,
    val backgroundColor: Int = Color.WHITE,
) {
    init {
        require(renderDpi in 72..300)
        require(maximumOutputPixelsPerPage in 1_000_000L..100_000_000L)
    }
}

data class PdfExportResult(
    val pagesWritten: Int,
    val outputBytes: Long?,
    val rasterized: Boolean = true,
    val searchableTextAdded: Boolean = false,
    val warning: String = RASTER_WARNING,
) {
    companion object {
        const val RASTER_WARNING = "Pages were rendered into a new PDF. Searchable text, forms, links, layers, annotations and original metadata may not be preserved."
    }
}

object PdfPagePlanPolicy {
    const val MAX_PAGES_PER_EXPORT = 2_000
    const val MAX_INPUT_DOCUMENTS = 100

    fun validate(references: List<PdfPageReference>): String? = when {
        references.isEmpty() -> "Choose at least one page."
        references.size > MAX_PAGES_PER_EXPORT -> "The export exceeds the $MAX_PAGES_PER_EXPORT-page safety limit."
        references.map { it.sourceUri.toString() }.distinct().size > MAX_INPUT_DOCUMENTS ->
            "The export contains too many source documents."
        else -> null
    }
}

/**
 * PDF page tools using platform PdfRenderer and PdfDocument -- the app's one implementation of
 * merge/extract/split, DPI-aware (unlike the fixed 2048px-edge renders the now-deleted
 * `PdfToolService` used). P1.13 consolidated `PdfToolService`'s searchable-OCR
 * capability into this class rather than the other way around, since this one already had the
 * page-geometry-aware rendering [PdfExportOptions.renderDpi] depends on; `PdfToolService`'s own
 * simpler fixed-edge render and its `merge`/`exportPages` naming are gone, not kept alongside.
 *
 * Android does not expose object-level PDF rewriting. These operations therefore create a
 * rasterized visual copy and disclose the fidelity trade-off instead of silently claiming a
 * lossless merge or edit.
 *
 * [ocrEngineFactory] is the P0.13 seam for decision D1 (see [OcrEngine]) -- it defaults to
 * [MlKitOcrEngine], today's only implementation, and this class always requests
 * [OcrScript.LATIN], matching `PdfToolService`'s own prior behavior exactly.
 */
class PdfPageTools(
    private val context: Context,
    private val ocrEngineFactory: OcrEngineFactory = MlKitOcrEngine.Companion,
) {
    suspend fun inspect(uri: Uri, maxPageDetails: Int = 500): PdfDocumentInfo = withContext(Dispatchers.IO) {
        require(maxPageDetails in 1..PdfPagePlanPolicy.MAX_PAGES_PER_EXPORT)
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    PdfDocumentInfo(
                        pageCount = renderer.pageCount,
                        pages = List(minOf(renderer.pageCount, maxPageDetails)) { index ->
                            renderer.openPage(index).use { page ->
                                PdfPageInfo(index, page.width, page.height)
                            }
                        },
                    )
                }
            } ?: error("The provider did not expose a readable PDF descriptor.")
        }.getOrElse { PdfDocumentInfo(0, emptyList(), encryptedOrUnreadable = true) }
    }

    suspend fun allPages(uri: Uri): List<PdfPageReference> = withContext(Dispatchers.IO) {
        val info = inspect(uri, PdfPagePlanPolicy.MAX_PAGES_PER_EXPORT)
        require(!info.encryptedOrUnreadable) { "The PDF is encrypted or unreadable." }
        require(info.pageCount <= PdfPagePlanPolicy.MAX_PAGES_PER_EXPORT) { "The PDF has too many pages for one export." }
        List(info.pageCount) { PdfPageReference(uri, it) }
    }

    /** Every source's pages, in source order, into one output -- fails as soon as the running
     * total is known to exceed the page limit, without inspecting every remaining source first. */
    suspend fun merge(
        sources: List<Uri>,
        outputUri: Uri,
        searchableOcr: Boolean = false,
        options: PdfExportOptions = PdfExportOptions(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): PdfExportResult = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "Choose at least one PDF to merge." }
        val pages = mutableListOf<PdfPageReference>()
        for (source in sources) {
            pages += allPages(source)
            require(pages.size <= PdfPagePlanPolicy.MAX_PAGES_PER_EXPORT) {
                "The export exceeds the ${PdfPagePlanPolicy.MAX_PAGES_PER_EXPORT}-page safety limit."
            }
        }
        export(pages, outputUri, options, searchableOcr, onProgress)
    }

    suspend fun export(
        references: List<PdfPageReference>,
        destinationUri: Uri,
        options: PdfExportOptions = PdfExportOptions(),
        searchableOcr: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): PdfExportResult = withContext(Dispatchers.IO) {
        PdfPagePlanPolicy.validate(references)?.let { error(it) }
        val output = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: error("The destination is not writable.")
        val document = PdfDocument()
        val renderers = linkedMapOf<String, RendererHandle>()
        val recognizer = if (searchableOcr) ocrEngineFactory.create(OcrScript.LATIN) else null
        try {
            references.forEachIndexed { outputIndex, reference ->
                coroutineContext.ensureActive()
                val handle = renderers.getOrPut(reference.sourceUri.toString()) {
                    val descriptor = context.contentResolver.openFileDescriptor(reference.sourceUri, "r")
                        ?: error("A source PDF is unavailable.")
                    RendererHandle(descriptor, PdfRenderer(descriptor))
                }
                require(reference.pageIndex < handle.renderer.pageCount) {
                    "Page ${reference.pageIndex + 1} no longer exists in a source PDF."
                }
                handle.renderer.openPage(reference.pageIndex).use { sourcePage ->
                    val rotation = normalizedRotation(reference.rotationQuarterTurns)
                    val sourceWidthPoints = sourcePage.width.coerceAtLeast(1)
                    val sourceHeightPoints = sourcePage.height.coerceAtLeast(1)
                    val rotated = rotation % 2 != 0
                    val outputWidthPoints = if (rotated) sourceHeightPoints else sourceWidthPoints
                    val outputHeightPoints = if (rotated) sourceWidthPoints else sourceHeightPoints
                    val dimensions = renderDimensions(sourceWidthPoints, sourceHeightPoints, options)
                    val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(options.backgroundColor)
                        sourcePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        // Recognized BEFORE any rotation is applied: the recognizer sees the same
                        // pixels [drawBitmap] draws, so a text line's bounding box is expressed in
                        // that same bitmap-local coordinate space regardless of the page's own
                        // rotation -- see [drawInvisibleSearchText] for how it's placed back.
                        val recognized = recognizer?.recognize(bitmap)
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            outputWidthPoints,
                            outputHeightPoints,
                            outputIndex + 1,
                        ).create()
                        val targetPage = document.startPage(pageInfo)
                        try {
                            val matrix = pageDrawMatrix(rotation, bitmap.width, bitmap.height, outputWidthPoints, outputHeightPoints)
                            drawBitmap(targetPage.canvas, bitmap, matrix, options.backgroundColor)
                            recognized?.let {
                                drawInvisibleSearchText(
                                    targetPage.canvas,
                                    it.textBlocks.flatMap { block -> block.lines },
                                    matrix,
                                    bitmap.width,
                                    bitmap.height,
                                )
                            }
                        } finally {
                            document.finishPage(targetPage)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                onProgress(outputIndex + 1, references.size)
            }
            output.use { document.writeTo(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            recognizer?.close()
            document.close()
            renderers.values.forEach(RendererHandle::close)
        }
        PdfExportResult(
            pagesWritten = references.size,
            outputBytes = runCatching {
                context.contentResolver.openAssetFileDescriptor(destinationUri, "r")?.use { it.length.takeIf { length -> length >= 0L } }
            }.getOrNull(),
            searchableTextAdded = searchableOcr,
        )
    }

    suspend fun split(
        sourceUri: Uri,
        destinations: List<Uri>,
        searchableOcr: Boolean = false,
        options: PdfExportOptions = PdfExportOptions(),
    ): List<PdfExportResult> {
        val pages = allPages(sourceUri)
        require(destinations.size == pages.size) { "Provide one destination for each page." }
        return pages.indices.map { index -> export(listOf(pages[index]), destinations[index], options, searchableOcr) }
    }

    private fun renderDimensions(widthPoints: Int, heightPoints: Int, options: PdfExportOptions): Pair<Int, Int> {
        val scale = options.renderDpi / 72.0
        var width = (widthPoints * scale).roundToInt().coerceAtLeast(1)
        var height = (heightPoints * scale).roundToInt().coerceAtLeast(1)
        val pixels = width.toLong() * height.toLong()
        if (pixels > options.maximumOutputPixelsPerPage) {
            val reduction = kotlin.math.sqrt(options.maximumOutputPixelsPerPage.toDouble() / pixels.toDouble())
            width = (width * reduction).roundToInt().coerceAtLeast(1)
            height = (height * reduction).roundToInt().coerceAtLeast(1)
        }
        return width to height
    }

    private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, matrix: Matrix, background: Int) {
        canvas.drawColor(background)
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * An invisible (alpha ~0) text layer over an already-drawn, rasterized page, so the exported
     * PDF is searchable/selectable despite being a raster copy -- ported from `PdfToolService`
     * unchanged in spirit, generalized to any rotation via [matrix] rather than assuming an
     * upright page: [lines]' bounding boxes are in the SAME bitmap-local coordinate space
     * [pageDrawMatrix] was built from (see [export]'s own comment on why OCR runs before
     * rotation), so concatenating that identical matrix onto the canvas before drawing each box's
     * text places it correctly whatever the page's rotation is, with no separate rotation math.
     */
    private fun drawInvisibleSearchText(canvas: Canvas, lines: List<Text.Line>, matrix: Matrix, bitmapWidth: Int, bitmapHeight: Int) {
        canvas.save()
        try {
            canvas.concat(matrix)
            canvas.clipRect(RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(1, 0, 0, 0) }
            lines.take(MAX_OCR_LINES_PER_PAGE).forEach { line ->
                val box = line.boundingBox ?: return@forEach
                if (box.width() <= 0 || box.height() <= 0 || line.text.isBlank()) return@forEach
                paint.textSize = box.height().toFloat().coerceAtLeast(4f)
                val measured = paint.measureText(line.text).coerceAtLeast(1f)
                val scaleX = box.width() / measured
                canvas.save()
                canvas.scale(scaleX, 1f, box.left.toFloat(), box.bottom.toFloat())
                canvas.drawText(line.text.take(MAX_OCR_LINE_CHARS), box.left.toFloat(), box.bottom.toFloat(), paint)
                canvas.restore()
            }
        } finally {
            canvas.restore()
        }
    }

    private fun normalizedRotation(value: Int): Int = ((value % 4) + 4) % 4

    private data class RendererHandle(
        val descriptor: android.os.ParcelFileDescriptor,
        val renderer: PdfRenderer,
    ) {
        fun close() {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    private companion object {
        const val MAX_OCR_LINES_PER_PAGE = 10_000
        const val MAX_OCR_LINE_CHARS = 2_000
    }
}

/**
 * The [Matrix] that draws a [bitmapWidth]x[bitmapHeight] bitmap, rotated by [quarterTurns] (0-3,
 * clockwise), scaled and centered to fill a [pageWidthPoints]x[pageHeightPoints] page. A quarter
 * or three-quarter turn swaps which of the bitmap's own axes maps to the page's width vs. height
 * -- callers already pass an [pageWidthPoints]/[pageHeightPoints] pair with that swap already
 * applied (see [PdfPageTools.export]'s own `outputWidthPoints`/`outputHeightPoints`), so this
 * function only ever has to fit, never itself decide which axis is "the wide one".
 *
 * A top-level, [Context]-free function (unlike the rest of [PdfPageTools]) specifically so it can
 * be unit-tested without a real PDF, bitmap or canvas -- the trickiest new correctness surface
 * this class gained this task (positioning an OCR text line under an arbitrary page rotation)
 * depends only on this matrix being right; drawing pixels through it is unit-testable nowhere in
 * this sandbox (see the P1.13 progress note on why), but the matrix itself is pure [Matrix] math.
 */
internal fun pageDrawMatrix(
    quarterTurns: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
): Matrix {
    val source = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
    val destination = RectF(0f, 0f, pageWidthPoints.toFloat(), pageHeightPoints.toFloat())
    val matrix = Matrix()
    when (((quarterTurns % 4) + 4) % 4) {
        0 -> matrix.setRectToRect(source, destination, Matrix.ScaleToFit.CENTER)
        1 -> {
            matrix.postRotate(90f)
            matrix.postTranslate(bitmapHeight.toFloat(), 0f)
            val rotated = RectF(0f, 0f, bitmapHeight.toFloat(), bitmapWidth.toFloat())
            matrix.postConcat(Matrix().apply { setRectToRect(rotated, destination, Matrix.ScaleToFit.CENTER) })
        }
        2 -> {
            matrix.postRotate(180f)
            matrix.postTranslate(bitmapWidth.toFloat(), bitmapHeight.toFloat())
            matrix.postConcat(Matrix().apply { setRectToRect(source, destination, Matrix.ScaleToFit.CENTER) })
        }
        else -> {
            matrix.postRotate(270f)
            matrix.postTranslate(0f, bitmapWidth.toFloat())
            val rotated = RectF(0f, 0f, bitmapHeight.toFloat(), bitmapWidth.toFloat())
            matrix.postConcat(Matrix().apply { setRectToRect(rotated, destination, Matrix.ScaleToFit.CENTER) })
        }
    }
    return matrix
}
