package io.github.mbaliga.fylz.ui.tags

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.ui.theme.ThemeStyle

/**
 * Every row/card/tile's tags, looked up by uri -- an empty set when nothing was ever tagged, in
 * which case [TagMark] draws nothing. A function rather than a plain map so the composition root
 * can back it with [io.github.mbaliga.fylz.library.LibraryStore] plus whatever cache and
 * invalidation (a `tagsVersion` counter bumped on every write, the same idiom the store's own
 * KDoc names) it needs, without threading a store handle through every surface that wants to show
 * a mark. Same shape and the same reason as
 * [io.github.mbaliga.fylz.ui.components.LocalFolderAppearance]. Defaults to "nothing is ever
 * tagged" so a preview or a test that never provides one still draws cleanly.
 */
val LocalTagsFor: ProvidableCompositionLocal<(Uri) -> Set<String>> =
    compositionLocalOf { { _: Uri -> emptySet() } }

/**
 * A quiet mark that [uri] carries at least one tag -- nothing at all when it carries none, so a
 * caller can place this unconditionally (a `Box { EntryThumbnail(...); TagMark(entry.uri, ...) }`
 * the way `CanvasTile.kt`'s `SelectionMark` already rides a thumbnail's corner) without checking
 * first.
 *
 * Three registers, matching [ThemeStyle] itself rather than [io.github.mbaliga.fylz.ui.theme
 * .FolderMaterial] -- a tag mark rides a *file's* corner as often as a folder's, so it reads the
 * theme directly. None of the three is a bare tinted dot: `docs/DESIGN.md` forbids colour alone
 * carrying meaning, and a dot with no shape or glyph difference from any other coloured dot in
 * the theme would be exactly that.
 *
 *  - **Neo / Fylz**: a small filled badge carrying the tag glyph, echoing `SelectionMark`'s own
 *    disc-on-surface treatment for these two styles.
 *  - **Vintage / Retro**: a flat one-colour square, no icon, no curve -- the same swap
 *    `SelectionMark` makes for these two, matching the integer-grid pixel art they draw
 *    everywhere else.
 *  - **CLI**: a single monospace character in the gutter's own amber accent (`cliScheme`'s
 *    `secondary`, reserved by that scheme's own KDoc for exactly this kind of mark) rather than a
 *    shape, since the style draws no icons at all. `CliListing.kt` builds its rows as one plain
 *    string rather than a composable tree, so this register is for any *composable* row this
 *    style reaches (details, canvas, grid) -- splicing a mark into `CliListing`'s own line text
 *    is that file's own change to make, not something this composable can reach into.
 */
@Composable
fun TagMark(uri: Uri, modifier: Modifier = Modifier) {
    if (LocalTagsFor.current(uri).isEmpty()) return
    val markModifier = modifier.semantics { contentDescription = "Tagged" }
    when (LocalThemeStyle.current) {
        ThemeStyle.NEO, ThemeStyle.FYLZ -> Box(
            markModifier
                .size(18.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Sell,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(11.dp),
            )
        }

        ThemeStyle.VINTAGE, ThemeStyle.RETRO ->
            Box(markModifier.size(14.dp).background(MaterialTheme.colorScheme.primary)) {}

        ThemeStyle.CLI -> Text(
            "#",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = markModifier,
        )
    }
}
