package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. Every FolderFinish on the real folder silhouette, at the two sizes
// folders actually draw at, in both schemes. A material is the one kind of code that cannot be
// reviewed by reading it -- "brushed aluminium" either looks like metal or it looks like a striped
// rectangle, and only the picture says which.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../FolderFinishSheetRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*FolderFinishSheetRender*" --offline
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.appearance.FolderFinish
import io.github.mbaliga.fylz.appearance.FolderPalette
import io.github.mbaliga.fylz.ui.components.FolderPlane
import io.github.mbaliga.fylz.ui.components.FolderSilhouetteShape
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
@Config(sdk = [35], qualifiers = "w620dp-h1560dp-480dpi")
class FolderFinishSheetRender {

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

    /** Grid thumb (56dp) beside the size a grid card's face really gets, so a grain can be judged at both. */
    @Composable
    private fun FinishRow(finish: FolderFinish, dark: Boolean) {
        val silhouette = FolderSilhouetteShape()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                finish.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (dark) Color(0xFFBBBBBB) else Color(0xFF555555),
                modifier = Modifier.width(84.dp),
            )
            TONES.forEach { slug ->
                val tone = FolderPalette.colorFor(slug, dark) ?: Color.Gray
                FolderPlane(finish, tone, silhouette, dark, Modifier.size(56.dp))
            }
            // One at grid-card size, to catch a grain that only reads at 56dp or only at 96dp.
            val wide = FolderPalette.colorFor("blue", dark) ?: Color.Gray
            FolderPlane(finish, wide, silhouette, dark, Modifier.size(96.dp))
        }
    }

    @Composable
    private fun Sheet(dark: Boolean) {
        Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF121216) else Color(0xFFF6F4F8))) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FolderFinish.entries.forEach { FinishRow(it, dark) }
            }
        }
    }

    @Test
    fun light() {
        compose.setContent { Sheet(dark = false) }
        snap("folder-finishes-light.png")
    }

    @Test
    fun dark() {
        compose.setContent { Sheet(dark = true) }
        snap("folder-finishes-dark.png")
    }

    private companion object {
        /** A warm, a cool and a neutral-ish tone -- enough to see whether a recipe survives a hue change. */
        val TONES = listOf("blue", "amber", "green", "red", "teal")
    }
}
