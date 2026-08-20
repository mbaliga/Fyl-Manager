package io.github.mbaliga.fylz.ui.desktop

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import kotlinx.coroutines.isActive
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.wallpaper.ANIMALCULES_PLAY_STORE_URL
import io.github.mbaliga.fylz.wallpaper.PondWaterRenderer
import io.github.mbaliga.fylz.wallpaper.WallpaperSpec

/**
 * Draws [spec] full-bleed behind [modifier]'s bounds. [WallpaperSpec.None] draws nothing (the
 * theme's own background shows through); every other variant fills the given bounds.
 */
@Composable
fun WallpaperLayer(spec: WallpaperSpec, modifier: Modifier = Modifier) {
    when (spec) {
        is WallpaperSpec.None -> Unit
        is WallpaperSpec.Solid -> Box(
            modifier
                .fillMaxSize()
                .background(solidColor(spec.slug, isSystemInDarkTheme())),
        ) {}
        is WallpaperSpec.Gradient -> Box(
            modifier
                .fillMaxSize()
                .background(gradientBrush(spec.slug, isSystemInDarkTheme())),
        ) {}
        is WallpaperSpec.Image -> ImageWallpaper(spec, modifier)
        is WallpaperSpec.PondWater -> PondWaterWallpaper(modifier)
    }
}

@Composable
private fun ImageWallpaper(spec: WallpaperSpec.Image, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        AsyncImage(
            model = spec.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .let { if (spec.blur) it.blur(WALLPAPER_IMAGE_BLUR_RADIUS) else it },
        )
        val dim = spec.dim.coerceIn(0f, 0.6f)
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim))) {}
        }
    }
}

@Composable
private fun PondWaterWallpaper(modifier: Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()

    val renderer = remember { PondWaterRenderer(isDark = dark) }
    LaunchedEffect(dark) { renderer.setDark(dark) }

    // Settings.Global.ANIMATOR_DURATION_SCALE == 0f is the system "Remove animations" toggle --
    // render one static frame instead of animating when it's on.
    val reducedMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) == 0f
    }

    var hasSize by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(hasSize, lifecycleState, reducedMotion) {
        if (!hasSize) return@LaunchedEffect
        if (reducedMotion) {
            renderer.step(0f)
            tick++
            return@LaunchedEffect
        }
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                lastNanos = now
                renderer.step(dt)
                tick++
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        renderer.resize(size.width, size.height, density)
                        hasSize = true
                    }
                }
                .pointerInput(Unit) {
                    // detectTapGestures' trailing-lambda slot is onDoubleTap, not onTap -- onTap
                    // must be passed by name or a bare `{ }` here would silently wire up the
                    // wrong gesture.
                    detectTapGestures(
                        onTap = { offset ->
                            renderer.poke(offset.x, offset.y)
                            tick++
                        },
                    )
                },
        ) {
            // Reading `tick` here -- state bumped once per animation frame above -- is what
            // turns this into a per-frame redraw: Compose's draw phase observes state reads the
            // same way composition and layout do, so incrementing it schedules exactly this redraw.
            tick.let { }
            renderer.draw(drawContext.canvas.nativeCanvas)
        }
        PondAttributionChip(Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

@Composable
private fun PondAttributionChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ANIMALCULES_PLAY_STORE_URL)))
            }
        },
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.35f),
    ) {
        Text(
            text = stringResource(R.string.wallpaper_pond_attribution),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private val WALLPAPER_IMAGE_BLUR_RADIUS = 24.dp

/**
 * Fixed light/dark colour pair per solid slug. Slugs: ink, moss, clay, sky, plum, sand -- an
 * unrecognised slug (a future build's) resolves to fully transparent rather than crashing on a
 * missing map entry, which reads the same as [WallpaperSpec.None] visually.
 */
private fun solidColor(slug: String, dark: Boolean): Color {
    val pair = SOLID_PALETTE[slug] ?: TRANSPARENT_PAIR
    return if (dark) pair.second else pair.first
}

private fun gradientBrush(slug: String, dark: Boolean): Brush {
    val (top, bottom) = gradientStops(slug, dark)
    return Brush.verticalGradient(listOf(top, bottom))
}

/** (top, bottom) for [slug] in the given theme -- shared with [WallpaperPickerSheet]'s swatch
 *  previews so the picker and the actual render can never drift onto different colours. */
internal fun gradientStops(slug: String, dark: Boolean): Pair<Color, Color> {
    val palette = if (dark) GRADIENT_PALETTE_DARK else GRADIENT_PALETTE_LIGHT
    return palette[slug] ?: TRANSPARENT_PAIR
}

private val TRANSPARENT_PAIR = Color.Transparent to Color.Transparent

/** Fixed solid-slug order the picker's swatch row renders in. */
internal val SOLID_SLUGS = listOf("ink", "moss", "clay", "sky", "plum", "sand")

/** Fixed gradient-slug order the picker's swatch row renders in. */
internal val GRADIENT_SLUGS = listOf("dawn", "dusk", "depth")

/** Each value is (light, dark). Shared with [WallpaperPickerSheet]'s swatch previews. */
internal val SOLID_PALETTE: Map<String, Pair<Color, Color>> = mapOf(
    "ink" to (Color(0xFFE7EAF0) to Color(0xFF1B222E)),
    "moss" to (Color(0xFFE3ECDD) to Color(0xFF1E2B1E)),
    "clay" to (Color(0xFFF1E1D4) to Color(0xFF2E211A)),
    "sky" to (Color(0xFFE1EEF7) to Color(0xFF16232E)),
    "plum" to (Color(0xFFEFE0EC) to Color(0xFF291A26)),
    "sand" to (Color(0xFFF6ECDA) to Color(0xFF2A2519)),
)

/** Each value is (top, bottom) for the light theme. */
private val GRADIENT_PALETTE_LIGHT: Map<String, Pair<Color, Color>> = mapOf(
    "dawn" to (Color(0xFFFCE8D6) to Color(0xFFF6C9D0)),
    "dusk" to (Color(0xFFE7D7F2) to Color(0xFFC9D3F1)),
    "depth" to (Color(0xFFD6E9EC) to Color(0xFFAFC9D6)),
)

/** Each value is (top, bottom) for the dark theme. */
private val GRADIENT_PALETTE_DARK: Map<String, Pair<Color, Color>> = mapOf(
    "dawn" to (Color(0xFF2B1E22) to Color(0xFF3A2430)),
    "dusk" to (Color(0xFF1E1B33) to Color(0xFF14172B)),
    "depth" to (Color(0xFF0F1E24) to Color(0xFF071219)),
)
