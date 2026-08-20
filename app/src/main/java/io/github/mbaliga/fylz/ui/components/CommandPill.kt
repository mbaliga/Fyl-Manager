package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.mbaliga.fylz.ui.chrome.TabBandHeight

/**
 * How much vertical room the search surface claims when it is showing, for a listing that wants
 * to reserve content padding beneath it.
 *
 * This used to cover the pill's tab strip too; the tabs are [io.github.mbaliga.fylz.ui.chrome.TabBand]
 * now, with their own [io.github.mbaliga.fylz.ui.chrome.TabBandHeight], and a live selection adds
 * [io.github.mbaliga.fylz.ui.chrome.SelectionRowHeight] on top of that -- three independent
 * heights instead of one blanket constant, because the pill itself is no longer a permanent
 * fixture a listing must always clear. A caller reserves whichever of the three is actually
 * mounted, not all three unconditionally.
 */
val CommandPillSearchHeight: Dp = 88.dp

/**
 * The browser's command pill: search, its scope, and the parser's reading of a live query.
 *
 * Tabs used to ride a strip along the top of this pill; they now live in their own bottom band
 * ([io.github.mbaliga.fylz.ui.chrome.TabBand]), overlapping folder tabs on a black plinth rather
 * than a row of chips, and the pill no longer needs to know tabs exist at all. What's left is a
 * transient surface -- shown while search is actually in use, not a permanent fixture the browser
 * always floats above -- and search plus sort plus select-all, and nothing else. Everything that
 * *changes* a file lives in the actions room; a button that *does* something to the current file
 * has no business here even now that the pill has more room to spare.
 *
 * The search-scope chips and the query-syntax hint appear above the field only while a query is
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
    modifier: Modifier = Modifier,
    // Additive, optional: null (the default) leaves the field exactly as it was. A caller that
    // programmatically reveals this field with nothing to focus first (Workstream W's FocusSearch
    // command -- see FylzV1App.kt) attaches one so the reveal actually lands the keyboard, not
    // just the field's visibility.
    focusRequester: FocusRequester? = null,
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
                        modifier = Modifier.fillMaxWidth()
                            .onFocusChanged { fieldFocused = it.isFocused }
                            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
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
 * The listing's content padding when chrome floats over its bottom edge: the caller's own padding
 * plus enough room at the bottom that the last row clears it.
 *
 * [reserve] defaults to [io.github.mbaliga.fylz.ui.chrome.TabBandHeight] alone -- the tab band is
 * the one piece of bottom chrome that's always present. A caller with a live selection (adding
 * [io.github.mbaliga.fylz.ui.chrome.SelectionRowHeight]) or a revealed search field (adding
 * [CommandPillSearchHeight]) passes the taller sum explicitly rather than relying on this default.
 */
fun listingPaddingFor(base: Dp, reserve: Dp = TabBandHeight): PaddingValues =
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
