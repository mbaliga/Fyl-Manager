package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. The press-hold cluster while it is UNDER A THUMB -- the whole point
// of the size and lift work, and the one thing a code read cannot answer. Each frame draws a
// thumb-sized disc centred on the real contact point, so "does the cargo peek out from under the
// thumb" is a question the picture answers rather than one the constants imply.
//
// The layer runs a withFrameNanos loop for as long as a drag lives, so it is NEVER idle: calling
// waitForIdle here would hang forever. The clock is driven by hand instead (autoAdvance = false),
// which also makes each frame an exact, reproducible point in the gather rather than whatever the
// host machine's timing happened to produce.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../ClusterDragRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*ClusterDragRender*" --offline
// Output lands in app/build/outputs/build11-shots/.

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.staging.StagedItem
import io.github.mbaliga.fylz.ui.cluster.ClusterDragController
import io.github.mbaliga.fylz.ui.cluster.ClusterDragLayer
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@Ignore("Render tool, not a gate -- remove locally to rasterize the drag; see the header comment.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w440dp-h900dp-480dpi")
class ClusterDragRender {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/outputs/build11-shots").apply { mkdirs() }

    private fun snap(name: String) {
        val decor = compose.activity.window.decorView
        check(decor.width > 0 && decor.height > 0) { "decor not laid out" }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        FileOutputStream(File(outDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val cargo = listOf(
        StagedItem(Uri.parse("content://fylz/1"), "Invoice-2026-Q3.pdf", EntryKind.PDF),
        StagedItem(Uri.parse("content://fylz/2"), "roadmap.md", EntryKind.MARKDOWN),
        StagedItem(Uri.parse("content://fylz/3"), "site-survey.jpg", EntryKind.IMAGE),
        StagedItem(Uri.parse("content://fylz/4"), "handover.zip", EntryKind.ARCHIVE),
    )

    /**
     * A thumb, drawn where a thumb actually sits: a ~46dp-radius disc reaching down and away from
     * the point the digitiser reports, which is the top of the contact patch, not its middle.
     */
    @Composable
    private fun ThumbProxy(at: Offset) {
        val density = LocalDensity.current
        Box(
            Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (at.x - with(density) { 46.dp.toPx() }).toInt(),
                        (at.y - with(density) { 18.dp.toPx() }).toInt(),
                    )
                }
                .size(92.dp)
                .zIndex(40f)
                .background(Color(0x66C08A62), CircleShape),
        )
    }

    @Composable
    private fun Scene(controller: ClusterDragController) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize()) {
                ClusterDragLayer(controller = controller) { _, _ -> }
                // Reads the live drag position, so a frame captured mid-throw shows the thumb
                // where the finger actually is rather than where the drag began.
                ThumbProxy(controller.dragPosition)
            }
        }
    }

    /** Seeds four rows stacked down the listing, the way a real multi-select grab would. */
    private fun controllerAt(point: Offset): ClusterDragController {
        val controller = ClusterDragController()
        val origins = cargo.mapIndexed { index, item ->
            item.uri to Offset(160f, 900f + index * 150f)
        }.toMap()
        controller.start(cargo, origins, point)
        return controller
    }

    @Test
    fun settled() {
        val at = Offset(560f, 1100f)
        val controller = controllerAt(at)
        compose.mainClock.autoAdvance = false
        compose.setContent { Scene(controller) }
        // Long enough for the gather to finish and the springs to stop ringing.
        compose.mainClock.advanceTimeBy(900)
        snap("cluster-drag-settled.png")
    }

    @Test
    fun midGather() {
        val at = Offset(560f, 1100f)
        val controller = controllerAt(at)
        compose.mainClock.autoAdvance = false
        compose.setContent { Scene(controller) }
        // Partway through the 190ms swell: the stack is still arriving and still growing.
        compose.mainClock.advanceTimeBy(110)
        snap("cluster-drag-mid-gather.png")
    }

    @Test
    fun movingFast() {
        val at = Offset(560f, 1100f)
        val controller = controllerAt(at)
        compose.mainClock.autoAdvance = false
        compose.setContent { Scene(controller) }
        compose.mainClock.advanceTimeBy(600)
        // A hard sideways throw, so bank and squash are in the frame with the lift.
        repeat(8) { step ->
            controller.drag(Offset(560f + step * 34f, 1100f - step * 6f))
            compose.mainClock.advanceTimeBy(16)
        }
        snap("cluster-drag-moving.png")
    }

    @Test
    fun nearTopEdge() {
        // Dragging into the action arc: the lift has to give way rather than push the stack out
        // through the top of the window.
        val at = Offset(300f, 300f)
        val controller = controllerAt(at)
        compose.mainClock.autoAdvance = false
        compose.setContent { Scene(controller) }
        compose.mainClock.advanceTimeBy(900)
        snap("cluster-drag-near-top.png")
    }
}
