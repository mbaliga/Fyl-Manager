package io.github.mbaliga.fylz.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.ui.places.LandingText
import io.github.mbaliga.fylz.ui.places.StorageBreakdown
import io.github.mbaliga.fylz.ui.places.StorageCategory

/** Corner radius shared by every card on the landing screen, so they read as one family. */
private val CardShape = RoundedCornerShape(28.dp)

/**
 * Category colours for the storage bar. A swatch only ever sits beside the category's name and
 * size, so colour is never the sole carrier of meaning.
 */
private fun categoryColor(category: StorageCategory): Color = when (category) {
    StorageCategory.PHOTOS -> Color(0xFFF0657A)
    StorageCategory.VIDEOS -> Color(0xFF2B3A8C)
    StorageCategory.DOCUMENTS -> Color(0xFF3F78E0)
    StorageCategory.AUDIO -> Color(0xFF2FA866)
    StorageCategory.APKS -> Color(0xFFE2A53A)
}

/** The volume at a glance: used over total, a segmented bar, and what the bytes are. */
@Composable
internal fun StorageSummaryCard(
    usedBytes: Long?,
    totalBytes: Long?,
    breakdown: StorageBreakdown,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(CardShape)
            .background(scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = buildString {
                    append("Device storage")
                    if (usedBytes != null && totalBytes != null && totalBytes > 0L) {
                        append(", ").append(LandingText.usedOfTotal(usedBytes, totalBytes)).append(" used")
                    }
                    StorageCategory.entries.forEach {
                        append(", ").append(it.label).append(' ').append(LandingText.compactBytes(breakdown.bytes(it)))
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (usedBytes != null && totalBytes != null && totalBytes > 0L) {
                Text(
                    LandingText.usedOfTotal(usedBytes, totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // The bar: a plain track with coloured runs, no M3 progress indicator, so it renders
        // identically across library versions and can show several colours.
        Row(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(scheme.surface),
        ) {
            val segments = if (totalBytes != null) breakdown.segments(totalBytes) else emptyList()
            segments.forEach { (category, share) ->
                Box(Modifier.weight(share).fillMaxHeight().background(categoryColor(category)))
            }
            val rest = 1f - segments.sumOf { it.second.toDouble() }.toFloat()
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            StorageCategory.entries.forEach { category ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(categoryColor(category)))
                    Spacer(Modifier.width(8.dp))
                    Text(category.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(
                        LandingText.compactBytes(breakdown.bytes(category)),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Downloads: what is in it, or a plain "No Downloads!" so an empty folder still reads as a place. */
@Composable
internal fun DownloadsCard(
    count: Int,
    newestName: String?,
    loaded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.semantics {
            contentDescription = when {
                !loaded || count <= 0 -> "Downloads, empty"
                newestName != null -> "Downloads, $count files, newest $newestName"
                else -> "Downloads, $count files"
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
            if (loaded && count > 0) {
                Text(
                    newestName.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                if (!loaded || count <= 0) "No Downloads!" else if (count == 1) "1 Download" else "$count Downloads",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A folder with a name and a count under it, drawn as the handoff's layered [FolderIcon]. Pressing
 * fans its cards out of the front plate (the handoff's hover, which Android does not have); lifting
 * the finger settles them back. A tap still opens the folder.
 */
@Composable
internal fun FolderTile(
    label: String,
    subtitle: String,
    cards: List<FolderCardContent>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // clickable's own press state drives the fan: it waits out the scroll-detection delay inside
    // the list (so a scroll does not fan every folder it passes), and it is focusable and
    // keyboard / D-pad operable, which a raw tap detector is not.
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "Open $label",
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = "$label. $subtitle" }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FolderIcon(cards = cards, fanned = pressed, modifier = Modifier.fillMaxWidth(0.78f))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The recycle bin as a card: how many files, and a one-tap way to empty it for good. */
@Composable
internal fun DeletedFilesCard(
    count: Int,
    onOpen: () -> Unit,
    shredding: Boolean,
    onShredAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = CardShape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier.semantics { contentDescription = "Recycle bin, ${LandingText.deletedCount(count)} deleted files" },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Deleted Files", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().heightIn(min = 76.dp), contentAlignment = Alignment.Center) {
                Text(
                    LandingText.deletedCount(count),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                )
            }
            Surface(
                onClick = onShredAll,
                enabled = count > 0 && !shredding,
                shape = CircleShape,
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Shred all deleted files" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (shredding) "SHREDDING…" else "SHRED ALL",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (count > 0 && !shredding) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
