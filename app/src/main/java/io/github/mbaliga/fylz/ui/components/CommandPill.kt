package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aarso.search.ChipKind
import dev.aarso.search.Diagnostic
import dev.aarso.search.QueryChip
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.model.FolderTab

/** The tab strip's own claim on the pill's height, added to [CommandPillReservedHeight] below. */
private val TAB_STRIP_HEIGHT: Dp = 40.dp

/**
 * How much vertical room the pill and its margins claim at the bottom of the browser.
 *
 * Exported so the listing can reserve it as content padding and the edge scrubber can stop
 * short of it. A floating control that hides the last row of the thing it controls is worse
 * than a docked one, and a travel strip that runs underneath it is a target you cannot hit.
 *
 * `88.dp` was sized for the search Surface alone; the tab strip is a permanent row above it now
 * (there is always at least the open tab), not a conditional one like the search-scope chips, so
 * its height is folded into the one constant every reader already trusts rather than left for
 * six call sites to each remember to add separately.
 */
val CommandPillReservedHeight: Dp = 88.dp + TAB_STRIP_HEIGHT

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
 * "one more useful button" it becomes the row it replaced. The tab strip below is not an
 * exception carved into that rule — it is not a button. Multiple tabs open together don't make
 * sense without a place that shows which one you're in and lets you reach the others, the same
 * way the search box and the up-arrow already answer "where am I, how do I leave" for a single
 * folder. Navigation earns its rent here; a button that *does* something to the current file
 * still does not.
 *
 * The search-scope chips and the query-syntax hint appear above the pill only while a query is
 * live: the scope choice is meaningless with an empty box, and drawing both unconditionally is
 * how the old row ended up as tall as it was. Once the parser has an opinion about the query, its
 * reading appears the same way Spotlight's does: a row of removable [chips], one filter each, so
 * the interpretation is visible and correctable rather than a black box. An empty, focused box
 * shows [recentSearches] instead — the two rows never compete for the same line because one only
 * exists when the other cannot.
 *
 * @param chips the parser's reading of [query] ([dev.aarso.search.ParsedQuery.chips]) — one
 *   removable chip per recognised term or facet.
 * @param onRemoveChip called with the chip the user dismissed; the caller drops it from [chips]
 *   and reparses (`(chips - chip).toQueryText()`), which is what makes removal behave like undoing
 *   one thing typed rather than clearing the whole box.
 * @param diagnostics parse-time notices ([dev.aarso.search.Diagnostic]) — an unknown field, a
 *   value nothing indexes. Only the first renders; a query malformed in six ways at once still
 *   gets one quiet line, not a stack of them.
 * @param recentSearches up to eight prior queries, most recent first, shown only while the field
 *   is focused and [query] is blank.
 * @param onRecentSearchSelected called with the tapped recent query; the caller sets it as the
 *   live query.
 * @param tabs every open tab, drawn as a chip strip above the search box — the pill's answer to
 *   "multiple tabs open together won't make sense otherwise". Defaulted to empty so a caller that
 *   hasn't wired tabs yet still compiles; a real browsing surface always has at least the one
 *   it's showing.
 * @param activeTabId which of [tabs] reads as selected.
 * @param onTabSelected called with the id of the tapped tab.
 * @param onTabClosed called with the tab whose close glyph was tapped — a separate target from
 *   the chip body, which selects instead.
 * @param onAddTab called from the strip's trailing "+" — the caller launches the same SAF folder
 *   picker a new tab already opens from elsewhere.
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
    chips: List<QueryChip> = emptyList(),
    onRemoveChip: (QueryChip) -> Unit = {},
    diagnostics: List<Diagnostic> = emptyList(),
    recentSearches: List<String> = emptyList(),
    onRecentSearchSelected: (String) -> Unit = {},
    tabs: List<FolderTab> = emptyList(),
    activeTabId: String? = null,
    onTabSelected: (String) -> Unit = {},
    onTabClosed: (FolderTab) -> Unit = {},
    onAddTab: () -> Unit = {},
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    var fieldFocused by remember { mutableStateOf(false) }

    // No navigationBarsPadding here: the pill lives inside the Scaffold's content, which is
    // already inset for the system bars. Applying it again floats the pill a nav bar's
    // height above where it belongs.
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Always drawn, not gated behind tabs.size > 1: the strip is where a tab is opened from
        // as much as where it's switched between, and a lone tab's chip is still the answer to
        // "which folder am I in" the search box alone doesn't give.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                InputChip(
                    selected = tab.id == activeTabId,
                    onClick = { onTabSelected(tab.id) },
                    label = {
                        Text(tab.title.ifBlank { "Folder" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingIcon = {
                        // Its own click target, independent of the chip body's onClick above: the
                        // body selects the tab, this closes it, and a tap must only ever do one.
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Close ${tab.title}",
                            modifier = Modifier
                                .size(14.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onTabClosed(tab) },
                        )
                    },
                )
            }
            IconButton(onClick = onAddTab, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = "Open a new tab")
            }
        }

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
            if (chips.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chips.forEach { chip ->
                        InputChip(
                            selected = false,
                            onClick = { onRemoveChip(chip) },
                            label = { Text(chipLabel(chip)) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove filter",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }
            // The query syntax is not guessable, so it is taught where it is used. Only
            // while a query is live: on an empty box it is a tip about nothing.
            Text(
                stringResource(R.string.browser_search_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // One line, not one per diagnostic: a query malformed several ways at once still owes
            // the user a single, readable notice rather than a stack that pushes the pill down.
            diagnostics.firstOrNull()?.let {
                Text(
                    diagnosticMessage(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        } else if (fieldFocused && recentSearches.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recentSearches.forEach { recent ->
                    SuggestionChip(
                        onClick = { onRecentSearchSelected(recent) },
                        label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
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
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.browser_parent_folder))
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
                        modifier = Modifier.fillMaxWidth().onFocusChanged { fieldFocused = it.isFocused },
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

/** Display plural for a `type:`/`kind:` facet value, matching `FylzSearch`'s kind vocabulary --
 *  kept as a display-only mirror rather than an import from that module, since this file has no
 *  other reason to know about [io.github.mbaliga.fylz.search.FylzSearch] and a chip label is a
 *  presentation concern, not a search one. */
private val KIND_CHIP_LABELS: Map<String, String> = mapOf(
    "folder" to "Folders",
    "image" to "Images",
    "video" to "Videos",
    "audio" to "Audio",
    "pdf" to "PDFs",
    "archive" to "Archives",
    "text" to "Text files",
    "markdown" to "Markdown files",
    "document" to "Documents",
    "screenshot" to "Screenshots",
)

/**
 * A chip's canonical text (`type:image`, `modified:>=october`) rendered as something a person
 * would type, not something they'd parse. Bare terms and phrases already read fine as themselves,
 * so only facet chips ([ChipKind.FACET]) get rewritten, and only the facets a natural-language
 * query actually produces ([io.github.mbaliga.fylz.search.FylzSearch]'s `type`/`modified`/`size`)
 * -- every other facet key falls back to its raw text, which is still legible (`tag:work`,
 * `is:folder`) even if it isn't prose.
 */
private fun chipLabel(chip: QueryChip): String {
    if (chip.kind != ChipKind.FACET) return chip.text
    val key = chip.key?.lowercase() ?: return chip.text
    val value = chip.text.substringAfter(':', "")
    val body = when (key) {
        "type", "kind" -> KIND_CHIP_LABELS[value.lowercase()] ?: value.replaceFirstChar { it.titlecase() }
        "modified", "date", "when" -> humanizeModifiedValue(value)
        "size" -> humanizeSizeValue(value)
        else -> return chip.text
    }
    return if (chip.negated) "Not $body" else body
}

/** `last-week` -> "Last week", `december-2024` -> "December 2024", `>=october` -> "Since October".
 *  Checked longest-prefix-first (`>=`/`<=` before `>`/`<`) since the shorter operator is itself a
 *  prefix of the longer one. */
private fun humanizeModifiedValue(raw: String): String {
    val (prefix, body) = when {
        raw.startsWith(">=") -> "Since " to raw.removePrefix(">=")
        raw.startsWith("<=") -> "Through " to raw.removePrefix("<=")
        raw.startsWith("!=") -> "Not " to raw.removePrefix("!=")
        raw.startsWith(">") -> "After " to raw.removePrefix(">")
        raw.startsWith("<") -> "Before " to raw.removePrefix("<")
        else -> "" to raw
    }
    return prefix + body.replace('-', ' ').replaceFirstChar { it.titlecase() }
}

/** `>25mb` -> "Over 25MB", `<100kb` -> "Under 100KB". */
private fun humanizeSizeValue(raw: String): String {
    val (prefix, body) = when {
        raw.startsWith(">=") -> "At least " to raw.removePrefix(">=")
        raw.startsWith("<=") -> "At most " to raw.removePrefix("<=")
        raw.startsWith("!=") -> "Not " to raw.removePrefix("!=")
        raw.startsWith(">") -> "Over " to raw.removePrefix(">")
        raw.startsWith("<") -> "Under " to raw.removePrefix("<")
        else -> "" to raw
    }
    return prefix + body.uppercase()
}

/** One sentence per [Diagnostic] case -- see that sealed interface's own KDoc for what each one
 *  means and why it isn't fatal. */
private fun diagnosticMessage(diagnostic: Diagnostic): String = when (diagnostic) {
    is Diagnostic.UnknownField -> if (diagnostic.suggestion != null) {
        "Unknown filter “${diagnostic.typed}” -- did you mean “${diagnostic.suggestion}”?"
    } else {
        "Unknown filter “${diagnostic.typed}”"
    }
    is Diagnostic.UnindexedFacet -> "Nothing matches ${diagnostic.key}:${diagnostic.value}"
    is Diagnostic.UnterminatedQuote -> "Unclosed quote"
    is Diagnostic.UnterminatedRegex -> "Unclosed pattern"
    is Diagnostic.InvalidDate -> "“${diagnostic.raw}” isn't a date ${diagnostic.key} understands"
    is Diagnostic.InvalidNumber -> "“${diagnostic.raw}” isn't a number ${diagnostic.key} understands"
    is Diagnostic.SemanticUnavailable -> "Semantic search isn't available here"
}
