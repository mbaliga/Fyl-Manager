package io.github.mbaliga.fylz.ui.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.IndexManagerActivity
import io.github.mbaliga.fylz.R
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.index.IndexScope
import io.github.mbaliga.fylz.index.IndexState
import io.github.mbaliga.fylz.index.IndexedFile
import io.github.mbaliga.fylz.index.LocalIndexStore
import io.github.mbaliga.fylz.library.LibraryStore
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The search box's suggestion layer: what can be completed while a query is being typed, what an
 * empty box can offer as a starting point, and what a query that matched nothing can escalate to.
 *
 * Everything here is an OFFER. Nothing in this file rewrites a live query on its own: a completion
 * replaces only the token the user is still typing and only on an explicit tap, a starter fills an
 * empty box, and the device-wide search runs only once its own button is pressed. Silent widening
 * -- quietly re-running a failed search somewhere else and presenting the result as if it were the
 * answer to what was asked -- is the one behaviour this layer must never have.
 *
 * The vocabulary is real, not invented here:
 * - facet keys come from [io.github.mbaliga.fylz.search.FylzSearch.registry]'s own
 *   [dev.aarso.search.FieldRegistry.keys], so a completion is only ever a key the parser
 *   recognises;
 * - kind values come from [io.github.mbaliga.fylz.search.FylzSearch.vocabulary]'s
 *   [dev.aarso.search.NaturalVocabulary.kinds], the same table `type:`/`kind:` resolves against;
 * - tags come from [LibraryStore.allTags], counts included, so a tag is only ever offered when
 *   something actually carries it.
 *
 * The pure halves ([searchCompletionsFor], [applySearchCompletion], [searchStartersFor],
 * [deviceIndexNeedle], [deviceIndexCoverage]) take no Compose and no Android types beyond the data
 * classes, so the string surgery is testable without a composition.
 */

// ── Completion ────────────────────────────────────────────────────────────────────────────

/** What a tapped [SearchCompletion] leaves behind in the box. */
enum class SearchCompletionKind {
    /** A bare token becomes a facet key (`typ` -> `type:`), and the box waits for the value. */
    FACET_KEY,

    /** The value after a key is filled in (`type:im` -> `type:image `), and the term is closed. */
    FACET_VALUE,
}

/**
 * One completion offered for the token currently being typed.
 *
 * @param insert the text that REPLACES that token -- never the whole query.
 * @param label what the chip reads.
 * @param detail a substantiated qualifier shown after the label (a tag's item count), or null.
 */
data class SearchCompletion(
    val insert: String,
    val label: String,
    val detail: String? = null,
    val kind: SearchCompletionKind,
)

/**
 * The trailing token of [query] -- what a caret sitting at the end of the box is inside.
 *
 * The box is a `BasicTextField(String)`, which hands this file text and nothing else: there is no
 * caret offset to read, so "the token being typed" is defined as the run after the last
 * whitespace. That is exactly right while someone types forward (the usual case) and deliberately
 * offers nothing when they have gone back to edit mid-query -- better than completing a token
 * their caret is not in.
 */
internal data class QueryTail(val start: Int, val token: String)

internal fun queryTail(query: String): QueryTail {
    val cut = query.indexOfLast { it.isWhitespace() }
    return QueryTail(cut + 1, query.substring(cut + 1))
}

/**
 * Completions for [query]'s trailing token, most relevant first, at most [limit].
 *
 * Bare token -> the facet keys it prefixes. Token with a colon -> the values that key actually
 * has. Values exist for `type`/`kind` ([kindValues]), `tag` ([tagCounts]) and `is`; `size` and
 * `modified` take open-ended expressions no vocabulary can enumerate, and `ext` has no verified
 * source of extensions in this app, so none of the three offers values -- the syntax help teaches
 * their shape instead of this pretending to know their contents.
 *
 * A leading `-` (search-core's negation, see `QueryParser`) is carried through rather than eaten,
 * so completing `-typ` gives `-type:` and not `type:`.
 */
fun searchCompletionsFor(
    query: String,
    facetKeys: List<String>,
    kindValues: List<String>,
    tagCounts: Map<String, Int>,
    limit: Int = 6,
): List<SearchCompletion> {
    val raw = queryTail(query).token
    // A quote or a slash means the token is a phrase or a regex mid-flight; completing inside
    // either would corrupt it, and neither has a vocabulary to complete against anyway.
    if (raw.isEmpty() || raw.any { it == '"' || it == '/' || it == '(' || it == ')' }) return emptyList()
    val negation = if (raw.startsWith("-")) "-" else ""
    val token = raw.removePrefix("-")
    if (token.isEmpty()) return emptyList()
    val colon = token.indexOf(':')
    return if (colon < 0) {
        facetKeys.asSequence()
            .filter { it.startsWith(token, ignoreCase = true) }
            .take(limit)
            .map { key ->
                SearchCompletion(
                    insert = "$negation$key:",
                    label = "$key:",
                    kind = SearchCompletionKind.FACET_KEY,
                )
            }
            .toList()
    } else {
        val key = token.substring(0, colon)
        val typed = token.substring(colon + 1)
        valueCompletions(negation, key, typed, kindValues, tagCounts, limit)
    }
}

private fun valueCompletions(
    negation: String,
    key: String,
    typed: String,
    kindValues: List<String>,
    tagCounts: Map<String, Int>,
    limit: Int,
): List<SearchCompletion> {
    val values: List<Pair<String, String?>> = when (key.lowercase()) {
        "type", "kind" -> kindValues.filter { it.startsWith(typed, ignoreCase = true) }.map { it to null }
        "tag" -> tagCounts.entries
            .filter { it.key.isNotBlank() && it.value > 0 && it.key.startsWith(typed, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
            )
            .map { it.key to it.value.toString() }
        "is" -> listOf("folder", "file").filter { it.startsWith(typed, ignoreCase = true) }.map { it to null }
        else -> emptyList()
    }
    return values.take(limit).map { (value, detail) ->
        SearchCompletion(
            insert = "$negation$key:${quoteIfNeeded(value)}",
            label = "$key:$value",
            detail = detail,
            kind = SearchCompletionKind.FACET_VALUE,
        )
    }
}

/** A facet value carrying whitespace has to be quoted for `QueryParser` to read it as one value
 *  (`tag:"work trip"`); a bare one must not be, or the quotes end up in the tag name. */
private fun quoteIfNeeded(value: String): String =
    if (value.any { it.isWhitespace() }) "\"$value\"" else value

/**
 * [query] with its trailing token replaced by [completion] -- the only edit this layer ever makes
 * to a live query, and only from a tap. Everything before that token is preserved byte for byte.
 *
 * A completed VALUE closes with a space so the next term starts clean; a completed KEY does not,
 * because the caret belongs immediately after the colon where the value goes.
 */
fun applySearchCompletion(query: String, completion: SearchCompletion): String {
    val tail = queryTail(query)
    val trailing = if (completion.kind == SearchCompletionKind.FACET_VALUE) " " else ""
    return query.substring(0, tail.start) + completion.insert + trailing
}

// ── Starting points for an empty box ──────────────────────────────────────────────────────

enum class SearchStarterKind { RECENT, TAG, KIND }

/** One offer for an empty box: [query] is what the box becomes when it is tapped. */
data class SearchStarter(
    val query: String,
    val label: String,
    val detail: String? = null,
    val kind: SearchStarterKind,
)

/**
 * What an empty, focused box offers: prior queries first, then tags, then kinds.
 *
 * Only things that can actually return something are offered. A tag is offered only when
 * [tagCounts] says items carry it; a kind is offered only when the caller has established it is
 * present ([kindStartersFrom] over the listing it is about to search) -- this function invents
 * neither, and an empty [kinds] means the caller could not vouch for any, so none are shown.
 *
 * The two guarantees are not the same strength, and the copy is careful about it. A kind starter
 * is present in the very listing about to be searched. A tag's count is the LIBRARY's -- every
 * item carrying it, wherever it lives -- so `tag:work` can still come back empty in one folder,
 * which is why the chip's own reading is "carried by 12 items" and never "12 items here". A
 * folder-scoped tag count would mean a tag lookup per entry per keystroke, which is the wrong
 * trade for a suggestion; the zero-result escalation is what catches the case that misses.
 */
fun searchStartersFor(
    recentSearches: List<String>,
    tagCounts: Map<String, Int>,
    kinds: List<String>,
    kindLabel: (String) -> String = { it },
    recentLimit: Int = 4,
    tagLimit: Int = 4,
    kindLimit: Int = 4,
): List<SearchStarter> = buildList {
    recentSearches.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(recentLimit)
        .forEach { add(SearchStarter(it, it, null, SearchStarterKind.RECENT)) }
    tagCounts.entries.asSequence()
        .filter { it.key.isNotBlank() && it.value > 0 }
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
        )
        .take(tagLimit)
        .forEach { add(SearchStarter("tag:${quoteIfNeeded(it.key)}", it.key, it.value.toString(), SearchStarterKind.TAG)) }
    kinds.asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .take(kindLimit)
        .forEach { add(SearchStarter("type:$it", kindLabel(it), null, SearchStarterKind.KIND)) }
}

/** Canonical `type:` values actually present in [entries], in a fixed display order rather than
 *  whatever order the folder happened to list. Only these can honestly be offered as starters:
 *  every one of them has at least one entry behind it. */
fun kindStartersFrom(entries: List<FileEntry>): List<String> {
    val present = entries.mapNotNullTo(HashSet<String>()) { canonicalKindOf(it.kind) }
    return KIND_STARTER_ORDER.filter { it in present }
}

/** Display order for [kindStartersFrom] -- folders first, then the media kinds people search for
 *  by name most often. Values match `FylzSearch`'s own canonical `type:` values. */
private val KIND_STARTER_ORDER =
    listOf("folder", "image", "video", "audio", "pdf", "archive", "text", "markdown")

private fun canonicalKindOf(kind: EntryKind): String? = when (kind) {
    EntryKind.DIRECTORY -> "folder"
    EntryKind.IMAGE -> "image"
    EntryKind.VIDEO -> "video"
    EntryKind.AUDIO -> "audio"
    EntryKind.PDF -> "pdf"
    EntryKind.ARCHIVE -> "archive"
    EntryKind.TEXT -> "text"
    EntryKind.MARKDOWN -> "markdown"
    // No canonical `type:` value maps to it, so nothing here can be offered honestly.
    EntryKind.OTHER -> null
}

// ── The device index behind the zero-result escalation ────────────────────────────────────

/**
 * What the on-device index can currently answer for -- read straight off
 * [io.github.mbaliga.fylz.index.LocalIndexStore]'s persisted state, never assumed.
 *
 * This is the WIRED index: `IndexManagerActivity` and `PostV1ToolsActivity` build it through
 * [io.github.mbaliga.fylz.index.LocalIndexScheduler]. (A second, unreferenced implementation of
 * the same idea -- `library/LocalFileIndex.kt` -- used to sit beside it, writing to the same
 * `files.json` path with a different schema; it has been deleted, and the `index` package is the
 * only rule engine and on-device index in the app.)
 */
data class DeviceIndexCoverage(
    val indexedFiles: Int,
    val enabledScopes: Int,
    val lastCompletedAtMillis: Long?,
    val truncated: Boolean,
    val paused: Boolean,
    val lastError: String?,
) {
    /** Whether the index holds anything at all. Everything else about it -- paused, truncated, a
     *  failed rebuild -- is a caveat printed ALONGSIDE its answer, never a reason to hide it or to
     *  present a thin answer as a complete one. */
    val hasContent: Boolean get() = indexedFiles > 0 && lastCompletedAtMillis != null
}

fun deviceIndexCoverage(state: IndexState, scopes: List<IndexScope>): DeviceIndexCoverage =
    DeviceIndexCoverage(
        indexedFiles = state.indexedFiles.coerceAtLeast(0),
        enabledScopes = scopes.count { it.enabled },
        lastCompletedAtMillis = state.lastCompletedAtMillis,
        truncated = state.truncated,
        paused = state.paused,
        lastError = state.lastError,
    )

/**
 * The one substring the device index can be asked for, or null when the query has nothing it
 * could match on.
 *
 * [io.github.mbaliga.fylz.index.LocalIndexStore.query] is a single case-insensitive `contains`
 * over name, extension and tags -- it has no facet grammar at all. Handing it `type:pdf report`
 * verbatim would match nothing and read as an honest device-wide zero, which would be a lie. So
 * the escalation is offered on the query's longest plain word instead, and the UI says outright
 * which word it used and that only names and tags were consulted.
 */
fun deviceIndexNeedle(query: String): String? =
    query.split(' ', '\t', '\n', '\r')
        .map { it.trim().trim('"').removePrefix("-") }
        .filter {
            it.length >= 2 &&
                !it.contains(':') &&
                !it.startsWith("/") &&
                it.lowercase() !in QUERY_OPERATOR_WORDS
        }
        .maxByOrNull { it.length }

private val QUERY_OPERATOR_WORDS = setOf("and", "or", "not")

/** One device-index answer: how many files matched in total, and the first few of them. */
data class DeviceSearchOutcome(val total: Int, val shown: List<IndexedFile>)

/** Runs the device-index search off the main thread. [shown] caps only what the caller renders --
 *  [DeviceSearchOutcome.total] is the real count, never the truncated one. */
suspend fun searchDeviceIndex(context: Context, needle: String, shown: Int = 4): DeviceSearchOutcome =
    withContext(Dispatchers.IO) {
        val hits = LocalIndexStore(context.applicationContext).query(needle)
        DeviceSearchOutcome(hits.size, hits.take(shown))
    }

/** The folder part of an indexed file's document URI, for telling two same-named hits apart.
 *  Empty when the URI carries no path to read -- shown only when there is something real to
 *  show, never a placeholder. */
internal fun indexedFileFolder(file: IndexedFile): String =
    runCatching { Uri.parse(file.uri).lastPathSegment.orEmpty() }
        .getOrDefault("")
        .substringAfter(':')
        .substringBeforeLast('/', missingDelimiterValue = "")

// ── Compose surfaces ──────────────────────────────────────────────────────────────────────

/**
 * [LibraryStore.allTags] read once each time [active] turns true, off the main thread.
 *
 * That store documents its own caching obligation -- `preferences.all` copies the whole map, so it
 * must never be called per recomposition. Keyed on [active] so the scan happens when a suggestion
 * surface is actually about to use it (the field taking focus) and not on every keystroke, and so
 * a tag added since the last focus is picked up on the next one.
 */
@Composable
fun rememberLibraryTagCounts(active: Boolean): Map<String, Int> {
    val context = LocalContext.current
    var counts by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(active) {
        if (active) {
            counts = withContext(Dispatchers.IO) { LibraryStore(context.applicationContext).allTags() }
        }
    }
    return counts
}

/** The completions for the token being typed, as one scrolling row of chips. Tapping one inserts
 *  it; nothing here changes the query on its own. */
@Composable
fun SearchCompletionRow(
    completions: List<SearchCompletion>,
    onPick: (SearchCompletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (completions.isEmpty()) return
    ChipScroller(modifier) {
        completions.forEach { completion ->
            SearchChip(
                label = completion.label,
                detail = completion.detail,
                description = stringResource(R.string.search_completion_insert, completion.label),
                onClick = { onPick(completion) },
            )
        }
    }
}

/** An empty box's starting points, as one scrolling row of chips. */
@Composable
fun SearchStarterRow(
    starters: List<SearchStarter>,
    onPick: (SearchStarter) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (starters.isEmpty()) return
    ChipScroller(modifier) {
        starters.forEach { starter ->
            SearchChip(
                label = when (starter.kind) {
                    SearchStarterKind.TAG -> "#${starter.label}"
                    else -> starter.label
                },
                detail = starter.detail,
                description = when (starter.kind) {
                    SearchStarterKind.RECENT -> stringResource(R.string.search_starter_recent, starter.label)
                    SearchStarterKind.TAG ->
                        stringResource(R.string.search_starter_tag, starter.label, starter.detail.orEmpty())
                    SearchStarterKind.KIND -> stringResource(R.string.search_starter_kind, starter.label)
                },
                onClick = { onPick(starter) },
            )
        }
    }
}

/**
 * The syntax reference, shown only where it can teach: behind the pill's own help key, or under an
 * empty focused box. It is the one place the non-guessable half of the grammar is spelled out, so
 * it stays complete rather than being trimmed to fit -- concealment is the affordance's job, not
 * this content's.
 */
@Composable
fun SearchSyntaxHelp(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HelpLine(stringResource(R.string.browser_search_hint))
        HelpLine(stringResource(R.string.search_syntax_example_type))
        HelpLine(stringResource(R.string.search_syntax_example_ext))
        HelpLine(stringResource(R.string.search_syntax_example_size))
        HelpLine(stringResource(R.string.search_syntax_example_modified))
        HelpLine(stringResource(R.string.search_syntax_example_tag))
        HelpLine(stringResource(R.string.search_syntax_example_phrase))
    }
}

@Composable
private fun HelpLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What a query that matched nothing is offered instead -- an offer, in escalating order of cost,
 * that the user accepts or ignores.
 *
 * 1. Widening the scope to the whole subtree, when the search is still confined to one folder
 *    ([canWidenScope]). This is the cheap, complete answer: the recursive engine walks live
 *    storage, so it needs no index and is never stale.
 * 2. The device index, which is a different KIND of answer and is described as one: it covers only
 *    the folders the user added to it, only as of its last rebuild, and matches names and tags
 *    rather than the full query grammar. Every one of those limits is printed next to the offer,
 *    before it is accepted -- a device-wide "no matches" that quietly meant "the index is empty"
 *    would be the worst answer this surface could give.
 *
 * Neither runs on its own.
 *
 * @param onOpenDeviceHit when non-null, device hits become tappable and this is called with the
 *   hit's URI. When null the hits are still listed -- they answer "does it exist, and where" --
 *   but they carry no tap affordance, rather than a dead one.
 */
@Composable
fun ZeroResultEscalation(
    query: String,
    canWidenScope: Boolean,
    onWidenScope: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeviceHit: ((Uri) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val needle = remember(query) { deviceIndexNeedle(query) }
    var coverage by remember { mutableStateOf<DeviceIndexCoverage?>(null) }
    // Keyed on the query: an answer to the last one must never be left standing under a new one.
    var outcome by remember(query) { mutableStateOf<DeviceSearchOutcome?>(null) }
    var running by remember(query) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        coverage = withContext(Dispatchers.IO) {
            val store = LocalIndexStore(context.applicationContext)
            deviceIndexCoverage(store.state(), store.scopes())
        }
    }

    Column(
        modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Note(stringResource(R.string.search_zero_here), maxLines = 2)
        if (canWidenScope) {
            TactileButton(
                text = stringResource(R.string.search_zero_widen),
                onClick = onWidenScope,
                style = TactileButtonStyle.SECONDARY,
            )
        }

        val current = coverage
        when {
            // Still reading the index's own state. Saying nothing is the only honest thing to
            // show here; a placeholder count would be a number nothing stands behind.
            current == null -> Unit

            !current.hasContent -> {
                Note(stringResource(R.string.search_device_none))
                TactileButton(
                    text = stringResource(R.string.search_device_setup),
                    onClick = { context.startActivity(Intent(context, IndexManagerActivity::class.java)) },
                    style = TactileButtonStyle.SECONDARY,
                )
            }

            needle == null -> {
                Note(stringResource(R.string.search_device_needs_word))
            }

            else -> {
                Note(deviceCoverageLine(current))
                if (current.truncated) Note(stringResource(R.string.search_device_truncated))
                if (current.paused) Note(stringResource(R.string.search_device_paused))
                current.lastError?.let { Note(stringResource(R.string.search_device_error, it)) }
                if (outcome == null) {
                    Note(stringResource(R.string.search_device_needle, needle))
                    TactileButton(
                        text = stringResource(R.string.search_zero_device),
                        onClick = {
                            running = true
                            scope.launch {
                                outcome = runCatching { searchDeviceIndex(context, needle) }
                                    .getOrDefault(DeviceSearchOutcome(0, emptyList()))
                                running = false
                            }
                        },
                        style = TactileButtonStyle.SECONDARY,
                        enabled = !running,
                    )
                }
                if (running) Note(stringResource(R.string.search_device_searching))
                outcome?.let { DeviceHits(it, onOpenDeviceHit) }
            }
        }
    }
}

@Composable
private fun DeviceHits(outcome: DeviceSearchOutcome, onOpen: ((Uri) -> Unit)?) {
    if (outcome.total == 0) {
        Note(stringResource(R.string.search_device_no_results))
        return
    }
    Note(stringResource(R.string.search_device_results, outcome.total))
    outcome.shown.forEach { file ->
        val folder = indexedFileFolder(file)
        val openLabel = stringResource(R.string.search_device_open, file.name)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (onOpen == null) {
                        Modifier
                    } else {
                        Modifier
                            .clickable(role = Role.Button, onClickLabel = openLabel) {
                                onOpen(Uri.parse(file.uri))
                            }
                            .semantics { contentDescription = openLabel }
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                file.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (folder.isNotEmpty()) {
                Text(
                    folder,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val remaining = outcome.total - outcome.shown.size
    if (remaining > 0) Note(stringResource(R.string.search_device_more, remaining))
}

/** "1,240 files across 3 folders, indexed 2 days ago" -- every figure read from the index's own
 *  persisted state, none of it estimated. */
@Composable
private fun deviceCoverageLine(coverage: DeviceIndexCoverage): String {
    val updated = coverage.lastCompletedAtMillis?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    return if (updated == null) {
        stringResource(R.string.search_device_coverage_undated, coverage.indexedFiles)
    } else {
        stringResource(R.string.search_device_coverage, coverage.indexedFiles, coverage.enabledScopes, updated)
    }
}

@Composable
private fun Note(text: String, maxLines: Int = 3) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ChipScroller(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One suggestion chip. Kept an M3 [SuggestionChip] for the same reason the pill's own filter and
 * recent rows are (a tight scrolling flow of taps reads as chips, not as keycaps), with the kit's
 * 48dp touch floor imposed on top -- the stock chip is 32dp tall, which is under it. `heightIn`
 * outside the chip's own fixed height wins: a fixed size coerces itself into the incoming minimum.
 */
@Composable
private fun SearchChip(label: String, detail: String?, description: String, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        label = {
            Text(
                if (detail == null) label else "$label · $detail",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    )
}
