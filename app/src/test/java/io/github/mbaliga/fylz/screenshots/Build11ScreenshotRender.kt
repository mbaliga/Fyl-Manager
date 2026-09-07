package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate: rasterizes real Build-11.5 screens through Robolectric native
// graphics (seeded stores, real theme + fonts) so screens can be eyeballed without a device.
// @Ignore'd by default so CI never depends on host rendering: remove the annotation locally and
// run  ./gradlew :app:testDebugUnitTest --tests "io.github.mbaliga.fylz.screenshots.*"  to render
// into app/build/outputs/build11-shots/. The Build-11 pass caught the seed-layout overlap fixed
// in DesktopPolicy.defaultSeed; the 11.5 pass renders the Hyle fidelity + tactile-kit surfaces.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.desktop.DesktopPolicy
import io.github.mbaliga.fylz.desktop.DesktopStore
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.history.RecentOpensStore
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.operations.RecycleRecord
import io.github.mbaliga.fylz.operations.RecycleBinStore
import io.github.mbaliga.fylz.storage.LargeFileFact
import io.github.mbaliga.fylz.storage.StorageKind
import io.github.mbaliga.fylz.storage.StorageUsageSnapshot
import io.github.mbaliga.fylz.storage.StorageUsageStore
import io.github.mbaliga.fylz.ui.FolderPeek
import io.github.mbaliga.fylz.ui.components.CountChip
import io.github.mbaliga.fylz.ui.components.FolderFace
import io.github.mbaliga.fylz.ui.components.FolderHero
import io.github.mbaliga.fylz.ui.components.LeftTimelineRail
import io.github.mbaliga.fylz.ui.components.ProvideIconStyle
import io.github.mbaliga.fylz.ui.components.ProvideShowExtensions
import io.github.mbaliga.fylz.ui.components.dateSections
import io.github.mbaliga.fylz.ui.components.groupByDay
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
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileField
import io.github.mbaliga.fylz.ui.tactile.TactileFieldState
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import io.github.mbaliga.fylz.ui.tactile.TactileSlider
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch
import io.github.mbaliga.fylz.ui.tactile.TactileToggle
import io.github.mbaliga.fylz.ui.tactile.TactileToggleOption
import io.github.mbaliga.fylz.wallpaper.WallpaperSpec
import io.github.mbaliga.fylz.widgets.StorageWidgetProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Star
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

    private val now = 1_755_700_000_000L // fixed reference instant for stable "as of" stamps

    @Before
    fun seed() {
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
        library.setTags(Uri.parse("content://demo/doc/thesis.pdf"), listOf("uni", "important"))
        library.setTags(Uri.parse("content://demo/doc/invoice.pdf"), listOf("finance", "important"))

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
        // No fixture folder shortcut: a fake demo:// URI renders the broken-link state, which read
        // as a "stray phantom icon" in the Build-11 render review. Shortcuts are exercised by the
        // real app; the seeded widget field is the honest render.
    }

    private val callbacks = DesktopCallbacks(
        onOpenFolderShortcut = { _, _ -> }, onOpenFileShortcut = {}, onOpenTrash = {},
        onOpenShelf = {}, onFocusSearch = {}, onScan = {}, onOpenTag = {}, onOpenFavorite = {},
        onOpenWallpaperPicker = {}, onOpenLargeFiles = {}, onPickFolderShortcut = {},
        onPickQuickAccessFolder = {}, onPickDuplicatesFolder = {}, onGrantFullAccess = {},
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

    private fun doc(name: String, daysAgo: Long, hour: Long = 9): FileEntry = FileEntry(
        uri = Uri.parse("content://demo/doc/" + name),
        name = name,
        mimeType = "application/pdf",
        sizeBytes = 120_000L + name.length,
        lastModifiedMillis = now - daysAgo * 86_400_000L - hour * 3_600_000L,
        flags = 0,
        kind = EntryKind.PDF,
    )

    private fun dir(name: String): FileEntry = FileEntry(
        uri = Uri.parse("content://demo/tree/" + name.lowercase().replace(" ", "-")),
        name = name,
        mimeType = "vnd.android.document/directory",
        sizeBytes = null,
        lastModifiedMillis = now - 86_400_000L,
        flags = 0,
        kind = EntryKind.DIRECTORY,
    )

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
    fun tactileSamplerLight() {
        setThemedContent(ThemeMode.LIGHT, ThemeStyle.NEO) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Tactile kit", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TactileToggle(
                        options = listOf(
                            TactileToggleOption(label = "#", contentDescription = "Grid"),
                            TactileToggleOption(label = "*", contentDescription = "Stars"),
                        ),
                        selectedIndex = 1,
                        onSelect = {},
                    )
                    TactileToggle(
                        options = listOf(
                            TactileToggleOption(label = "#", contentDescription = "Grid"),
                            TactileToggleOption(label = "*", contentDescription = "Stars"),
                        ),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TactileSwitch(checked = false, onCheckedChange = {})
                    TactileSwitch(checked = true, onCheckedChange = {})
                    TactileIconKey(icon = Icons.Outlined.GridView, contentDescription = "Grid", onClick = {})
                    TactileIconKey(icon = Icons.Outlined.Star, contentDescription = "Star", onClick = {}, latched = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TactileButton(text = "Primary", onClick = {})
                    TactileButton(text = "Secondary", onClick = {}, style = TactileButtonStyle.SECONDARY)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TactileButton(text = "Shred", onClick = {}, style = TactileButtonStyle.DESTRUCTIVE)
                    TactileButton(text = "Disabled", onClick = {}, enabled = false)
                }
                TactileField(value = "", onValueChange = {}, label = "Display name", placeholder = "Not selected")
                TactileField(value = "Selected & mandatory", onValueChange = {}, state = TactileFieldState.Selected, mandatory = true)
                TactileField(value = "Error & mandatory", onValueChange = {}, state = TactileFieldState.Error("Invalid input"), mandatory = true)
                TactileField(value = "Disabled", onValueChange = {}, enabled = false)
                TactileOptionRow(text = "Not selected", selected = false, onClick = {})
                TactileOptionRow(text = "Selected", selected = true, onClick = {})
                TactileSlider(value = 0.4f, onValueChange = {}, modifier = Modifier.fillMaxWidth())
            }
        }
        snap("3-tactile-sampler-light.png")
    }

    @Test
    fun dateSectionedDocuments() {
        val docs = listOf(
            doc("BMW Financial Services.pdf", 0, 9), doc("Invoice 2041.pdf", 0, 11), doc("Scan 12.pdf", 0, 14),
            doc("Lease agreement.pdf", 1, 10), doc("Insurance.pdf", 1, 12), doc("Receipt.pdf", 1, 16),
            doc("Thesis draft.pdf", 10, 9), doc("Notes.pdf", 10, 10), doc("Marksheet.pdf", 10, 11),
        )
        val sections = groupByDay(docs, now)
        setThemedContent(ThemeMode.LIGHT, ThemeStyle.FYLZ) {
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                LeftTimelineRail(
                    sectionAnchors = listOf(0.06f, 0.38f, 0.70f),
                    modifier = Modifier.padding(start = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        FolderHero(name = "Figura - invoices", countLabel = "9 documents") {
                            FolderFace(
                                entry = dir("Figura"),
                                peek = FolderPeek(itemCount = 9, thumbs = emptyList(), hasNonMedia = true),
                                modifier = Modifier.width(120.dp).height(100.dp),
                                showLabel = false,
                            )
                        }
                    }
                    dateSections(sections, onEntryClick = {}) { entry ->
                        Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        snap("4-date-sectioned-documents.png")
    }

    @Test
    fun folderGridFrosted() {
        val folders = listOf("Japan 2024" to 83, "Paris 2024" to 62, "Amsterdam 2024" to 68, "Other photos" to 247)
        setThemedContent(ThemeMode.LIGHT, ThemeStyle.FYLZ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                items(folders.size) { index ->
                    val (name, count) = folders[index]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FolderFace(
                            entry = dir(name),
                            peek = FolderPeek(itemCount = count, thumbs = emptyList(), hasNonMedia = false),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(120.dp),
                            showLabel = false,
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        CountChip("$count photos", modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        snap("5-folder-grid-frosted.png")
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
        snap("6-desktop-cli.png")
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
        FileOutputStream(File(outDir, "7-launcher-widget-storage.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
