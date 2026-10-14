package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FolderTab

/**
 * The owner's mockup pill tab strip, restyled from Fylz's own existing multi-tab-browsing state
 * (`BrowserViewModel.tabs`/`activeTabId`, the [FolderTab] data class) -- not a new tab-bar
 * component. There was no PRE-EXISTING rendering of `tabs` as a horizontal strip anywhere in this
 * app to restyle in place (confirmed by reading every `tabs`/`activeTabId` use site in
 * `FylzV1App.kt` and `ui/actions/RoomActionsRenderer.kt`): the only place open tabs were already
 * visible was `LocationsRoom`'s vertical `WordWheelRail`, a left-edge drawer, not a strip on the
 * main browse screen. This composable is the mockup's pill strip, newly built against that same
 * existing state, kept in its own file per the size ratchet. See `docs/agent/PROGRESS.md` for the
 * full note on this (a documented deviation, not silently invented scope).
 *
 * Show/hide: renders only when there is more than one tab (`tabs.size > 1`) -- the one documented
 * reading of "two permanent tabs for a screen most sessions never open is chrome charging rent"
 * (`FylzAppShell.kt`'s own header comment) that still applies to a feature the owner has now asked
 * to exist: don't show a switcher when there is nothing to switch between. The call site
 * (`FylzV1App.kt`'s `Scaffold.bottomBar`) is the one that actually gates this, so this composable
 * itself does not re-check `tabs.size`; it draws whatever it is given.
 */
@Composable
fun FolderTabStrip(
    tabs: List<FolderTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        // Neutral chrome, deliberately not the accent: only the active pill below carries it.
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                val active = tab.id == activeTabId
                Surface(
                    color = if (active) MaterialTheme.colorScheme.inversePrimary else Color.Transparent, // accent role: the chosen accent on the inverse bar
                    contentColor = if (active) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clickable { onSelect(tab.id) },
                ) {
                    Text(
                        tab.title,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
