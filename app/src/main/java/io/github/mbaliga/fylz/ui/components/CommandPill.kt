package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.R

/**
 * How much vertical room the pill and its margins claim at the bottom of the browser.
 *
 * Exported so the listing can reserve it as content padding and the edge scrubber can stop
 * short of it. A floating control that hides the last row of the thing it controls is worse
 * than a docked one, and a travel strip that runs underneath it is a target you cannot hit.
 */
val CommandPillReservedHeight: Dp = 88.dp

/**
 * The browser's command pill: a small floating bar above the bottom edge, holding search and
 * the two controls worth showing outright.
 *
 * This replaces a full-width row of chrome that sat directly under the app bar — a back button,
 * a boxed text field, a sort menu and a select-all button, stacked on top of the toolbar's own
 * two buttons, so the top of every folder was two bands of controls before a single file. The
 * pill is one band, it floats clear of the listing rather than pushing it down, and it sits
 * where the thumb already is.
 *
 * It stays deliberately shallow: search plus sort plus select-all, and nothing else. Everything
 * that *changes* a file lives in the actions room, and the moment this pill starts collecting
 * "one more useful button" it becomes the row it replaced.
 *
 * The search-scope chips and the query-syntax hint appear above the pill only while a query is
 * live: the scope choice is meaningless with an empty box, and drawing both unconditionally is
 * how the old row ended up as tall as it was.
 *
 * @param trailing the controls shown outright to the right of the box — sort and select-all.
 */
@Composable
fun CommandPill(
    query: String,
    onQueryChange: (String) -> Unit,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    searchRecursive: Boolean,
    onSearchRecursiveChange: (Boolean) -> Unit,
    searchBusy: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    // No navigationBarsPadding here: the pill lives inside the Scaffold's content, which is
    // already inset for the system bars. Applying it again floats the pill a nav bar's
    // height above where it belongs.
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (query.isNotBlank()) {
            Row(
                Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = !searchRecursive,
                    onClick = { onSearchRecursiveChange(false) },
                    label = { Text("This folder") },
                )
                FilterChip(
                    selected = searchRecursive,
                    onClick = { onSearchRecursiveChange(true) },
                    label = { Text("Everything below") },
                )
                if (searchBusy) CircularProgressIndicator(Modifier.size(18.dp))
            }
            // The query syntax is not guessable, so it is taught where it is used. Only
            // while a query is live: on an empty box it is a tip about nothing.
            Text(
                stringResource(R.string.browser_search_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    enabled = canNavigateUp,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Outlined.ArrowBack, stringResource(R.string.browser_parent_folder))
                }

                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                stringResource(R.string.browser_search_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                    }
                }
                trailing()
            }
        }
    }
}

/**
 * The listing's content padding when the pill is floating over it: the caller's own padding
 * plus enough room at the bottom that the last row clears the pill.
 */
fun listingPaddingFor(base: Dp, reserve: Dp = CommandPillReservedHeight): PaddingValues =
    PaddingValues(start = base, top = base, end = base, bottom = base + reserve)
