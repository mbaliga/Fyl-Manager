package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. SelectImageOverlay's one genuinely new visual idea: a completed
// selection (lasso or wand) gets a semi-transparent tint layered over the base image so the user
// can see what they selected before choosing an action. The overlay bitmap's own pixels are
// already pinned by ImageSelectionRendererTest ("the preview overlay tints only the selected
// pixels"); this is the look of it actually stacked on top of an image via two Compose Image
// composables in the same Box, which that pixel test cannot show. The full SelectImageOverlay
// screen (Dialog + LaunchedEffect + real bitmap decode + drag/tap gestures) is not rendered here
// for the same reason AnnotateOverlay/ConvertImageFormatOverlay's own full screens aren't --
// established earlier this session as a real Robolectric-hang risk, not a proven-safe pattern.
// TactileOptionRow (tool switcher, tolerance presets) is already a proven, previously-rendered
// component and isn't repeated here either.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../SelectionPreviewOverlayRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*SelectionPreviewOverlayRender*" --offline
// Output lands in app/build/outputs/build11-shots/.

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.ImageSelectionRenderer
import io.github.mbaliga.fylz.data.SelectionMask
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@Ignore("Render tool, not a gate -- remove locally to rasterize the sheet; see the header comment.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w440dp-h500dp-480dpi")
class SelectionPreviewOverlayRender {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/outputs/build11-shots").apply { mkdirs() }

    private fun snap(name: String) {
        repeat(6) {
            Thread.sleep(100)
            compose.waitForIdle()
        }
        val decor = compose.activity.window.decorView
        check(decor.width > 0 && decor.height > 0) { "decor not laid out" }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        FileOutputStream(File(outDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sampleImage(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(0xFF, 0xE0, 0xB2))
        canvas.drawRect(size / 2f, 0f, size.toFloat(), size.toFloat(), Paint().apply { color = AndroidColor.rgb(0x81, 0xD4, 0xFA) })
        return bitmap
    }

    @Test
    fun render() {
        val source = sampleImage(200)
        val mask = SelectionMask.fromPath(Path().apply { addCircle(100f, 100f, 60f, Path.Direction.CW) }, 200, 200)

        compose.setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "SELECTION PREVIEW -- circular wand selection tinted over the base image",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888),
                        )
                        val tint = (0x66 shl 24) or (MaterialTheme.colorScheme.primary.toArgb() and 0x00FFFFFF)
                        val overlay = ImageSelectionRenderer.maskPreviewOverlay(mask, tint)
                        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                            Image(
                                bitmap = source.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                contentScale = ContentScale.Fit,
                            )
                            Image(
                                bitmap = overlay.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
        snap("selection-preview-overlay.png")
    }
}
