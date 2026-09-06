package io.github.mbaliga.fylz.ui.components.preview

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.data.ArchiveEntryReader
import io.github.mbaliga.fylz.data.ArchiveMember
import io.github.mbaliga.fylz.data.ArchiveTree
import io.github.mbaliga.fylz.data.PresentationDeckReader
import io.github.mbaliga.fylz.data.WorkbookReader
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.ui.components.DesignDocumentPreview
import io.github.mbaliga.fylz.ui.components.FontFilePreview
import io.github.mbaliga.fylz.ui.components.MarkdownPreview
import io.github.mbaliga.fylz.ui.components.MediaFilePreview
import io.github.mbaliga.fylz.ui.components.MonospaceTextPreview
import io.github.mbaliga.fylz.ui.components.PdfPagerPreview
import io.github.mbaliga.fylz.ui.components.RichImagePreview
import io.github.mbaliga.fylz.ui.components.UniversalInspectorPreview
import io.github.mbaliga.fylz.ui.components.ZipDocumentPreview
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileField
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
import io.github.mbaliga.fylz.util.FileType
import io.github.mbaliga.fylz.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Browse an archive the way a folder is browsed, then preview one file from inside it.
 *
 * Descending is pure arithmetic over the flat entry list ([ArchiveTree]); opening a file extracts
 * that single entry -- and nothing else -- to a bounded, self-pruning cache file, then hands the
 * result to the same preview renderers a file on disk would get. Formats Fylz cannot open inside
 * an archive still cannot be opened inside one: the member falls through to the universal
 * inspector exactly as it would elsewhere, rather than being advertised and then failing.
 */
@Composable
fun ArchiveContentPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
    depth: Int = 0,
) {
    val context = LocalContext.current
    val listing by produceState<Result<ArchiveEntryReader.Listing>?>(null, entry.uri, entry.name) {
        value = withContext(Dispatchers.IO) {
            runCatching { ArchiveEntryReader(context.applicationContext).list(entry.uri, entry.name) }
        }
    }
    when (val result = listing) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { details -> ArchiveBrowser(entry, details, depth, modifier) },
            onFailure = { failure ->
                UniversalInspectorPreview(
                    entry = entry,
                    descriptor = descriptor,
                    modifier = modifier,
                    warning = failure.message ?: "This archive could not be listed safely.",
                )
            },
        )
    }
}

@Composable
private fun ArchiveBrowser(
    archive: FileEntry,
    listing: ArchiveEntryReader.Listing,
    depth: Int,
    modifier: Modifier,
) {
    var directory by remember(archive.uri) { mutableStateOf("") }
    var openPath by remember(archive.uri) { mutableStateOf<String?>(null) }
    val counts = remember(listing) { ArchiveTree.childCounts(listing.members) }
    val rows = remember(listing, directory) { ArchiveTree.children(listing.members, directory) }
    val open = remember(listing, openPath) {
        listing.members.firstOrNull { it.path == openPath && !it.directory }
    }

    // One password unlocks the whole archive for as long as this browser stays open -- zip
    // encryption is archive-wide in every case Fylz itself creates, so asking again per member
    // would only be friction. Held here rather than in ArchiveMemberScreen so it survives
    // stepping between members, and wiped whenever the archive identity changes or this browser
    // leaves composition, the same discipline ArchiveToolsOverlay's own password state keeps.
    var password by remember(archive.uri) { mutableStateOf<CharArray?>(null) }
    var passwordPrompt by remember(archive.uri) { mutableStateOf(false) }
    // The member a password prompt is unlocking for -- set the moment a locked row is tapped,
    // opened only once a password actually arrives, so a cancelled prompt never half-opens a row.
    var pendingPath by remember(archive.uri) { mutableStateOf<String?>(null) }
    DisposableEffect(archive.uri) {
        onDispose { password?.fill('\u0000') }
    }

    // Back walks the archive the way it walks a folder: out of an open member first, then up one
    // level, and only once at the archive's own root does back fall through to whatever owns this
    // preview (the quick-look card's dismiss, the pane's host).
    BackHandler(enabled = open != null || directory.isNotEmpty()) {
        if (open != null) openPath = null else directory = ArchiveTree.parentOf(directory).orEmpty()
    }

    if (open != null) {
        ArchiveMemberScreen(
            archive = archive,
            member = open,
            depth = depth,
            password = password,
            onBack = { openPath = null },
            // A password that fails to open its member is worse than useless -- kept, it makes
            // every other member in the archive fail the exact same way with no way to correct
            // it short of leaving the whole preview. Clearing it here means the very next tap
            // re-prompts instead.
            onExtractionFailed = { password?.fill('\u0000'); password = null },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        ArchiveSummary(listing, unlocked = password != null)
        ArchiveBreadcrumbs(
            directory = directory,
            onNavigate = { directory = it },
        )
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                item {
                    ArchiveNote(
                        if (listing.members.isEmpty()) {
                            "This archive declares no entries."
                        } else {
                            "This folder is empty."
                        },
                    )
                }
            }
            items(rows, key = { it.path }) { member ->
                ArchiveRow(
                    member = member,
                    childCount = if (listing.truncated) null else counts[member.path],
                    enabled = true,
                    onOpen = {
                        when {
                            member.directory -> directory = member.path
                            listing.encrypted && password == null -> {
                                pendingPath = member.path
                                passwordPrompt = true
                            }
                            else -> openPath = member.path
                        }
                    },
                )
            }
            item { ArchiveFooter(listing) }
        }
    }

    if (passwordPrompt) {
        ArchiveUnlockDialog(
            onDismiss = {
                passwordPrompt = false
                pendingPath = null
            },
            onConfirm = { entered ->
                passwordPrompt = false
                password = entered
                openPath = pendingPath
                pendingPath = null
            },
        )
    }
}

/** A bare password prompt for opening an already-encrypted archive -- no toggle, no confirmation
 *  field; those belong to [io.github.mbaliga.fylz.ui.ArchiveToolsOverlay]'s CREATE flow, which
 *  is choosing whether to encrypt rather than unlocking something that already is. */
@Composable
private fun ArchiveUnlockDialog(onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text("Archive password") },
        text = {
            TactileField(
                value = password,
                onValueChange = { password = it.take(256) },
                label = "Password",
                visualTransformation = PasswordVisualTransformation(),
                mandatory = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TactileButton(
                text = "Unlock",
                onClick = {
                    val entered = password.toCharArray()
                    password = ""
                    onConfirm(entered)
                },
                enabled = password.isNotEmpty(),
            )
        },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
    )
}

@Composable
internal fun ArchiveSummary(listing: ArchiveEntryReader.Listing, unlocked: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(listing.formatLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        val total = listing.entryCount
                        when {
                            listing.singleCompressedStream -> append("1 inner file")
                            // Null means the walk stopped early: what this preview can honestly
                            // state then is how many entries it read, not how many there are.
                            total == null -> append("first ${listing.members.size} entries listed")
                            else -> append(total).append(if (total == 1) " entry" else " entries")
                        }
                        // Only ever printed from a figure the archive itself declares; families
                        // that declare no uncompressed size print no size at all.
                        listing.expandedBytes?.let { append(" · ").append(formatBytes(it)).append(" expanded") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (listing.encrypted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    if (unlocked) {
                        "Password protected — unlocked for this preview."
                    } else {
                        "Password protected — tap a file to unlock."
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        listing.blockedReason?.let { reason ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
                    Column {
                        Text("Extracting this archive is blocked", style = MaterialTheme.typography.labelLarge)
                        Text(reason, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveBreadcrumbs(directory: String, onNavigate: (String) -> Unit) {
    val crumbs = remember(directory) { ArchiveTree.breadcrumbs(directory) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TactileIconKey(
            icon = Icons.Outlined.ChevronLeft,
            contentDescription = "Up one folder",
            enabled = directory.isNotEmpty(),
            onClick = { onNavigate(ArchiveTree.parentOf(directory).orEmpty()) },
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crumb(
                label = "Archive root",
                selected = crumbs.isEmpty(),
                onClick = { onNavigate("") },
            )
            crumbs.forEachIndexed { index, crumb ->
                Text("/", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Crumb(
                    label = crumb.label,
                    selected = index == crumbs.lastIndex,
                    onClick = { onNavigate(crumb.path) },
                )
            }
        }
    }
}

/**
 * A breadcrumb hop. The visible chip is deliberately small; the target under it is not. A one-word
 * folder name would otherwise give a target barely wider than the word, so the box is held to
 * 48dp in both directions -- a small visual inside a full-size touch target, never a small target.
 */
@Composable
private fun Crumb(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(onClickLabel = "Go to $label", role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArchiveRow(
    member: ArchiveMember,
    childCount: Int?,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val detail = when {
        member.directory -> childCount?.let { "$it ${if (it == 1) "item" else "items"}" }
        // Never a fabricated 0 B: a family that declares no size for an entry prints no size.
        else -> member.sizeBytes?.let(::formatBytes)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                enabled = enabled,
                onClickLabel = if (member.directory) "Open folder ${member.name}" else "Preview ${member.name}",
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (member.directory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                member.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (member.directory) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ArchiveFooter(listing: ArchiveEntryReader.Listing) {
    if (!listing.truncated && listing.unsafeMemberCount == 0) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (listing.truncated) {
            ArchiveNote("This archive has more entries than Fylz lists; folder item counts are hidden because they would be short.")
        }
        if (listing.unsafeMemberCount > 0) {
            ArchiveNote("${listing.unsafeMemberCount} entries are hidden because their names are unsafe to extract.")
        }
    }
}

@Composable
private fun ArchiveNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ArchiveMemberScreen(
    archive: FileEntry,
    member: ArchiveMember,
    depth: Int,
    password: CharArray?,
    onBack: () -> Unit,
    onExtractionFailed: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val extracted by produceState<Result<File>?>(null, archive.uri, member.path, password) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ArchiveEntryReader(context.applicationContext)
                    .extract(archive.uri, archive.name, member, password = password)
            }
        }
    }
    // A password that turned out to be wrong (or an archive that turned out not to need one at
    // all) is a parent-level fact, not something this one member's own failure screen can act on
    // -- signalled up rather than retried here, so every OTHER member in the same archive gets a
    // fresh prompt instead of silently inheriting the same bad key.
    LaunchedEffect(extracted) {
        val result = extracted
        if (password != null && result != null && result.isFailure) onExtractionFailed()
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TactileIconKey(
                icon = Icons.Outlined.ChevronLeft,
                contentDescription = "Back to archive contents",
                onClick = onBack,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    member.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("in ")
                        append(if (member.parent.isEmpty()) archive.name else "${archive.name}/${member.parent}")
                        member.sizeBytes?.let { append(" · ").append(formatBytes(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider()
        when (val result = extracted) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> result.fold(
                onSuccess = { file -> ArchiveMemberBody(file, member, depth, Modifier.fillMaxSize()) },
                onFailure = { failure ->
                    ArchiveMemberFailure(failure.message ?: "This entry could not be extracted for preview.")
                },
            )
        }
    }
}

@Composable
private fun ArchiveMemberFailure(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(36.dp))
            Text("Nothing to preview", style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The extracted member, routed to the same renderers a file on disk gets.
 *
 * The extracted copy lives in the app's own cache under a content-addressed name, so the
 * `file://` URI handed to those renderers is one this process wrote itself; it is never passed
 * outside the app.
 */
@Composable
private fun ArchiveMemberBody(file: File, member: ArchiveMember, depth: Int, modifier: Modifier) {
    val mimeType = remember(member.path) { guessMimeType(member.name) }
    val entry = remember(file.path, member.path, mimeType) {
        FileEntry(
            uri = Uri.fromFile(file),
            name = member.name,
            mimeType = mimeType,
            sizeBytes = file.length(),
            lastModifiedMillis = member.lastModifiedMillis,
            flags = 0,
            kind = FileType.classify(member.name, mimeType),
        )
    }
    val descriptor = remember(entry.name, entry.mimeType, entry.kind) {
        FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
    }
    when {
        descriptor.family == PreviewFamily.MARKDOWN || descriptor.family == PreviewFamily.TEXT ->
            ArchiveTextBody(file, descriptor.family == PreviewFamily.MARKDOWN, modifier)
        descriptor.family == PreviewFamily.IMAGE -> RichImagePreview(entry, modifier.padding(10.dp))
        // PDF, font, media and design renderers all reach their file through the platform's
        // ContentResolver, which resolves a file:// URI this app owns the same way it resolves a
        // provider document -- so none of them needs a special case for an extracted member.
        descriptor.family == PreviewFamily.PDF -> PdfPagerPreview(entry, descriptor, modifier)
        descriptor.family == PreviewFamily.AUDIO || descriptor.family == PreviewFamily.VIDEO ->
            MediaFilePreview(entry, descriptor, modifier)
        descriptor.family == PreviewFamily.FONT -> FontFilePreview(entry, descriptor, modifier)
        descriptor.family == PreviewFamily.DESIGN -> DesignDocumentPreview(entry, descriptor, modifier)
        // A spreadsheet or a deck inside an archive gets the same real reader it would get on
        // disk, not the generic container dump -- asked of the reader itself so this list cannot
        // drift from what is actually implemented.
        WorkbookReader.spreadsheetKind(descriptor.extension) != null ->
            SpreadsheetPreview(entry, descriptor, modifier)
        PresentationDeckReader.deckKind(descriptor.extension) != null ->
            PresentationPreview(entry, descriptor, modifier)
        descriptor.extension in SEMANTIC_ZIP_DOCUMENTS -> ZipDocumentPreview(entry, descriptor, modifier)
        // An archive inside an archive is browsable in place, bounded so a nesting bomb cannot
        // recurse the UI forever. Past the limit the member still gets the honest inspector.
        ArchiveEntryReader.supports(member.name) && depth < MAX_NESTED_ARCHIVE_DEPTH ->
            ArchiveContentPreview(entry, descriptor, modifier, depth + 1)
        descriptor.rendererId == "mesh-wireframe" || descriptor.rendererId == "dxf" ||
            descriptor.rendererId == "gltf" ->
            ModelWireframePreview(entry, descriptor, modifier)
        else -> UniversalInspectorPreview(entry, descriptor, modifier)
    }
}

@Composable
private fun ArchiveTextBody(file: File, markdown: Boolean, modifier: Modifier) {
    val loaded by produceState<BoundedText?>(null, file.path) {
        value = withContext(Dispatchers.IO) { readBoundedText(file) }
    }
    when (val text = loaded) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Column(modifier) {
            if (text.truncated) {
                ArchiveNote("Preview limited to the first ${MAX_TEXT_BYTES / 1024} KiB of this entry.")
            }
            if (markdown) {
                MarkdownPreview(text.value, Modifier.fillMaxSize())
            } else {
                MonospaceTextPreview(text.value, Modifier.fillMaxSize())
            }
        }
    }
}

private data class BoundedText(val value: String, val truncated: Boolean)

private fun readBoundedText(file: File): BoundedText {
    val buffer = ByteArray(MAX_TEXT_BYTES)
    var total = 0
    file.inputStream().use { input ->
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
    }
    return BoundedText(String(buffer, 0, total, Charsets.UTF_8), file.length() > total)
}

private fun guessMimeType(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val guessed = if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    return guessed ?: "application/octet-stream"
}

/** ZIP-based documents whose semantic reader is a better answer than browsing the container. */
private val SEMANTIC_ZIP_DOCUMENTS = setOf(
    "docx", "docm", "dotx", "pptx", "pptm", "ppsx", "xlsx", "xlsm",
    "odt", "ods", "odp", "odg", "epub",
)

/** How many archives deep the browser will descend before handing over to the inspector. */
private const val MAX_NESTED_ARCHIVE_DEPTH = 2

private const val MAX_TEXT_BYTES = 512 * 1024
