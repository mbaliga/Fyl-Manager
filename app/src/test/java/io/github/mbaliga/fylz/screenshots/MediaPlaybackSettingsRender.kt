package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. The playback-settings upgrade's one genuinely new visual idea: a
// small floating icon button, on a translucent dark circle, sitting over live video content --
// nothing else it adds is new (PlaybackSettingsSheet's own body is entirely TactileOptionRow /
// TactileSwitch / TactileSlider rows, all already proven and rendered elsewhere, per the same
// "only render what's new" convention ConvertImageFormatOverlay's absence of a harness follows).
// The full MediaFilePreview composable (real ExoPlayer + AndroidView + Dialog sheet) is not
// rendered here for the same reason AnnotateOverlay/SelectImageOverlay's own full screens
// aren't -- a real Robolectric-hang risk established earlier in this project, not a proven-safe
// pattern -- so this isolates just the floating button's own contrast/placement over a sample
// frame, plus the equalizer band-slider stack's label-above-slider layout.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../MediaPlaybackSettingsRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*MediaPlaybackSettingsRender*" --offline
// Output lands in app/build/outputs/build11-shots/.

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.tactile.TactileSlider
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
@Config(sdk = [35], qualifiers = "w440dp-h760dp-480dpi")
class MediaPlaybackSettingsRender {

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
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "PLAYBACK SETTINGS -- floating gear over content, then the equalizer band stack",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF888888),
                    )
                    // A mid-tone frame stands in for a real video/poster frame -- the button must
                    // read against light and dark content alike, not just a convenient dark test bg.
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF9E9E9E))) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Playback settings", tint = Color.White)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("60 Hz" to 0.85f, "230 Hz" to 0.6f, "910 Hz" to 0.5f, "3.6 kHz" to 0.35f, "14 kHz" to 0.5f)
                            .forEach { (label, fraction) ->
                                Text(label, style = MaterialTheme.typography.labelSmall)
                                var value by remember { mutableFloatStateOf(fraction) }
                                TactileSlider(value = value, onValueChange = { value = it })
                            }
                    }
                }
            }
        }
        snap("media-playback-settings.png")
    }
}
