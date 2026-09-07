package io.github.mbaliga.fylz.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.backup.BackupConditions
import io.github.mbaliga.fylz.backup.BackupNetworkConstraint
import io.github.mbaliga.fylz.backup.BackupPlan
import io.github.mbaliga.fylz.backup.BackupRunRecord
import io.github.mbaliga.fylz.backup.BackupSchedule
import io.github.mbaliga.fylz.backup.BackupScheduler
import io.github.mbaliga.fylz.backup.BackupService
import io.github.mbaliga.fylz.backup.BackupSnapshotRecord
import io.github.mbaliga.fylz.backup.BackupStore
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileField
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Owns the store, its derived state, and every dialog in the backups journey. Hoisted so a caller
 * that already has its own open/close affordance (a recovery card) can drive this directly instead
 * of going through a FAB it doesn't want.
 */
@Composable
fun BackupHost(open: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BackupStore(context.applicationContext) }
    val service = remember { BackupService(context.applicationContext, store) }
    val scheduler = remember { BackupScheduler(context.applicationContext) }
    var editor by remember { mutableStateOf<BackupPlanDraft?>(null) }
    var plans by remember { mutableStateOf(store.plans()) }
    var snapshots by remember { mutableStateOf(store.snapshots()) }
    var runs by remember { mutableStateOf(store.runs()) }
    var restoreSnapshot by remember { mutableStateOf<BackupSnapshotRecord?>(null) }
    var deleteSnapshot by remember { mutableStateOf<BackupSnapshotRecord?>(null) }
    var working by remember { mutableStateOf(false) }

    fun refresh() {
        plans = store.plans()
        snapshots = store.snapshots()
        runs = store.runs()
    }

    // The store is a thin JSON wrapper, not a live feed — re-read it whenever the dialog is about
    // to show so it reflects runs and snapshots recorded elsewhere since the last open.
    LaunchedEffect(open) {
        if (open) refresh()
    }

    fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            editor = editor?.copy(sourceTreeUri = uri.toString())
        }
    }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            editor = editor?.copy(destinationTreeUri = uri.toString())
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val snapshot = restoreSnapshot
        restoreSnapshot = null
        if (uri == null || snapshot == null) return@rememberLauncherForActivityResult
        persist(uri)
        scope.launch {
            working = true
            val result = service.restoreSnapshot(snapshot.id, uri)
            Toast.makeText(
                context,
                if (result.restored) {
                    "Restored ${result.restoredFiles} files into a new folder."
                } else {
                    result.message ?: result.status.name.replace('_', ' ').lowercase()
                },
                Toast.LENGTH_LONG,
            ).show()
            working = false
            refresh()
        }
    }

    if (open) {
        BackupManagerDialog(
            plans = plans,
            snapshots = snapshots,
            runs = runs,
            working = working,
            onDismiss = onDismiss,
            onAdd = { editor = BackupPlanDraft() },
            onEdit = { editor = BackupPlanDraft.from(it) },
            onRun = { plan ->
                scope.launch {
                    working = true
                    val run = service.runBackup(plan.id)
                    Toast.makeText(context, run.message ?: run.status.name, Toast.LENGTH_LONG).show()
                    working = false
                    refresh()
                }
            },
            onRestore = {
                restoreSnapshot = it
                restorePicker.launch(null)
            },
            onDeleteSnapshot = { deleteSnapshot = it },
            onDeletePlan = { plan ->
                scheduler.cancel(plan.id)
                store.removePlan(plan.id)
                refresh()
            },
        )
    }

    editor?.let { draft ->
        BackupPlanEditorDialog(
            draft = draft,
            onDraftChange = { editor = it },
            onPickSource = { sourcePicker.launch(null) },
            onPickDestination = { destinationPicker.launch(null) },
            onDismiss = { editor = null },
            onSave = {
                val plan = it.toPlan()
                store.putPlan(plan)
                scheduler.schedule(plan)
                editor = null
                refresh()
            },
        )
    }

    deleteSnapshot?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { deleteSnapshot = null },
            title = { Text("Delete backup?") },
            text = { Text("This permanently deletes ${snapshot.displayName} from the selected backup destination.") },
            confirmButton = {
                TactileButton(
                    text = "Delete",
                    style = TactileButtonStyle.DESTRUCTIVE,
                    onClick = {
                        val deleted = service.deleteSnapshot(snapshot.id)
                        Toast.makeText(context, if (deleted) "Backup deleted" else "Unable to delete backup", Toast.LENGTH_LONG).show()
                        deleteSnapshot = null
                        refresh()
                    },
                )
            },
            dismissButton = {
                TactileButton(text = "Cancel", style = TactileButtonStyle.SECONDARY, onClick = { deleteSnapshot = null })
            },
        )
    }
}

@Composable
private fun BackupManagerDialog(
    plans: List<BackupPlan>,
    snapshots: List<BackupSnapshotRecord>,
    runs: List<BackupRunRecord>,
    working: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (BackupPlan) -> Unit,
    onRun: (BackupPlan) -> Unit,
    onRestore: (BackupSnapshotRecord) -> Unit,
    onDeleteSnapshot: (BackupSnapshotRecord) -> Unit,
    onDeletePlan: (BackupPlan) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Backups", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Manual and scheduled folder snapshots stored in a destination you choose.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TactileButton(text = "New plan", onClick = onAdd, enabled = !working)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f, fill = false)) {
                    if (plans.isEmpty()) {
                        item {
                            Text(
                                "No backup plans yet. Create one, choose a source folder and a different destination folder, then run it manually or enable a schedule.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(plans, key = BackupPlan::id) { plan ->
                        val planSnapshots = snapshots.filter { it.planId == plan.id }
                        val latestRun = runs.firstOrNull { it.planId == plan.id }
                        BackupPlanCard(
                            plan = plan,
                            snapshots = planSnapshots,
                            latestRun = latestRun,
                            working = working,
                            onEdit = { onEdit(plan) },
                            onRun = { onRun(plan) },
                            onRestore = onRestore,
                            onDeleteSnapshot = onDeleteSnapshot,
                            onDeletePlan = { onDeletePlan(plan) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TactileButton(text = "Done", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun BackupPlanCard(
    plan: BackupPlan,
    snapshots: List<BackupSnapshotRecord>,
    latestRun: BackupRunRecord?,
    working: Boolean,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onRestore: (BackupSnapshotRecord) -> Unit,
    onDeleteSnapshot: (BackupSnapshotRecord) -> Unit,
    onDeletePlan: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        scheduleSummary(plan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    latestRun?.let {
                        Text(
                            "Last: ${it.status.name.replace('_', ' ').lowercase()} · ${formatDate(it.completedAtMillis ?: it.startedAtMillis)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                TactileIconKey(
                    icon = Icons.Outlined.Delete,
                    contentDescription = "Delete backup plan",
                    onClick = onDeletePlan,
                    enabled = !working,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // TactileButton carries a single text label, no icon slot -- the leading
                // Backup/Schedule glyphs these two buttons used to show are dropped here, same as
                // every other icon+label Button/OutlinedButton this conversion touches.
                TactileButton(text = "Back up now", onClick = onRun, enabled = !working)
                TactileButton(text = "Settings", onClick = onEdit, style = TactileButtonStyle.SECONDARY, enabled = !working)
            }
            if (snapshots.isNotEmpty()) {
                Text("Snapshots", style = MaterialTheme.typography.labelLarge)
                snapshots.take(10).forEach { snapshot ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(snapshot.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${snapshot.fileCount} files · ${formatBytes(snapshot.totalBytes)} · ${formatDate(snapshot.createdAtMillis)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TactileIconKey(
                            icon = Icons.Outlined.Restore,
                            contentDescription = "Restore this backup",
                            onClick = { onRestore(snapshot) },
                            enabled = !working,
                        )
                        TactileIconKey(
                            icon = Icons.Outlined.Delete,
                            contentDescription = "Delete this backup",
                            onClick = { onDeleteSnapshot(snapshot) },
                            enabled = !working,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupPlanEditorDialog(
    draft: BackupPlanDraft,
    onDraftChange: (BackupPlanDraft) -> Unit,
    onPickSource: () -> Unit,
    onPickDestination: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (BackupPlanDraft) -> Unit,
) {
    val valid = draft.name.isNotBlank() && draft.sourceTreeUri.isNotBlank() &&
        draft.destinationTreeUri.isNotBlank() && draft.sourceTreeUri != draft.destinationTreeUri &&
        draft.retention.toIntOrNull() in 1..100 &&
        draft.hour.toIntOrNull() in 0..23 && draft.minute.toIntOrNull() in 0..59 &&
        draft.mediaThreshold.toIntOrNull() in 1..100_000 &&
        draft.scanMinutes.toIntOrNull() in 15..10_080
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Backup plan", style = MaterialTheme.typography.headlineSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp)) {
                    item {
                        TactileField(
                            value = draft.name,
                            onValueChange = { onDraftChange(draft.copy(name = it)) },
                            label = "Plan name",
                            mandatory = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        FolderChoice("Source folder", draft.sourceTreeUri, onPickSource)
                    }
                    item {
                        FolderChoice("Backup destination", draft.destinationTreeUri, onPickDestination)
                    }
                    item {
                        TactileField(
                            value = draft.retention,
                            onValueChange = { onDraftChange(draft.copy(retention = it.filter(Char::isDigit))) },
                            label = "Backups to retain",
                            mandatory = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Oldest snapshots are removed after a successful backup.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        SettingSwitch("Enable scheduled backups", draft.enabled) { onDraftChange(draft.copy(enabled = it)) }
                    }
                    item {
                        SettingSwitch("Daily time window", draft.dailyEnabled) { onDraftChange(draft.copy(dailyEnabled = it)) }
                    }
                    if (draft.dailyEnabled) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TactileField(
                                    value = draft.hour,
                                    onValueChange = { onDraftChange(draft.copy(hour = it.filter(Char::isDigit).take(2))) },
                                    label = "Hour 0–23",
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TactileField(
                                    value = draft.minute,
                                    onValueChange = { onDraftChange(draft.copy(minute = it.filter(Char::isDigit).take(2))) },
                                    label = "Minute",
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                "Android may defer scheduled work until system conditions allow it; this is an eligibility window, not an alarm.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        SettingSwitch("After new images or videos", draft.mediaEnabled) { onDraftChange(draft.copy(mediaEnabled = it)) }
                    }
                    if (draft.mediaEnabled) {
                        item {
                            TactileField(
                                value = draft.mediaThreshold,
                                onValueChange = { onDraftChange(draft.copy(mediaThreshold = it.filter(Char::isDigit))) },
                                label = "New media count",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            TactileField(
                                value = draft.scanMinutes,
                                onValueChange = { onDraftChange(draft.copy(scanMinutes = it.filter(Char::isDigit))) },
                                label = "Check interval in minutes",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Minimum 15 minutes. The first scan establishes a baseline.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { SettingSwitch("Only while charging", draft.charging) { onDraftChange(draft.copy(charging = it)) } }
                    item { SettingSwitch("Only while device is idle", draft.idle) { onDraftChange(draft.copy(idle = it)) } }
                    item { SettingSwitch("Require battery not low", draft.batteryNotLow) { onDraftChange(draft.copy(batteryNotLow = it)) } }
                    item { SettingSwitch("Require storage not low", draft.storageNotLow) { onDraftChange(draft.copy(storageNotLow = it)) } }
                    item { SettingSwitch("Require unmetered network", draft.unmetered) { onDraftChange(draft.copy(unmetered = it)) } }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY)
                    Spacer(Modifier.width(8.dp))
                    TactileButton(text = "Save", onClick = { onSave(draft) }, enabled = valid)
                }
            }
        }
    }
}

// KEPT STOCK (LOUD): TactileButton's kit contract is `text: String` only -- no icon slot and no
// second line. This row is a folder-picker affordance that needs both the FolderOpen icon and a
// two-tier label (fixed caption above, live chosen-path value below); collapsing that into a
// single TactileButton string would either drop the icon or drop the live value, both of which
// are real information the user relies on here, not decoration. Left as an OutlinedButton rather
// than a lossy conversion.
@Composable
private fun FolderChoice(label: String, value: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.FolderOpen, null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label)
            Text(
                value.ifBlank { "Choose folder" },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        TactileSwitch(checked = checked, onCheckedChange = onChecked)
    }
}

private data class BackupPlanDraft(
    val id: String? = null,
    val name: String = "Phone backup",
    val sourceTreeUri: String = "",
    val destinationTreeUri: String = "",
    val retention: String = "5",
    val enabled: Boolean = false,
    val dailyEnabled: Boolean = false,
    val hour: String = "2",
    val minute: String = "0",
    val mediaEnabled: Boolean = false,
    val mediaThreshold: String = "25",
    val scanMinutes: String = "60",
    val charging: Boolean = false,
    val idle: Boolean = false,
    val batteryNotLow: Boolean = true,
    val storageNotLow: Boolean = true,
    val unmetered: Boolean = false,
) {
    fun toPlan(): BackupPlan = BackupPlan(
        id = id ?: java.util.UUID.randomUUID().toString(),
        name = name.trim(),
        sourceTreeUri = sourceTreeUri,
        destinationTreeUri = destinationTreeUri,
        enabled = enabled,
        retentionCount = retention.toInt(),
        schedule = BackupSchedule(
            dailyEnabled = dailyEnabled,
            dailyHour = hour.toInt(),
            dailyMinute = minute.toInt(),
            mediaCountEnabled = mediaEnabled,
            mediaThreshold = mediaThreshold.toInt(),
            mediaScanIntervalMinutes = scanMinutes.toInt(),
        ),
        conditions = BackupConditions(
            requiresCharging = charging,
            requiresDeviceIdle = idle,
            requiresBatteryNotLow = batteryNotLow,
            requiresStorageNotLow = storageNotLow,
            network = if (unmetered) BackupNetworkConstraint.UNMETERED else BackupNetworkConstraint.NONE,
        ),
    )

    companion object {
        fun from(plan: BackupPlan) = BackupPlanDraft(
            id = plan.id,
            name = plan.name,
            sourceTreeUri = plan.sourceTreeUri,
            destinationTreeUri = plan.destinationTreeUri,
            retention = plan.retentionCount.toString(),
            enabled = plan.enabled,
            dailyEnabled = plan.schedule.dailyEnabled,
            hour = plan.schedule.dailyHour.toString(),
            minute = plan.schedule.dailyMinute.toString(),
            mediaEnabled = plan.schedule.mediaCountEnabled,
            mediaThreshold = plan.schedule.mediaThreshold.toString(),
            scanMinutes = plan.schedule.mediaScanIntervalMinutes.toString(),
            charging = plan.conditions.requiresCharging,
            idle = plan.conditions.requiresDeviceIdle,
            batteryNotLow = plan.conditions.requiresBatteryNotLow,
            storageNotLow = plan.conditions.requiresStorageNotLow,
            unmetered = plan.conditions.network == BackupNetworkConstraint.UNMETERED,
        )
    }
}

private fun scheduleSummary(plan: BackupPlan): String {
    if (!plan.enabled || !plan.schedule.hasAutomaticTrigger) return "Manual · retain ${plan.retentionCount}"
    val triggers = buildList {
        if (plan.schedule.dailyEnabled) add("daily near %02d:%02d".format(plan.schedule.dailyHour, plan.schedule.dailyMinute))
        if (plan.schedule.mediaCountEnabled) add("after ${plan.schedule.mediaThreshold} new media")
    }
    return triggers.joinToString(" · ") + " · retain ${plan.retentionCount}"
}

private fun formatDate(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.1f GiB".format(value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.1f MiB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.1f KiB".format(value / 1024.0)
    else -> "$value B"
}
