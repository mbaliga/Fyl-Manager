package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. The new "which archive format" choice ArchivePasswordDialog's
// CREATE branch gained -- two TactileOptionRows, ZIP vs 7z. The dialog itself is not rendered
// here: it carries a live password TextField whose cursor blink hangs Robolectric's compose-idle
// wait for reasons unrelated to layout (the same issue ArchivePasswordAndRemoteRootRender's own
// header documents for ArchiveUnlockDialog), and TactileOptionRow itself is already a proven,
// previously-rendered component (SettingsOverlay's chrome-scale picker uses the identical
// pattern) -- what is actually new here is just the two rows' text and selection state.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../ArchiveFormatPickerRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*ArchiveFormatPickerRender*" --offline
// Output lands in app/build/outputs/build11-shots/.

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.mbaliga.fylz.data.CreatableArchiveFormat
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
class ArchiveFormatPickerRender {

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
                            "FORMAT PICKER -- ZIP selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888),
                        )
                        var format by remember { mutableStateOf(CreatableArchiveFormat.ZIP) }
                        CreatableArchiveFormat.entries.forEach { candidate ->
                            TactileOptionRow(
                                text = candidate.label,
                                selected = format == candidate,
                                onClick = { format = candidate },
                            )
                        }
                    }
                }
            }
        }
        snap("archive-format-picker.png")
    }
}
