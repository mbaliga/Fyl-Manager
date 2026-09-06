package io.github.mbaliga.fylz.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * One freehand pen stroke, in the coordinate space of the drawing surface it was captured on
 * ([AnnotateOverlay]'s canvas) -- not the source bitmap's own pixel space, which is usually a
 * different size. [ImageAnnotationRenderer.burnIn] is what reconciles the two.
 */
data class AnnotationStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidthPx: Float,
)

/**
 * Burns [strokes] into a copy of [source], the one place annotate's "what you drew" and "what got
 * saved" are the same operation -- [AnnotateOverlay] calls this for both the overwrite and the
 * save-as path, so there is exactly one way a stroke ends up in a file's actual bytes.
 */
object ImageAnnotationRenderer {

    fun burnIn(source: Bitmap, strokes: List<AnnotationStroke>, canvasSize: Size): Bitmap {
        val target = source.copy(Bitmap.Config.ARGB_8888, true) ?: source
        if (strokes.isEmpty() || canvasSize.width <= 0f || canvasSize.height <= 0f) return target

        // The canvas the user actually drew on is almost never the bitmap's own pixel size (a
        // 4000x3000 photo does not render at its own resolution on screen) -- every point is
        // scaled from screen space into bitmap space before it touches the output.
        val scaleX = target.width / canvasSize.width
        val scaleY = target.height / canvasSize.height
        val canvas = Canvas(target)
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            paint.color = stroke.color.toArgb()
            // A stroke drawn thick relative to the on-screen canvas must stay just as thick
            // relative to the saved image, not shrink to a hairline on a photo far larger than
            // the screen that drew it.
            paint.strokeWidth = stroke.strokeWidthPx * ((scaleX + scaleY) / 2f)
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                val x = point.x * scaleX
                val y = point.y * scaleY
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        return target
    }
}
