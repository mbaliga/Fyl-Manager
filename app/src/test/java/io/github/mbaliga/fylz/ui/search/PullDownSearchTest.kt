package io.github.mbaliga.fylz.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.aarso.search.ChipKind
import dev.aarso.search.QueryChip
import io.github.mbaliga.fylz.model.AccentPreset
import io.github.mbaliga.fylz.model.ThemeMode
import io.github.mbaliga.fylz.ui.components.CommandPill
import io.github.mbaliga.fylz.ui.components.CommandPillFieldBodyTag
import io.github.mbaliga.fylz.ui.components.CommandPillSearchHeight
import io.github.mbaliga.fylz.ui.theme.FylzTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The band's side of the reveal: what [PullDownSearchHost] does to the field it is handed.
 *
 * [PullDownGestureTest] covers the accumulate/threshold/latch decision, which is pure and needs no
 * composition. Nothing there could have caught what shipped in Build 12, because that failure was
 * a measurement fact rather than a decision: the host forced its `searchField` slot to
 * [CommandPillSearchHeight], and a Column short of room does not overflow -- it hands each child
 * the remainder and coerces a fixed `height` down into it. [CommandPill]'s field body is the last
 * child in every state, so with a live query above it (scope toggle, syntax hint) it measured at
 * nothing: a scope toggle floating alone in an empty band, with no way to see or clear the query
 * that was hiding the user's files. Every assertion here reads a height the layout actually
 * produced, so a fixed height imposed on the field again fails here rather than on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-420dpi")
class PullDownSearchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * A revealed host, with the real pill in its band and a handle on the two things a test needs
     * to poke: the query the pill is showing, and whichever [PullDownSearchState] the composition
     * is holding *now*, not the one it started with -- which is the whole point of the growth test
     * below.
     */
    private inner class Revealed(initialQuery: String, chips: List<QueryChip>) {
        private var query by mutableStateOf(initialQuery)
        private lateinit var captured: PullDownSearchState

        val state: PullDownSearchState get() = captured

        init {
            compose.setContent {
                FylzTheme(themeMode = ThemeMode.DARK, accentPreset = AccentPreset.MOSS, dynamicColor = false) {
                    val hosted = rememberPullDownSearchState(revealHeight = CommandPillSearchHeight)
                    captured = hosted
                    PullDownSearchHost(
                        listAtTop = true,
                        revealHeight = CommandPillSearchHeight,
                        state = hosted,
                        searchField = {
                            CommandPill(
                                query = query,
                                onQueryChange = { query = it },
                                searchRecursive = true,
                                onSearchRecursiveChange = {},
                                // False deliberately: the busy spinner is the one indefinite
                                // animation the pill can mount, and waitForIdle would never
                                // return with it up.
                                searchBusy = false,
                                chips = chips,
                                modifier = Modifier.testTag(PILL),
                                trailing = {},
                            )
                        },
                        content = { listModifier -> Box(listModifier.fillMaxSize().testTag(LISTING)) },
                    )
                }
            }
            compose.runOnIdle { captured.reveal() }
            compose.waitForIdle()
        }

        fun type(text: String) {
            compose.runOnIdle { query = text }
            compose.waitForIdle()
        }
    }

    private fun revealed(query: String = "", chips: List<QueryChip> = emptyList()) = Revealed(query, chips)

    /**
     * The field body got its own height rather than the Column's leftovers.
     *
     * Probed directly through [CommandPillFieldBodyTag]: the pill's own fixed-`height(56.dp)`
     * body, present in every state the pill has, so it stands or falls with the body's own
     * measurement -- 56dp when it gets its full height, less when a starving parent Column
     * coerces that `height()` modifier down instead of overflowing. Reading the tagged node's own
     * height is more direct than the earlier probe through a child control that happened to sit
     * inside it (see git history) -- there is now no such control living in this file at all.
     */
    private fun assertFieldNotStarved() {
        compose.onNodeWithTag(CommandPillFieldBodyTag, useUnmergedTree = true).assertHeightIsAtLeast(56.dp)
    }

    /**
     * The whole pill sits inside the band: nothing cropped off the top, nothing running under the
     * listing below it. Read off *unclipped* bounds deliberately -- a clipped pill still reports
     * where it wanted to be, which is the only way to tell "fits" from "cropped".
     */
    private fun assertPillFullyInBand() {
        val pill = compose.onNodeWithTag(PILL).getUnclippedBoundsInRoot()
        val listingTop = compose.onNodeWithTag(LISTING).getUnclippedBoundsInRoot().top
        // A dp of slack for the px<->dp round trip the band's height makes on the way through
        // Modifier.height. The starve this guards was tens of dp, never a rounding edge.
        assertTrue("the pill's top edge (${pill.top}) is cropped off the top of the band", pill.top >= -1.dp)
        assertTrue("the pill runs to ${pill.bottom}, past a listing starting at $listingTop", pill.bottom <= listingTop)
    }

    @Test
    fun `an empty field is fully revealed`() {
        // The one state that always worked -- 80dp of pill inside the 88dp band -- pinned so the
        // fix is not read as licence to regress it.
        revealed()
        assertFieldNotStarved()
        assertPillFullyInBand()
    }

    @Test
    fun `a live query does not starve the field it was typed into`() {
        // The owner's shots 1 and 2: scope toggle and syntax hint stacked above the field, 160dp
        // of pill inside an 88dp band whose Column could only ever spend 64dp of it.
        revealed(query = "report")
        assertFieldNotStarved()
        assertPillFullyInBand()
    }

    @Test
    fun `the tallest state -- toggle, chips and hint together -- still shows the field`() {
        revealed(
            query = "type:pdf report",
            chips = listOf(
                QueryChip(text = "report", kind = ChipKind.TERM),
                QueryChip(text = "type:pdf", kind = ChipKind.FACET, key = "type"),
            ),
        )
        assertFieldNotStarved()
        assertPillFullyInBand()
    }

    @Test
    fun `a field that grows under a latched reveal keeps its gesture`() {
        // The hazard the fix is built around: sizing the band off a state-dependent height is only
        // safe while that height stays out of the gesture's own `remember` key. Typing the first
        // character of a query near triples the pill, and if that reached
        // [rememberPullDownSearchState] the gesture, its Animatable and the latch would all be
        // rebuilt underneath the user's finger. [Revealed.state] re-reads whatever the composition
        // is holding now, so a rebuilt state is a fresh, unlatched one here and fails.
        val host = revealed()
        assertTrue("the reveal must be latched before the pill grows", host.state.isRevealed)

        host.type("type:pdf report")

        assertTrue("a taller pill must not rebuild the search gesture", host.state.isRevealed)
        assertFieldNotStarved()
        assertPillFullyInBand()
    }

    private companion object {
        const val PILL = "command-pill"
        const val LISTING = "listing"
    }
}
