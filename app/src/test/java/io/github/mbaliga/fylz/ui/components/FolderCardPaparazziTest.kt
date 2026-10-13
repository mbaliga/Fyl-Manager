package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.github.mbaliga.fylz.actions.ActionContext
import io.github.mbaliga.fylz.actions.ActionDispatcher
import io.github.mbaliga.fylz.actions.ActionRegistry
import io.github.mbaliga.fylz.actions.ActionResolver
import io.github.mbaliga.fylz.actions.BrowserState
import io.github.mbaliga.fylz.actions.BuiltInActions
import io.github.mbaliga.fylz.actions.RoomId
import io.github.mbaliga.fylz.browse.SortSpec
import io.github.mbaliga.fylz.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.PinchInBehavior
import io.github.mbaliga.fylz.model.PreviewMode
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.model.ViewMode
import io.github.mbaliga.fylz.ui.actions.SelectionActionBar
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * Renders the folder-card redesign to real PNGs on pure JVM (no device/emulator -- the only way to
 * check visual fidelity against the Figma reference without a device in this sandbox; see
 * `docs/agent/PROGRESS.md` for where the rendered files would land,
 * `src/test/snapshots/images/`, Paparazzi's own convention, via `./gradlew :app:recordPaparazziDebug`).
 *
 * Every composable under test here takes already-resolved data ([FolderCardVisual] itself, never
 * the data-fetching [FileCard] wrapper), so nothing needs a real `ContentResolver`/SAF tree: seeded
 * [FileEntry] fixtures below use non-image/video kinds on purpose, so `EntryThumbnail` always takes
 * its deterministic vector-icon fallback branch rather than trying a real thumbnail load.
 *
 * **Why every test here is `@Ignore`d (full account in `docs/agent/PROGRESS.md` and
 * `docs/agent/REVIEW_QUEUE.md`):** getting this far already required fixing two real, confirmed
 * bugs in `app.cash.paparazzi:paparazzi-gradle-plugin:1.3.3` (both root-caused by decompiling its
 * own bytecode, both fixed in `app/build.gradle.kts`, both worth keeping regardless of this
 * class's own status): (1) `Environment.detectEnvironment()`'s SDK-path fallback ignores
 * `local.properties`, fixed by setting `ANDROID_HOME`/`ANDROID_SDK_ROOT` for the `Test` JVM; (2)
 * `Renderer.configureBuildProperties` throws reflecting fields onto a 2023-era shadow `Build`
 * class that predates this project's compileSdk 36 `Build.VERSION_CODES_FULL`, fixed by stripping
 * that one nested class from a copy of `android.jar` on the `Test` task's own classpath. Past
 * both: `com.android.layoutlib.bridge.Bridge.init()` fails to load its own bundled native
 * renderer, `libandroid_runtime.so`, with `UnsatisfiedLinkError: unsupported JNI version
 * 0xFFFFFFFF` -- confirmed to reproduce in a bare `System.load(...)` call with no Gradle or
 * Paparazzi code involved at all, on the one JDK this sandbox has (OpenJDK 21; the artifact is
 * literally named `layoutlib-native-jdk11`, and no JDK 11/17 is installed here to test whether
 * that's the actual boundary -- a pre-existing sandbox constraint, not one this task introduced,
 * already recorded in `docs/agent/PROGRESS.md`'s own JDK note). This is a native-library
 * initialization failure below the JVM/Gradle/Kotlin layer entirely; nothing left to configure
 * from here fixes it. Left `@Ignore`d rather than deleted so a future environment with a matching
 * JDK can re-enable this class and get real PNGs immediately, on top of the two real fixes above.
 */
class FolderCardPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun file(name: String, kind: EntryKind, modified: Long) = FileEntry(
        uri = Uri.parse("content://paparazzi/$name"),
        name = name,
        mimeType = "application/octet-stream",
        sizeBytes = 2_048,
        lastModifiedMillis = modified,
        flags = 0,
        kind = kind,
    )

    private fun directory(name: String) = FileEntry(
        uri = Uri.parse("content://paparazzi/dir/$name"),
        name = name,
        mimeType = "vnd.android.document/directory",
        sizeBytes = null,
        lastModifiedMillis = null,
        flags = 0,
        kind = EntryKind.DIRECTORY,
    )

    /** Three seeded folders: a genuinely empty one, one with a single file, and one with more than
     * [FOLDER_PREVIEW_LIMIT] files -- so a recorded grid snapshot exercises both the "Empty" state
     * and the live-thumbnail-preview state the task brief asked for. */
    @Composable
    private fun SampleGrid(palette: FolderCardPalette, background: androidx.compose.ui.graphics.Color) {
        val emptyFolder = directory("Thailand 2026")
        val oneFileFolder = directory("Vietnam 2026")
        val oneFilePreview = FolderPreview(files = listOf(file("sunset.pdf", EntryKind.PDF, 4_000)), totalFileCount = 1)
        val manyFilesFolder = directory("Documents")
        val sixFiles = (1..6).map { file("report$it.txt", EntryKind.TEXT, it * 1_000L) }
        val manyFilesPreview = FolderPreview(files = pickFolderPreviewFiles(sixFiles), totalFileCount = sixFiles.size)

        Column(Modifier.fillMaxSize().background(background).padding(12.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(listOf(emptyFolder, oneFileFolder, manyFilesFolder)) { entry ->
                    val preview = when (entry) {
                        oneFileFolder -> oneFilePreview
                        manyFilesFolder -> manyFilesPreview
                        else -> FolderPreview(files = emptyList(), totalFileCount = 0)
                    }
                    FolderCardVisual(
                        entry = entry,
                        previewFiles = preview.files,
                        subtitle = folderSubtitle(preview),
                        selected = false,
                        palette = palette,
                        onClick = {}, onDoubleClick = {}, onLongClick = {}, onToggleSelected = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Test
    fun `folder card grid - light mode`() {
        paparazzi.snapshot {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SampleGrid(FolderCardPalettes.Light, androidx.compose.ui.graphics.Color(0xFFF5F5F5))
            }
        }
    }

    @Test
    fun `folder card grid - dark mode`() {
        paparazzi.snapshot {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SampleGrid(FolderCardPalettes.Dark, androidx.compose.ui.graphics.Color(0xFF121212))
            }
        }
    }

    @Test
    fun `selection mode - one card selected, restyled SelectionActionBar`() {
        val selected = directory("Thailand 2026")
        val registry = ActionRegistry(BuiltInActions.all())
        val resolver = ActionResolver(registry)
        val dispatcher = ActionDispatcher(registry)
        val state = BrowserState(
            hasActiveTab = true,
            canNavigateUp = true,
            entries = listOf(selected),
            visibleEntries = listOf(selected),
            selection = listOf(selected),
            selectionOrder = listOf(selected.uri),
            focused = null,
            clipboard = null,
            sortSpec = SortSpec.Default,
            viewMode = ViewMode.GRID,
            previewMode = PreviewMode.HIDDEN,
            query = "",
            searchRecursive = false,
            themeMode = ThemeMode.LIGHT,
            currentFolderIsFavourite = false,
            legacyBinCount = 0,
            operationsNeedingAttention = 0,
            pinchInBehavior = PinchInBehavior.GO_UP,
        )

        paparazzi.snapshot {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF5F5F5))) {
                    ViewModeSwitcherBar(ViewMode.GRID, onViewModeChange = {}, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.padding(16.dp)) {
                        FolderCardVisual(
                            entry = selected,
                            previewFiles = emptyList(),
                            subtitle = "Empty",
                            selected = true,
                            palette = FolderCardPalettes.Light,
                            onClick = {}, onDoubleClick = {}, onLongClick = {}, onToggleSelected = {},
                            modifier = Modifier.fillMaxWidth(0.5f),
                        )
                    }
                    SelectionActionBar(resolver, dispatcher, state, NoopActionContext)
                }
            }
        }
    }
}

/** Every `ActionContext` method is called only from a `run`/`gesture` handler, never from
 * `visibleWhen`/`enabledWhen`/`label`/`checked` (what `ActionResolver.resolve` reads) -- and a
 * Paparazzi snapshot renders one frame, it never clicks anything -- so a plain no-op
 * implementation is enough to satisfy [SelectionActionBar]'s `ActionContext` parameter. */
private object NoopActionContext : ActionContext {
    override fun cut() {}
    override fun copy() {}
    override fun copyTo() {}
    override fun moveTo() {}
    override fun recycleSelection() {}
    override fun rename() {}
    override fun tags() {}
    override fun compress() {}
    override fun extract() {}
    override fun extractHere() {}
    override fun extractIntoFolder() {}
    override fun extractTo() {}
    override fun extractSelected() {}
    override fun openExtractMenu(archive: Uri) {}
    override fun openCompressMenu(sources: List<Uri>) {}
    override fun addArchiveEntries() {}
    override fun batchRename() {}
    override fun pdfTools() {}
    override fun share() {}
    override fun clearSelection() {}
    override fun paste() {}
    override fun clearClipboard() {}
    override fun toggleViewMode() {}
    override fun refresh() {}
    override fun newFolder() {}
    override fun newFile() {}
    override fun scanToPdf() {}
    override fun findDuplicates() {}
    override fun aiOrganize() {}
    override fun navigateUp() {}
    override fun selectAll() {}
    override fun setSortField(field: io.github.mbaliga.fylz.browse.SortField) {}
    override fun toggleFoldersFirst() {}
    override fun open(entry: FileEntry) {}
    override fun openWith(entry: FileEntry) {}
    override fun toggleSelected(entry: FileEntry) {}
    override fun addTab() {}
    override fun closeTab(tabId: String) {}
    override fun openRoot() {}
    override fun toggleFavourite() {}
    override fun openRecycleBin() {}
    override fun openRemotes() {}
    override fun openWebDavQuick() {}
    override fun openToolsActivity() {}
    override fun openIndexActivity() {}
    override fun setThemeMode(mode: ThemeMode) {}
    override fun stepDetailLevel(delta: Int) {}
    override fun setPinchInBehavior(behavior: PinchInBehavior) {}
    override fun openThemePicker() {}
    override fun showOperationHistory() {}
    override fun openRoom(room: RoomId) {}
    override fun openCommandPalette() {}
    override fun showRegistryProblems() {}
}
