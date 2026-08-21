package io.github.mbaliga.fylz.ui.cluster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.operations.RecycleRecord
import io.github.mbaliga.fylz.staging.LoopedCarousel
import io.github.mbaliga.fylz.staging.StagedItem
import io.github.mbaliga.fylz.staging.StagingTray
import io.github.mbaliga.fylz.staging.TrayKind
import io.github.mbaliga.fylz.ui.components.StackCard
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.ui.tactile.TactileToggle
import io.github.mbaliga.fylz.ui.tactile.TactileToggleOption

/**
 * The expanded clipboard/move bulge: the tray's contents, browsable.
 *
 * The scroll is the looped carousel from the reference — flick in either direction forever,
 * the items simply come around again ([LoopedCarousel] owns the wrap-around math). Removing an
 * item is the inverse of how it arrived: it flew UP into the bulge, so pulling it DOWN past
 * the release threshold takes it back out. Because a pull is a gesture and gestures are never
 * the only path (DESIGN.md), every card also carries a custom accessibility action with the
 * same effect.
 *
 * @param onCommitHere "Paste here" / "Move here" into the folder the browser is showing.
 */
@Composable
internal fun TrayBrowserSheet(
    tray: StagingTray,
    otherTray: StagingTray?,
    onPickTray: (TrayKind) -> Unit,
    onRemove: (StagedItem) -> Unit,
    onCommitHere: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(30f)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // Consume taps so the scrim's dismiss doesn't fire through the sheet: an
                // ENABLED no-op click claims them; a disabled one would let them fall through.
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Text(
                        tray.kind.title(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "  ·  ${tray.size} ${if (tray.size == 1) "file" else "files"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TactileIconKey(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close ${tray.kind.title()}",
                        onClick = onDismiss,
                    )
                }

                // Genuinely a 2-way single-select -- TrayKind has exactly CLIPBOARD/MOVE, never
                // more -- so this is TactileToggle's own shape (2-3 segments) rather than the
                // stock FilterChip pair it replaces. selectedIndex/onSelect map straight onto
                // the same two-candidate list the chips iterated.
                if (otherTray != null && !otherTray.isEmpty) {
                    val candidates = listOf(tray, otherTray).sortedBy { it.kind }
                    TactileToggle(
                        options = candidates.map { candidate ->
                            val text = "${candidate.kind.title()} · ${candidate.size}"
                            TactileToggleOption(label = text, contentDescription = text)
                        },
                        selectedIndex = candidates.indexOfFirst { it.kind == tray.kind },
                        onSelect = { index -> onPickTray(candidates[index].kind) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                LoopedTrayRow(
                    tray = tray,
                    onRemove = onRemove,
                    // Matches PullableTrayCard's own 138dp height -- LazyRow measures each card
                    // with this as its cross-axis MAX, so leaving this at the card's old height
                    // would silently coerce the taller card back down regardless of its own
                    // `.size()`.
                    modifier = Modifier.fillMaxWidth().height(138.dp).padding(top = 12.dp),
                )
                Text(
                    "Pull a file down to take it off the ${tray.kind.title().lowercase()}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    TactileButton(
                        text = if (tray.kind == TrayKind.CLIPBOARD) "Paste here" else "Move here",
                        onClick = onCommitHere,
                        style = TactileButtonStyle.PRIMARY,
                        enabled = !tray.isEmpty,
                    )
                    TactileButton(text = "Clear", onClick = onClear, style = TactileButtonStyle.SECONDARY, enabled = !tray.isEmpty)
                }
            }
        }
    }
}

/** The endless strip of staged files. */
@Composable
private fun LoopedTrayRow(
    tray: StagingTray,
    onRemove: (StagedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tray.isEmpty) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Nothing staged.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // The list state survives item removals; keying items by uri keeps the carousel stable as
    // the modulo mapping shifts underneath it.
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = LoopedCarousel.startIndex(tray.size),
    )
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        modifier = modifier,
    ) {
        items(
            count = LoopedCarousel.virtualCount(tray.size),
            key = { it },
        ) { virtualIndex ->
            val item = tray.items[LoopedCarousel.itemIndex(virtualIndex, tray.size)]
            PullableTrayCard(item = item, onRemove = { onRemove(item) })
        }
    }
}

/**
 * One staged file: rides its row, comes out by being pulled down past the threshold. The card
 * tracks the finger 1:1 while pulled (house rule — drags track, releases decide) and fades as
 * it approaches the point of no return, so the release outcome is legible before it happens.
 */
@Composable
private fun PullableTrayCard(item: StagedItem, onRemove: () -> Unit) {
    val density = LocalDensity.current
    var pull by remember(item.uri) { mutableFloatStateOf(0f) }
    val threshold = with(density) { 72.dp.toPx() }
    val removeLabel = "Remove ${item.displayName}"

    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp,
        modifier = Modifier
            // Sized for its real content now: an 84dp StackCard plus a 2-line caption needs more
            // than the old icon-and-name card did, or both clip against this Surface's own
            // rounded-corner shape.
            .size(width = 116.dp, height = 138.dp)
            .graphicsLayer {
                translationY = pull
                alpha = 1f - (pull / (threshold * 1.6f)).coerceIn(0f, 0.55f)
            }
            .pointerInput(item.uri) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, delta ->
                        val next = pull + delta
                        // Upward pulls stop at rest: the only way OUT of the tray is down.
                        pull = next.coerceAtLeast(0f)
                        change.consume()
                    },
                    onDragEnd = {
                        if (pull >= threshold) onRemove() else pull = 0f
                    },
                    onDragCancel = { pull = 0f },
                )
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(removeLabel) {
                        onRemove()
                        true
                    },
                )
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        ) {
            StackCard(
                entry = item.entry,
                fallbackName = item.displayName,
                kind = item.kind,
                size = 84.dp,
            )
            Text(
                item.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The expanded trash bulge: what this session recycled through the can, each item offering the
 * two honest ways forward — back where it was, or through the shredder. Buttons, not gestures:
 * both actions here are consequential, and DESIGN.md requires the non-gesture path anyway.
 */
@Composable
internal fun TrashBrowserSheet(
    records: List<RecycleRecord>,
    onPutBack: (RecycleRecord) -> Unit,
    onShred: (RecycleRecord) -> Unit,
    onShredAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(30f)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    TrashGlyph(proximity = 0f, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                    Text(
                        "  In the can",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    TactileIconKey(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close the trash can",
                        onClick = onDismiss,
                    )
                }
                Text(
                    "Everything currently in the Recycle Bin.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(records, key = RecycleRecord::itemId) { record ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        ) {
                            Text(
                                record.originalDisplayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TactileButton(text = "Put back", onClick = { onPutBack(record) }, style = TactileButtonStyle.SECONDARY)
                            TactileButton(text = "Shred", onClick = { onShred(record) }, style = TactileButtonStyle.DESTRUCTIVE)
                        }
                    }
                }

                if (records.size > 1) {
                    TactileButton(
                        text = "Shred all ${records.size}",
                        onClick = onShredAll,
                        style = TactileButtonStyle.DESTRUCTIVE,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

private fun TrayKind.title() = when (this) {
    TrayKind.CLIPBOARD -> "Clipboard"
    TrayKind.MOVE -> "Move tray"
}
