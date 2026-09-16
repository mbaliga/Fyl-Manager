package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.mbaliga.fylz.appearance.FolderFinish
import kotlin.math.sin
import kotlin.random.Random

/**
 * One face of a folder -- its body or its tab -- painted in a [FolderFinish].
 *
 * Everything a finish is made of resolves from two inputs: the folder's own [tone] and whether the
 * scheme around it is dark. Nothing is sampled from an image and nothing is cached, so the same
 * recipe draws a blue gloss folder and an amber one, in either scheme, from the same code.
 *
 * The plane clips to [shape] and paints inside it, so callers keep owning the silhouette -- the
 * body's asymmetric corners and the tab's own rounding are unchanged by which material fills them.
 */
@Composable
internal fun FolderPlane(
    finish: FolderFinish,
    tone: Color,
    shape: Shape,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clip(shape).drawBehind { drawFinish(finish, tone, dark) })
}

/**
 * The recipes.
 *
 * Each is layers, painted back to front, and each is written against the drawn size rather than
 * against dp -- a folder face is 56dp in a list row and over 90dp in a grid card, and a grain
 * measured in absolute dp would look like two different materials at the two sizes.
 */
internal fun DrawScope.drawFinish(finish: FolderFinish, tone: Color, dark: Boolean) {
    when (finish) {
        // Flat, on purpose: this is what a folder with no finish chosen draws, and it has to be
        // the same paint the app drew before this axis existed rather than a subtle restyle of
        // every folder nobody asked to change.
        FolderFinish.DEFAULT -> drawRect(tone)

        FolderFinish.SATIN -> {
            drawRect(
                Brush.verticalGradient(
                    0f to tone.lighten(0.14f),
                    0.55f to tone,
                    1f to tone.darken(0.12f),
                ),
            )
            // The sheen is a wide, very soft diagonal -- satin scatters its highlight rather than
            // returning it, which is the whole difference from GLOSS below.
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.00f),
                    0.45f to Color.White.copy(alpha = 0.10f),
                    1f to Color.White.copy(alpha = 0.00f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                ),
            )
        }

        FolderFinish.GLOSS -> {
            // The first cut of this had a hard stop at the waist, and every folder rendered as
            // two flat colours meeting at a line -- "half filled", not "lacquered". Lacquer falls
            // off; it does not step. The bounce light at the foot is what sells the curve.
            drawRect(
                Brush.verticalGradient(
                    0f to tone.lighten(0.32f),
                    0.28f to tone.lighten(0.12f),
                    0.58f to tone.darken(0.08f),
                    0.88f to tone.darken(0.02f),
                    1f to tone.lighten(0.08f),
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.30f),
                    0.55f to Color.White.copy(alpha = 0.07f),
                    1f to Color.White.copy(alpha = 0f),
                ),
                size = Size(size.width, size.height * 0.50f),
            )
            drawLitTopEdge(alpha = 0.5f)
        }

        FolderFinish.PLASTIC -> {
            drawRect(Brush.verticalGradient(listOf(tone.lighten(0.10f), tone.darken(0.16f))))
            // A single moulded hotspot off the top-left, not a band: the giveaway of a curved
            // plastic surface rather than a flat lacquered one.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0f)),
                    center = Offset(size.width * 0.30f, size.height * 0.24f),
                    radius = size.minDimension * 0.55f,
                ),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.30f, size.height * 0.24f),
            )
            drawInnerEdge(tone.darken(0.34f), alpha = 0.32f, widthFraction = 0.014f)
        }

        FolderFinish.BRUSHED -> {
            val metal = tone.metallic(dark)
            drawRect(Brush.verticalGradient(listOf(metal.lighten(0.10f), metal.darken(0.14f))))
            drawBrushLines(metal)
            // The one band a brushed face returns cleanly, across the middle.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0f),
                    0.5f to Color.White.copy(alpha = 0.22f),
                    1f to Color.White.copy(alpha = 0f),
                ),
                topLeft = Offset(0f, size.height * 0.34f),
                size = Size(size.width, size.height * 0.30f),
            )
        }

        FolderFinish.CHROME -> {
            val metal = tone.metallic(dark)
            // A mirror does not have a colour of its own -- it has a horizon. Light sky above,
            // dark ground below, a hard line between, and the tone surviving only as a cast.
            drawRect(
                Brush.verticalGradient(
                    0f to metal.lighten(0.55f),
                    0.34f to metal.lighten(0.16f),
                    0.50f to metal.darken(0.34f),
                    0.52f to metal.darken(0.10f),
                    0.80f to metal.lighten(0.28f),
                    1f to metal.darken(0.06f),
                ),
            )
            drawLitTopEdge(alpha = 0.7f)
        }

        FolderFinish.ANODISED -> {
            val deep = lerp(tone, Color.Black, if (dark) 0.30f else 0.16f)
            drawRect(
                Brush.verticalGradient(
                    0f to deep.darken(0.18f),
                    0.42f to deep.lighten(0.16f),
                    1f to deep.darken(0.24f),
                ),
            )
            drawBrushLines(deep, alpha = 0.05f)
            drawInnerEdge(Color.Black, alpha = 0.35f)
        }

        FolderFinish.PAPER -> {
            val kraft = tone.papery(dark)
            drawRect(Brush.verticalGradient(listOf(kraft.lighten(0.05f), kraft.darken(0.08f))))
            drawGrainSpeckle(kraft.darken(0.5f), alpha = 0.09f, count = 220)
            drawShadedBottom(alpha = 0.12f)
        }

        FolderFinish.CARDBOARD -> {
            val kraft = tone.papery(dark)
            drawRect(Brush.verticalGradient(listOf(kraft.lighten(0.08f), kraft.darken(0.06f))))
            drawGrainSpeckle(kraft.darken(0.5f), alpha = 0.08f, count = 180)
            drawFluting(kraft)
        }

        FolderFinish.WOOD -> {
            val timber = tone.timber(dark)
            drawRect(Brush.verticalGradient(listOf(timber.lighten(0.10f), timber.darken(0.12f))))
            drawWoodGrain(timber)
            drawShadedBottom(alpha = 0.10f)
        }

        FolderFinish.LEATHER -> {
            val hide = lerp(tone, Color(0xFF3A2A20), 0.42f)
            drawRect(Brush.verticalGradient(listOf(hide.lighten(0.10f), hide.darken(0.14f))))
            drawPebbleGrain(hide)
            drawStitchedSeam()
            drawInnerEdge(Color.Black, alpha = 0.30f)
        }

        FolderFinish.CARBON -> {
            // Near-black whatever the tone: carbon is a weave, and the tone survives only as the
            // sheen coming off it.
            val weave = lerp(Color(0xFF16171A), tone, 0.12f)
            drawRect(weave)
            drawTwill(weave, tone)
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.12f),
                    0.5f to Color.White.copy(alpha = 0.02f),
                    1f to Color.White.copy(alpha = 0.10f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }

        FolderFinish.GLASS -> {
            // Raised from a quarter: at that alpha a glass folder on a light listing was very
            // nearly nothing at all, which is a folder you cannot find rather than a subtle one.
            drawRect(tone.copy(alpha = if (dark) 0.46f else 0.52f))
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.26f),
                    0.42f to Color.White.copy(alpha = 0.06f),
                    0.44f to Color.White.copy(alpha = 0.00f),
                    1f to Color.White.copy(alpha = 0.10f),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            drawInnerEdge(Color.White, alpha = 0.85f, widthFraction = 0.014f)
        }

        FolderFinish.NEON -> {
            // The body is dark but never BLACK. Two earlier cuts failed here for the same reason
            // in two different ways -- a centre-struck radial gradient gave a vignette, and a thin
            // rim over pure black gave a black folder with a coloured shadow. A lit sign is not a
            // dark object beside a light; it is an object the light is coming OUT of, so the tone
            // has to reach the body as a cast before the rim is drawn at all.
            // The tube's own colour, not the folder's paint. FolderPalette's light-scheme tones
            // are pastels -- correct for a painted folder, and washed-out grey haze once used as a
            // light source, which is exactly how this rendered in light mode before. A neon sign
            // does not change colour with the room it is in.
            val lit = tone.vivid()
            drawRect(
                Brush.verticalGradient(
                    listOf(lerp(Color(0xFF15151C), lit, 0.16f), lerp(Color(0xFF0B0B10), lit, 0.06f)),
                ),
            )
            drawEdgeSpill(lit.copy(alpha = 0.50f))
            drawInnerEdge(lit.lighten(0.45f), alpha = 1f, widthFraction = 0.022f)
        }
    }
}

// ── Layer helpers ─────────────────────────────────────────────────────────────────────────────

/** The lit edge a hard finish catches along its top -- one line, never a gradient band. */
private fun DrawScope.drawLitTopEdge(alpha: Float) {
    val thickness = (size.height * 0.035f).coerceAtLeast(1f)
    drawRect(Color.White.copy(alpha = alpha), size = Size(size.width, thickness))
}

/**
 * Light spilling inward from all four edges, as four gradients rather than concentric strokes.
 *
 * The stroke version banded: overlapping rings of uniform alpha composite into visible steps, and
 * at grid-card size a neon folder came out as a stack of nested frames. Four gradients have no
 * steps to show, cost four draws instead of twenty, and read as an object the light is coming out
 * of rather than one with a frame around it.
 */
private fun DrawScope.drawEdgeSpill(glow: Color) {
    val spill = size.minDimension * 0.34f
    val clear = Color.Transparent
    drawRect(
        brush = Brush.verticalGradient(listOf(glow, clear), startY = 0f, endY = spill),
        size = Size(size.width, spill),
    )
    drawRect(
        brush = Brush.verticalGradient(listOf(clear, glow), startY = size.height - spill, endY = size.height),
        topLeft = Offset(0f, size.height - spill),
        size = Size(size.width, spill),
    )
    drawRect(
        brush = Brush.horizontalGradient(listOf(glow, clear), startX = 0f, endX = spill),
        size = Size(spill, size.height),
    )
    drawRect(
        brush = Brush.horizontalGradient(listOf(clear, glow), startX = size.width - spill, endX = size.width),
        topLeft = Offset(size.width - spill, 0f),
        size = Size(spill, size.height),
    )
}

/** A rim drawn inside the clip, so it traces whatever silhouette the caller chose. */
private fun DrawScope.drawInnerEdge(color: Color, alpha: Float, widthFraction: Float = 0.02f) {
    val width = (size.minDimension * widthFraction).coerceAtLeast(1f)
    drawRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(width / 2f, width / 2f),
        size = Size(size.width - width, size.height - width),
        style = Stroke(width = width),
    )
}

/** Contact shadow along the bottom, for the materials that sit rather than float. */
private fun DrawScope.drawShadedBottom(alpha: Float) {
    drawRect(
        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = alpha))),
        topLeft = Offset(0f, size.height * 0.68f),
        size = Size(size.width, size.height * 0.32f),
    )
}

/**
 * Fine vertical banding: the direction the brush ran.
 *
 * Line COUNT is derived from the drawn width, not fixed. A fixed 34 lines made a 56dp folder look
 * like corduroy and a 96dp one look like something else again; a line every ~2.5px is a brushed
 * face at any size, which is the whole reason these recipes work in drawn pixels.
 */
private fun DrawScope.drawBrushLines(base: Color, alpha: Float = 0.06f) {
    val step = 2.5f
    val lines = (size.width / step).toInt()
    repeat(lines) { index ->
        val shade = if (index % 2 == 0) Color.White else Color.Black
        val jitter = GRAIN[index % GRAIN.size]
        drawRect(
            color = shade.copy(alpha = alpha * (0.25f + jitter)),
            topLeft = Offset(index * step, 0f),
            size = Size(step * 0.55f, size.height),
        )
    }
}

/**
 * The deterministic grain table.
 *
 * A fixed set of unit-space points, generated once from a fixed seed rather than sampled per
 * frame: a speckle re-rolled on every recomposition crawls, and a folder that shimmers when the
 * list scrolls is worse than one with no grain at all.
 */
private val GRAIN: FloatArray = Random(20260902).let { random -> FloatArray(512) { random.nextFloat() } }

private fun DrawScope.drawGrainSpeckle(color: Color, alpha: Float, count: Int) {
    val radius = (size.minDimension * 0.012f).coerceAtLeast(0.6f)
    repeat(count) { index ->
        val x = GRAIN[(index * 2) % GRAIN.size] * size.width
        val y = GRAIN[(index * 2 + 1) % GRAIN.size] * size.height
        drawCircle(color.copy(alpha = alpha), radius = radius, center = Offset(x, y))
    }
}

/** Corrugation showing along the cut bottom edge, which is the only place board reveals it. */
private fun DrawScope.drawFluting(base: Color) {
    val bandTop = size.height * 0.82f
    val bandHeight = size.height - bandTop
    drawRect(base.darken(0.10f), topLeft = Offset(0f, bandTop), size = Size(size.width, bandHeight))
    val flutes = 16
    val step = size.width / flutes
    repeat(flutes) { index ->
        drawRect(
            color = Color.Black.copy(alpha = 0.16f),
            topLeft = Offset(index * step, bandTop),
            size = Size(step * 0.45f, bandHeight),
        )
    }
    drawRect(
        color = Color.Black.copy(alpha = 0.18f),
        topLeft = Offset(0f, bandTop),
        size = Size(size.width, (size.height * 0.012f).coerceAtLeast(1f)),
    )
}

/**
 * Bands running the long way, each wandering a little, the way a board's figure does.
 *
 * The strengths here are deliberately low. The first cut multiplied them by three and every
 * folder came out a barcode: timber figure is a whisper of tone against tone, and the moment a
 * band is dark enough to read as a LINE it stops reading as grain.
 */
private fun DrawScope.drawWoodGrain(base: Color) {
    val bands = 22
    val step = size.height / bands
    repeat(bands) { index ->
        val wobble = sin(index * 1.7f) * step * 0.4f
        val strength = 0.04f + GRAIN[index % GRAIN.size] * 0.07f
        val shade = if (index % 3 == 0) base.darken(0.30f) else base.lighten(0.16f)
        drawRect(
            color = shade.copy(alpha = strength),
            topLeft = Offset(0f, index * step + wobble),
            size = Size(size.width, step * (0.4f + GRAIN[(index + 7) % GRAIN.size] * 0.9f)),
        )
    }
}

/**
 * Hide is pebbled, not speckled: overlapping soft cells, each lit on one side.
 *
 * Small and dense, and faint. At 46 cells of a twentieth of the face at a third alpha the first
 * cut looked like bubble wrap -- separate circles you can count. Grain is not countable.
 */
private fun DrawScope.drawPebbleGrain(base: Color) {
    val cells = 150
    val radius = size.minDimension * 0.032f
    repeat(cells) { index ->
        val x = GRAIN[(index * 3) % GRAIN.size] * size.width
        val y = GRAIN[(index * 3 + 1) % GRAIN.size] * size.height
        drawCircle(base.lighten(0.20f).copy(alpha = 0.13f), radius = radius, center = Offset(x, y))
        drawCircle(
            color = base.darken(0.34f).copy(alpha = 0.11f),
            radius = radius * 0.80f,
            center = Offset(x + radius * 0.28f, y + radius * 0.30f),
        )
    }
}

/** The inset seam a bound edge carries -- dashes, so it reads as thread rather than as a border. */
private fun DrawScope.drawStitchedSeam() {
    val inset = size.minDimension * 0.12f
    val dash = size.minDimension * 0.07f
    val thickness = (size.minDimension * 0.016f).coerceAtLeast(1f)
    var x = inset
    while (x < size.width - inset) {
        drawRect(
            color = Color.White.copy(alpha = 0.16f),
            topLeft = Offset(x, inset),
            size = Size(dash * 0.5f, thickness),
        )
        drawRect(
            color = Color.White.copy(alpha = 0.13f),
            topLeft = Offset(x, size.height - inset - thickness),
            size = Size(dash * 0.5f, thickness),
        )
        x += dash
    }
}

/**
 * 2x2 twill.
 *
 * The first cut used cells a ninth of the face and rendered as a chessboard -- a weave is only a
 * weave when the cell is far smaller than the thing woven, and when the pattern STEPS along the
 * diagonal rather than alternating. Twenty-two cells across, and `(column + row) % 4 < 2` for the
 * step, is what turns a checker into a fabric.
 */
private fun DrawScope.drawTwill(weave: Color, tone: Color) {
    val cell = size.minDimension / 22f
    val columns = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val lit = (column + row) % 4 < 2
            val color = if (lit) lerp(weave.lighten(0.30f), tone, 0.14f) else weave.darken(0.40f)
            drawRect(
                color = color.copy(alpha = 0.42f),
                topLeft = Offset(column * cell, row * cell),
                size = Size(cell, cell),
            )
        }
    }
}

// ── Tone derivations ──────────────────────────────────────────────────────────────────────────

private fun Color.lighten(fraction: Float): Color = lerp(this, Color.White, fraction)

private fun Color.darken(fraction: Float): Color = lerp(this, Color.Black, fraction)

/**
 * Pulled most of the way to a neutral of its own brightness. A metal keeps a cast of the folder's
 * colour -- that is what distinguishes brass from steel -- but a fully saturated "metal" reads as
 * painted, not machined.
 */
private fun Color.metallic(dark: Boolean): Color {
    val neutral = Color(luminance(), luminance(), luminance()).let { if (dark) it.darken(0.15f) else it }
    return lerp(this, neutral, 0.62f)
}

/** Desaturated and warmed: paper stock is never the colour it was dyed, it is that colour on pulp. */
private fun Color.papery(dark: Boolean): Color {
    val stock = if (dark) Color(0xFF4A4034) else Color(0xFFD8C4A0)
    return lerp(this, stock, 0.62f)
}

/**
 * The same hue at full saturation and full value -- what the colour would be if it were emitting
 * rather than reflecting.
 *
 * Rebasing the channels so the darkest reads 0 and the brightest reads 1 keeps the hue exactly
 * and throws away only the pastel wash, which is the part that has no meaning for a light source.
 * A grey is left alone: a grey tube is a white tube, and stretching it would invent a hue.
 */
private fun Color.vivid(): Color {
    val low = minOf(red, green, blue)
    val high = maxOf(red, green, blue)
    val span = high - low
    if (span < 0.02f) return this
    return Color(
        red = ((red - low) / span).coerceIn(0f, 1f),
        green = ((green - low) / span).coerceIn(0f, 1f),
        blue = ((blue - low) / span).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

/** Same idea, pulled to a mid-oak rather than to pulp. */
private fun Color.timber(dark: Boolean): Color {
    val oak = if (dark) Color(0xFF4A3320) else Color(0xFFB07A45)
    return lerp(this, oak, 0.58f)
}
