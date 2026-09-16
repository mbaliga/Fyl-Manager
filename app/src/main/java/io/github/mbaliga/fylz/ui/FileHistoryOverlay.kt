package io.github.mbaliga.fylz.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.history.FileHistoryReason
import io.github.mbaliga.fylz.history.FileHistoryRestoreStatus
import io.github.mbaliga.fylz.history.FileHistorySettings
import io.github.mbaliga.fylz.history.FileHistoryStore
import io.github.mbaliga.fylz.history.FileHistoryUsage
import io.github.mbaliga.fylz.history.FileHistoryVersion
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileField
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Owns the store, its derived state, toasts, and the dialog itself. Hoisted so a caller that
 * already has its own open/close affordance (a recovery card, a menu row) can drive this directly
 * instead of going through a FAB it doesn't want.
 */
@Composable
fun FileHistoryHost(open: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { FileHistoryStore(context.applicationContext) }
    var settings by remember { mutableStateOf(store.settings()) }
    var versions by remember { mutableStateOf(store.allVersions()) }
    var usage by remember { mutableStateOf(store.usage()) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        settings = store.settings()
        versions = store.allVersions()
        usage = store.usage()
    }

    // The store is a thin JSON+file wrapper, not a live feed — re-read it whenever the dialog
    // is about to be shown so it reflects captures taken elsewhere since the last open.
    LaunchedEffect(open) {
        if (open) refresh()
    }

    if (open) {
        FileHistoryDialog(
            settings = settings,
            usage = usage,
            versions = versions,
            busy = busy,
            onDismiss = onDismiss,
            onSaveSettings = { value ->
                scope.launch {
                    busy = true
                    runCatching {
                        withContext(Dispatchers.IO) { store.updateSettings(value) }
                    }.onSuccess {
                        refresh()
                        Toast.makeText(context, "File history settings saved.", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Unable to save settings.", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            },
            onRestore = { version ->
                scope.launch {
                    busy = true
                    val result = runCatching {
                        store.restore(version.id, Uri.parse(version.sourceUri))
                    }.getOrElse { failure ->
                        Toast.makeText(context, failure.message ?: "Restore failed.", Toast.LENGTH_LONG).show()
                        busy = false
                        return@launch
                    }
                    val message = when (result.status) {
                        FileHistoryRestoreStatus.RESTORED -> "Version restored."
                        FileHistoryRestoreStatus.VERIFICATION_FAILED_ROLLED_BACK ->
                            result.message ?: "Restore did not verify; the previous file was restored."
                        FileHistoryRestoreStatus.VERIFICATION_FAILED_ROLLBACK_FAILED ->
                            result.message ?: "Restore and rollback both failed. Check the file immediately."
                        FileHistoryRestoreStatus.CURRENT_VERSION_NOT_PRESERVED ->
                            result.message ?: "The current file could not be preserved, so restore was cancelled."
                        FileHistoryRestoreStatus.VERSION_NOT_FOUND -> "That saved version no longer exists."
                        FileHistoryRestoreStatus.SNAPSHOT_MISSING -> "The saved version data is missing."
                        FileHistoryRestoreStatus.TARGET_UNREADABLE -> "The original file is no longer accessible."
                        FileHistoryRestoreStatus.WRITE_FAILED -> "The provider refused the restore write."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    refresh()
                    busy = false
                }
            },
            onDelete = { version ->
                if (store.delete(version.id)) refresh()
            },
            onClear = {
                val removed = store.clear()
                refresh()
                Toast.makeText(context, "Deleted $removed saved versions.", Toast.LENGTH_LONG).show()
            },
        )
    }
}

@Composable
private fun FileHistoryDialog(
    settings: FileHistorySettings,
    usage: FileHistoryUsage,
    versions: List<FileHistoryVersion>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSaveSettings: (FileHistorySettings) -> Unit,
    onRestore: (FileHistoryVersion) -> Unit,
    onDelete: (FileHistoryVersion) -> Unit,
    onClear: () -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var versionLimit by remember(settings) { mutableStateOf(settings.maxVersionsPerFile.toString()) }
    var fileLimitMb by remember(settings) {
        mutableStateOf((settings.maxFileBytes / MEBIBYTE).coerceAtLeast(1L).toString())
    }
    var storageLimitGb by remember(settings) {
        mutableStateOf(formatDecimal(settings.maxStorageBytes.toDouble() / GIBIBYTE.toDouble()))
    }
    // Timeline selection. Held as a raw id, not a version: versions is replaced wholesale on
    // every refresh, and re-resolving against the current list below is what lets a stale id
    // (deleted, or filtered out) fall back to the most recent version instead of selecting nothing.
    var filterKey by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    val parsedVersions = versionLimit.toIntOrNull()
    val parsedFileMb = fileLimitMb.toLongOrNull()
    val parsedStorageGb = storageLimitGb.toDoubleOrNull()
    val valid = parsedVersions != null && parsedVersions in 1..100 &&
        parsedFileMb != null && parsedFileMb in 1L..102_400L &&
        parsedStorageGb != null && parsedStorageGb > 0.0 && parsedStorageGb <= 1024.0

    val distinctFiles = remember(versions) {
        versions.groupBy(FileHistoryVersion::sourceKey)
            .map { (key, group) -> key to (group.maxByOrNull(FileHistoryVersion::capturedAtMillis)?.displayName ?: key) }
            .sortedBy { it.second.lowercase() }
    }
    val filteredVersions = if (filterKey == null) versions else versions.filter { it.sourceKey == filterKey }
    val selectedVersion = filteredVersions.firstOrNull { it.id == selectedId }
        ?: filteredVersions.maxByOrNull(FileHistoryVersion::capturedAtMillis)

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp),
        ) {
            // verticalScroll, not a bare Column: with the settings panel expanded plus a
            // populated timeline and detail card, this comfortably exceeds the height a phone in
            // landscape (or split-screen) has to give the 760.dp cap above -- without a scrolling
            // container the excess is simply clipped, taking the Save-settings and Restore
            // buttons with it, with no gesture to reach them.
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("File history", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Private, on-device snapshots created before Fylz overwrites eligible files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TactileIconKey(
                        icon = Icons.Outlined.Settings,
                        contentDescription = if (settingsOpen) "Hide file history settings" else "File history settings",
                        onClick = { settingsOpen = !settingsOpen },
                        latched = settingsOpen,
                        enabled = !busy,
                    )
                    TactileIconKey(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close file history",
                        onClick = onDismiss,
                        enabled = !busy,
                    )
                }

                if (settingsOpen) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep file versions", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Disabled by default because snapshots duplicate file contents.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TactileSwitch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        TactileField(
                            value = versionLimit,
                            onValueChange = { versionLimit = it.filter(Char::isDigit).take(3) },
                            label = "Versions/file",
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                        TactileField(
                            value = fileLimitMb,
                            onValueChange = { fileLimitMb = it.filter(Char::isDigit).take(6) },
                            label = "Max file (MiB)",
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TactileField(
                        value = storageLimitGb,
                        onValueChange = { value ->
                            storageLimitGb = value.filter { it.isDigit() || it == '.' }.take(8)
                        },
                        label = "History storage cap (GiB)",
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "Oldest versions are pruned first when this cap is reached.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    TactileButton(
                        text = "Save settings",
                        onClick = {
                            onSaveSettings(
                                FileHistorySettings(
                                    enabled = enabled,
                                    maxVersionsPerFile = parsedVersions!!,
                                    maxFileBytes = parsedFileMb!! * MEBIBYTE,
                                    maxStorageBytes = (parsedStorageGb!! * GIBIBYTE).toLong(),
                                ),
                            )
                        },
                        enabled = valid && !busy,
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text(
                    "${formatBytes(usage.totalBytes)} of ${formatBytes(usage.maxBytes)} · " +
                        "${usage.versionCount} versions across ${usage.fileCount} files",
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    progress = {
                        if (usage.maxBytes <= 0L) 0f
                        else (usage.totalBytes.toFloat() / usage.maxBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                )

                HorizontalDivider(Modifier.padding(bottom = 12.dp))

                if (versions.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(34.dp))
                        Text("No saved versions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (settings.enabled) {
                                "A snapshot will be added before the next eligible overwrite."
                            } else {
                                "Enable file history to begin preserving eligible versions."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (distinctFiles.size > 1) {
                        // KEPT STOCK (LOUD): a horizontally-scrolling row of an unbounded number of
                        // per-file name chips. FilterChip's compact pill + built-in selected
                        // checkmark is the right density for that; TactileButton's fixed 48dp
                        // RAISED CAP is sized for one or two deliberate actions; a whole scrolling
                        // row of them here would fight the layout the spec itself calls out
                        // ("FilterChips stay stock if layout fights").
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = filterKey == null,
                                    onClick = { filterKey = null },
                                    label = { Text("All files") },
                                )
                            }
                            items(distinctFiles, key = { it.first }) { (key, name) ->
                                FilterChip(
                                    selected = filterKey == key,
                                    onClick = { filterKey = key },
                                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }

                    HistoryTimelineStrip(
                        versions = filteredVersions,
                        selectedId = selectedVersion?.id,
                        onSelect = { selectedId = it.id },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    selectedVersion?.let { version ->
                        FileHistoryDetailCard(
                            version = version,
                            restoreEnabled = !busy,
                            deleteEnabled = !busy,
                            onRestore = { onRestore(version) },
                            onDelete = { onDelete(version) },
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TactileButton(
                        text = "Clear history",
                        onClick = onClear,
                        style = TactileButtonStyle.DESTRUCTIVE,
                        enabled = versions.isNotEmpty() && !busy,
                    )
                }
            }
        }
    }
}

/**
 * A horizontal map of [versions] by capture time, one marker per version. Tapping or dragging
 * anywhere on the strip selects whichever marker's x is nearest the finger — there is no per-dot
 * hit target to miss.
 */
@Composable
private fun HistoryTimelineStrip(
    versions: List<FileHistoryVersion>,
    selectedId: String?,
    onSelect: (FileHistoryVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (versions.isEmpty()) return

    val minMillis = versions.minOf(FileHistoryVersion::capturedAtMillis)
    val maxMillis = versions.maxOf(FileHistoryVersion::capturedAtMillis)
    val colorByReason = FileHistoryReason.entries.associateWith { reasonColor(it) }
    val markerOutline = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outlineVariant

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TIMELINE_HEIGHT)
                .pointerInput(versions) {
                    fun selectNearest(x: Float) {
                        nearestVersionTo(
                            x = x,
                            versions = versions,
                            minMillis = minMillis,
                            maxMillis = maxMillis,
                            widthPx = size.width.toFloat(),
                            paddingPx = TIMELINE_PADDING.toPx(),
                        )?.let(onSelect)
                    }
                    // Down selects immediately (a plain tap), then every subsequent move
                    // re-selects the nearest marker — the same loop covers both gestures the
                    // strip needs to support without two competing detectors fighting for the
                    // pointer.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        selectNearest(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChanged()) selectNearest(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            val paddingPx = TIMELINE_PADDING.toPx()
            val baselineY = size.height * TIMELINE_BASELINE_FRACTION
            drawLine(
                color = track,
                start = Offset(paddingPx, baselineY),
                end = Offset(size.width - paddingPx, baselineY),
                strokeWidth = 1.5.dp.toPx(),
            )
            versions.forEach { version ->
                if (version.id == selectedId) return@forEach // drawn last, on top of the rest
                val x = timelineX(version.capturedAtMillis, minMillis, maxMillis, size.width, paddingPx)
                drawCircle(
                    color = colorByReason.getValue(version.reason),
                    radius = TIMELINE_DOT_RADIUS.toPx(),
                    center = Offset(x, baselineY),
                )
            }
            versions.firstOrNull { it.id == selectedId }?.let { version ->
                val x = timelineX(version.capturedAtMillis, minMillis, maxMillis, size.width, paddingPx)
                val topY = baselineY - TIMELINE_PIN_HEIGHT.toPx()
                val color = colorByReason.getValue(version.reason)
                drawLine(
                    color = color,
                    start = Offset(x, baselineY),
                    end = Offset(x, topY),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(color = color, radius = TIMELINE_SELECTED_DOT_RADIUS.toPx(), center = Offset(x, topY))
                drawCircle(
                    color = markerOutline,
                    radius = TIMELINE_SELECTED_DOT_RADIUS.toPx(),
                    center = Offset(x, topY),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatHistoryTime(minMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (maxMillis != minMillis) {
                Text(
                    formatHistoryTime(maxMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileHistoryDetailCard(
    version: FileHistoryVersion,
    restoreEnabled: Boolean,
    deleteEnabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    version.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ReasonChip(version.reason)
            }
            Text(
                "${formatFullDateTime(version.capturedAtMillis)} · ${formatBytes(version.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TactileButton(text = "Restore", onClick = onRestore, enabled = restoreEnabled)
                Spacer(Modifier.width(8.dp))
                TactileIconKey(
                    icon = Icons.Outlined.Delete,
                    contentDescription = "Delete saved version",
                    onClick = onDelete,
                    enabled = deleteEnabled,
                )
            }
        }
    }
}

@Composable
private fun ReasonChip(reason: FileHistoryReason, modifier: Modifier = Modifier) {
    val color = reasonColor(reason)
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            reason.readableLabel(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** One scheme color per reason, shared by the timeline dots and [ReasonChip]. */
@Composable
private fun reasonColor(reason: FileHistoryReason): Color = when (reason) {
    FileHistoryReason.OBSERVED -> MaterialTheme.colorScheme.primary
    FileHistoryReason.BEFORE_WRITE -> MaterialTheme.colorScheme.tertiary
    FileHistoryReason.BEFORE_REPLACE -> MaterialTheme.colorScheme.secondary
    FileHistoryReason.BEFORE_RESTORE -> MaterialTheme.colorScheme.error
}

private fun FileHistoryReason.readableLabel(): String = when (this) {
    FileHistoryReason.OBSERVED -> "Observed"
    FileHistoryReason.BEFORE_WRITE -> "Before write"
    FileHistoryReason.BEFORE_REPLACE -> "Before replace"
    FileHistoryReason.BEFORE_RESTORE -> "Before restore"
}

/**
 * Where [capturedAtMillis] lands on the strip: linear over `[minMillis, maxMillis]`, inset by
 * [paddingPx] at both ends. A single-instant range (or a lone version) collapses to the centre
 * rather than dividing by zero.
 */
private fun timelineX(
    capturedAtMillis: Long,
    minMillis: Long,
    maxMillis: Long,
    widthPx: Float,
    paddingPx: Float,
): Float {
    if (maxMillis <= minMillis) return widthPx / 2f
    val available = (widthPx - 2f * paddingPx).coerceAtLeast(0f)
    val fraction = (capturedAtMillis - minMillis).toFloat() / (maxMillis - minMillis).toFloat()
    return paddingPx + fraction * available
}

/** The marker whose [timelineX] is closest to [x] — how a tap or drag position resolves to a version. */
private fun nearestVersionTo(
    x: Float,
    versions: List<FileHistoryVersion>,
    minMillis: Long,
    maxMillis: Long,
    widthPx: Float,
    paddingPx: Float,
): FileHistoryVersion? = versions.minByOrNull { version ->
    abs(timelineX(version.capturedAtMillis, minMillis, maxMillis, widthPx, paddingPx) - x)
}

private fun formatHistoryTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))

private fun formatFullDateTime(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.MEDIUM).format(Date(timeMillis))

private fun formatDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

private fun formatBytes(bytes: Long): String = when {
    bytes >= GIBIBYTE -> "%.2f GiB".format(bytes.toDouble() / GIBIBYTE)
    bytes >= MEBIBYTE -> "%.1f MiB".format(bytes.toDouble() / MEBIBYTE)
    bytes >= 1024L -> "%.1f KiB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

private const val MEBIBYTE = 1024L * 1024L
private const val GIBIBYTE = 1024L * 1024L * 1024L

private val TIMELINE_HEIGHT = 72.dp
private val TIMELINE_PADDING = 20.dp
private const val TIMELINE_BASELINE_FRACTION = 0.68f
private val TIMELINE_DOT_RADIUS = 4.5.dp
private val TIMELINE_SELECTED_DOT_RADIUS = 7.dp
private val TIMELINE_PIN_HEIGHT = 22.dp
