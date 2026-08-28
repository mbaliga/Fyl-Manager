package io.github.mbaliga.fylz.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aarso.search.ChipKind
import dev.aarso.search.Diagnostic
import dev.aarso.search.QueryChip
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.search.FylzSearch
import io.github.mbaliga.fylz.ui.chrome.TabBandHeight
import io.github.mbaliga.fylz.ui.search.SearchCompletionRow
import io.github.mbaliga.fylz.ui.search.SearchStarterRow
import io.github.mbaliga.fylz.ui.search.SearchSyntaxHelp
import io.github.mbaliga.fylz.ui.search.ZeroResultEscalation
import io.github.mbaliga.fylz.ui.search.applySearchCompletion
import io.github.mbaliga.fylz.ui.search.rememberLibraryTagCounts
import io.github.mbaliga.fylz.ui.search.searchCompletionsFor
import io.github.mbaliga.fylz.ui.search.searchStartersFor
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.ui.tactile.TactileToggle
import io.github.mbaliga.fylz.ui.tactile.TactileToggleOption
import io.github.mbaliga.fylz.ui.tactile.drawTactileSlashTick
import io.github.mbaliga.fylz.ui.tactile.tactileFieldGroove
import io.github.mbaliga.fylz.ui.tactile.tactilePalette
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle

/**
 * How far a downward drag must travel to reveal the search surface -- the pull distance that
 * latches [io.github.mbaliga.fylz.ui.search.PullDownSearchHost]'s band open. **Not a height this
 * pill is ever drawn at**, despite the name, which predates the pill growing state-dependent rows
 * and should be read as `CommandPillRevealDistance` (renaming it means touching `FylzV1App.kt`,
 * which this change does not own).
 *
 * The distinction is the whole defect it was in: [CommandPill] has no single height. A live query
 * adds the scope toggle, the parser's chips, a completion row and a diagnostic line -- and, once a
 * query is known to have matched nothing, the escalation offer, which is the tallest state this
 * pill has -- from 80dp empty to past 240dp with all of it showing. The syntax hint that used to
 * ride along unconditionally is now one discreet line under an empty box, or the help panel, and
 * either can also wrap at a raised font scale. Imposing this constant on it as a height did not
 * overflow the extra rows, it starved them: a Column short of room hands each child the remainder
 * and coerces a fixed `height` into whatever is left, and the field is the last child in every
 * state, so it was the one measured at nothing. The host measures the pill instead and draws its
 * band to that.
 *
 * Kept a constant rather than derived from that measurement because it keys the gesture
 * ([io.github.mbaliga.fylz.ui.search.rememberPullDownSearchState]): a value that moved when the
 * pill grew would rebuild the gesture on a keystroke and drop a live pull.
 *
 * It is not a figure any listing reserves. Bottom chrome is what a listing must clear -- the tab
 * band's own [io.github.mbaliga.fylz.ui.chrome.TabBandHeight] always, plus
 * [io.github.mbaliga.fylz.ui.chrome.SelectionRowHeight] while a selection is live -- and the
 * revealed band is not bottom chrome; see [listingPaddingFor].
 */
val CommandPillSearchHeight: Dp = 88.dp

/** Tags the pill's own fixed-`height(56.dp)` body -- the exact node a parent Column can starve by
 *  coercing that height down instead of overflowing (the Build 12 bug the search test suite
 *  guards against). Public so that suite can probe this node's real measured height directly,
 *  rather than through a child control that happens to sit inside it. */
const val CommandPillFieldBodyTag: String = "command-pill-field-body"

/** Width of the pill's own hand-drawn state slash-tick -- see [CommandPill]'s own note on why it
 *  reaches for [io.github.mbaliga.fylz.ui.tactile.drawTactileSlashTick] directly rather than the
 *  kit's [io.github.mbaliga.fylz.ui.tactile.TactileField]'s fixed `SlashSlotWidth`, which is
 *  private to that file. Same proportions, a local copy of the constant. */
private val PillSlashWidth: Dp = 20.dp

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
 * The search-scope chips appear above the field only while a query is live: the scope choice is
 * meaningless with an empty box, and drawing everything unconditionally is how the old row ended
 * up as tall as it was. Once the parser has an opinion about the query, its reading appears the
 * same way Spotlight's does: a row of removable [chips], one filter each, so the interpretation is
 * visible and correctable rather than a black box. An empty, focused box shows starting points
 * instead — the two rows never compete for the same line because one only exists when the other
 * cannot.
 *
 * **Where the syntax hint went.** It used to print unconditionally under every live query, a full
 * sentence stapled over a box someone was already typing in. The grammar genuinely is not
 * guessable, so it is not gone — it is where it can teach and nowhere else: one discreet line
 * under an EMPTY focused box, and the whole reference behind the pill's own help key
 * ([SearchSyntaxHelp]), which latches so it stays open while it is being read. A live query gets
 * completions it can act on ([SearchCompletionRow]) rather than prose it cannot.
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
 * @param onRecentSearchSelected called with a tapped starting point — a recent query, a tag or a
 *   kind — which the caller sets as the live query, exactly as it already does for a recent one.
 *   The name predates tags and kinds joining that row; the contract ("this text becomes the
 *   query") is unchanged, so the parameter was widened rather than duplicated.
 * @param resultCount how many results the CALLER is showing for this query right now, or null
 *   when it has no count to give (still searching, or a surface that does not count). Exactly `0`
 *   is what opens the zero-result escalation, so a caller that cannot substantiate a zero must
 *   pass null rather than guess one.
 * @param kindStarters canonical `type:` values the caller has established are actually present
 *   here ([io.github.mbaliga.fylz.ui.search.kindStartersFrom] over its own listing). Empty means
 *   the caller could not vouch for any and none are offered — a kind that would return nothing is
 *   never suggested.
 * @param tagCounts the caller's own cached [io.github.mbaliga.fylz.library.LibraryStore.allTags]
 *   map, when it keeps one (`FylzV1App`'s `allTagsMap`). Null makes the pill read the store itself
 *   on focus, off the main thread — a real read either way, never an empty stand-in.
 * @param onOpenDeviceHit what a device-index hit does when tapped, after the user has accepted the
 *   zero-result escalation. Null (the default) leaves those hits listed but not tappable rather
 *   than giving them a tap that does nothing.
 * @param trailing the controls shown outright to the right of the box — sort and select-all.
 */
@Composable
fun CommandPill(
    query: String,
    onQueryChange: (String) -> Unit,
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
    resultCount: Int? = null,
    kindStarters: List<String> = emptyList(),
    tagCounts: Map<String, Int>? = null,
    onOpenDeviceHit: ((Uri) -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    var fieldFocused by remember { mutableStateOf(false) }
    // Latched, not hover/press: the reference is there to be read while typing continues, so it
    // closes on the same key that opened it and on nothing else.
    var helpOpen by remember { mutableStateOf(false) }
    // Read once, up here: it decides where the help toggle goes as well as how the pill body is
    // painted, and the two must never disagree about which theme is on.
    val cli = LocalThemeStyle.current == ThemeStyle.CLI
    val helpLabel = stringResource(
        if (helpOpen) R.string.search_syntax_help_close else R.string.search_syntax_help_open,
    )

    // Both vocabularies are static tables owned by FylzSearch -- the same ones the parser
    // validates against -- so they are built once per pill rather than per keystroke.
    val facetKeys = remember { FylzSearch.registry().keys }
    val kindValues = remember { FylzSearch.vocabulary.kinds.values.distinct().sorted() }
    // The store scan is the expensive one and carries its own caching obligation; a caller that
    // already keeps the map hands it over instead.
    val tags = if (tagCounts != null) tagCounts else rememberLibraryTagCounts(active = fieldFocused)
    val completions = remember(query, facetKeys, kindValues, tags) {
        if (query.isBlank()) emptyList() else searchCompletionsFor(query, facetKeys, kindValues, tags)
    }
    val starters = remember(recentSearches, tags, kindStarters) {
        searchStartersFor(
            recentSearches = recentSearches,
            tagCounts = tags,
            kinds = kindStarters,
            kindLabel = { KIND_CHIP_LABELS[it] ?: it },
        )
    }

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
                // Two-option TactileToggle per the conversion rule (width allows it here -- this
                // row has no other permanent occupant besides the busy spinner). BoxWithConstraints
                // inside TactileToggle needs a bounded width from its parent to size its segments;
                // weight(fill = false) gives it that bound without forcing it to stretch the full
                // remaining row width the way a plain weight(1f) would.
                TactileToggle(
                    options = listOf(
                        TactileToggleOption(label = "This folder", contentDescription = "This folder"),
                        TactileToggleOption(label = "Everything below", contentDescription = "Everything below"),
                    ),
                    selectedIndex = if (searchRecursive) 1 else 0,
                    onSelect = { index -> onSearchRecursiveChange(index == 1) },
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (searchBusy) CircularProgressIndicator(Modifier.size(18.dp))
            }
            if (chips.isNotEmpty()) {
                // Kept stock: these are small, removable, horizontally-scrolling filter pills --
                // recasting each as a TactileButton(SECONDARY, compact) keycap would fight the tight
                // chip-flow reading (a row of keycaps doesn't read as "tap to remove one filter" the
                // way a chip's own trailing X does), so per the conversion rule's own carve-out this
                // stays InputChip. Noted, not left unconsidered.
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
            // What a live query gets instead of the old always-on sentence: the completions for
            // the token still being typed. Focused only -- an unfocused box is a query someone
            // has finished with, and completing it is an offer nobody asked for.
            if (fieldFocused) {
                SearchCompletionRow(
                    completions = completions,
                    onPick = { onQueryChange(applySearchCompletion(query, it)) },
                )
            }
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
            // A zero the caller can substantiate, and not while it is still counting: an offer to
            // widen, never a widening. See ZeroResultEscalation on why the device index is
            // described before it is consulted rather than after.
            if (resultCount == 0 && !searchBusy) {
                ZeroResultEscalation(
                    query = query,
                    canWidenScope = !searchRecursive,
                    onWidenScope = { onSearchRecursiveChange(true) },
                    onOpenDeviceHit = onOpenDeviceHit,
                )
            }
        } else if (fieldFocused) {
            // Starting points for an empty box: prior queries, then tags and kinds that are known
            // to have something behind them. All three set the query, which is what
            // onRecentSearchSelected already did for the recents this row grew out of.
            SearchStarterRow(starters = starters, onPick = { onRecentSearchSelected(it.query) })
            // The one place a hint can still teach unprompted, and one discreet line of it -- the
            // full reference is behind the help key. Suppressed while that panel is open, so the
            // two never say the same thing twice.
            if (!helpOpen) {
                Text(
                    stringResource(R.string.search_hint_compact),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // CLI's half of the help toggle -- see the note on the key inside the field row. Bracketed
        // rather than iconic because that is the only vocabulary this theme has, and given the
        // touch floor by hand since it is a plain Text, not one of the kit's keys.
        if (cli) {
            Text(
                if (helpOpen) "[$helpLabel]" else " $helpLabel ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = helpLabel) { helpOpen = !helpOpen }
                    .semantics { selected = helpOpen }
                    .padding(vertical = 14.dp),
            )
        }

        if (helpOpen) SearchSyntaxHelp()

        // The pill keeps its own exact geometry (stadium shape, 56dp height, back/field/clear/
        // trailing Row) rather than wrapping in the kit's own TactileField -- that component's
        // slanted-leading-edge body and label/asterisk anatomy is built for a labelled form field,
        // not a full-bleed search bar sitting between a nav glyph and trailing controls, and forcing
        // it in here would break the pill's layout contract the conversion rules ask this call site
        // to preserve. Instead this applies the RECESSED GROOVE fill/inner-shadow recipe and the
        // state slash-tick straight from the kit's own recipe functions, onto the pill's existing
        // stadium [pillShape] -- the groove treatment the mission calls for, without the field
        // anatomy that would fight this shape. CLI theme is guarded by hand here (rather than
        // relying on a kit composable's own internal branch) since this is the one piece of the
        // pill CommandPill paints itself instead of handing to the kit -- never a faked cap/groove
        // in CLI, per the hard rule; the plain flat pill it falls back to is exactly what shipped
        // before this conversion.
        val palette = if (cli) null else tactilePalette()
        val pillShape = remember { RoundedCornerShape(50) }
        val stateColor = palette?.let { if (fieldFocused) it.accent else it.indicatorIdle }

        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(CommandPillFieldBodyTag)
                .then(
                    if (palette != null && stateColor != null) {
                        Modifier
                            .tactileFieldGroove(palette, pillShape)
                            .border(1.dp, stateColor, pillShape)
                    } else {
                        Modifier
                            .clip(pillShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    },
                ),
        ) {
            Row(
                Modifier.fillMaxHeight().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (palette != null && stateColor != null) {
                        Canvas(Modifier.width(PillSlashWidth).fillMaxHeight()) {
                            drawTactileSlashTick(stateColor, withErrorDot = false)
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = palette?.indicatorIdle ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    stringResource(R.string.browser_search_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette?.indicatorIdle ?: MaterialTheme.colorScheme.onSurfaceVariant,
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
                            cursorBrush = SolidColor(palette?.accent ?: MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                                .onFocusChanged { fieldFocused = it.isFocused }
                                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
                        )
                    }
                }

                if (query.isNotEmpty()) {
                    TactileIconKey(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Clear search",
                        onClick = { onQueryChange("") },
                    )
                }
                // The concealed half of the hint. A permanent key rather than a state-dependent
                // one: an affordance that appears only once you are already typing is not one you
                // can go looking for. It latches, so its own label says which way it goes and
                // TalkBack reads the panel as open (TactileIconKey publishes `latched` as
                // `selected`), and it is the kit's own 48dp key, not a shrunken glyph.
                //
                // Not in CLI. That theme's keys degrade to their own contentDescription as plain
                // text (see TactileIconKey's CliIconKey), so this row already carries "Clear
                // search" in full; a second label would leave the weighted field with nothing to
                // measure at on a narrow screen -- the same starve the band fix was about. CLI
                // gets the same toggle as its own row above the field instead, where a line of
                // text costs it nothing.
                if (!cli) {
                    TactileIconKey(
                        icon = Icons.Outlined.HelpOutline,
                        contentDescription = helpLabel,
                        onClick = { helpOpen = !helpOpen },
                        latched = helpOpen,
                    )
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
 * [io.github.mbaliga.fylz.ui.chrome.SelectionRowHeight]) passes the taller sum explicitly rather
 * than relying on this default.
 *
 * A revealed search field is deliberately *not* in that sum, this KDoc having once said it was:
 * [io.github.mbaliga.fylz.ui.search.PullDownSearchHost] is a Column and its band takes real space
 * above the listing rather than floating over it, so the band already shortens the listing and
 * adding [CommandPillSearchHeight] here would reserve a second time, at the wrong end.
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
