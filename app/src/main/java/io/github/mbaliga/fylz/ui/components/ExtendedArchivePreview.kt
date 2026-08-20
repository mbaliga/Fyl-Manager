package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.data.ExtendedArchiveBrowserService
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Listing for the non-ZIP archive families [ExtendedArchiveBrowserService] handles -- 7z and the
 * TAR family (including the compressed tgz/tbz/tbz2/txz shorthands), cpio, ar, arj, and a lone
 * gz/bz2/xz/zst compression wrapping a single inner file. Same look as [ZipArchivePreview]: a
 * header with a count summary, a Files/Folders/Expanded metrics row, then the entry list itself,
 * bounded to [DISPLAY_CAP] rows so a many-thousand-entry archive never asks Compose to lay out
 * every row at once.
 */
@Composable
fun ExtendedArchivePreview(entry: FileEntry, descriptor: FileFormatDescriptor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val listing by produceState<Result<ExtendedArchiveBrowserService.Listing>?>(null, entry.uri, entry.name) {
        value = withContext(Dispatchers.IO) {
            runCatching { ExtendedArchiveBrowserService(context.applicationContext).list(entry.uri, entry.name) }
        }
    }
    when (val result = listing) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { details -> ExtendedArchiveContent(details, modifier) },
            onFailure = {
                UniversalInspectorPreview(
                    entry,
                    descriptor,
                    modifier,
                    it.message ?: "This archive format could not be listed safely.",
                )
            },
        )
    }
}

@Composable
private fun ExtendedArchiveContent(listing: ExtendedArchiveBrowserService.Listing, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(36.dp))
            Column {
                val format = listing.format.replaceFirstChar { it.uppercase() }
                Text(
                    if (listing.compressedSingleStream) "$format compressed file" else "$format archive",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (listing.compressedSingleStream) {
                        "Decompresses to ${formatBytes(listing.totalDeclaredBytes)}"
                    } else {
                        "${listing.entries.size} entries · ${formatBytes(listing.totalDeclaredBytes)} expanded"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!listing.compressedSingleStream) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val fileCount = listing.entries.count { !it.directory }
                val directoryCount = listing.entries.count { it.directory }
                ExtendedArchiveMetric("Files", fileCount.toString())
                ExtendedArchiveMetric("Folders", directoryCount.toString())
                ExtendedArchiveMetric("Expanded", formatBytes(listing.totalDeclaredBytes))
            }
        }
        HorizontalDivider()
        Text(if (listing.compressedSingleStream) "Inner file" else "Contents", style = MaterialTheme.typography.titleSmall)
        val shown = listing.entries.take(DISPLAY_CAP)
        shown.forEach { archiveEntry ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (archiveEntry.directory) Icons.Outlined.Archive else Icons.Outlined.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(archiveEntry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!archiveEntry.directory) {
                    archiveEntry.declaredSize?.let { size -> Text(formatBytes(size), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        val hiddenByDisplayCap = listing.entries.size - shown.size
        when {
            hiddenByDisplayCap > 0 -> Text(
                "and $hiddenByDisplayCap more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listing.truncated -> Text(
                "Only the first ${listing.entries.size} entries are shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExtendedArchiveMetric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Rows rendered before the list falls back to a "and N more" summary line. */
private const val DISPLAY_CAP = 500
