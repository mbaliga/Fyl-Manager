package io.github.mbaliga.fylz.ui.landing

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.aarso.cellshell.SpatialMotion
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.components.StackCard
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the hero holds before it scrolls itself away on its own. */
private const val SPLASH_HOLD_MILLIS = 2200L

/**
 * The front folder (and its content peek) travel at this fraction of everything else's exit
 * distance -- moving less far in the same settle means it visually lags behind the back layer,
 * which is what reads as parallax depth rather than one flat card sliding off together.
 */
private const val FRONT_LAYER_LAG = 0.88f

/** An upward drag past this distance skips the hero early, same as a tap. */
private val SKIP_DRAG_DISTANCE = 48.dp

private const val FRONT_INDEX = 2
private val FAN_TILTS = floatArrayOf(-7f, 5f, -3f, 6f, -4f)
private val FAN_DX = listOf((-118).dp, (-58).dp, 0.dp, 58.dp, 118.dp)
private val FAN_DY = listOf(6.dp, (-6).dp, (-18).dp, (-6).dp, 6.dp)
private val FAN_Z = listOf(1f, 2f, 3f, 2f, 1f)
private val BACK_FOLDER_SIZE = DpSize(126.dp, 104.dp)
private val FRONT_FOLDER_SIZE = DpSize(156.dp, 128.dp)
private val FRONT_PEEK_HEADROOM = 60.dp

private const val MAX_PEEK_THUMBS = 3
private val PEEK_CARD_SIZE = 44.dp
private val PEEK_TILTS = floatArrayOf(-6f, 5f, -3f)

/**
 * The cold-start hero: a fan of drawn folders behind the "Fylz" wordmark, gone again a couple of
 * seconds later. Mounted by the composition root above everything else -- rooms, bulges, the
 * drag layer, QuickLook -- so it must own the whole screen and take no dependency on anything
 * beneath it beyond [peekEntries].
 *
 * Enters exactly as composed, no animation in: the first frame IS the held frame. After
 * [SPLASH_HOLD_MILLIS], or immediately on a tap or an upward drag past [SKIP_DRAG_DISTANCE],
 * the art slides up and out on the house settle spec ([SpatialMotion.settleSpec]); [onFinished]
 * fires once that animation actually completes, never before, so the caller's own removal from
 * composition never clips the exit mid-flight.
 */
@Composable
fun LandingSplash(
    peekEntries: List<FileEntry> = emptyList(),
    onFinished: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var containerHeightPx by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    var dismissStarted by remember { mutableStateOf(false) }
    var finishedFired by remember { mutableStateOf(false) }

    // Idempotent on purpose: the 2200ms timeout and the tap/drag handlers below all call this,
    // and a tap that lands a beat before the timeout would otherwise fire two competing settles.
    fun startDismiss() {
        if (dismissStarted) return
        dismissStarted = true
        scope.launch {
            progress.animateTo(1f, animationSpec = SpatialMotion.settleSpec)
            if (!finishedFired) {
                finishedFired = true
                onFinished()
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(SPLASH_HOLD_MILLIS)
        startDismiss()
    }

    val skipDragPx = with(density) { SKIP_DRAG_DISTANCE.toPx() }
    // 15% beyond the measured height so the slower, lagging front layer below also fully clears
    // the screen instead of parking just short of the top edge once the settle completes.
    val fullTravelPx = containerHeightPx * 1.15f
    val backOffsetY = (-progress.value * fullTravelPx).roundToInt()
    val frontOffsetY = (-progress.value * fullTravelPx * FRONT_LAYER_LAG).roundToInt()

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { containerHeightPx = it.height }
                .clickable(onClickLabel = "Skip", role = Role.Button) { startDismiss() }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { dragged = 0f },
                        onDragCancel = { dragged = 0f },
                    ) { change, delta ->
                        dragged += delta
                        if (dragged <= -skipDragPx) startDismiss()
                        change.consume()
                    }
                },
        ) {
            // Back layer: the wordmark and every folder but the front one, at the full rate.
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, backOffsetY) },
            ) {
                Wordmark(Modifier.align(Alignment.TopCenter).padding(top = 116.dp))
                BackFan(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            }

            // Front layer: the hero folder and its content peek, lagging behind the back layer.
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, frontOffsetY) },
            ) {
                FrontFolderArt(
                    peekEntries = peekEntries,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                )
            }
        }
    }
}

/**
 * "Fylz" in the bundled Hyle display face. Local to this file on purpose -- the theme's own
 * typography stays on the default sans everywhere else, so the wordmark is a one-time flourish,
 * not a font swap the rest of the app inherits.
 */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    val hyleDecoPro = remember { FontFamily(Font(R.font.hyle_deco_pro_bold, FontWeight.Bold)) }
    Text(
        "Fylz",
        style = MaterialTheme.typography.displayLarge.copy(fontFamily = hyleDecoPro, fontSize = 64.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** Every fanned folder except the front one, which [FrontFolderArt] draws so it can carry the peek. */
@Composable
private fun BackFan(modifier: Modifier = Modifier) {
    val tones = listOf(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.primaryContainer, // index FRONT_INDEX -- unused here
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    Box(modifier.fillMaxWidth().height(BACK_FOLDER_SIZE.height)) {
        FAN_DX.indices.forEach { index ->
            if (index == FRONT_INDEX) return@forEach
            FanFolder(
                tone = tones[index],
                tilt = FAN_TILTS[index],
                width = BACK_FOLDER_SIZE.width,
                height = BACK_FOLDER_SIZE.height,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = FAN_DX[index], y = FAN_DY[index])
                    .zIndex(FAN_Z[index]),
            )
        }
    }
}

/**
 * The hero folder: the same [FanFolder] as the back fan, but with a blank sheet peeking tall
 * from behind it (the reference's single sheet of paper standing proud of the stack) and, when
 * [peekEntries] arrived in time, up to three real thumbnails tucked into its mouth.
 */
@Composable
private fun FrontFolderArt(peekEntries: List<FileEntry>, modifier: Modifier = Modifier) {
    Box(modifier.size(width = FRONT_FOLDER_SIZE.width, height = FRONT_FOLDER_SIZE.height + FRONT_PEEK_HEADROOM)) {
        // Drawn first so the folder's own tab (below) covers its base, leaving only the top of
        // the sheet poking out above the fold -- the same behind-the-pane trick FolderFace's
        // DocumentSheet uses, just tall enough here to clear a whole extra folder height.
        Surface(
            color = MaterialTheme.colorScheme.surfaceBright,
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 66.dp, height = 130.dp)
                .graphicsLayer { rotationZ = -4f },
        ) {}
        FanFolder(
            tone = MaterialTheme.colorScheme.primaryContainer,
            tilt = FAN_TILTS[FRONT_INDEX],
            width = FRONT_FOLDER_SIZE.width,
            height = FRONT_FOLDER_SIZE.height,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (peekEntries.isNotEmpty()) {
            ContentPeek(
                entries = peekEntries,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = FRONT_PEEK_HEADROOM - 34.dp),
            )
        }
    }
}

/**
 * One drawn folder -- a rounded-rect body with a smaller rounded-rect tab set into its top-left
 * corner, the plainest possible reading of "folder" with no icon asset. [FolderFace] draws the
 * same two-[Surface] vocabulary for the in-browser card, so a folder here and a folder three
 * taps later read as the same object wearing different light.
 */
@Composable
private fun FanFolder(tone: Color, tilt: Float, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = width, height = height)
            .graphicsLayer { rotationZ = tilt },
    ) {
        Surface(
            color = tone,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 10.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(width = width * 0.44f, height = height * 0.18f),
        ) {}
        Surface(
            color = tone,
            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height * 0.85f),
        ) {}
    }
}

/**
 * Up to three real thumbnails peeking out of the hero folder's mouth, the iPad-Photos stacked
 * print look: each card six/four dp further out than the last, alternating tilt, descending
 * zIndex so the first stays on top -- the same offsets the tray and drag stacks use, so the
 * hero's stack and every other stack in the app read as one family.
 */
@Composable
private fun ContentPeek(entries: List<FileEntry>, modifier: Modifier = Modifier) {
    val shown = entries.take(MAX_PEEK_THUMBS)
    Box(modifier) {
        shown.forEachIndexed { index, entry ->
            StackCard(
                entry = entry,
                fallbackName = entry.name,
                kind = entry.kind,
                size = PEEK_CARD_SIZE,
                rotation = PEEK_TILTS[index % PEEK_TILTS.size],
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = (index * 6).dp, y = (index * 4).dp)
                    .zIndex((shown.size - index).toFloat()),
            )
        }
    }
}
