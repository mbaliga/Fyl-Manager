package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate: rasterizes real Build-11 screens through Robolectric native
// graphics (seeded stores, real theme + fonts) so screens can be eyeballed without a device.
// @Ignore'd by default so CI never depends on host rendering: remove the annotation locally and
// run  ./gradlew :app:testDebugUnitTest --tests "io.github.mbaliga.fylz.screenshots.*"  to render
// into app/build/outputs/build11-shots/. This pass caught the seed-layout overlap fixed in
// DesktopPolicy.defaultSeed.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.mbaliga.fylz.canvas.TilePlacement
import io.github.mbaliga.fylz.desktop.DesktopItem
import io.github.mbaliga.fylz.desktop.DesktopPolicy
import io.github.mbaliga.fylz.desktop.DesktopStore
import io.github.mbaliga.fylz.history.RecentOpensStore
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.operations.RecycleRecord
import io.github.mbaliga.fylz.operations.RecycleBinStore
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.storage.LargeFileFact
import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import io.github.mbaliga.fylz.storage.StorageUsageStore
import io.github.mbaliga.fylz.ui.components.ProvideIconStyle
import io.github.mbaliga.fylz.ui.components.ProvideShowExtensions
import io.github.mbaliga.fylz.ui.desktop.DesktopCallbacks
import io.github.mbaliga.fylz.ui.desktop.DesktopCli
import io.github.mbaliga.fylz.ui.desktop.DesktopScreen
import io.github.mbaliga.fylz.ui.desktop.WallpaperLayer
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import io.github.mbaliga.fylz.wallpaper.WallpaperSpec
import io.github.mbaliga.fylz.widgets.StorageWidgetProvider
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.FileOutputStream

// Robolectric 4.16's built-in ShadowEnvironment has no isExternalStorageManager() implementation,
// so the real framework code runs and throws under the test harness. Extend the built-in shadow
// with just that one static so everything else keeps its stock shadow behaviour.
@Implements(android.os.Environment::class)
class RenderShadowEnvironment : org.robolectric.shadows.ShadowEnvironment() {
    companion object {
        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = true
    }
}

@Ignore("Render tool, not a gate -- remove locally to rasterize screens; see the header comment.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-420dpi", shadows = [RenderShadowEnvironment::class])
class Build11ScreenshotRender {

    companion object {
        init {
            System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val outDir = File("build/outputs/build11-shots").apply { mkdirs() }

    private lateinit var desktopStore: DesktopStore

    @Before
    fun seed() {
        val now = 1_755_700_000_000L // fixed reference instant for stable "as of" stamps

        // Render single static frames: the pond-water withFrameNanos loop otherwise keeps
        // Compose permanently busy and Espresso's idle wait times out.
        android.provider.Settings.Global.putFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )

        StorageUsageStore(context).write(
            StorageUsageSnapshot(
                scannedAtMillis = now,
                kindBytes = mapOf(
                    StorageKind.PHOTOS to 48L * 1024 * 1024 * 1024,
                    StorageKind.DOCUMENTS to 13L * 1024 * 1024 * 1024,
                    StorageKind.VIDEOS to 26L * 1024 * 1024 * 1024,
                    StorageKind.SOUNDS to 10L * 1024 * 1024 * 1024,
                    StorageKind.ARCHIVES to 6L * 1024 * 1024 * 1024,
                    StorageKind.OTHER to 4L * 1024 * 1024 * 1024,
                ),
                truncated = false,
                largestFiles = listOf(
                    LargeFileFact("content://demo/holiday-cut.mp4", "holiday-cut.mp4", 3_965_190_144L),
                    LargeFileFact("content://demo/backup-2026.tar.gz", "backup-2026.tar.gz", 2_147_483_648L),
                    LargeFileFact("content://demo/lecture-recording.mkv", "lecture-recording.mkv", 1_610_612_736L),
                ),
            )
        )

        val library = LibraryStore(context)
        library.toggleFavorite(Uri.parse("content://demo/tree/documents"), "Documents")
        library.toggleFavorite(Uri.parse("content://demo/tree/camera"), "Camera")
        library.toggleFavorite(Uri.parse("content://demo/tree/projects"), "Projects")
        library.setTags(Uri.parse("content://demo/doc/thesis.pdf"), listOf("uni", "important"))
        library.setTags(Uri.parse("content://demo/doc/invoice.pdf"), listOf("finance", "important"))
        library.setTags(Uri.parse("content://demo/doc/scan.pdf"), listOf("finance"))

        val recents = RecentOpensStore(context)
        recents.record(Uri.parse("content://demo/doc/thesis.pdf"), "thesis.pdf", "PDF", now - 3_600_000)
        recents.record(Uri.parse("content://demo/img/IMG_2041.jpg"), "IMG_2041.jpg", "IMAGE", now - 1_800_000)
        recents.record(Uri.parse("content://demo/doc/notes.md"), "notes.md", "MARKDOWN", now - 600_000)

        RecycleBinStore(context).put(
            RecycleRecord(
                itemId = "demo-1",
                originalUri = Uri.parse("content://demo/doc/old-draft.docx"),
                recycledUri = Uri.parse("content://demo/trash/old-draft.docx"),
                originalParentUri = Uri.parse("content://demo/tree/documents"),
                originalDisplayName = "old-draft.docx",
                providerAuthority = "demo",
                sizeBytes = 254_321L,
                recycledAtMillis = now - 86_400_000,
            )
        )

        desktopStore = DesktopStore(context)
        desktopStore.seedIfEmpty(DesktopPolicy.defaultSeed())
        desktopStore.upsert(
            DesktopItem.FolderShortcut(
                id = "shot-folder",
                treeUri = Uri.parse("content://demo/tree/projects"),
                folderUri = Uri.parse("content://demo/tree/projects/doc/root"),
                displayName = "Projects",
                placement = TilePlacement(0.72f, 0.06f, 40),
            )
        )
    }

    private val callbacks = DesktopCallbacks(
        onOpenFolderShortcut = { _, _ -> }, onOpenFileShortcut = {}, onOpenTrash = {},
        onOpenShelf = {}, onFocusSearch = {}, onScan = {}, onOpenTag = {}, onOpenFavorite = {},
        onOpenWallpaperPicker = {}, onOpenLargeFiles = {}, onPickFolderShortcut = {},
        onPickQuickAccessFolder = {}, onPickDuplicatesFolder = {},
    )

    private fun setThemedContent(
        mode: ThemeMode,
        style: ThemeStyle,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            FylzTheme(themeMode = mode, accentPreset = AccentPreset.MOSS, dynamicColor = false, themeStyle = style) {
                CompositionLocalProvider(LocalThemeStyle provides style) {
                    ProvideIconStyle(IconStyle.DEFAULT) {
                        ProvideShowExtensions(true) {
                            content()
                        }
                    }
                }
            }
        }
    }

    private fun settle() {
        repeat(12) {
            Thread.sleep(120)
            compose.waitForIdle()
        }
    }

    private fun snap(name: String) {
        settle()
        val decor = compose.activity.window.decorView
        check(decor.width > 0 && decor.height > 0) { "decor not laid out" }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        FileOutputStream(File(outDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun repository() = DocumentRepository(context)

    @Test
    fun desktopOverPondWaterDark() {
        setThemedContent(ThemeMode.DARK, ThemeStyle.FYLZ) {
            DesktopScreen(
                store = desktopStore,
                wallpaperSpec = WallpaperSpec.PondWater,
                repository = repository(),
                refreshKey = 0,
                bottomReserve = 57.dp,
                callbacks = callbacks,
                modifier = Modifier.fillMaxSize(),
            )
        }
        snap("1-desktop-pondwater-dark.png")
    }

    @Test
    fun desktopOverGradientLight() {
        setThemedContent(ThemeMode.LIGHT, ThemeStyle.NEO) {
            DesktopScreen(
                store = desktopStore,
                wallpaperSpec = WallpaperSpec.Gradient("dusk"),
                repository = repository(),
                refreshKey = 0,
                bottomReserve = 57.dp,
                callbacks = callbacks,
                modifier = Modifier.fillMaxSize(),
            )
        }
        snap("2-desktop-gradient-light.png")
    }

    @Test
    fun pondWaterAlone() {
        setThemedContent(ThemeMode.DARK, ThemeStyle.FYLZ) {
            Box(Modifier.fillMaxSize()) { WallpaperLayer(WallpaperSpec.PondWater, Modifier.fillMaxSize()) }
        }
        snap("3-pondwater-full.png")
    }

    @Test
    fun desktopCliTheme() {
        setThemedContent(ThemeMode.DARK, ThemeStyle.CLI) {
            DesktopCli(
                store = desktopStore,
                refreshKey = 0,
                callbacks = callbacks,
                modifier = Modifier.fillMaxSize(),
            )
        }
        snap("4-desktop-cli.png")
    }

    @Test
    fun storageLauncherWidget() {
        val views = StorageWidgetProvider.buildViews(context, 7)
        val host = FrameLayout(context)
        val view = views.apply(context, host)
        val density = context.resources.displayMetrics.density
        val w = (250 * density).toInt()
        val h = (180 * density).toInt()
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(File(outDir, "5-launcher-widget-storage.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
