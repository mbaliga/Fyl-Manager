package io.github.mbaliga.fylz.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.search.Op
import dev.aarso.search.QueryCompiler
import dev.aarso.search.QueryNode

/**
 * Every tag the library knows about, with how many items carry each -- the surface the owner
 * asked for and the app never had ("I do not see the tags functionality ... there is no way to
 * see what tags exist, no way to browse or filter by one").
 *
 * Tapping a row hands its bare name to [onTagSelected] rather than a finished search-box string:
 * [tagSearchQuery] is the one place that turns a name into `tag:<name>` text, so this list's tap
 * handler and the landing overview's own tag chips (`ui/overview/OverviewCards.kt`'s `TagsCard`,
 * which hands its own tap the same bare name) can share one lambda at the call site instead of
 * two subtly different ideas of what "pick a tag" means.
 *
 * @param tags every known tag mapped to its item count, in the order this list draws them --
 *   from [io.github.mbaliga.fylz.library.LibraryStore.allTags], which already returns them
 *   sorted case-insensitively. That scan copies the whole preferences map, so the caller must
 *   compute it inside a `remember` keyed on whatever version counter it bumps on write (the
 *   `tagsVersion` idiom) rather than call it fresh on every recomposition this list happens to
 *   go through.
 */
@Composable
fun TagBrowser(
    tags: Map<String, Int>,
    onTagSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                "No tags yet. Tag a file to see it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier) {
        items(tags.entries.toList(), key = { it.key }) { (tag, count) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onTagSelected(tag) }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .semantics { contentDescription = "$tag, $count items" },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Sell,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    tag,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The exact `tag:<name>` search-box text for [tag] -- rendered through the same [QueryCompiler]
 * every other chip in the app round-trips through, rather than a hand-rolled `"tag:$tag"` that
 * would silently misparse the moment a tag's name has a space or a paren in it (a tag is up to 40
 * arbitrary characters -- see `LibraryStore.normalizeTags` -- so this is a real case, not a
 * hypothetical one). `FylzSearch`'s `TagFacet` already matches any text case-insensitively once
 * parsed; the only real risk here was ever in *building* the query string correctly, which is why
 * the fix lives beside the tag UI that needs it rather than as a change to `FylzSearch.kt` itself.
 *
 * The one place this is meant to be called from is wherever a bare tag name reaches a "filter by
 * this tag" action -- [TagBrowser]'s own [onTagSelected] and the overview's tag chips alike.
 */
fun tagSearchQuery(tag: String): String =
    QueryCompiler.renderCanonical(QueryNode.Facet(key = "tag", op = Op.EQ, value = tag))
