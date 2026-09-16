package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. Two small UI surfaces from the same change: the locked/unlocked
// archive summary banner (new copy) and a "Remote locations" row (new producer, reusing
// StorageRootRow). Neither was looked at as a picture before landing -- this is that look, after
// the fact. ArchiveUnlockDialog itself is not rendered here: its password TextField's cursor
// blink hangs Robolectric's compose-idle wait for reasons unrelated to its own layout, and it
// reuses the same TactileField/AlertDialog shape ArchiveToolsOverlay's own, already-shipped
// password dialog already uses.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../ArchivePasswordAndRemoteRootRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*ArchivePasswordAndRemoteRootRender*" --offline
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.ArchiveEntryReader
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.storage.StorageRootKind
import io.github.mbaliga.fylz.ui.StorageRootRow
import io.github.mbaliga.fylz.ui.components.preview.ArchiveSummary
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
@Config(sdk = [35], qualifiers = "w440dp-h900dp-480dpi")
class ArchivePasswordAndRemoteRootRender {

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

    private val lockedListing = ArchiveEntryReader.Listing(
        formatLabel = "ZIP-compatible archive",
        members = emptyList(),
        entryCount = 42,
        expandedBytes = 18_400_000,
        encrypted = true,
        truncated = false,
        singleCompressedStream = false,
        blockedReason = null,
        unsafeMemberCount = 0,
    )

    private val remoteRoot = StorageRoot(
        id = "remote:nas-1",
        title = "Office NAS",
        subtitle = "SMB / Windows share",
        kind = StorageRootKind.REMOTE,
    )

    @Composable
    private fun Caption(text: String) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
    }

    @Composable
    private fun Scene(content: @Composable () -> Unit) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
        }
    }

    @Test
    fun render() {
        compose.setContent {
            Scene {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Caption("SUMMARY -- locked, no password entered yet")
                    ArchiveSummary(lockedListing, unlocked = false)

                    Caption("SUMMARY -- unlocked for this preview")
                    ArchiveSummary(lockedListing, unlocked = true)

                    Caption("REMOTE ROOT ROW -- a saved SMB connection on the storage home screen")
                    StorageRootRow(remoteRoot, onClick = {})
                }
            }
        }
        snap("archive-password-and-remote-root.png")
    }
}
