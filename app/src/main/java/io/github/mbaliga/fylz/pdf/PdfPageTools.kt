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
 * Dependency-free PDF page tools using platform PdfRenderer and PdfDocument.
 *
 * Android does not expose object-level PDF rewriting. These operations therefore create a
 * rasterized visual copy and disclose the fidelity trade-off instead of silently claiming a
 * lossless merge or edit.
 */
class PdfPageTools(private val context: Context) {
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

    suspend fun export(
        references: List<PdfPageReference>,
        destinationUri: Uri,
        options: PdfExportOptions = PdfExportOptions(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): PdfExportResult = withContext(Dispatchers.IO) {
        PdfPagePlanPolicy.validate(references)?.let { error(it) }
        val output = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: error("The destination is not writable.")
        val document = PdfDocument()
        val renderers = linkedMapOf<String, RendererHandle>()
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
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            outputWidthPoints,
                            outputHeightPoints,
                            outputIndex + 1,
                        ).create()
                        val targetPage = document.startPage(pageInfo)
                        try {
                            drawBitmap(targetPage.canvas, bitmap, rotation, outputWidthPoints, outputHeightPoints, options.backgroundColor)
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
            document.close()
            renderers.values.forEach(RendererHandle::close)
        }
        PdfExportResult(
            pagesWritten = references.size,
            outputBytes = runCatching {
                context.contentResolver.openAssetFileDescriptor(destinationUri, "r")?.use { it.length.takeIf { length -> length >= 0L } }
            }.getOrNull(),
        )
    }

    suspend fun split(
        sourceUri: Uri,
        destinations: List<Uri>,
        options: PdfExportOptions = PdfExportOptions(),
    ): List<PdfExportResult> {
        val pages = allPages(sourceUri)
        require(destinations.size == pages.size) { "Provide one destination for each page." }
        return pages.indices.map { index -> export(listOf(pages[index]), destinations[index], options) }
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

    private fun drawBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        quarterTurns: Int,
        pageWidth: Int,
        pageHeight: Int,
        background: Int,
    ) {
        canvas.drawColor(background)
        val source = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val matrix = Matrix()
        when (quarterTurns) {
            0 -> matrix.setRectToRect(source, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), Matrix.ScaleToFit.CENTER)
            1 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(bitmap.height.toFloat(), 0f)
                val rotated = RectF(0f, 0f, bitmap.height.toFloat(), bitmap.width.toFloat())
                val fit = Matrix().apply { setRectToRect(rotated, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), Matrix.ScaleToFit.CENTER) }
                matrix.postConcat(fit)
            }
            2 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(bitmap.width.toFloat(), bitmap.height.toFloat())
                val fit = Matrix().apply { setRectToRect(source, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), Matrix.ScaleToFit.CENTER) }
                matrix.postConcat(fit)
            }
            3 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, bitmap.width.toFloat())
                val rotated = RectF(0f, 0f, bitmap.height.toFloat(), bitmap.width.toFloat())
                val fit = Matrix().apply { setRectToRect(rotated, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), Matrix.ScaleToFit.CENTER) }
                matrix.postConcat(fit)
            }
        }
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
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
}
