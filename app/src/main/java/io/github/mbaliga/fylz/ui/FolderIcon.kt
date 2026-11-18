package io.github.mbaliga.fylz.ui

import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mbaliga.fylz.ui.places.FolderIconSpec
import io.github.mbaliga.fylz.ui.places.FolderIconSpec.Slot

/** What one card in the folder shows. */
sealed interface FolderCardContent {
    /** A picture, cropped to the card. */
    data class Photo(val uri: Uri) : FolderCardContent

    /** A page: a coloured gradient with three text bars, for anything that has no picture. */
    data class Sheet(val from: Color, val to: Color) : FolderCardContent
}

/** Default page colours per slot, from the handoff's sample folders. */
fun sheetFor(slot: Slot): FolderCardContent.Sheet = when (slot) {
    Slot.A -> FolderCardContent.Sheet(Color(0xFF8E9BF0), Color(0xFF5B4FC4))
    Slot.B -> FolderCardContent.Sheet(Color(0xFF93C47D), Color(0xFF45818E))
    Slot.C -> FolderCardContent.Sheet(Color(0xFFF6B26B), Color(0xFFE06666))
}

/** The front plate's outline, scaled to whatever size it is drawn at. */
private object FrontPlateShape : Shape {
    private val nodes = PathParser().parsePathString(FolderIconSpec.FRONT_PATH)

    fun path(size: Size): Path = nodes.toPath().apply {
        transform(Matrix().apply { scale(size.width / FolderIconSpec.FRONT_W, size.height / FolderIconSpec.FRONT_H) })
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(path(size))
}

/**
 * The layered folder icon from the design handoff: a back plate, up to three recent-file cards,
 * and a frosted front plate with the cards faintly blurred through it.
 *
 * [cards] lists contents newest first (index 0 is card A, the one on top), at most three. With
 * none it is an empty folder. [fanned] raises the cards out of the front plate; the frosted copy of
 * the cards animates with the same pose so the blurred ghost never lags the crisp card.
 *
 * Everything is laid out in the handoff's 139 x 124 unit space and scaled to the width given.
 */
@Composable
fun FolderIcon(
    cards: List<FolderCardContent>,
    fanned: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val theme = FolderIconSpec.theme(dark)
    val shown = cards.take(3)
    val slots = FolderIconSpec.slots(shown.size)
    // Contents run newest-first; slots run bottom-to-top, so the lists are reversed against each other.
    val bottomToTop = shown.reversed()

    // Honour the system's "remove animations" setting: with the scale at zero the fan just snaps.
    val context = LocalContext.current
    val animationsOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val progress by animateFloatAsState(
        targetValue = if (fanned) 1f else 0f,
        animationSpec = if (animationsOff) snap() else spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "folder-fan",
    )

    BoxWithConstraints(modifier.aspectRatio(FolderIconSpec.CANVAS_W / FolderIconSpec.CANVAS_H)) {
        val unit: Dp = maxWidth / FolderIconSpec.CANVAS_W
        val unitPx = with(LocalDensity.current) { unit.toPx() }

        // Back plate.
        Box(
            Modifier
                .offset(unit * FolderIconSpec.BACK_X, unit * FolderIconSpec.BACK_Y)
                .size(unit * FolderIconSpec.BACK_W, unit * FolderIconSpec.BACK_H)
                .clip(RoundedCornerShape(unit * FolderIconSpec.BACK_RADIUS))
                .background(Color(theme.back)),
        )

        // The cards, sharp.
        CardLayer(bottomToTop, slots, shown.size, dark, unit, unitPx, yShiftUnits = 0f) { progress }

        // Frosted front plate. First the blurred copy of the cards, clipped to the plate.
        Box(
            Modifier
                .offset(0.dp, unit * FolderIconSpec.FRONT_Y)
                .size(unit * FolderIconSpec.FRONT_W, unit * FolderIconSpec.FRONT_H)
                .clip(FrontPlateShape),
        ) {
            // Blur needs API 31; below that the plate's 85% fill already hides most of the card.
            // The handoff gives a Gaussian standard deviation, but Modifier.blur takes a radius,
            // which the platform turns into sigma = 0.57735 * r + 0.5px. Invert that here.
            val blurModifier = if (Build.VERSION.SDK_INT >= 31) {
                val sigmaPx = FolderIconSpec.BLUR_STD_DEV * unitPx
                val radiusPx = ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0f)
                Modifier.blur(with(LocalDensity.current) { radiusPx.toDp() }, BlurredEdgeTreatment.Unbounded)
            } else {
                Modifier
            }
            Box(Modifier.fillMaxSize().then(blurModifier)) {
                CardLayer(bottomToTop, slots, shown.size, dark, unit, unitPx, yShiftUnits = -FolderIconSpec.FRONT_Y) { progress }
            }
        }

        // Then the plate itself: tinted fill, a one-pixel inner edge, and the two paper lines.
        Canvas(
            Modifier
                .offset(0.dp, unit * FolderIconSpec.FRONT_Y)
                .size(unit * FolderIconSpec.FRONT_W, unit * FolderIconSpec.FRONT_H),
        ) {
            val path = FrontPlateShape.path(size)
            val s = size.width / FolderIconSpec.FRONT_W
            drawPath(path, Color(theme.front).copy(alpha = theme.frontAlpha))
            // A 2-unit stroke clipped to the shape leaves exactly the inner 1 unit visible.
            clipPath(path) { drawPath(path, Color(theme.stroke), style = Stroke(width = 2f * s)) }
            fun line(x: Float, y: Float, w: Float, color: Color) =
                drawRoundRect(color, Offset(x * s, y * s), Size(w * s, 2f * s), CornerRadius(1f * s))
            val shadow = Color(theme.lineShadow).copy(alpha = theme.lineShadowAlpha)
            val ink = Color(theme.line)
            line(13f, 91.0011f, 114f, shadow); line(13f, 90.0011f, 114f, ink)
            line(14f, 96.0011f, 112f, shadow); line(14f, 95.0011f, 112f, ink)
        }
    }
}

/** The stack of cards at the current fan progress; drawn twice (sharp, then blurred under the plate). */
@Composable
private fun CardLayer(
    contents: List<FolderCardContent>,
    slots: List<Slot>,
    cardCount: Int,
    dark: Boolean,
    unit: Dp,
    unitPx: Float,
    yShiftUnits: Float,
    progress: () -> Float,
) {
    contents.forEachIndexed { index, content ->
        val slot = slots[index]
        Box(
            Modifier
                .requiredSize(unit * FolderIconSpec.CARD_W, unit * FolderIconSpec.CARD_H)
                .graphicsLayer {
                    // Pose is read here, in the layer block, so the animation never recomposes.
                    val pose = FolderIconSpec.pose(slot, cardCount, dark, progress())
                    translationX = pose.x * unitPx
                    translationY = (pose.y + yShiftUnits) * unitPx
                    rotationZ = pose.rotationDeg
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .clipToBounds(),
        ) {
            when (content) {
                is FolderCardContent.Photo -> AsyncImage(
                    model = content.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // requiredSize centres the 83-unit square in the 74.7-unit card, which puts it
                    // at exactly PHOTO_X (-4.151): the handoff's crop, with no extra offset.
                    modifier = Modifier.requiredSize(unit * FolderIconSpec.PHOTO_SIZE),
                )
                is FolderCardContent.Sheet -> {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(content.from, content.to))))
                    for ((y, w) in listOf(30f to 46f, 40f to 46f, 50f to 28f)) {
                        Box(
                            Modifier
                                .offset(unit * 14f, unit * y)
                                .size(unit * w, unit * 4f)
                                .clip(RoundedCornerShape(unit * 2f))
                                .background(Color.White.copy(alpha = 0.85f)),
                        )
                    }
                }
            }
        }
    }
}
