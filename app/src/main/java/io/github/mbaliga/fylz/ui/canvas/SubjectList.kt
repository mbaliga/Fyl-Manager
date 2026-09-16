package io.github.mbaliga.fylz.ui.canvas

import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.ui.ClusterGestureHooks
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.ui.landing.LandingSubject
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.theme.LocalThemeStyle
import io.github.mbaliga.fylz.util.formatBytes
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The list home surface: [subject]'s children as lightweight rows -- not `FileRowV1`, but now
 * carrying the same selection and cluster-drag discipline that composable uses: long-press
 * toggles, tap opens unless [selectionActive], and the cluster drag only ever attaches to an
 * already-selected row. [selectedUris], [selectionActive], [onToggleSelection] and [cluster] are
 * all optional so a caller that hasn't wired them yet gets exactly today's behaviour.
 */
@Composable
fun SubjectList(
    subject: LandingSubject,
    repository: DocumentRepository,
    refreshKey: Int,
    onOpenFolder: (FolderLocation) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    selectedUris: Set<Uri> = emptySet(),
    selectionActive: Boolean = false,
    onToggleSelection: ((FileEntry) -> Unit)? = null,
    cluster: ClusterGestureHooks? = null,
) {
    var failure by remember { mutableStateOf<String?>(null) }

    val children by produceState(initialValue = null as List<FileEntry>?, subject, refreshKey) {
        value = null
        failure = null
        value = runCatching { repository.listChildren(subject.treeUri, subject.folderUri) }
            .onFailure { failure = it.message ?: "Unable to read this location" }
            .getOrNull()
    }

    Column(modifier.fillMaxSize()) {
        SubjectListHeader(
            subjectName = subject.name,
            onOpenAsFolder = { onOpenFolder(FolderLocation(subject.folderUri, subject.name)) },
        )
        HorizontalDivider()

        val listing = children
        // Weighted, not a bare fillMaxSize: this Box follows the header and divider inside the
        // same Column, and an un-weighted fillMaxSize child there claims the Column's full
        // incoming height regardless of what its earlier siblings already used, overflowing past
        // the bottom of whatever is actually left.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                listing == null -> Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                }

                failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                }

                listing.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(listing, key = { it.uri.toString() }) { entry ->
                        SubjectListRow(
                            entry = entry,
                            selected = entry.uri in selectedUris,
                            selectionActive = selectionActive,
                            onOpen = {
                                if (entry.isDirectory) {
                                    onOpenFolder(FolderLocation(entry.uri, entry.name))
                                } else {
                                    onOpenFile(entry)
                                }
                            },
                            onToggleSelection = onToggleSelection?.let { toggle -> { toggle(entry) } },
                            cluster = cluster,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectListHeader(subjectName: String, onOpenAsFolder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            subjectName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TactileButton(text = "Open as folder", onClick = onOpenAsFolder, style = TactileButtonStyle.SECONDARY)
    }
}

@Composable
private fun SubjectListRow(
    entry: FileEntry,
    selected: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: (() -> Unit)?,
    cluster: ClusterGestureHooks?,
) {
    val showExtensions = LocalShowExtensions.current
    val themeStyle = LocalThemeStyle.current
    val haptics = LocalHapticFeedback.current
    // A bare `selected` inside the semantics block below would resolve back to this parameter on
    // both sides of an assignment (it shadows `SemanticsPropertyReceiver.selected` even in that
    // extension's own receiver lambda), so the read gets its own name up front.
    val rowSelected = selected
    var originInRoot by remember { mutableStateOf(Offset.Zero) }

    // Read fresh inside the long-lived pointerInput coroutine below -- see CanvasTile's own
    // KDoc for why: plain parameters would freeze at whatever they were the one time this key
    // launched the coroutine, and `entry.uri` is the only thing that key is allowed to change on.
    val selectedState = rememberUpdatedState(selected)
    val selectionActiveState = rememberUpdatedState(selectionActive)
    val onOpenState = rememberUpdatedState(onOpen)
    val toggleSelectionState = rememberUpdatedState(onToggleSelection)
    val clusterState = rememberUpdatedState(cluster)

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onGloballyPositioned { coordinates ->
                originInRoot = coordinates.positionInRoot()
                if (selected) cluster?.onPositioned(entry.uri, coordinates.boundsInRoot().center)
            }
            // One pointerInput owns this row for its whole lifetime -- CanvasTile's own KDoc
            // explains why: a combinedClickable stacked alongside a raw drag pointerInput are
            // two independently-suspended gesture consumers racing the same up event, so a long
            // press held past the timeout and released without moving could fire BOTH a
            // selection toggle AND a (cancelled) cluster-drag start off one finger. Racing
            // long-press-vs-lift exactly once, here, is what keeps that from happening.
            .pointerInput(entry.uri) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // `true` = up arrived first (a tap), `false` = the wait was cancelled some
                    // other way, `null` = the timeout won while still down.
                    val liftedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation() != null
                    }
                    when (liftedEarly) {
                        true -> if (selectionActiveState.value) toggleSelectionState.value?.invoke() else onOpenState.value()
                        false -> Unit
                        null -> {
                            val liveCluster = clusterState.value
                            if (selectedState.value && liveCluster != null) {
                                // Already part of the live selection: hand off to the
                                // whole-selection cluster drag, never a toggle -- the two must
                                // not both claim this finger.
                                liveCluster.onStart(originInRoot + down.position)
                                val completed = drag(down.id) { change ->
                                    change.consume()
                                    liveCluster.onDrag(originInRoot + change.position)
                                }
                                if (completed) liveCluster.onEnd() else liveCluster.onCancel()
                            } else {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                toggleSelectionState.value?.invoke()
                                waitForUpOrCancellation()
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .semantics {
                this.selected = rowSelected
                onClick(label = if (selectionActive) "Toggle selection" else "Open") {
                    if (selectionActive) onToggleSelection?.invoke() else onOpen()
                    true
                }
                onToggleSelection?.let { toggle ->
                    customActions = listOf(CustomAccessibilityAction("Toggle selection") { toggle(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            EntryThumbnail(entry, size = 40.dp)
            if (selected && !themeStyle.marksSelectionInline()) {
                SelectionMark(themeStyle, Modifier.align(Alignment.TopStart))
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                (if (selected && themeStyle.marksSelectionInline()) "> " else "") +
                    displayName(entry.name, entry.isDirectory, showExtensions),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val caption = entry.sizeBytes?.takeUnless { entry.isDirectory }?.let(::formatBytes)
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
