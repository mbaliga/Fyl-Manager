package io.github.mbaliga.fylz.ui.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.mbaliga.fylz.archive.ArchiveDocumentId
import io.github.mbaliga.fylz.archive.ArchiveEncodingOverrides
import io.github.mbaliga.fylz.archive.ArchiveNameEncoding
import io.github.mbaliga.fylz.archive.ArchiveRef
import io.github.mbaliga.fylz.model.FolderTab
import io.github.mbaliga.fylz.storage.ArchiveDocumentsProvider

/**
 * The [ArchiveRef] of [tab]'s current location, or `null` when it is not inside an archive at
 * all. Unlike `operations.archiveEditContext` (M3.6, scoped to a top-level ZIP-family archive for
 * its own editing purposes), this is deliberately unscoped: the charset override is a display
 * concern for *any* archive location, any format, any nesting depth -- a tar or ISO can carry a
 * lossy name exactly as a ZIP can, and a nested archive's own entries are no less worth
 * re-decoding than a top-level one's.
 */
private fun currentArchiveRef(tab: FolderTab?): ArchiveRef? {
    val uri = tab?.current?.uri ?: return null
    if (!ArchiveDocumentsProvider.isArchiveUri(uri)) return null
    return runCatching { ArchiveDocumentId.parse(uri).archive }.getOrNull()
}

/**
 * The archive header bar's manual legacy-charset control (M3.7, `docs/agent/REVIEW_QUEUE.md`'s
 * M3.7 entry): a small icon button next to the folder name, visible only while [tab]'s current
 * location is inside an archive, opening a menu of [ArchiveNameEncoding]'s six choices (five real
 * charsets plus "Auto") against [overrides]. Picking one changes nothing about any entry's
 * ordinal, id or extraction target -- only how a lossy-named entry's row is displayed -- so
 * [onChanged] only needs to re-query the current listing for new display strings (a plain
 * `FylzV1App.refresh()`, never a fresh archive listing: nothing was re-read from the archive).
 */
@Composable
fun ArchiveEncodingControl(tab: FolderTab?, overrides: ArchiveEncodingOverrides, onChanged: () -> Unit) {
    val archive = currentArchiveRef(tab) ?: return
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Outlined.Translate, contentDescription = "Legacy filename charset")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        ArchiveNameEncoding.entries.forEach { encoding ->
            DropdownMenuItem(
                text = { Text(encoding.label) },
                onClick = {
                    expanded = false
                    overrides.setEncoding(archive, encoding)
                    onChanged()
                },
            )
        }
    }
}
