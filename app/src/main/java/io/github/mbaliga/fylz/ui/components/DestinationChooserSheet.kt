package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider
import io.github.mbaliga.fylz.storage.StorageRoot
import io.github.mbaliga.fylz.ui.StorageHomeScreen

/** The open tabs a copy or move may land in: a tab whose current location is an archive is not
 * writable (M3.3, DESIGN-M33 §2.5) and is left out. */
internal fun destinationTabs(tabs: List<FolderTab>): List<FolderTab> =
    tabs.filterNot { ArchiveDocumentsProvider.isArchiveUri(it.current.uri) }

/**
 * P1.8: the in-app destination chooser "Copy to…"/"Move to…" (and Paste) open instead of going
 * straight to the system `OpenDocumentTree` picker -- picking an already-open tab or a storage
 * root is now one tap, with the system picker still one more tap away as "Other location…" for
 * anywhere not already open.
 *
 * [onChooseTab] hands back the tab itself, not just a URI: a tab's *current* folder (P1.8's own
 * wording), not its tree's root, is what "the open tabs" means here, and the caller needs
 * [FolderTab.current]'s document URI specifically, which may be nested arbitrarily deep inside
 * [FolderTab.treeUri].
 */
@Composable
fun DestinationChooserSheet(
    tabs: List<FolderTab>,
    onChooseTab: (FolderTab) -> Unit,
    onChooseRoot: (StorageRoot) -> Unit,
    onOtherLocation: () -> Unit,
    onCancel: () -> Unit,
) {
    val tabs = destinationTabs(tabs)
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Choose a destination", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Pick an open location or a storage root. The system picker is still there for anywhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (tabs.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SectionLabel("Open tabs")
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        tabs.forEach { tab ->
                            DestinationRow(
                                title = tab.current.name,
                                subtitle = tab.locations.joinToString(" / ") { it.name },
                                onClick = { onChooseTab(tab) },
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SectionLabel("Storage")
                StorageHomeScreen(
                    onOpenRoot = onChooseRoot,
                    onPickFolder = { onOtherLocation() },
                    onOpenRemotes = {},
                    showRemotes = false,
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp),
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOtherLocation) { Text("Other location…") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun DestinationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
