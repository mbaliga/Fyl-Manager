package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. TabBand's new leading chip -- up-chevron at rest, red recycle
// button while a selection is live -- at 3x so the 48dp circle and its glyph can be read against
// the 47dp tab strip it sits beside, rather than guessed at from a phone-sized frame.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../TabBandRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*TabBandRender*" --offline
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import io.github.mbaliga.fylz.ui.chrome.ChromeScale
import io.github.mbaliga.fylz.ui.chrome.LocalChromeScale
import io.github.mbaliga.fylz.ui.chrome.TabBand
import io.github.mbaliga.fylz.ui.chrome.TabBandItem
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@Ignore("Render tool, not a gate -- remove locally to rasterize the band; see the header comment.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w440dp-h700dp-480dpi")
class TabBandRender {

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

    private val twoTabs = listOf(
        TabBandItem("downloads", "Downloads"),
        TabBandItem("documents", "Documents"),
    )

    @Composable
    private fun Caption(text: String) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun Scene(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
            content()
        }
    }

    @Test
    fun render() {
        // One composition, one setContent call -- the rule only tolerates one per test -- so all
        // three states stack in a single scrollable-free column instead of three separate frames.
        compose.setContent {
            Scene {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    // Frame 1: at rest, one folder short of home -- the up-chevron shows.
                    Caption("REST -- up-chevron, canNavigateUp=true")
                    TabBand(
                        tabs = twoTabs,
                        activeTabId = "downloads",
                        onTabSelected = {},
                        onTabClosed = {},
                        onAddTab = {},
                        onTrashTap = {},
                        canNavigateUp = true,
                        onNavigateUp = {},
                    )

                    // Frame 2: a selection is live -- the up-chevron is replaced by the red
                    // recycle chip, regardless of canNavigateUp (recycling never depends on
                    // folder depth).
                    Caption("SELECTING -- red recycle chip replaces it")
                    TabBand(
                        tabs = twoTabs,
                        activeTabId = "documents",
                        onTabSelected = {},
                        onTabClosed = {},
                        onAddTab = {},
                        onTrashTap = {},
                        canNavigateUp = true,
                        onNavigateUp = {},
                        selectionActive = true,
                        onRecycleSelection = {},
                    )

                    // Frame 3: home tab, nowhere to navigate up to, no selection -- no leading
                    // chip at all.
                    Caption("HOME -- canNavigateUp=false, no chip")
                    TabBand(
                        tabs = twoTabs,
                        activeTabId = "downloads",
                        onTabSelected = {},
                        onTabClosed = {},
                        onAddTab = {},
                        onTrashTap = {},
                        canNavigateUp = false,
                        onNavigateUp = {},
                    )

                    // Frame 4: the same band at the user's larger chrome scale, where every
                    // control clears the 48dp touch floor.
                    Caption("SCALE = MAX -- every control at/over the 48dp floor")
                    CompositionLocalProvider(LocalChromeScale provides ChromeScale.MAX) {
                        TabBand(
                            tabs = twoTabs,
                            activeTabId = "downloads",
                            onTabSelected = {},
                            onTabClosed = {},
                            onAddTab = {},
                            onTrashTap = {},
                            canNavigateUp = true,
                            onNavigateUp = {},
                        )
                    }
                }
            }
        }
        snap("tabband-states.png")
    }
}
