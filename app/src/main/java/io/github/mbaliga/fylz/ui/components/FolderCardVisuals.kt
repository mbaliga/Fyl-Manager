package io.github.mbaliga.fylz.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Folder-card redesign -- geometry and colour tokens read directly off the owner's two Figma
 * exports (`docs/agent/PROGRESS.md` names the exact files). Every numeric constant below is a
 * literal copy of a coordinate or colour that appears in one of those two SVGs, not a
 * transcription/approximation, so this object is the one place to re-check against the source if
 * the design ever changes.
 *
 * Both exports draw the *same* folder silhouette (confirmed by diffing their `<path d="...">`
 * data: once you subtract each file's own plate origin -- (61,49) in the light export, (37,48) in
 * the dark one, forced apart only because the two artboards are different widths -- every
 * remaining coordinate matches exactly). Only the fills/strokes differ between light and dark, so
 * this object keeps ONE geometry (the light export's own absolute numbers) and two colour
 * palettes, rather than duplicating the path.
 */
object FolderCardGeometry {
    // The background plate (a plain rounded rect) -- light export, `<rect x="61" y="49" width="122"
    // height="113" rx="10" .../>`.
    const val PLATE_LEFT = 61f
    const val PLATE_TOP = 49f
    const val PLATE_WIDTH = 122f
    const val PLATE_HEIGHT = 113f
    const val PLATE_CORNER = 10f

    // The folder silhouette itself -- light export's own `<path d="...">`, copied verbatim. Parsed
    // at runtime by `androidx.compose.ui.graphics.vector.PathParser` (see [rememberFolderPath])
    // rather than hand-transcribed into Compose draw calls, so there is no bezier-control-point
    // transcription error to audit for.
    const val FOLDER_PATH_DATA = "M62.791 57.5H104.56C107.782 57.5 110.786 59.1334 112.536 61.8389L117.625 69.7041C119.56 72.6944 122.879 74.5 126.44 74.5H180.295C185.796 74.5 190.148 79.1581 189.773 84.6465L185.137 152.646C184.797 157.631 180.654 161.5 175.658 161.5H69.2666C64.3001 161.5 60.1722 157.674 59.7949 152.722L53.3184 67.7217C52.8982 62.2066 57.2599 57.5 62.791 57.5Z"
    const val GLASS_STROKE_WIDTH = 1f
    const val GLASS_CORNER = 10f // matches the path's own rounded corners; used only for the thumbnail clip below, the path itself is already rounded.

    // The two short highlight lines near the bottom of the folder's visible body -- light export's
    // own `<rect .../>` pairs (`rx="1"` each).
    const val HIGHLIGHT_1_LEFT = 65f
    const val HIGHLIGHT_1_TOP = 147f
    const val HIGHLIGHT_1_WIDTH = 114f
    const val HIGHLIGHT_2_LEFT = 66f
    const val HIGHLIGHT_2_TOP = 152f
    const val HIGHLIGHT_2_WIDTH = 112f
    const val HIGHLIGHT_HEIGHT = 2f
    const val HIGHLIGHT_CORNER = 1f
    // Both highlight rects carry a `feOffset dy="1"` drop shadow in the SVG's own `<filter>` (no
    // blur node at all -- see FolderCardGeometry's KDoc in FolderGlassIcon below for how this is
    // approximated).
    const val HIGHLIGHT_SHADOW_OFFSET_Y = 1f

    // The glass path and its rounded top-right/bottom corners bleed slightly past the plate's own
    // left/right edges (confirmed against the path's control points, not just its endpoints -- the
    // top-right corner's own control point reaches x=190.148, ~1dp past the x=189.773 endpoint).
    // This is the real Figma geometry, not a mistake to "fix": the plate is a drop-shadow-style
    // backing a little narrower than the glass sitting on top of it. The icon's own layout box
    // must be wide enough to show that bleed without clipping it, hence the +/-10dp margin (a
    // couple of dp of headroom past the ~7-8dp the control points actually need).
    const val ICON_LEFT = PLATE_LEFT - 10f
    const val ICON_TOP = PLATE_TOP
    const val ICON_WIDTH = (PLATE_LEFT + PLATE_WIDTH + 10f) - ICON_LEFT
    const val ICON_HEIGHT = PLATE_HEIGHT

    // Where the live file-preview thumbnails sit: inset within the folder's visible BODY (below
    // the tab notch, above the two highlight lines), not clipped to the exact bezier -- a plain
    // inset rounded rect is visually indistinguishable here since thumbnails never reach that far
    // up toward the tab or down past the highlight lines, and is far cheaper/safer to lay out and
    // render than clipping to the folder's own path. See `FolderGlassIcon`'s own KDoc.
    const val THUMB_AREA_LEFT = 69f
    const val THUMB_AREA_TOP = 92f
    const val THUMB_AREA_WIDTH = 107f
    const val THUMB_AREA_HEIGHT = 48f
    const val THUMB_AREA_CORNER = 8f

    /** 2dp per the Figma export's own `backdrop-filter:blur(2px)` (the `foreignObject`'s inline
     * style -- the sole authoritative value; the sibling `data-figma-bg-blur-radius="4"` attribute
     * is Figma-editor-internal metadata in its own, different, blur-radius convention and is not
     * what the export actually renders). */
    val BACKDROP_BLUR = 2.dp
}

/** One half (light or dark) of the folder card's colour tokens -- every value a literal copy from
 * the matching Figma export; see [FolderCardGeometry]'s own KDoc. */
data class FolderCardPalette(
    val plate: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val highlight: Color,
    /** Approximation of the SVG's `feOffset dy="1"` drop shadow (no blur node in the original
     * filter) -- a second copy of the highlight rect, 1dp lower, at this colour/alpha, drawn
     * first/underneath. Documented in `docs/agent/PROGRESS.md` as a deliberate simplification: an
     * exact CSS box-shadow match was explicitly not required by the brief. */
    val highlightShadow: Color,
    val title: Color,
    val subtitle: Color,
)

object FolderCardPalettes {
    /** `11a47228-Frame_2147226877.svg` (light mode). */
    val Light = FolderCardPalette(
        plate = Color(0xFFD6DADB),
        glassFill = Color.White.copy(alpha = 0.85f),
        glassStroke = Color(0xFFDEDFE1),
        highlight = Color.White,
        highlightShadow = Color(0xFFD9D9D9).copy(alpha = 0.5f),
        title = Color(0xFF181818),
        subtitle = Color(0xFF737373),
    )

    /** `ca6479d2-Frame_2147226878.svg` (dark mode). */
    val Dark = FolderCardPalette(
        plate = Color(0xFF2C2C2C),
        glassFill = Color.Black.copy(alpha = 0.5f),
        glassStroke = Color(0xFF242424),
        highlight = Color(0xFF040404),
        highlightShadow = Color(0xFFD9D9D9).copy(alpha = 0.3f),
        title = Color.White,
        subtitle = Color(0xFFC1C1C1),
    )
}

/** Parses [FolderCardGeometry.FOLDER_PATH_DATA] once per composition (it never changes) via the
 * real SVG path-data parser rather than a hand-transcribed set of Compose draw calls. */
@Composable
internal fun rememberFolderPath(): Path = remember {
    PathParser().parsePathString(FolderCardGeometry.FOLDER_PATH_DATA).toPath()
}

/**
 * The folder card's icon: background plate -> [thumbnails] (clipped to the body, blurred where the
 * platform supports it) -> the translucent glass path on top -> the two highlight lines. Text
 * (name/subtitle) is deliberately NOT part of this composable -- it sits below the icon, drawn by
 * the caller (`FolderCard.kt`), since the Figma export draws it as plain text layers below the
 * card's icon artwork, not inside it.
 *
 * Sizing: the icon lays out at [FolderCardGeometry.ICON_WIDTH]:[FolderCardGeometry.ICON_HEIGHT]'s
 * aspect ratio, filling whatever width the caller gives it (a grid cell's width varies with the
 * density/adaptive-columns setting) -- every other measurement below scales off that same ratio,
 * computed once via `BoxWithConstraints.maxWidth`, so the whole icon (plate, glass path, highlight
 * lines and the thumbnail body) stays proportioned exactly like the Figma export at any size.
 */
@Composable
fun FolderGlassIcon(
    palette: FolderCardPalette,
    modifier: Modifier = Modifier,
    thumbnails: @Composable (width: Dp, height: Dp) -> Unit = { _, _ -> },
) {
    val designPath = rememberFolderPath()
    val density = LocalDensity.current
    val blurRadiusPx = with(density) { FolderCardGeometry.BACKDROP_BLUR.toPx() }

    BoxWithConstraints(modifier.aspectRatio(FolderCardGeometry.ICON_WIDTH / FolderCardGeometry.ICON_HEIGHT)) {
        val scale = maxWidth / FolderCardGeometry.ICON_WIDTH.dp

        Canvas(Modifier.size(maxWidth, maxHeight)) {
            val s = size.width / FolderCardGeometry.ICON_WIDTH
            drawDesignRoundRect(
                color = palette.plate,
                left = FolderCardGeometry.PLATE_LEFT, top = FolderCardGeometry.PLATE_TOP,
                width = FolderCardGeometry.PLATE_WIDTH, height = FolderCardGeometry.PLATE_HEIGHT,
                corner = FolderCardGeometry.PLATE_CORNER, scalePx = s,
            )
        }

        Box(
            Modifier
                .offset(
                    x = (FolderCardGeometry.THUMB_AREA_LEFT - FolderCardGeometry.ICON_LEFT).dp * scale,
                    y = (FolderCardGeometry.THUMB_AREA_TOP - FolderCardGeometry.ICON_TOP).dp * scale,
                )
                .size(
                    FolderCardGeometry.THUMB_AREA_WIDTH.dp * scale,
                    FolderCardGeometry.THUMB_AREA_HEIGHT.dp * scale,
                )
                .clip(RoundedCornerShape(FolderCardGeometry.THUMB_AREA_CORNER.dp * scale))
                .thenBackdropBlur(blurRadiusPx),
        ) {
            thumbnails(
                FolderCardGeometry.THUMB_AREA_WIDTH.dp * scale,
                FolderCardGeometry.THUMB_AREA_HEIGHT.dp * scale,
            )
        }

        Canvas(Modifier.size(maxWidth, maxHeight)) {
            val s = size.width / FolderCardGeometry.ICON_WIDTH
            val matrix = Matrix().apply {
                scale(s, s)
                translate(-FolderCardGeometry.ICON_LEFT, -FolderCardGeometry.ICON_TOP)
            }
            val localPath = Path().apply { addPath(designPath) }.apply { transform(matrix) }
            drawPath(localPath, color = palette.glassFill)
            drawPath(localPath, color = palette.glassStroke, style = Stroke(width = FolderCardGeometry.GLASS_STROKE_WIDTH * s))

            drawHighlightLine(
                palette = palette,
                left = FolderCardGeometry.HIGHLIGHT_1_LEFT, top = FolderCardGeometry.HIGHLIGHT_1_TOP,
                width = FolderCardGeometry.HIGHLIGHT_1_WIDTH, scalePx = s,
            )
            drawHighlightLine(
                palette = palette,
                left = FolderCardGeometry.HIGHLIGHT_2_LEFT, top = FolderCardGeometry.HIGHLIGHT_2_TOP,
                width = FolderCardGeometry.HIGHLIGHT_2_WIDTH, scalePx = s,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDesignRoundRect(
    color: Color,
    left: Float, top: Float, width: Float, height: Float, corner: Float, scalePx: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset((left - FolderCardGeometry.ICON_LEFT) * scalePx, (top - FolderCardGeometry.ICON_TOP) * scalePx),
        size = Size(width * scalePx, height * scalePx),
        cornerRadius = CornerRadius(corner * scalePx, corner * scalePx),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHighlightLine(
    palette: FolderCardPalette,
    left: Float, top: Float, width: Float, scalePx: Float,
) {
    val corner = CornerRadius(FolderCardGeometry.HIGHLIGHT_CORNER * scalePx, FolderCardGeometry.HIGHLIGHT_CORNER * scalePx)
    val size = Size(width * scalePx, FolderCardGeometry.HIGHLIGHT_HEIGHT * scalePx)
    // The shadow copy first (underneath), offset down by the SVG filter's own `feOffset dy="1"`.
    drawRoundRect(
        color = palette.highlightShadow,
        topLeft = Offset(
            (left - FolderCardGeometry.ICON_LEFT) * scalePx,
            (top - FolderCardGeometry.ICON_TOP + FolderCardGeometry.HIGHLIGHT_SHADOW_OFFSET_Y) * scalePx,
        ),
        size = size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = palette.highlight,
        topLeft = Offset((left - FolderCardGeometry.ICON_LEFT) * scalePx, (top - FolderCardGeometry.ICON_TOP) * scalePx),
        size = size,
        cornerRadius = corner,
    )
}

/** Real `RenderEffect`-backed blur (API 31+) of the thumbnail layer sitting behind the translucent
 * glass path -- there is nothing else composited between the two, so blurring the thumbnails
 * directly is visually identical to a true CSS-style backdrop blur sampled through the glass, not
 * just an approximation of one. This app's `minSdk` is already 31 (`app/build.gradle.kts`), so the
 * `else` branch below is unreachable at runtime; it is kept anyway, per the brief, as the
 * documented fallback for a lower API level: no blur, just the glass panel's own translucency
 * softening whatever shows through it. */
private fun Modifier.thenBackdropBlur(radiusPx: Float): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        graphicsLayer {
            renderEffect = RenderEffect
                .createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else {
        this
    }
