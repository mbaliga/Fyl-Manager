package io.github.mbaliga.fylz.ui.activity

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mbaliga.fylz.operations.ActivityKind
import io.github.mbaliga.fylz.operations.ActivityProgress
import io.github.mbaliga.fylz.operations.ActivityTracker
import io.github.mbaliga.fylz.ui.components.rememberUriThumbnail
import kotlinx.coroutines.delay
import java.text.NumberFormat

/**
 * The owner's reference: an in-flight file operation surfaces as a full-bleed band across the very
 * top of the screen, shrinks to a thin progress bar on its own after a few seconds, and expands to
 * a detail panel on a tap of either -- "present at the top, multiple simultaneous activities
 * supported".
 *
 * **Full-bleed, flush to the top edge.** Every state runs edge to edge with square top corners and
 * a rounded lower lip, so it reads as a band the screen wears rather than a floating card. That is
 * why this composable takes no horizontal inset of its own, and its caller mounts it without one.
 *
 * **Several at once share ONE row.** Minimized activities divide a single [MinimizedRow]
 * horizontally, a segment each, rather than stacking into a pile of bars that would push the
 * listing further down with every operation started -- the reference's own three-up frame. Each
 * segment is a real progress bar: accent up to its own fraction, track behind the rest, so the top
 * edge says how far along each one is rather than merely that something is running.
 *
 * At most one activity is expanded at a time; the reference never shows two detail panels, so a
 * tap on a second replaces the first rather than stacking.
 */
@Composable
fun ActivityOverlay(tracker: ActivityTracker, modifier: Modifier = Modifier) {
    val activities by tracker.activities.collectAsState()
    if (activities.isEmpty()) return

    var expandedId by remember { mutableStateOf<String?>(null) }
    // An expanded card whose activity just finished would otherwise leave expandedId pointing at
    // an id no longer in the list -- harmless to what draws, but it would silently block the NEXT
    // activity from expanding until a second tap cleared it.
    LaunchedEffect(activities) {
        if (activities.none { it.id == expandedId }) expandedId = null
    }

    val expanded = activities.firstOrNull { it.id == expandedId }
    val rest = activities.filter { it.id != expandedId }
    // Split by whether each is still inside its own opening window. Evaluated for every activity
    // (not short-circuited) so each one's timer is a stable composable call across recompositions.
    val notifying = rest.filter { isNotifying(it) }
    val minimized = rest.filterNot { it in notifying }

    Column(modifier.fillMaxWidth()) {
        if (expanded != null) {
            ExpandedCard(
                activity = expanded,
                accent = accentFor(expanded.kind),
                icon = iconFor(expanded.kind),
                fraction = fractionOf(expanded),
                onTap = { expandedId = null },
            )
        }
        notifying.forEach { activity ->
            key(activity.id) {
                NotificationCard(
                    activity = activity,
                    accent = accentFor(activity.kind),
                    icon = iconFor(activity.kind),
                    onTap = { expandedId = activity.id },
                )
            }
        }
        if (minimized.isNotEmpty()) {
            MinimizedRow(minimized, onTap = { expandedId = it.id })
        }
    }
}

/**
 * Whether [activity] is still inside its opening notification window. Keyed on the activity's own
 * id, not a bare `remember`: one activity finishing and another arriving at the same list position
 * must not inherit an already-elapsed timer.
 */
@Composable
private fun isNotifying(activity: ActivityProgress): Boolean {
    var notifying by remember(activity.id) { mutableStateOf(true) }
    LaunchedEffect(activity.id) {
        delay(NOTIFICATION_DURATION_MS)
        notifying = false
    }
    return notifying
}

/** How long a freshly started activity holds its full notification before shrinking on its own. */
private const val NOTIFICATION_DURATION_MS = 2_500L

private val BandInk = Color(0xFF0B0B0D)
private val MinimizedRowHeight = 6.dp
private val BandCorner = 18.dp

private fun fractionOf(activity: ActivityProgress): Float =
    if (activity.itemCount > 0) {
        (activity.itemIndex.toFloat() / activity.itemCount).coerceIn(0f, 1f)
    } else {
        0f
    }

internal fun accentFor(kind: ActivityKind): Color = when (kind) {
    // Never the only signal telling two concurrent activities apart -- see [iconFor] -- but a
    // real, stable colour per KIND of operation rather than a borrowed status colour: no faked
    // "warning" or "saved" state Fylz cannot actually back up for a plain transfer.
    ActivityKind.TRANSFER -> Color(0xFF2F80ED)
    ActivityKind.ARCHIVE -> Color(0xFFE0912F)
    ActivityKind.DOCUMENT -> Color(0xFF34C171)
}

internal fun iconFor(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.TRANSFER -> Icons.AutoMirrored.Outlined.DriveFileMove
    ActivityKind.ARCHIVE -> Icons.Outlined.Archive
    ActivityKind.DOCUMENT -> Icons.Outlined.Description
}

/**
 * "4,822 of 12,366" -- grouped by the reader's own locale, the count at full strength and the
 * total quieter behind it, which is the reference's own two-weight treatment. Grouping earns its
 * keep at this scale: `12366` is a number you parse, `12,366` is one you read.
 */
@Composable
private fun CountText(activity: ActivityProgress, modifier: Modifier = Modifier) {
    val grouped = remember(activity.itemIndex, activity.itemCount) {
        val format = NumberFormat.getIntegerInstance()
        format.format(activity.itemIndex.toLong()) to format.format(activity.itemCount.toLong())
    }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                append(grouped.first)
            }
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.55f))) {
                append(" of ${grouped.second}")
            }
        },
        fontSize = 15.sp,
        maxLines = 1,
        modifier = modifier,
    )
}

/** The opening state: a full-bleed band, square against the screen's top edge, rounded below. */
@Composable
internal fun NotificationCard(
    activity: ActivityProgress,
    accent: Color,
    icon: ImageVector,
    onTap: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = BandCorner, bottomEnd = BandCorner))
            .background(BandInk)
            .clickable(role = Role.Button, onClickLabel = "${activity.label} details", onClick = onTap)
            .padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Text(
            activity.label,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CountText(activity)
    }
}

/**
 * The shared minimized row: one hairline band across the top, divided horizontally between every
 * activity currently minimized, each segment carrying its own fill and accent.
 */
@Composable
internal fun MinimizedRow(activities: List<ActivityProgress>, onTap: (ActivityProgress) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(MinimizedRowHeight)) {
        activities.forEach { activity ->
            key(activity.id) {
                MinimizedSegment(
                    accent = accentFor(activity.kind),
                    fraction = fractionOf(activity),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Button, onClickLabel = "${activity.label} details") {
                            onTap(activity)
                        },
                )
            }
        }
    }
}

@Composable
private fun MinimizedSegment(accent: Color, fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.background(BandInk)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
    }
}

/**
 * The detail panel: the same full-bleed band grown to carry a preview of what is actually in
 * flight, the label and count, and the operation's own progress across the bottom.
 *
 * The preview fans up to three real thumbnails of the items being moved, resolved through the same
 * provider-thumbnail path every listing row uses ([rememberUriThumbnail]). Items with nothing
 * behind them -- documents, archives, anything the provider will not render -- simply do not draw,
 * and a batch where none resolves falls back to the kind glyph rather than inventing artwork for
 * files that have none.
 */
@Composable
internal fun ExpandedCard(
    activity: ActivityProgress,
    accent: Color,
    icon: ImageVector,
    fraction: Float,
    onTap: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = BandCorner, bottomEnd = BandCorner))
            .background(BandInk)
            .clickable(role = Role.Button, onClickLabel = "Collapse ${activity.label}", onClick = onTap)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(124.dp), contentAlignment = Alignment.Center) {
            PreviewFan(activity.previewUris, accent, icon)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                activity.label,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CountText(activity)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(accent),
            )
        }
    }
}

/** Up to three thumbnails, fanned and overlapping the way a handful of picked-up cards sits. */
@Composable
private fun PreviewFan(uris: List<Uri>, accent: Color, icon: ImageVector) {
    val resolved = uris.take(3).mapNotNull { uri -> rememberUriThumbnail(uri)?.let { uri to it } }
    if (resolved.isEmpty()) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(36.dp))
        }
        return
    }
    Box(contentAlignment = Alignment.Center) {
        resolved.forEachIndexed { index, (_, bitmap) ->
            // The middle card upright and front-most, the others tucked behind at a slight lean,
            // so the stack reads as several things picked up rather than one picture.
            val offset = index - (resolved.size - 1) / 2f
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        rotationZ = offset * 9f
                        translationX = offset * 52f
                        val shrink = if (offset == 0f) 1f else 0.9f
                        scaleX = shrink
                        scaleY = shrink
                    }
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
    }
}
