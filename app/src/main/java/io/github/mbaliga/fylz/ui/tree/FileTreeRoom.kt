package io.github.mbaliga.fylz.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.ui.formatStorageBytes
import java.text.DateFormat
import java.util.Date

/**
 * The top room: where you are in the folder tree.
 *
 * Pulling down over the file pane makes the pane *shrink into its own folder* — the current
 * folder's node on the tree — the way Fotoz's photo shrinks into its pin on the map. The rest of
 * the tree (the trail down from the root, and the folders inside this one) resolves under the
 * flying pane, and the names arrive last, sliding up into place.
 *
 * It says two things, as Fotoz's room does for a photo: what this folder *is* (name, where it
 * sits, what is in it, how big, how recent, which volume it lives on) and where it sits in the
 * tree. The facts come first; the tree is the map.
 *
 * @param facts what the folder contains, derived from the listing already on screen.
 * @param volumeLine "42.0 GB free of 128 GB" for the volume the folder lives on, when known.
 * @param trail root → … → current, exactly the tab's `locations`. Empty when no folder is open.
 * @param childFolders the sub-folders of the current folder, as (key, name).
 * @param reveal how open the room is, 0..1. Read at draw time so a drag animates without
 *   recomposing the tree on every frame.
 */
@Composable
fun FileTreeRoom(
    trail: List<FolderLocation>,
    childFolders: List<FileEntry>,
    reveal: () -> Float,
    onOpenAncestor: (Int) -> Unit,
    onOpenChild: (FileEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    facts: FolderFacts = FolderFacts.Empty,
    volumeLine: String? = null,
) {
    val rows = remember(trail, childFolders) {
        FileTreeModel.rows(trail, childFolders.map { it.uri.toString() to it.name })
    }
    val childrenByKey = remember(childFolders) { childFolders.associateBy { it.uri.toString() } }

    var roomOrigin by remember { mutableStateOf(Offset.Zero) }
    var roomSize by remember { mutableStateOf(IntSize.Zero) }
    var nodeCenter by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onGloballyPositioned {
                roomOrigin = it.positionInRoot()
                roomSize = it.size
            },
    ) {
        // The pane starts as wide as the room and ~as tall as the card it left, so the first
        // frame of the pull is the file pane you were just looking at.
        val heroX = FolderMorph.heroScale(maxWidth.value)
        val heroY = FolderMorph.heroScale(maxHeight.value * 0.85f)
        val ink = MaterialTheme.colorScheme.onSurface
        val paneColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val accent = MaterialTheme.colorScheme.primary

        if (rows.isEmpty()) {
            Text(
                "Open a folder to see its tree.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .graphicsLayer { alpha = FolderMorph.textAlpha(reveal()) },
            )
            return@BoxWithConstraints
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 16.dp, top = 32.dp, bottom = 32.dp),
        ) {
            FolderInfoBlock(
                name = trail.last().name.ifBlank { "Folder" },
                path = trail.joinToString(" / ") { it.name.ifBlank { "Folder" } },
                facts = facts,
                volumeLine = volumeLine,
                reveal = reveal,
            )
            Text(
                "TREE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 28.dp, bottom = 8.dp)
                    .arrives(reveal),
            )
            rows.forEach { row ->
                val isCurrent = row.kind == TreeRowKind.CURRENT
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        // The flying pane must draw over the rows below it, not under them.
                        .zIndex(if (isCurrent) 1f else 0f)
                        .clickable {
                            when (row.kind) {
                                TreeRowKind.ANCESTOR -> onOpenAncestor(row.locationIndex ?: 0)
                                TreeRowKind.CURRENT -> onClose()
                                TreeRowKind.CHILD -> childrenByKey[row.childKey]?.let(onOpenChild)
                            }
                        }
                        // The tree's trunk: a hairline from the row above down to this row's
                        // node. Resolves with the tree, not with the pane.
                        .drawBehind {
                            if (row.depth > 0) {
                                val x = ((row.depth - 1) * INDENT_DP + NODE_HALF_DP).dp.toPx()
                                val a = FolderMorph.treeAlpha(reveal()) * 0.35f
                                val c = ink.copy(alpha = a)
                                drawLine(c, Offset(x, 0f), Offset(x, size.height / 2f), 1.dp.toPx())
                                drawLine(c, Offset(x, size.height / 2f), Offset(x + 12.dp.toPx(), size.height / 2f), 1.dp.toPx())
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width((row.depth * INDENT_DP).dp))
                    if (isCurrent) {
                        // The file pane, arriving as this folder's node. Measured BEFORE the
                        // graphicsLayer so the flight is computed from the node's resting
                        // place, not from wherever the flight has already put it.
                        Box(
                            Modifier
                                .size(FolderMorph.NODE_SIZE_DP.dp)
                                .onGloballyPositioned {
                                    nodeCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                                }
                                .zIndex(1f)
                                .graphicsLayer {
                                    val r = reveal()
                                    val sx = FolderMorph.paneScale(r, heroX)
                                    val sy = FolderMorph.paneScale(r, heroY)
                                    scaleX = sx
                                    scaleY = sy
                                    // From the node's resting centre toward the room's centre as
                                    // the flight winds back, so frame zero is the whole pane.
                                    val away = 1f - FolderMorph.flight(FolderMorph.stagger(r, 0f, 0.85f))
                                    val roomCentre = Offset(
                                        roomOrigin.x + roomSize.width / 2f,
                                        roomOrigin.y + roomSize.height * 0.42f,
                                    )
                                    translationX = (roomCentre.x - nodeCenter.x) * away
                                    translationY = (roomCentre.y - nodeCenter.y) * away
                                }
                                .drawBehind {
                                    val r = reveal()
                                    // Corner radius is drawn in the node's own units, which the
                                    // layer then scales — so divide the target radius back out.
                                    val sx = FolderMorph.paneScale(r, heroX)
                                    val corner = FolderMorph.cornerDp(r).dp.toPx() / sx
                                    drawRoundRect(paneColor, size = size, cornerRadius = CornerRadius(corner))
                                    // File rows, the pane's own content, fading as it becomes a folder.
                                    val rowsAlpha = 1f - FolderMorph.folderGlyphAlpha(r)
                                    if (rowsAlpha > 0f) {
                                        val pad = size.width * 0.16f
                                        val barH = size.height * 0.07f
                                        repeat(4) { i ->
                                            drawRoundRect(
                                                ink.copy(alpha = 0.14f * rowsAlpha),
                                                topLeft = Offset(pad, size.height * (0.22f + i * 0.17f)),
                                                size = Size(size.width * (if (i % 2 == 0) 0.68f else 0.5f), barH),
                                                cornerRadius = CornerRadius(barH / 2f),
                                            )
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer { alpha = FolderMorph.folderGlyphAlpha(reveal()) },
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .size(FolderMorph.NODE_SIZE_DP.dp)
                                .graphicsLayer { alpha = FolderMorph.treeAlpha(reveal()) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = ink.copy(alpha = if (row.kind == TreeRowKind.ANCESTOR) 0.6f else 0.4f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = ink.copy(alpha = if (isCurrent) 1f else 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            val r = reveal()
                            alpha = FolderMorph.textAlpha(r)
                            translationY = FolderMorph.textOffsetDp(r) * density.density
                        },
                    )
                }
            }
        }
    }
}

/** What this folder is: its name, where it sits, and the facts about its contents. */
@Composable
private fun FolderInfoBlock(
    name: String,
    path: String,
    facts: FolderFacts,
    volumeLine: String?,
    reveal: () -> Float,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().arrives(reveal)) {
        Text("FOLDER", style = MaterialTheme.typography.labelSmall, color = muted)
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            path,
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
        )
        val date = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        InfoRow("Contents", FolderFacts.countLine(facts))
        if (!facts.isEmpty) {
            if (facts.fileCount > 0) {
                val lowerBound = if (facts.unknownSizeCount > 0) "at least " else ""
                InfoRow("Size", lowerBound + formatStorageBytes(facts.totalBytes) + " in this folder")
            }
            facts.newestModifiedMillis?.let { InfoRow("Modified", date.format(Date(it))) }
            facts.largestName?.let { largest ->
                InfoRow("Largest", "$largest · ${formatStorageBytes(facts.largestBytes ?: 0L)}")
            }
            FolderFacts.kindsLine(facts).takeIf { it.isNotBlank() }?.let { InfoRow("Types", it) }
        }
        volumeLine?.let { InfoRow("Volume", it) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** The room's text arrives last and slides up into place, like every other block here. */
private fun Modifier.arrives(reveal: () -> Float): Modifier = graphicsLayer {
    val r = reveal()
    alpha = FolderMorph.textAlpha(r)
    translationY = FolderMorph.textOffsetDp(r) * density
}

private const val INDENT_DP = 20
private const val NODE_HALF_DP = FolderMorph.NODE_SIZE_DP / 2
