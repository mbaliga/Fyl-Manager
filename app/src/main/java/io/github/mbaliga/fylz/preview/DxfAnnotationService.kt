package io.github.mbaliga.fylz.preview

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** One freehand stroke in on-screen canvas space, the DXF analogue of
 * [io.github.mbaliga.fylz.pdf.PdfInkStroke] -- [DxfAnnotationService] maps it into the drawing's
 * own world space itself, since that mapping depends on the drawing's own bounding box. */
data class DxfInkStroke(val points: List<Offset>, val colorArgb: Int, val strokeWidthPx: Float)

/**
 * Burns freehand ink into a DXF drawing as a new POLYLINE entity per stroke, spliced into the
 * source file's own ENTITIES section -- real DXF geometry any CAD viewer can open, not a
 * Fylz-only sidecar. The DXF analogue of [io.github.mbaliga.fylz.pdf.PdfAnnotationService]: append
 * to the format's own vocabulary rather than rewrite the file, so every layer, block and other
 * entity already in it survives byte-for-byte untouched outside the one insertion point.
 *
 * POLYLINE + VERTEX + SEQEND (the pre-LWPOLYLINE legacy encoding, per [GeometryPreviewParser]'s
 * own read-side KDoc) rather than LWPOLYLINE: it is the flat, no-subclass-marker entity form every
 * DXF reader from R12 onward accepts with no object-model scaffolding, and -- unlike LWPOLYLINE --
 * it is exactly what [GeometryPreviewParser.parse] already reads back on this codebase's own
 * preview path, so a written stroke can be verified with the same parser that renders every DXF
 * this app opens, not a hand-rolled second reader.
 *
 * No entity handle (group 5) is emitted: assigning one that is guaranteed not to collide with a
 * handle already used elsewhere in the file would require scanning the whole document and updating
 * its `$HANDSEED`, and DXF handles are optional under the R12-era encoding this writer already
 * targets -- every reader that requires them assigns one itself on load.
 *
 * Always writes to a new [destinationUri], matching every other annotate/edit path in this app.
 */
object DxfAnnotationService {

    /** Loads and parses a drawing for display -- the overlay's own read path, shared with
     * [annotateDrawing]'s save path so both agree on exactly the same geometry and bounding box. */
    suspend fun loadGeometry(context: Context, uri: Uri, name: String): GeometryPreview = withContext(Dispatchers.IO) {
        GeometryPreviewParser.parse(name, readBounded(context, uri, GeometryPreviewParser.MAX_INPUT_BYTES))
    }

    suspend fun annotateDrawing(
        context: Context,
        sourceUri: Uri,
        sourceName: String,
        canvasSize: Size,
        strokes: List<DxfInkStroke>,
        destinationUri: Uri,
    ) = withContext(Dispatchers.IO) {
        require(strokes.isNotEmpty()) { "At least one stroke is required." }
        require(canvasSize.width > 0f && canvasSize.height > 0f) { "The drawing was not measured yet." }
        val sourceBytes = readBounded(context, sourceUri, GeometryPreviewParser.MAX_INPUT_BYTES)
        val geometry = GeometryPreviewParser.parse(sourceName, sourceBytes)
        val bounds = boundsOf(geometry)
        val sourceText = sourceBytes.toString(Charsets.UTF_8)
        val newline = if ("\r\n" in sourceText) "\r\n" else "\n"
        val insertAt = entitiesSectionEnd(sourceText)
            ?: error("This DXF has no ENTITIES section to annotate.")
        val scaleX = bounds.spanX / canvasSize.width
        val scaleY = bounds.spanY / canvasSize.height
        val entityText = buildString {
            strokes.forEach { stroke -> appendPolyline(this, stroke, bounds, scaleX, scaleY, newline) }
        }
        val output = sourceText.substring(0, insertAt) + entityText + sourceText.substring(insertAt)
        val stream = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: error("The destination is not writable.")
        stream.use { it.write(output.toByteArray(Charsets.UTF_8)) }
    }

    internal data class DxfBounds(val minX: Float, val minY: Float, val spanX: Float, val spanY: Float)

    /** Exposed (not private) so [io.github.mbaliga.fylz.ui.DxfAnnotateOverlay] can fit and map
     * against the exact same bounds this save path itself will recompute from the same geometry. */
    internal fun boundsOf(geometry: GeometryPreview): DxfBounds {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        geometry.vertices.forEach {
            minX = min(minX, it.x); maxX = max(maxX, it.x)
            minY = min(minY, it.y); maxY = max(maxY, it.y)
        }
        return DxfBounds(minX, minY, max(maxX - minX, MIN_SPAN), max(maxY - minY, MIN_SPAN))
    }

    private fun appendPolyline(
        out: StringBuilder,
        stroke: DxfInkStroke,
        bounds: DxfBounds,
        scaleX: Float,
        scaleY: Float,
        newline: String,
    ) {
        if (stroke.points.size < 2) return
        val worldPoints = stroke.points.map { canvasToWorld(it, bounds, scaleX, scaleY) }
        val width = (stroke.strokeWidthPx * scaleX).coerceAtLeast(0f)
        fun line(code: Int, value: String) { out.append(code).append(newline).append(value).append(newline) }
        fun number(value: Float) = String.format(Locale.ROOT, "%.6f", value)
        line(0, "POLYLINE")
        line(8, "0") // The default layer -- every valid DXF already has it, so the ink is visible
        // without adding a new entry to the TABLES/LAYER section this splice never touches.
        line(62, aciColorFor(stroke.colorArgb).toString())
        line(420, trueColorFor(stroke.colorArgb).toString())
        line(66, "1") // "Vertices follow" -- required by the POLYLINE entity's own spec.
        line(70, "0") // Open polyline (not closed): a freehand stroke, not a filled loop.
        line(43, number(width))
        worldPoints.forEach { point ->
            line(0, "VERTEX")
            line(8, "0")
            line(10, number(point.x))
            line(20, number(point.y))
            line(30, number(0f))
        }
        line(0, "SEQEND")
        line(8, "0")
    }

    private fun canvasToWorld(point: Offset, bounds: DxfBounds, scaleX: Float, scaleY: Float): Offset =
        Offset(bounds.minX + point.x * scaleX, bounds.minY + bounds.spanY - point.y * scaleY)

    /** The nearest of the seven basic AutoCAD Color Index entries -- a coarse fallback for
     * readers that ignore the true-color [trueColorFor] group code 420 sets alongside it. */
    private fun aciColorFor(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        return ACI_PALETTE.minBy { (_, channels) ->
            val (pr, pg, pb) = channels
            (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
        }.first
    }

    private fun trueColorFor(argb: Int): Int = argb and 0x00ffffff

    private val ACI_PALETTE = listOf(
        1 to Triple(255, 0, 0),
        2 to Triple(255, 255, 0),
        3 to Triple(0, 255, 0),
        4 to Triple(0, 255, 255),
        5 to Triple(0, 0, 255),
        6 to Triple(255, 0, 255),
        7 to Triple(255, 255, 255),
    )

    private data class DxfRecord(val code: Int, val value: String, val lineStart: Int)

    /**
     * Locates the "0"/"ENDSEC" record that closes the ENTITIES section, by walking the file's own
     * group-code pairs the same way [GeometryPreviewParser.parseDxf] does, rather than a text
     * search for the literal word -- an entity whose own text content happens to contain
     * "ENTITIES" (a TEXT/MTEXT string, say) never counts, since this only recognizes the exact
     * "0/SECTION" followed by "2/ENTITIES" adjacency a real section header has.
     */
    private fun entitiesSectionEnd(text: String): Int? {
        var insideEntities = false
        var justSawSectionStart = false
        for (record in scanRecords(text)) {
            when {
                record.code == 0 && record.value == "SECTION" -> justSawSectionStart = true
                justSawSectionStart && record.code == 2 -> {
                    insideEntities = record.value == "ENTITIES"
                    justSawSectionStart = false
                }
                insideEntities && record.code == 0 && record.value == "ENDSEC" -> return record.lineStart
                else -> justSawSectionStart = false
            }
        }
        return null
    }

    private fun scanRecords(text: String): List<DxfRecord> {
        val lineStarts = mutableListOf<Int>()
        val lineContents = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index <= text.length) {
            if (index == text.length || text[index] == '\n') {
                var end = index
                if (end > start && text[end - 1] == '\r') end -= 1
                lineStarts += start
                lineContents += text.substring(start, end)
                start = index + 1
            }
            index += 1
        }
        val records = mutableListOf<DxfRecord>()
        var i = 0
        while (i + 1 < lineContents.size) {
            val code = lineContents[i].trim().toIntOrNull()
            if (code != null) records += DxfRecord(code, lineContents[i + 1].trim(), lineStarts[i])
            i += 2
        }
        return records
    }

    private fun readBounded(context: Context, uri: Uri, maxBytes: Int): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read this drawing.")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "This drawing exceeds the ${maxBytes / (1024 * 1024)} MiB annotation limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private const val MIN_SPAN = 0.0001f
}
