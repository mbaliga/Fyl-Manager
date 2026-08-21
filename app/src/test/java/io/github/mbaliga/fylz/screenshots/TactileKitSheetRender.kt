package io.github.mbaliga.fylz.screenshots

// RENDER SCAFFOLD, not a gate. A high-zoom contact sheet of the tactile kit ALONE, so the bevels,
// the corner glint, the leading-edge lean and the slash-tick can actually be inspected at the size
// they are drawn rather than guessed at from a 440dp phone frame. Rendered at 560dpi (3.5x) for
// exactly that reason -- Build 11.5 shipped a kit whose geometry was broken in ways a full-screen
// render was too small to show.
//
// @Ignore'd by default so CI never depends on host rendering. To run:
//   sed -i 's/^@Ignore/\/\/@Ignore/' app/src/test/java/.../TactileKitSheetRender.kt
//   ./gradlew :app:testDebugUnitTest --tests "*TactileKitSheetRender*" --offline
// Output lands in app/build/outputs/build11-shots/.

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.ui.components.IconStyle
import io.github.mbaliga.fylz.ui.components.ProvideIconStyle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import dev.aarso.search.ChipKind
import dev.aarso.search.QueryChip
import io.github.mbaliga.fylz.ui.components.CommandPill
import io.github.mbaliga.fylz.ui.components.CommandPillSearchHeight
import io.github.mbaliga.fylz.ui.search.PullDownSearchHost
import io.github.mbaliga.fylz.ui.search.rememberPullDownSearchState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.github.mbaliga.fylz.ui.components.NotchedCardShape
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@Ignore("Render tool, not a gate -- remove locally to rasterize the kit; see the header comment.")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1500dp-560dpi")
class TactileKitSheetRender {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/outputs/build11-shots").apply { mkdirs() }

    private fun setThemedContent(mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent {
            FylzTheme(themeMode = mode, accentPreset = AccentPreset.MOSS, dynamicColor = false, themeStyle = ThemeStyle.NEO) {
                CompositionLocalProvider(LocalThemeStyle provides ThemeStyle.NEO) {
                    ProvideIconStyle(IconStyle.DEFAULT) { content() }
                }
            }
        }
    }

    private fun snap(name: String) {
        repeat(10) {
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
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    /** Every control, every state, on one sheet -- the thing to look at before believing the kit. */
    @Composable
    private fun KitSheet() {
        val two = listOf(
            TactileToggleOption(label = "List", contentDescription = "List"),
            TactileToggleOption(label = "Grid", contentDescription = "Grid"),
        )
        val three = listOf(
            TactileToggleOption(label = "S", contentDescription = "Small"),
            TactileToggleOption(label = "M", contentDescription = "Medium"),
            TactileToggleOption(label = "L", contentDescription = "Large"),
        )
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Caption("TOGGLE — left selected / right selected / three-up")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TactileToggle(options = two, selectedIndex = 0, onSelect = {})
                TactileToggle(options = two, selectedIndex = 1, onSelect = {})
            }
            TactileToggle(options = three, selectedIndex = 1, onSelect = {})

            Caption("SWITCH off / on   ·   ICON KEY idle / latched")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TactileSwitch(checked = false, onCheckedChange = {})
                TactileSwitch(checked = true, onCheckedChange = {})
                TactileIconKey(Icons.Outlined.GridView, "Grid view", {}, latched = false)
                TactileIconKey(Icons.Outlined.Star, "Favourite", {}, latched = true)
            }

            Caption("BUTTONS — primary / secondary / destructive / disabled")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TactileButton("Primary", {}, style = TactileButtonStyle.PRIMARY)
                TactileButton("Secondary", {}, style = TactileButtonStyle.SECONDARY)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TactileButton("Shred", {}, style = TactileButtonStyle.DESTRUCTIVE)
                TactileButton("Disabled", {}, enabled = false)
            }

            Caption("FIELDS — the owner's seven states")
            TactileField(value = "", onValueChange = {}, label = "Display name", placeholder = "Not selected")
            TactileField(
                value = "Selected & mandatory",
                onValueChange = {},
                state = TactileFieldState.Selected,
                mandatory = true,
            )
            TactileField(
                value = "Error & mandatory",
                onValueChange = {},
                state = TactileFieldState.Error("Invalid input"),
                mandatory = true,
            )
            TactileField(value = "Disabled", onValueChange = {}, enabled = false)
            TactileOptionRow(text = "Not selected", selected = false, onClick = {})
            TactileOptionRow(text = "Selected", selected = true, onClick = {})

            Caption("SLIDER")
            TactileSlider(value = 0.4f, onValueChange = {}, modifier = Modifier.fillMaxWidth())
        }
    }

    @Test
    fun kitSheetLight() {
        setThemedContent(ThemeMode.LIGHT) { KitSheet() }
        snap("kit-sheet-light.png")
    }

    @Test
    fun kitSheetDark() {
        setThemedContent(ThemeMode.DARK) { KitSheet() }
        snap("kit-sheet-dark.png")
    }

    /**
     * The kit arranged the way a real settings screen arranges it -- headings, a single-select
     * group of option rows, a run of switch rows. The contact sheet above proves each control
     * draws correctly on its own; this one is about RHYTHM, which is what the owner actually sees:
     * every one of these primitives carries a 48-52dp floor, so a screen built from two dozen of
     * them stacked is where spacing mistakes compound.
     */
    @Test
    fun settingsRhythmLight() {
        setThemedContent(ThemeMode.LIGHT) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge)
                Caption("APPEARANCE")
                listOf("Follow system", "Always light", "Always dark").forEachIndexed { i, label ->
                    TactileOptionRow(text = label, selected = i == 2, onClick = {})
                }
                Caption("BEHAVIOUR")
                listOf("Show hidden files" to false, "Show file extensions" to true, "Animate transitions" to true)
                    .forEach { (label, on) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            TactileSwitch(checked = on, onCheckedChange = {})
                        }
                    }
                Caption("STORAGE")
                TactileField(value = "30", onValueChange = {}, label = "Keep deleted files for (days)")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TactileButton("Empty bin", {}, style = TactileButtonStyle.DESTRUCTIVE)
                    TactileButton("Manage index", {}, style = TactileButtonStyle.SECONDARY)
                }
            }
        }
        snap("kit-settings-rhythm-light.png")
    }

    /**
     * THE Build-12 regression scene. The owner's recording showed a revealed search band that had
     * been force-sized to a fixed 88dp while the pill inside it measures roughly 180-200dp with a
     * live query -- so the scope toggle floated alone and the field itself, the box holding the
     * query that was hiding every file, was clipped clean off the screen.
     *
     * Latched open with a live query and parsed chips: every row of the pill must be on screen,
     * ending with the input field, above an untouched listing.
     */
    @Test
    fun searchBandRevealedWithLiveQuery() {
        setThemedContent(ThemeMode.LIGHT) {
            val state = rememberPullDownSearchState(revealHeight = CommandPillSearchHeight)
            LaunchedEffect(Unit) { state.reveal() }
            PullDownSearchHost(
                listAtTop = true,
                revealHeight = CommandPillSearchHeight,
                state = state,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                searchField = {
                    CommandPill(
                        query = "pass kind:pdf",
                        onQueryChange = {},
                        canNavigateUp = true,
                        onNavigateUp = {},
                        searchRecursive = true,
                        onSearchRecursiveChange = {},
                        searchBusy = true,
                        chips = listOf(
                            // Canonical text, exactly what QueryCompiler.chipsFor emits: a FACET
                            // chip carries the whole "key:value" in `text` (chipLabel splits on
                            // the colon to humanise it), with `key` alongside for routing.
                            QueryChip("pass", ChipKind.TERM),
                            QueryChip("kind:pdf", ChipKind.FACET, key = "kind"),
                        ),
                        onRemoveChip = {},
                        trailing = {},
                    )
                },
                content = { listModifier ->
                    LazyColumn(listModifier) {
                        items(List(12) { "Result row " + (it + 1) }) { row ->
                            Text(row, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp))
                        }
                    }
                },
            )
        }
        snap("b12-search-band-live-query.png")
    }

    /** The same band with an empty box -- the short state, which must not leave a gap. */
    @Test
    fun searchBandRevealedEmpty() {
        setThemedContent(ThemeMode.LIGHT) {
            val state = rememberPullDownSearchState(revealHeight = CommandPillSearchHeight)
            LaunchedEffect(Unit) { state.reveal() }
            PullDownSearchHost(
                listAtTop = true,
                revealHeight = CommandPillSearchHeight,
                state = state,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                searchField = {
                    CommandPill(
                        query = "",
                        onQueryChange = {},
                        canNavigateUp = true,
                        onNavigateUp = {},
                        searchRecursive = false,
                        onSearchRecursiveChange = {},
                        searchBusy = false,
                        trailing = {},
                    )
                },
                content = { listModifier ->
                    LazyColumn(listModifier) {
                        items(List(12) { "Result row " + (it + 1) }) { row ->
                            Text(row, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp))
                        }
                    }
                },
            )
        }
        snap("b12-search-band-empty.png")
    }

    /** The Quick Look silhouette the owner called "messed up": a rounded card with a rail notch
     *  bitten out of the top-left and a close notch out of the bottom-right, at the sizes it is
     *  actually drawn. Filled solid and outlined so the cut geometry is unambiguous. */
    @Test
    fun notchedCardShapes() {
        setThemedContent(ThemeMode.LIGHT) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                listOf(3 to 1, 5 to 1, 5 to 3, 2 to 1).forEach { (rail, close) ->
                    Caption("rail=" + rail + " close=" + close)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(NotchedCardShape(railSlots = rail, closeSlots = close))
                            .background(Color(0xFF2C2C34)),
                    )
                }
                Caption("docked size 132x96")
                Box(
                    Modifier
                        .size(width = 132.dp, height = 96.dp)
                        .clip(NotchedCardShape(railSlots = 3, closeSlots = 1))
                        .background(Color(0xFF2C2C34)),
                )
            }
        }
        snap("b12-notched-card-shapes.png")
    }

    /** A single toggle and a single field blown up on their own, for corner-level inspection of
     *  the glint's two marks and the leading edge's lean. */
    @Test
    fun closeUpLight() {
        setThemedContent(ThemeMode.LIGHT) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                TactileToggle(
                    options = listOf(
                        TactileToggleOption(label = "One", contentDescription = "One"),
                        TactileToggleOption(label = "Two", contentDescription = "Two"),
                    ),
                    selectedIndex = 1,
                    onSelect = {},
                    modifier = Modifier.width(240.dp),
                )
                TactileButton("Keycap", {}, style = TactileButtonStyle.PRIMARY)
                TactileField(
                    value = "Leading edge",
                    onValueChange = {},
                    state = TactileFieldState.Selected,
                    mandatory = true,
                )
            }
        }
        snap("kit-closeup-light.png")
    }
}
