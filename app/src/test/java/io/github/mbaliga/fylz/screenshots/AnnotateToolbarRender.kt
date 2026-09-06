package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. AnnotateOverlay's bottom toolbar -- color swatches, the
// thin/thick TactileOptionRow pair, and the Save/Save As button row. The overlay itself is not
// rendered here: it decodes a real bitmap through a LaunchedEffect and drives a full-screen
// Dialog + BoxWithConstraints + drag-gesture canvas, none of which this harness needs to touch to
// check the one thing that was actually hand-built and unproven -- ColorSwatch's own selection
// ring -- everything else in the toolbar (TactileOptionRow, TactileButton) is already a proven,
// previously-rendered component.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../AnnotateToolbarRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*AnnotateToolbarRender*" --offline
// Output lands in app/build/outputs/build11-shots/.

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.ColorSwatch
import io.github.mbaliga.fylz.ui.STROKE_WIDTHS
import io.github.mbaliga.fylz.ui.SWATCHES
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
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
class AnnotateToolbarRender {

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

    @Test
    fun render() {
        compose.setContent {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "ANNOTATE TOOLBAR -- red selected, thin selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888),
                        )
                        var color by remember { mutableStateOf(SWATCHES.first()) }
                        var width by remember { mutableStateOf(STROKE_WIDTHS.first().first) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SWATCHES.forEach { swatch ->
                                ColorSwatch(swatch, selected = swatch == color, onClick = { color = swatch })
                            }
                        }
                        STROKE_WIDTHS.forEach { (value, label) ->
                            TactileOptionRow(text = label, selected = width == value, onClick = { width = value })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TactileButton(text = "Save", onClick = {}, fillWidth = true, modifier = Modifier.weight(1f))
                            TactileButton(
                                text = "Save as…",
                                onClick = {},
                                style = TactileButtonStyle.SECONDARY,
                                fillWidth = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        snap("annotate-toolbar.png")
    }
}
