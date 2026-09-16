package io.github.mbaliga.fylz.ui.components.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.data.DeckKind
import io.github.mbaliga.fylz.data.DeckSlide
import io.github.mbaliga.fylz.data.PresentationDeck
import io.github.mbaliga.fylz.data.PresentationDeckReader
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.components.UniversalInspectorPreview
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A deck, one slide at a time, in the deck's own order.
 *
 * This shows each slide's TEXT -- its title and the lines on it -- and says so plainly in a
 * footer, because Fylz has no slide renderer: there is no bundled way to rasterize PowerPoint
 * layout, theming and vector art, and captioning an outline as a rendered slide would be the
 * clearest possible way to lie about what this does. What it is instead is genuinely per-slide:
 * slide 4's text is slide 4's, in the order the author put it there.
 *
 * Formats with no bundled reader (.ppt, .key) never reach here.
 */
@Composable
fun PresentationPreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val kind = remember(descriptor.extension) { PresentationDeckReader.deckKind(descriptor.extension) }
    if (kind == null) {
        UniversalInspectorPreview(entry, descriptor, modifier, "Fylz has no bundled reader for this presentation format.")
        return
    }
    val deck by produceState<Result<PresentationDeck>?>(null, entry.uri, kind) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val reader = PresentationDeckReader {
                    context.contentResolver.openInputStream(entry.uri) ?: error("Unable to read this presentation.")
                }
                when (kind) {
                    DeckKind.OOXML -> reader.readOoxml()
                    DeckKind.OPEN_DOCUMENT -> reader.readOpenDocument()
                }
            }
        }
    }
    when (val result = deck) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { parsed -> DeckContent(parsed, modifier) },
            onFailure = { failure ->
                UniversalInspectorPreview(
                    entry,
                    descriptor,
                    modifier,
                    failure.message ?: "This presentation could not be read safely.",
                )
            },
        )
    }
}

@Composable
private fun DeckContent(deck: PresentationDeck, modifier: Modifier) {
    var index by remember(deck) { mutableIntStateOf(0) }
    val current = deck.slides.getOrNull(index.coerceIn(0, deck.slides.lastIndex)) ?: return

    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TactileIconKey(
                    icon = Icons.Outlined.ChevronLeft,
                    contentDescription = "Previous slide",
                    enabled = index > 0,
                    onClick = { index -= 1 },
                )
                Column(Modifier.weight(1f)) {
                    Text("Slide ${current.number} of ${deck.slides.size}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        deck.formatLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TactileIconKey(
                    icon = Icons.Outlined.ChevronRight,
                    contentDescription = "Next slide",
                    enabled = index + 1 < deck.slides.size,
                    onClick = { index += 1 },
                )
            }
        }
        // Numbers rather than thumbnails: a thumbnail strip would have to show something, and the
        // only honest something here is the slide's number.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            deck.slides.forEachIndexed { position, slide ->
                SlideJump(slide.number, position == index) { index = position }
            }
        }
        HorizontalDivider()
        SelectionContainer(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SlideBody(current)
            }
        }
        HorizontalDivider()
        Text(
            buildString {
                append(deck.note)
                if (deck.truncated) append(" Only the first ${deck.slides.size} slides were read.")
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlideBody(slide: DeckSlide) {
    if (slide.empty) {
        Text(
            "This slide carries no text. Its picture or chart content is not rendered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    slide.title?.takeIf { it.isNotBlank() }?.let { title ->
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
    slide.body.forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A jump-to-slide control. The digits are small; the target they sit in is not -- 48dp of height
 * and at least 48dp of width, per the kit's touch floor.
 */
@Composable
private fun SlideJump(number: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(onClickLabel = "Go to slide $number", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
