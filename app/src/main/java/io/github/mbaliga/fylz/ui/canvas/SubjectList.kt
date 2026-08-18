package io.github.mbaliga.fylz.ui.canvas

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.data.DocumentRepository
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.model.FolderLocation
import io.github.mbaliga.fylz.ui.components.EntryThumbnail
import io.github.mbaliga.fylz.ui.components.LocalShowExtensions
import io.github.mbaliga.fylz.ui.components.displayName
import io.github.mbaliga.fylz.ui.landing.LandingSubject
import io.github.mbaliga.fylz.util.formatBytes

/**
 * The list home surface: [subject]'s children as lightweight rows -- not `FileRowV1`, which
 * carries selection and the cluster drag this home never joins.
 */
@Composable
fun SubjectList(
    subject: LandingSubject,
    repository: DocumentRepository,
    refreshKey: Int,
    onOpenFolder: (FolderLocation) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
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
                            onClick = {
                                if (entry.isDirectory) {
                                    onOpenFolder(FolderLocation(entry.uri, entry.name))
                                } else {
                                    onOpenFile(entry)
                                }
                            },
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
        TextButton(onClick = onOpenAsFolder) { Text("Open as folder") }
    }
}

@Composable
private fun SubjectListRow(entry: FileEntry, onClick: () -> Unit) {
    val showExtensions = LocalShowExtensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryThumbnail(entry, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
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
