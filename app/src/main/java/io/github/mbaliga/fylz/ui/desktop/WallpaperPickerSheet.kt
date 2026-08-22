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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileSlider
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.hairline
import io.github.mbaliga.fylz.ui.theme.microLabel
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
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(stringResource(R.string.wallpaper_picker_title), style = MaterialTheme.typography.titleMedium)

            WallpaperLivePreview(current)

            WallpaperOptionRow(
                label = stringResource(R.string.wallpaper_option_none),
                onClick = { onPick(WallpaperSpec.None) },
            ) { NoneSwatch(selected = current is WallpaperSpec.None) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallpaper_section_solid), style = microLabel())
                SolidSwatchRow(current = current, onPick = { slug -> onPick(WallpaperSpec.Solid(slug)) })
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallpaper_section_gradient), style = microLabel())
                GradientSwatchRow(current = current, onPick = { slug -> onPick(WallpaperSpec.Gradient(slug)) })
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wallpaper_section_image), style = microLabel())
                TactileButton(
                    text = stringResource(R.string.wallpaper_choose_image),
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    style = TactileButtonStyle.SECONDARY,
                    fillWidth = true,
                )
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
                Text(stringResource(R.string.wallpaper_section_pond), style = microLabel())
                WallpaperOptionRow(
                    label = stringResource(R.string.wallpaper_option_pond),
                    onClick = { onPick(WallpaperSpec.PondWater) },
                ) { PondWaterSwatch(selected = current is WallpaperSpec.PondWater) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TactileButton(
                        text = stringResource(R.string.wallpaper_set_as_phone_wallpaper),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                        ComponentName(context, FylzPondWallpaperService::class.java),
                                    ),
                                )
                            }
                        },
                        style = TactileButtonStyle.SECONDARY,
                    )
                    TactileButton(
                        text = stringResource(R.string.wallpaper_get_animalcules),
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ANIMALCULES_PLAY_STORE_URL)))
                            }
                        },
                        style = TactileButtonStyle.SECONDARY,
                    )
                }
            }
        }
    }
}

/**
 * A live-scale preview of [spec] at the top of the sheet, so a pick shows before it's committed.
 * Every non-pond variant just delegates straight to [WallpaperLayer] -- none of those draw a
 * per-frame loop, so there is no extra cost to rendering the real thing here. [WallpaperSpec
 * .PondWater] is the one exception: it routes to [StaticPondPreview] instead of [WallpaperLayer]
 * so this decorative swap-preview never spins up the live wallpaper's Choreographer loop (the
 * spec calls this out explicitly -- "static frame for pond").
 */
@Composable
private fun WallpaperLivePreview(spec: WallpaperSpec) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(FylzGeometry.RadiusXl))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, hairline(), RoundedCornerShape(FylzGeometry.RadiusXl)),
    ) {
        if (spec is WallpaperSpec.PondWater) {
            StaticPondPreview(Modifier.fillMaxSize())
        } else {
            WallpaperLayer(spec, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun WallpaperOptionRow(
    label: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FylzGeometry.RadiusLg))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The 40dp ring-and-clip treatment every swatch shares: a 2dp primary selection ring around
 * content clipped to a circle. [onClick] is `null` for a swatch that only decorates an
 * already-clickable row ([NoneSwatch], [PondWaterSwatch] inside [WallpaperOptionRow]) and
 * non-null for a swatch that is its own tap target ([ColorSwatch] inside a swatch row), in which
 * case the ring is centred inside a [SWATCH_TOUCH] cell that carries the click -- circle-clipped,
 * so the ripple still stays round rather than spilling into square corners.
 *
 * [label] names the swatch for a screen reader. A swatch is a bare disc of colour: without it the
 * only thing distinguishing "ink" from "moss" is the colour itself, which is exactly the
 * colour-alone state `docs/DESIGN.md` forbids. Decorative rings (those with no [onClick]) take
 * their name from the row they sit in and pass none.
 */
@Composable
private fun SwatchRing(
    selected: Boolean,
    onClick: (() -> Unit)? = null,
    label: String? = null,
    content: @Composable () -> Unit,
) {
    val ringColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val ring = @Composable {
        Box(
            Modifier
                .size(SWATCH_SIZE)
                .clip(CircleShape)
                .border(2.dp, ringColor, CircleShape)
                .padding(2.dp)
                .clip(CircleShape),
        ) { content() }
    }
    if (onClick == null) {
        // A decoration inside an already-clickable row: it must NOT be a target of its own, and
        // padding it out to the floor would only put dead space between the row's own leading
        // edge and its label.
        ring()
    } else {
        // A swatch that IS its own target gets the floor around it, without growing the swatch:
        // [SWATCH_SIZE] is the visual the reference frames draw and 40dp is under the 48dp
        // minimum, so the ring keeps its size and the CELL around it clears the floor. The
        // circular clip is what keeps the press ripple round rather than a square behind a
        // round swatch -- the same reason the clickable used to sit after both clips.
        val swatchSelected = selected
        Box(
            Modifier
                .size(SWATCH_TOUCH)
                .clip(CircleShape)
                .clickable(onClick = onClick, onClickLabel = label, role = Role.Button)
                .semantics {
                    label?.let { contentDescription = it }
                    this.selected = swatchSelected
                },
            contentAlignment = Alignment.Center,
        ) { ring() }
    }
}

@Composable
private fun NoneSwatch(selected: Boolean) {
    SwatchRing(selected = selected) {
        Box(Modifier.fillMaxSize().border(1.dp, hairline(), CircleShape))
    }
}

@Composable
private fun SolidSwatchRow(current: WallpaperSpec, onPick: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    // 2dp, not 12: the swatches now sit in 48dp cells (see [SwatchRing]), so most of the old
    // gap has moved inside the cell. Six cells on a 2dp gap span 298dp -- narrower than the
    // 300dp this row already occupied -- and the visible gap between two rings only closes from
    // 12dp to 10dp.
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SOLID_SLUGS.forEach { slug ->
            val colors = SOLID_PALETTE.getValue(slug)
            val color = if (dark) colors.second else colors.first
            val selected = current is WallpaperSpec.Solid && current.slug == slug
            ColorSwatch(color = color, selected = selected, onClick = { onPick(slug) }, label = swatchLabel(slug))
        }
    }
}

@Composable
private fun GradientSwatchRow(current: WallpaperSpec, onPick: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    // Same 48dp cells as the solid row above, and the same 2dp gap so the two rows still read
    // as one grid rather than two differently-pitched ones.
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        GRADIENT_SLUGS.forEach { slug ->
            val (top, bottom) = gradientStops(slug, dark)
            val selected = current is WallpaperSpec.Gradient && current.slug == slug
            ColorSwatch(
                brush = Brush.verticalGradient(listOf(top, bottom)),
                selected = selected,
                onClick = { onPick(slug) },
                label = swatchLabel(slug),
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    color: Color? = null,
    brush: Brush? = null,
) {
    SwatchRing(selected = selected, onClick = onClick, label = label) {
        Box(
            Modifier
                .fillMaxSize()
                .let { if (brush != null) it.background(brush) else it.background(color ?: Color.Transparent) },
        )
    }
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
            TactileSwitch(checked = spec.blur, onCheckedChange = onBlurChanged)
        }
        Text(stringResource(R.string.wallpaper_image_dim_label), style = MaterialTheme.typography.bodyMedium)
        TactileSlider(
            value = spec.dim.coerceIn(0f, 0.6f),
            onValueChange = onDimChanged,
            valueRange = 0f..0.6f,
        )
    }
}

/**
 * A tiny static [PondWaterRenderer] frame -- seeded, resized once, and stepped forward a few
 * seconds so the field looks settled rather than freshly spawned, then never stepped again.
 * Shared by [PondWaterSwatch] and [StaticPondPreview]: both want a settle-then-freeze pond frame,
 * just at different sizes and shapes, and neither is the live wallpaper -- this never redraws
 * after that first frame, so there is no per-frame loop hiding in either of them.
 */
@Composable
private fun SettledPondCanvas(seed: Long, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current.density
    val renderer = remember(dark) { PondWaterRenderer(random = Random(seed), isDark = dark) }
    var settled by remember(dark) { mutableStateOf(false) }

    Canvas(
        modifier.onSizeChanged { size ->
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

@Composable
private fun PondWaterSwatch(selected: Boolean) {
    SwatchRing(selected = selected) {
        SettledPondCanvas(POND_SWATCH_SEED, Modifier.fillMaxSize())
    }
}

/** The pond variant's stand-in inside [WallpaperLivePreview] -- see [SettledPondCanvas]. */
@Composable
private fun StaticPondPreview(modifier: Modifier = Modifier) {
    SettledPondCanvas(POND_PREVIEW_SEED, modifier)
}

/**
 * A swatch's spoken name, straight off the slug the picker already stores -- "ink" reads as
 * "Ink". Deliberately not a translated string table: the slugs themselves are the persisted,
 * user-invisible ids in [WallpaperSpec], and inventing display names for them here would be a
 * second list to keep in step with [SOLID_SLUGS]/[GRADIENT_SLUGS] for no gain a reader would
 * notice. Naming the swatch at all is the point; naming it prettily is not.
 */
private fun swatchLabel(slug: String): String = slug.replaceFirstChar { it.uppercase() }

private val SWATCH_SIZE = 40.dp

/** The touch cell a self-clicking swatch sits in -- the floor, not the swatch's own size. */
private val SWATCH_TOUCH = 48.dp
private const val POND_SWATCH_SEED = 20260820L
private const val POND_PREVIEW_SEED = 20260821L
private const val POND_SWATCH_SETTLE_SECONDS = 1.5f
