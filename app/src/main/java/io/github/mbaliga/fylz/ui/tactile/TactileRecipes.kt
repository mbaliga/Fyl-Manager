package io.github.mbaliga.fylz.ui.tactile

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.ShadowLevel
import io.github.mbaliga.fylz.ui.theme.softShadow
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Pure geometry -- no Compose runtime, no Android framework, callable straight from a JUnit test.
// ---------------------------------------------------------------------------------------------

/**
 * Which of a cap or field body's own two vertical edges carries the slant. [LEADING] cuts the
 * LEFT edge -- a keycap resting on the right of its plate (inactive content to its left), or a
 * [TactileField]'s own leading edge. [TRAILING] cuts the RIGHT edge -- a keycap on the left.
 */
internal enum class TactileSlantSide { LEADING, TRAILING }

/**
 * How far the slanted edge's bottom is pulled in from its top, as a fraction of the element's
 * HEIGHT -- about 9.6 degrees off vertical.
 *
 * Deliberately NOT [io.github.mbaliga.fylz.ui.chrome.FolderTabSlant] (22dp). That constant is an
 * absolute run tuned for the chrome's own wide, short folder tabs; reused verbatim on a 30dp knob
 * or a 42dp cap it consumes most of the element's width and the "lean" reads as a torn shard --
 * the Build-11.5 render pass shipped exactly that. The lean has to scale with the thing it is
 * cutting, so it is a ratio of height here, clamped below against the element's own width.
 */
internal const val TactileSlantRatio: Float = 0.17f

/** The slanted edge's horizontal run for a [width] x [height] element: [TactileSlantRatio] of the
 *  height, but never more than a quarter of the width, so a narrow knob still reads as a keycap
 *  with a leaning edge rather than a wedge. */
internal fun tactileSlantRun(width: Float, height: Float): Float =
    (height * TactileSlantRatio).coerceAtMost(width * 0.25f).coerceAtLeast(0f)

/**
 * The four corners of a slanted keycap/field body, clockwise from the top-left, ready for
 * [roundedPolygonPath]. Only ONE vertical edge leans; the other three sides stay axis-aligned.
 */
internal fun tactileSlantPolygon(width: Float, height: Float, side: TactileSlantSide): List<Offset> {
    val run = tactileSlantRun(width, height)
    return when (side) {
        TactileSlantSide.LEADING -> listOf(
            Offset(0f, 0f), Offset(width, 0f), Offset(width, height), Offset(run, height),
        )
        TactileSlantSide.TRAILING -> listOf(
            Offset(0f, 0f), Offset(width, 0f), Offset(width - run, height), Offset(0f, height),
        )
    }
}

/**
 * A closed path through [points] with every corner rounded to [radius] -- including the two on a
 * slanted edge, which is the whole point of it.
 *
 * The previous implementation intersected a rounded rect with a straight-edged keep region, which
 * necessarily squared off BOTH corners of the slanted side (a hard point at each end of the lean).
 * Walking the polygon and easing each vertex with a quadratic instead keeps all four corners round
 * on any convex quad, so a leaning cap still reads as a keycap. Each corner's radius is clamped to
 * half of its shorter adjacent edge, so a short edge cannot produce crossing control points.
 */
internal fun roundedPolygonPath(points: List<Offset>, radius: Float): Path {
    val path = Path()
    if (points.size < 3) return path
    val n = points.size
    points.forEachIndexed { i, v ->
        val prev = points[(i - 1 + n) % n]
        val next = points[(i + 1) % n]
        val toPrev = prev - v
        val toNext = next - v
        val lenPrev = toPrev.getDistance()
        val lenNext = toNext.getDistance()
        if (lenPrev <= 0f || lenNext <= 0f) return@forEachIndexed
        val r = minOf(radius, lenPrev / 2f, lenNext / 2f)
        val entry = v + toPrev * (r / lenPrev)
        val exit = v + toNext * (r / lenNext)
        if (i == 0) path.moveTo(entry.x, entry.y) else path.lineTo(entry.x, entry.y)
        path.quadraticTo(v.x, v.y, exit.x, exit.y)
    }
    path.close()
    return path
}

/** The glint's arc + dot placement, always hugging the cap's own top-right corner. */
internal data class TactileGlintGeometry(
    val arcCenter: Offset,
    val arcRadius: Float,
    val strokeWidth: Float,
    val startAngleDegrees: Float,
    val sweepAngleDegrees: Float,
    val dotCenter: Offset,
    val dotRadius: Float,
)

/** Where the arc stops and the dot picks up again, in degrees on the glint's own circle. 270 is
 *  straight up (the top edge) and 360 is straight right (the trailing edge), so the pair rides the
 *  corner's own diagonal at 315. */
private const val GlintArcStart = 274f
private const val GlintArcSweep = 44f
private const val GlintDotAngle = 336f

/**
 * The comic shine mark: a short stroke riding the cap's top-right corner, then a smaller dot
 * continuing along the SAME circle after a gap.
 *
 * Both marks share one centre and one radius, which is what makes them read as a single highlight
 * catching the corner. The previous implementation centred the arc's sweep on 285 degrees (the top
 * of the circle, not the corner) and then placed the dot at the element's extreme corner pixel --
 * off the arc's circle entirely, and far enough away that the two read as an unrelated comma and a
 * crumb of dirt rather than one mark. Keep them concentric.
 */
internal fun tactileGlintGeometry(width: Float, height: Float): TactileGlintGeometry {
    val short = minOf(width, height)
    // Sized off the SHORT side so a wide, flat cap's glint does not grow past its own corner.
    val arcRadius = short * 0.20f
    val pad = short * 0.11f
    val stroke = (short * 0.055f).coerceAtLeast(1f)
    val arcCenter = Offset(width - arcRadius - pad, arcRadius + pad)
    val dotAngle = Math.toRadians(GlintDotAngle.toDouble())
    val dotCenter = Offset(
        arcCenter.x + arcRadius * cos(dotAngle).toFloat(),
        arcCenter.y + arcRadius * sin(dotAngle).toFloat(),
    )
    return TactileGlintGeometry(
        arcCenter = arcCenter,
        arcRadius = arcRadius,
        strokeWidth = stroke,
        startAngleDegrees = GlintArcStart,
        sweepAngleDegrees = GlintArcSweep,
        dotCenter = dotCenter,
        dotRadius = stroke * 0.55f,
    )
}

/** The state slash's parallelogram (4 points, clockwise) plus an optional floating dot above it
 *  ("!" reading) when [TactileSlashGeometry.dotCenter] is non-null. */
internal data class TactileSlashGeometry(
    val bar: List<Offset>,
    val dotCenter: Offset?,
    val dotRadius: Float,
)

internal fun tactileSlashGeometry(height: Float, withErrorDot: Boolean): TactileSlashGeometry {
    val barHeight = height * 0.38f
    val barWidth = height * 0.125f
    // The bar leans at exactly the body's own [TactileSlantRatio], so it reads as a tick cut from
    // the same leading edge it sits on rather than an unrelated floating parallelogram.
    val skew = barHeight * TactileSlantRatio
    val dotGap = if (withErrorDot) height * 0.10f else 0f
    val top = (height - barHeight) / 2f + dotGap / 2f
    val bottom = top + barHeight
    // Straddles the field's own leading edge: the caller positions this DrawScope at the body's
    // left, and the body's edge at mid-height sits half a slant-run in, so a bar spanning a little
    // either side of x=0 pokes past the edge instead of floating clear of it.
    val left = -barWidth * 0.30f
    val right = left + barWidth
    val bar = listOf(
        Offset(left + skew, top),
        Offset(right + skew, top),
        Offset(right, bottom),
        Offset(left, bottom),
    )
    val dotRadius = barWidth * 0.42f
    val dotCenter = if (withErrorDot) {
        Offset((left + right) / 2f + skew, top - dotGap - dotRadius * 0.4f)
    } else {
        null
    }
    return TactileSlashGeometry(bar, dotCenter, dotRadius)
}

/**
 * The five-spoke asterisk's outer endpoints -- a `size`x`size` box centred on itself, first spoke
 * pointing straight up, each following spoke 72 degrees further clockwise. [drawTactileAsterisk]
 * draws one stroke from the centre to each point; this is deliberately a burst of straight spokes
 * (a drawn path), never the literal `*` character.
 */
internal fun tactileAsteriskSpokes(size: Float): List<Offset> {
    val center = Offset(size / 2f, size / 2f)
    val radius = size / 2f
    return (0 until 5).map { i ->
        val angle = Math.toRadians(-90.0 + i * 72.0)
        Offset(
            center.x + radius * cos(angle).toFloat(),
            center.y + radius * sin(angle).toFloat(),
        )
    }
}

/** Clamps a selected segment index into `[0, optionCount)`, defensively -- a caller-supplied
 *  `selectedIndex` that has drifted out of range (a stale index after an option list shrank)
 *  still yields a drawable cap position instead of an out-of-bounds fraction. */
internal fun tactileClampSegmentIndex(index: Int, optionCount: Int): Int =
    if (optionCount <= 0) 0 else index.coerceIn(0, optionCount - 1)

/** The sliding cap's left-edge fraction of the plate's total width for an N-segment toggle. */
internal fun tactileSegmentFraction(selectedIndex: Int, optionCount: Int): Float {
    if (optionCount <= 0) return 0f
    return tactileClampSegmentIndex(selectedIndex, optionCount).toFloat() / optionCount.toFloat()
}

/** `value` -> `[0, 1]`, clamped to `range` first so an out-of-range caller value never yields an
 *  out-of-bounds fraction. An empty/inverted `range` resolves to `0f` rather than dividing by 0. */
internal fun tactileSliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/** The inverse of [tactileSliderFraction]: a `[0, 1]` drag fraction back to a value within `range`. */
internal fun tactileSliderValueAt(fraction: Float, range: ClosedFloatingPointRange<Float>): Float {
    val f = fraction.coerceIn(0f, 1f)
    val raw = range.start + f * (range.endInclusive - range.start)
    return raw.coerceIn(minOf(range.start, range.endInclusive), maxOf(range.start, range.endInclusive))
}

/** Thumb centre x for a track of [trackWidth] and thumb radius [thumbRadius], at drag [fraction]. */
internal fun tactileThumbCenterX(fraction: Float, trackWidth: Float, thumbRadius: Float): Float {
    val usable = (trackWidth - 2f * thumbRadius).coerceAtLeast(0f)
    return thumbRadius + fraction.coerceIn(0f, 1f) * usable
}

/** The inverse of [tactileThumbCenterX]: a tap/drag x back to a `[0, 1]` fraction. */
internal fun tactileFractionAtOffsetX(x: Float, trackWidth: Float, thumbRadius: Float): Float {
    val usable = (trackWidth - 2f * thumbRadius).coerceAtLeast(0.0001f)
    return ((x - thumbRadius) / usable).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------------------------
// Shapes
// ---------------------------------------------------------------------------------------------

/**
 * A keycap/field body with one vertical edge leaning at [TactileSlantRatio] and all four corners
 * still rounded to [cornerRadius] -- the shared outline for a toggle cap, a switch knob and a
 * [TactileField]'s own slanted leading edge.
 *
 * Built by rounding the corners of [tactileSlantPolygon] rather than by intersecting a rounded
 * rect with a straight-edged keep region: that older construction squared off both ends of the
 * lean into hard points, which is most of why the shipped Build-11.5 caps read as torn shards.
 */
internal class TactileSlantShape(
    private val side: TactileSlantSide,
    private val cornerRadius: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        with(density) {
            val radius = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
            Outline.Generic(roundedPolygonPath(tactileSlantPolygon(size.width, size.height, side), radius))
        }

    override fun equals(other: Any?): Boolean =
        other is TactileSlantShape && other.side == side && other.cornerRadius == cornerRadius

    override fun hashCode(): Int = side.hashCode() * 31 + cornerRadius.hashCode()
}

/** A [shape]'s outline as a plain [Path], for the rare case a caller needs to stroke it directly
 *  (e.g. [tactileCap]'s rim, which must follow the cap's own slanted edge rather than a plain
 *  bounding rect). */
private fun tactileOutlinePath(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): Path =
    when (val outline = shape.createOutline(size, layoutDirection, density)) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }

// ---------------------------------------------------------------------------------------------
// DrawScope helpers
// ---------------------------------------------------------------------------------------------

/** Comic-shine mark: a rounded corner-arc stroke plus a small dot, always top-right of the cap. */
internal fun DrawScope.drawTactileGlint(color: Color) {
    val g = tactileGlintGeometry(size.width, size.height)
    drawArc(
        color = color,
        startAngle = g.startAngleDegrees,
        sweepAngle = g.sweepAngleDegrees,
        useCenter = false,
        topLeft = Offset(g.arcCenter.x - g.arcRadius, g.arcCenter.y - g.arcRadius),
        size = Size(g.arcRadius * 2f, g.arcRadius * 2f),
        style = Stroke(width = g.strokeWidth, cap = StrokeCap.Round),
    )
    drawCircle(color = color, radius = g.dotRadius, center = g.dotCenter)
}

/** The field/option-row state mark: a slanted bar, plus a floating dot above it under error
 *  (the pair reading as "!"). Draws in the caller's own local coordinate space -- position this
 *  [DrawScope] just inside the leading edge before calling. */
internal fun DrawScope.drawTactileSlashTick(color: Color, withErrorDot: Boolean, dotColor: Color = color) {
    val g = tactileSlashGeometry(size.height, withErrorDot)
    val path = Path().apply {
        moveTo(g.bar[0].x, g.bar[0].y)
        g.bar.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color = color)
    g.dotCenter?.let { drawCircle(color = dotColor, radius = g.dotRadius, center = it) }
}

/** The five-spoke mandatory asterisk, drawn as a path (never the literal `*` glyph). */
internal fun DrawScope.drawTactileAsterisk(color: Color) {
    val dim = size.minDimension
    val shift = Offset((size.width - dim) / 2f, (size.height - dim) / 2f)
    val center = Offset(dim / 2f, dim / 2f) + shift
    val strokeWidth = dim * 0.12f
    tactileAsteriskSpokes(dim).forEach { p ->
        drawLine(color = color, start = center, end = p + shift, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------------------------
// Modifiers -- the three recipes
// ---------------------------------------------------------------------------------------------

/**
 * RAISED CAP / RECESSED GROOVE both cast a soft close shadow; [io.github.mbaliga.fylz.ui.theme
 * .softShadow] is the shared depth primitive for that (Build 11.5's own "no Material elevation
 * ramps" language), reused here rather than a third hand-rolled shadow tint.
 */
private val TactileDefaultPlateShape = RoundedCornerShape(FylzGeometry.RadiusXl)

/**
 * PLATE (container): a shallow WELL for a cap to sit in -- fill, top-down inset shading, a light
 * lip along the bottom wall, and a hairline following the plate's own outline.
 *
 * The previous version drew a 1dp white highlight along the top and left instead, which is the
 * bevel of a RAISED slab: it fought the cap's own "floating above the plate" story and, at 1dp on
 * a light-gray fill, was invisible anyway, leaving the plate reading as a flat blob. Light falls
 * from above in this kit, so a recess is dark at its top wall and light at its bottom one.
 */
internal fun Modifier.tactilePlate(palette: TactilePalette, shape: Shape = TactileDefaultPlateShape): Modifier =
    this
        .softShadow(ShadowLevel.SM, shape)
        .clip(shape)
        .drawWithContent {
            drawRect(palette.plate)
            val well = size.height * 0.34f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        TactileBevelDark2.copy(alpha = if (palette.isDark) 0.30f else 0.11f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = well,
                ),
                size = Size(size.width, well),
            )
            drawContent()
            val stroke = 1.dp.toPx()
            drawLine(
                color = palette.plateHighlight.copy(alpha = if (palette.isDark) 0.14f else 0.9f),
                start = Offset(stroke * 3f, size.height - stroke * 0.5f),
                end = Offset(size.width - stroke * 3f, size.height - stroke * 0.5f),
                strokeWidth = stroke,
            )
            // Follows the plate's OWN outline -- a pill or slanted plate must not get a
            // rectangular ring, the same reasoning as [tactileCap]'s rim below.
            drawPath(
                path = tactileOutlinePath(shape, size, layoutDirection, this),
                color = palette.edge,
                style = Stroke(width = stroke),
            )
        }

/**
 * RAISED CAP: a soft cast shadow (reusing [softShadow] -- the spec's own "shadow shrinks" on
 * press is approximated by the PRESS bevel-invert below rather than an animated elevation, since
 * [io.github.mbaliga.fylz.ui.theme.ShadowLevel] is a fixed three-step vocabulary, not a
 * continuous one; the 1dp translate + bevel invert already carry the primary press read) + a
 * 158deg-ish face gradient (approximated as a top-left -> bottom-right diagonal; exact angle is a
 * first-pass placeholder for the owner's on-device verification pass) + a 1dp rim ring + a top
 * inner lip + a bottom dark cavity, all fading per [pressedFraction] into the PRESS recipe's
 * inverted read (inner dark shadow growing in from the top instead). The 1dp translate-down half
 * of PRESS is the caller's own concern -- see [tactilePressOffset] -- so this modifier stays pure
 * paint, no layout.
 */
internal fun Modifier.tactileCap(
    palette: TactilePalette,
    shape: Shape,
    showGlint: Boolean = true,
    pressedFraction: () -> Float,
): Modifier =
    this
        .softShadow(ShadowLevel.MD, shape)
        .clip(shape)
        .drawWithContent {
            drawRect(
                Brush.linearGradient(
                    0f to palette.capHigh,
                    0.53f to palette.capMid,
                    1f to palette.capBase,
                    start = Offset(size.width * 0.12f, 0f),
                    end = Offset(size.width * 0.88f, size.height),
                ),
            )
            drawContent()
            val pressed = pressedFraction().coerceIn(0f, 1f)
            val edge = 1.dp.toPx()
            // The rim traces the SHAPE's own outline (not a plain bounding rect): one of the two
            // vertical edges is a diagonal slant, and a rectangular stroke would just get clipped
            // into an L-shaped fragment there instead of following the cut.
            drawPath(
                path = tactileOutlinePath(shape, size, layoutDirection, this),
                color = TactileCapRim.copy(alpha = TactileCapRim.alpha * (1f - pressed)),
                style = Stroke(width = edge),
            )
            drawLine(
                color = TactileCapRimSoft.copy(alpha = TactileCapRimSoft.alpha * (1f - pressed * 0.5f)),
                start = Offset(edge, edge * 1.5f),
                end = Offset(size.width - edge, edge * 1.5f),
                strokeWidth = edge * 1.5f,
            )
            val cavity = size.height * 0.22f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, TactileBevelDark2.copy(alpha = TactileBevelDark2.alpha * (1f - pressed))),
                    startY = size.height - cavity,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - cavity),
                size = Size(size.width, cavity),
            )
            if (pressed > 0f) {
                val innerShadow = size.height * 0.4f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(TactileBevelDark.copy(alpha = TactileBevelDark.alpha * pressed), Color.Transparent),
                        startY = 0f,
                        endY = innerShadow,
                    ),
                    size = Size(size.width, innerShadow),
                )
            }
            if (showGlint) drawTactileGlint(palette.glint)
        }

/**
 * RECESSED GROOVE: a vertical fill (flat for the light skin, a deep 3-stop gradient for dark --
 * see [TactilePalette]'s own doc) plus a soft inner top shadow and a 1dp bottom light lip. The
 * exact 2dp/4dp + 1dp/2dp double-band the fidelity spec calls for is approximated here as one
 * combined falloff; splitting it into two literal bands is a fine-tuning pass, not a first-pass
 * blocker.
 */
internal fun Modifier.tactileFieldGroove(palette: TactilePalette, shape: Shape): Modifier =
    this
        .softShadow(ShadowLevel.SM, shape)
        .clip(shape)
        .drawWithContent {
            drawRect(
                Brush.verticalGradient(
                    listOf(palette.fieldGradientTop, palette.fieldGradientMid, palette.fieldGradientBase),
                ),
            )
            // Inset shading and lip are painted BEFORE the caller's content, not after: drawn on
            // top they veiled the first few dp of the field's own text, dimming exactly the line
            // the user is reading. The groove is behind the text, so it paints behind the text.
            val shadowHeight = (size.height * 0.16f).coerceAtMost(8.dp.toPx())
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        TactileBevelDark2.copy(alpha = if (palette.isDark) 0.5f else 0.07f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = shadowHeight,
                ),
                size = Size(size.width, shadowHeight),
            )
            val lip = 1.dp.toPx()
            drawRect(color = TactileBevelLip, topLeft = Offset(0f, size.height - lip), size = Size(size.width, lip))
            drawContent()
        }

/**
 * A slider's CHANNEL: the same recessed read as [tactileFieldGroove] but over the PLATE tone
 * rather than the field body's own fill.
 *
 * A field is a light figure sitting ON the page; a slider track is a groove cut INTO it. Sharing
 * the field's fill meant the light skin drew a white channel on a near-white ground -- the unfilled
 * part of the track simply was not there, leaving a bare violet bar floating in space.
 */
internal fun Modifier.tactileTrackGroove(palette: TactilePalette, shape: Shape): Modifier =
    this
        .clip(shape)
        .drawWithContent {
            drawRect(palette.plate)
            val inset = size.height * 0.45f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        TactileBevelDark2.copy(alpha = if (palette.isDark) 0.55f else 0.16f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = inset,
                ),
                size = Size(size.width, inset),
            )
            drawContent()
            drawPath(
                path = tactileOutlinePath(shape, size, layoutDirection, this),
                color = palette.edge,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

/** Interior accent wash for a latched/ON/checked control -- the ON state's second channel, drawn
 *  as a soft glow INSIDE the shape, never an outer glow (the spec's explicit "never an outer
 *  glow" line). Alpha in `.22-.28`; callers fade it in/out themselves via [alpha].
 *
 *  Only safe as the LAST paint step before this element's own (non-drawn) children, or on an
 *  element with no children at all: it draws `drawContent()` first and the wash on top of that,
 *  so chaining it before something with its own foreground content (e.g. a keycap knob) would
 *  wash over that content too. [TactileSwitch] wants the wash under its knob, not over it, so it
 *  paints that one as a plain z-ordered sibling `Box` instead of reaching for this modifier. */
internal fun Modifier.tactileAccentWash(color: Color, alpha: Float): Modifier =
    if (alpha <= 0f) this else this.drawWithContent {
        drawContent()
        drawRect(color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)))
    }

/** 2dp accent focus ring, offset ~3dp from the control -- drawn outside this modifier's own
 *  bounds, so give a focusable tactile control a little breathing room from tightly-packed
 *  neighbours (the ring does not clip itself to the parent). Defaults to a plain rounded rect
 *  ([cornerRadius]); pass [shape] for a control whose own body isn't a plain rounded rect (e.g.
 *  [TactileField]'s slanted-leading-edge [TactileSlantShape]) so the ring follows that outline
 *  instead -- same reasoning as [tactileCap]'s own rim stroke below: a rectangular ring would just
 *  read as belonging to a different shape than the control it outlines. */
internal fun Modifier.tactileFocusRing(
    focused: Boolean,
    color: Color,
    cornerRadius: Dp = FylzGeometry.RadiusXl,
    shape: Shape? = null,
): Modifier = if (!focused) this else this.drawWithContent {
    drawContent()
    val offset = 3.dp.toPx()
    val stroke = 2.dp.toPx()
    if (shape != null) {
        // The outline is computed for the offset-inflated box (matching the plain-rect branch's
        // own topLeft/size inflation below) and then shifted back by -offset, so the ring reads as
        // the control's true shape pushed outward rather than a same-size copy of it.
        val inflatedSize = Size(size.width + offset * 2f, size.height + offset * 2f)
        val outline = tactileOutlinePath(shape, inflatedSize, layoutDirection, this)
        val ringPath = Path().apply { addPath(outline, Offset(-offset, -offset)) }
        drawPath(path = ringPath, color = color, style = Stroke(width = stroke))
    } else {
        drawRoundRect(
            color = color,
            topLeft = Offset(-offset, -offset),
            size = Size(size.width + offset * 2f, size.height + offset * 2f),
            cornerRadius = CornerRadius(cornerRadius.toPx() + offset),
            style = Stroke(width = stroke),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// PRESS + reduced motion
// ---------------------------------------------------------------------------------------------

/** The kit's own copy of the app's standard ease -- [io.github.mbaliga.fylz.ui.motion.FylzMotion]
 *  keeps its matching constant private and has no spec for 60/120ms durations (only its own
 *  320ms [io.github.mbaliga.fylz.ui.motion.FylzMotion.settle], reused directly wherever this kit
 *  needs exactly that duration -- [TactileToggle]'s cap slide, [TactileSwitch]'s knob slide). */
internal val TactileEase: CubicBezierEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** [pressed] the raw interaction state; [transform] 0-1 over 60ms for the 1dp translate;
 *  [shadow] 0-1 over 120ms for the shadow-shrink / bevel-invert paint. Reduced motion collapses
 *  both to a snap -- final states only, per `ANIMATOR_DURATION_SCALE == 0`. */
internal data class TactilePressState(val pressed: Boolean, val transform: Float, val shadow: Float)

@Composable
internal fun rememberTactilePressState(interactionSource: InteractionSource): TactilePressState {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = tactileReducedMotion()
    val transform by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(durationMillis = 60, easing = TactileEase),
        label = "tactile-press-transform",
    )
    val shadow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(durationMillis = 120, easing = TactileEase),
        label = "tactile-press-shadow",
    )
    return TactilePressState(pressed, transform, shadow)
}

/** The PRESS recipe's 1dp sink, driven by [TactilePressState.transform]. */
internal fun Modifier.tactilePressOffset(press: TactilePressState, maxOffset: Dp = 1.dp): Modifier =
    this.offset { IntOffset(0, (maxOffset.toPx() * press.transform).roundToInt()) }

/**
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` is the system "Remove animations" toggle --
 * every animated spec in this package snaps to its final state instead when this is true. Mirrors
 * [io.github.mbaliga.fylz.ui.desktop.WallpaperLayer]'s own read of the same setting rather than a
 * shared helper, since that file's copy is private to it and this kit otherwise has zero
 * dependency on `ui.desktop`.
 */
@Composable
internal fun tactileReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) == 0f
    }
}
