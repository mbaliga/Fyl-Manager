package io.github.mbaliga.fylz.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
            .semantics { contentDescription = "Open device storage" }
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
        modifier = modifier.semantics { contentDescription = "Open Downloads" },
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
 * A folder with a name and a count under it. [peek] is the photo that pokes out of the top, used
 * by the Images tile for the newest picture; the front panel is slightly see-through so it ghosts
 * through, as in the reference.
 */
@Composable
internal fun FolderTile(
    label: String,
    subtitle: String,
    peek: Uri?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open $label. $subtitle" }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FolderGlyph(peek = peek, modifier = Modifier.fillMaxWidth(0.78f))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The folder drawing: a back panel with a tab, an optional photo, and a translucent front panel. */
@Composable
private fun FolderGlyph(peek: Uri?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val back = scheme.surfaceContainerHighest
    val front = scheme.surfaceContainerLowest
    val edge = scheme.outlineVariant
    BoxWithConstraints(modifier.aspectRatio(1.3f)) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width * 0.07f
            // Back body, then the tab rising from its top-left.
            drawRoundRect(back, Offset(size.width * 0.06f, size.height * 0.10f), Size(size.width * 0.88f, size.height * 0.80f), CornerRadius(r))
            drawRoundRect(back, Offset(size.width * 0.06f, 0f), Size(size.width * 0.42f, size.height * 0.30f), CornerRadius(r))
        }
        if (peek != null) {
            AsyncImage(
                model = peek,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = w * 0.42f, y = h * -0.02f)
                    .size(width = w * 0.46f, height = h * 0.50f)
                    .rotate(6f)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width * 0.07f
            val topLeft = Offset(size.width * 0.02f, size.height * 0.26f)
            val panel = Size(size.width * 0.96f, size.height * 0.72f)
            drawRoundRect(
                Brush.verticalGradient(listOf(front.copy(alpha = 0.90f), front.copy(alpha = 0.97f))),
                topLeft,
                panel,
                CornerRadius(r),
            )
            drawRoundRect(edge, topLeft, panel, CornerRadius(r), style = Stroke(width = 1.dp.toPx()))
            // The two hairlines along the bottom edge that make it read as a stack of papers.
            for (y in floatArrayOf(0.86f, 0.90f)) {
                drawLine(edge, Offset(size.width * 0.10f, size.height * y), Offset(size.width * 0.90f, size.height * y), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

/** The recycle bin as a card: how many files, and a one-tap way to empty it for good. */
@Composable
internal fun DeletedFilesCard(
    count: Int,
    onOpen: () -> Unit,
    onShredAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = CardShape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier.semantics { contentDescription = "Open recycle bin, ${LandingText.deletedCount(count)} files" },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Deleted Files", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).weight(1f, fill = false), contentAlignment = Alignment.Center) {
                Text(
                    LandingText.deletedCount(count),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                )
            }
            Surface(
                onClick = onShredAll,
                enabled = count > 0,
                shape = CircleShape,
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Shred all deleted files" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "SHRED ALL",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (count > 0) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
