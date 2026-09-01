package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. The Activity overlay's three states -- notification, minimized bar
// (single and stacked three-up, matching the owner's own reference), expanded card -- driven
// directly through the internal sub-composables rather than the real notification-to-minimize
// timer, so the exact frame each state draws is deterministic instead of racing a real delay().
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../ActivityOverlayRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*ActivityOverlayRender*" --offline
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
import io.github.mbaliga.fylz.operations.ActivityKind
import io.github.mbaliga.fylz.operations.ActivityProgress
import io.github.mbaliga.fylz.ui.activity.ExpandedCard
import io.github.mbaliga.fylz.ui.activity.MinimizedBar
import io.github.mbaliga.fylz.ui.activity.NotificationCard
import io.github.mbaliga.fylz.ui.activity.accentFor
import io.github.mbaliga.fylz.ui.activity.iconFor
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@Ignore("Render tool, not a gate -- remove locally to rasterize the overlay; see the header comment.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w440dp-h900dp-480dpi")
class ActivityOverlayRender {

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

    @Composable
    private fun Caption(text: String) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
    }

    private val moving = ActivityProgress(
        id = "move-1",
        label = "Moving",
        itemIndex = 4822,
        itemCount = 12366,
        kind = ActivityKind.TRANSFER,
    )
    private val archiving = ActivityProgress("zip-1", "Compressing", 3, 8, ActivityKind.ARCHIVE)
    private val writing = ActivityProgress("pdf-1", "Merging pages", 6, 6, ActivityKind.DOCUMENT)

    @Test
    fun render() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Caption("NOTIFICATION -- freshly started, full card")
                    NotificationCard(moving, accentFor(moving.kind), iconFor(moving.kind), 4822f / 12366f)

                    Caption("EXPANDED -- tapped open, progress bar")
                    ExpandedCard(moving, accentFor(moving.kind), iconFor(moving.kind), 4822f / 12366f)

                    Caption("MINIMIZED -- single, auto-shrunk after 2.5s")
                    MinimizedBar(accentFor(moving.kind), iconFor(moving.kind))

                    Caption("MINIMIZED -- three simultaneous activities stacked (the owner's own reference)")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MinimizedBar(accentFor(writing.kind), iconFor(writing.kind))
                        MinimizedBar(accentFor(moving.kind), iconFor(moving.kind))
                        MinimizedBar(accentFor(archiving.kind), iconFor(archiving.kind))
                    }
                }
            }
        }
        snap("activity-overlay-states.png")
    }
}
