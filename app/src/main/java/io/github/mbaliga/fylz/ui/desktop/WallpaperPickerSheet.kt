package io.github.mbaliga.fylz.ui.desktop

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.wallpaper.ANIMALCULES_PLAY_STORE_URL
import io.github.mbaliga.fylz.wallpaper.FylzPondWallpaperService
import io.github.mbaliga.fylz.wallpaper.PondWaterRenderer
import io.github.mbaliga.fylz.wallpaper.WallpaperSpec
import kotlin.random.Random

/**
 * The desktop's wallpaper picker: None / solid swatches / gradient swatches / a user image (with
 * blur + dim tuning once one is picked) / the pond-water live wallpaper, plus its two extra
 * actions ("Set as phone wallpaper", "Get Animalcules"). Self-contained -- owns its own
 * `OpenDocument` launcher and the persistable-read-grant call, per the image-picking pattern at
 * `FylzV1App.kt:421-432` adapted to a single read-only document rather than a tree.
 *
 * [onPick] is called with a brand new [WallpaperSpec] on every choice, including the blur/dim
 * sliders on an already-picked [WallpaperSpec.Image] -- the caller is expected to persist it
 * (typically straight into `WallpaperPreferences.setSpec`) and feed the persisted value back in
 * as [current] on the next composition, the same controlled-component shape the rest of this
 * app's sheets use.
 */
@Composable
fun WallpaperPickerSheet(current: WallpaperSpec, onPick: (WallpaperSpec) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val previousImage = current as? WallpaperSpec.Image
            onPick(WallpaperSpec.Image(uri, blur = previousImage?.blur ?: false, dim = previousImage?.dim ?: 0.2f))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.wallpaper_picker_title), style = MaterialTheme.typography.titleMedium)

            WallpaperOptionRow(
                selected = current is WallpaperSpec.None,
                label = stringResource(R.string.wallpaper_option_none),
                onClick = { onPick(WallpaperSpec.None) },
            ) { NoneSwatch() }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallpaper_section_solid), style = MaterialTheme.typography.labelLarge)
                SolidSwatchRow(current = current, onPick = { slug -> onPick(WallpaperSpec.Solid(slug)) })
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallpaper_section_gradient), style = MaterialTheme.typography.labelLarge)
                GradientSwatchRow(current = current, onPick = { slug -> onPick(WallpaperSpec.Gradient(slug)) })
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallpaper_section_image), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                    Text(stringResource(R.string.wallpaper_choose_image))
                }
                if (current is WallpaperSpec.Image) {
                    ImageTuning(
                        spec = current,
                        onBlurChanged = { blur -> onPick(current.copy(blur = blur)) },
                        onDimChanged = { dim -> onPick(current.copy(dim = dim)) },
                    )
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallpaper_section_pond), style = MaterialTheme.typography.labelLarge)
                WallpaperOptionRow(
                    selected = current is WallpaperSpec.PondWater,
                    label = stringResource(R.string.wallpaper_option_pond),
                    onClick = { onPick(WallpaperSpec.PondWater) },
                ) { PondWaterSwatch() }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, FylzPondWallpaperService::class.java),
                                ),
                            )
                        }
                    }) {
                        Text(stringResource(R.string.wallpaper_set_as_phone_wallpaper))
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ANIMALCULES_PLAY_STORE_URL)))
                        }
                    }) {
                        Text(stringResource(R.string.wallpaper_get_animalcules))
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperOptionRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 2.dp))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun NoneSwatch() {
    Box(
        Modifier
            .size(SWATCH_SIZE)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    ) {}
}

@Composable
private fun SolidSwatchRow(current: WallpaperSpec, onPick: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SOLID_SLUGS.forEach { slug ->
            val colors = SOLID_PALETTE.getValue(slug)
            val color = if (dark) colors.second else colors.first
            val selected = current is WallpaperSpec.Solid && current.slug == slug
            ColorSwatch(color = color, selected = selected, onClick = { onPick(slug) })
        }
    }
}

@Composable
private fun GradientSwatchRow(current: WallpaperSpec, onPick: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GRADIENT_SLUGS.forEach { slug ->
            val (top, bottom) = gradientStops(slug, dark)
            val selected = current is WallpaperSpec.Gradient && current.slug == slug
            ColorSwatch(brush = Brush.verticalGradient(listOf(top, bottom)), selected = selected, onClick = { onPick(slug) })
        }
    }
}

@Composable
private fun ColorSwatch(
    selected: Boolean,
    onClick: () -> Unit,
    color: Color? = null,
    brush: Brush? = null,
) {
    val ringColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        Modifier
            .size(SWATCH_SIZE)
            .clip(CircleShape)
            .border(2.dp, ringColor, CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .let { if (brush != null) it.background(brush) else it.background(color ?: Color.Transparent) }
            .clickable(onClick = onClick),
    ) {}
}

@Composable
private fun ImageTuning(spec: WallpaperSpec.Image, onBlurChanged: (Boolean) -> Unit, onDimChanged: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.wallpaper_image_blur_label), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = spec.blur, onCheckedChange = onBlurChanged)
        }
        Text(stringResource(R.string.wallpaper_image_dim_label), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = spec.dim.coerceIn(0f, 0.6f),
            onValueChange = onDimChanged,
            valueRange = 0f..0.6f,
        )
    }
}

/**
 * A tiny static [PondWaterRenderer] preview -- seeded, resized once, and stepped forward a few
 * seconds so the field looks settled rather than freshly spawned, then never stepped again. It
 * intentionally never redraws after that first frame: this is a swatch, not the live wallpaper.
 */
@Composable
private fun PondWaterSwatch() {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current.density
    val renderer = remember(dark) { PondWaterRenderer(random = Random(POND_SWATCH_SEED), isDark = dark) }
    var settled by remember(dark) { mutableStateOf(false) }

    Box(
        Modifier
            .size(SWATCH_SIZE)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Canvas(
            Modifier
                .size(SWATCH_SIZE)
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0 && !settled) {
                        renderer.resize(size.width, size.height, density)
                        renderer.step(POND_SWATCH_SETTLE_SECONDS)
                        settled = true
                    }
                },
        ) {
            renderer.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

private val SWATCH_SIZE = 40.dp
private const val POND_SWATCH_SEED = 20260820L
private const val POND_SWATCH_SETTLE_SECONDS = 1.5f
