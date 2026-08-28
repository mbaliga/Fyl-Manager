package io.github.mbaliga.fylz.ui.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.operations.ActivityKind
import io.github.mbaliga.fylz.operations.ActivityProgress
import io.github.mbaliga.fylz.operations.ActivityTracker
import io.github.mbaliga.fylz.ui.motion.FylzMotion
import kotlinx.coroutines.delay

/**
 * The owner's reference: an in-flight file operation surfaces as a full notification, shrinks to
 * a thin colour-coded bar on its own after a few seconds, and expands back to a detail card on a
 * tap of either -- "present at the top, multiple simultaneous activities supported".
 *
 * One card per [ActivityTracker.activities] entry, stacked in a [Column] -- the tracker already
 * only ever holds what is genuinely in flight (see its own KDoc), so this draws nothing and
 * claims no space at rest. [ActivityCard] owns each entry's own notification-to-minimized timer;
 * this composable owns only which ONE of them, if any, is expanded -- the reference never shows
 * more than one big card at once, so a second tap while one is already expanded replaces it
 * rather than stacking a second.
 *
 * Deliberately narrower than the reference in one respect: no preview thumbnails. Building those
 * honestly means real bitmaps of the items actually in flight, which needs a `FileEntry` this
 * layer is never handed (`FileOperationService` only ever sees raw `Uri`s, and pulling the UI's
 * own thumbnail pipeline down into the operations layer to manufacture one would be the wrong
 * direction for that dependency to point) -- so the expanded card draws the same kind glyph the
 * notification and the minimized bar already do, honestly, rather than a placeholder image.
 */
@Composable
fun ActivityOverlay(tracker: ActivityTracker, modifier: Modifier = Modifier) {
    val activities by tracker.activities.collectAsState()
    if (activities.isEmpty()) return

    var expandedId by remember { mutableStateOf<String?>(null) }
    // An expanded card whose activity just finished would otherwise leave expandedId pointing at
    // an id no longer in the list -- harmless to the Column below (key{} just stops finding it),
    // but it would silently block the NEXT activity from expanding until a second tap cleared it.
    LaunchedEffect(activities) {
        if (activities.none { it.id == expandedId }) expandedId = null
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        activities.forEach { activity ->
            key(activity.id) {
                ActivityCard(
                    activity = activity,
                    expanded = activity.id == expandedId,
                    onTap = { expandedId = if (expandedId == activity.id) null else activity.id },
                )
            }
        }
    }
}

private enum class ActivityPhase { NOTIFICATION, MINIMIZED, EXPANDED }

/** How long a freshly started activity holds its full notification before shrinking on its own. */
private const val NOTIFICATION_DURATION_MS = 2_500L

private val MinimizedBarHeight = 4.dp
private val CardCorner = 16.dp
private val KindBadgeSize = 22.dp

internal fun accentFor(kind: ActivityKind): Color = when (kind) {
    // Never the only signal telling two concurrent activities apart -- see [iconFor] -- but a
    // real, stable colour per KIND of operation rather than a borrowed status colour (no faked
    // "warning" or "saved" state Fylz cannot actually back up for a plain transfer).
    ActivityKind.TRANSFER -> Color(0xFF4A90E2)
    ActivityKind.ARCHIVE -> Color(0xFFD9A441)
    ActivityKind.DOCUMENT -> Color(0xFF3FB876)
}

internal fun iconFor(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.TRANSFER -> Icons.AutoMirrored.Outlined.DriveFileMove
    ActivityKind.ARCHIVE -> Icons.Outlined.Archive
    ActivityKind.DOCUMENT -> Icons.Outlined.Description
}

@Composable
private fun ActivityCard(
    activity: ActivityProgress,
    expanded: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the activity's own id, not a bare `remember`: a finished activity's id leaving the
    // tracker and a DIFFERENT one arriving at the same list index must not inherit this timer's
    // already-elapsed state.
    var notifying by remember(activity.id) { mutableStateOf(true) }
    LaunchedEffect(activity.id) {
        delay(NOTIFICATION_DURATION_MS)
        notifying = false
    }
    val phase = when {
        expanded -> ActivityPhase.EXPANDED
        notifying -> ActivityPhase.NOTIFICATION
        else -> ActivityPhase.MINIMIZED
    }
    val fraction = if (activity.itemCount > 0) {
        (activity.itemIndex.toFloat() / activity.itemCount).coerceIn(0f, 1f)
    } else {
        0f
    }
    val accent = accentFor(activity.kind)
    val icon = iconFor(activity.kind)

    AnimatedContent(
        targetState = phase,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (phase == ActivityPhase.MINIMIZED) 50 else 0))
            .clickable(role = Role.Button, onClickLabel = "${activity.label} ${activity.itemIndex} of ${activity.itemCount}") {
                // A tap while still notifying skips the rest of that window outright -- collapsing
                // out of EXPANDED afterwards must land on MINIMIZED, never re-show NOTIFICATION.
                notifying = false
                onTap()
            },
        transitionSpec = {
            (FylzMotion.enter togetherWith FylzMotion.exit)
        },
        label = "activity-phase",
    ) { state ->
        when (state) {
            ActivityPhase.NOTIFICATION -> NotificationCard(activity, accent, icon, fraction)
            ActivityPhase.MINIMIZED -> MinimizedBar(accent, icon)
            ActivityPhase.EXPANDED -> ExpandedCard(activity, accent, icon, fraction)
        }
    }
}

@Composable
internal fun NotificationCard(activity: ActivityProgress, accent: Color, icon: ImageVector, fraction: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardCorner))
            .background(Color(0xFF16161A))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(28.dp),
                color = accent,
                trackColor = accent.copy(alpha = 0.25f),
                strokeWidth = 2.5.dp,
            )
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        }
        Text(activity.label, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        CountText(activity)
    }
}

@Composable
internal fun MinimizedBar(accent: Color, icon: ImageVector) {
    Box(Modifier.fillMaxWidth().height(KindBadgeSize), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(MinimizedBarHeight)
                .clip(RoundedCornerShape(50))
                .background(accent)
                .align(Alignment.Center),
        )
        Box(
            Modifier.size(KindBadgeSize).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
internal fun ExpandedCard(activity: ActivityProgress, accent: Color, icon: ImageVector, fraction: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardCorner))
            .background(Color(0xFF16161A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Text(activity.label, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            CountText(activity)
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = accent,
            trackColor = accent.copy(alpha = 0.2f),
        )
    }
}

@Composable
private fun RowScope.CountText(activity: ActivityProgress) {
    Text(
        "${activity.itemIndex} of ${activity.itemCount}",
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
    )
}
