package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.tactileFieldGroove
import io.github.mbaliga.fylz.ui.tactile.tactilePalette
import io.github.mbaliga.fylz.ui.theme.FylzGeometry
import io.github.mbaliga.fylz.ui.theme.microLabel
import io.github.mbaliga.fylz.util.FileType

private val SEMANTIC_ZIP_DOCUMENTS = setOf(
    "docx", "docm", "dotx", "pptx", "pptm", "ppsx", "xlsx", "xlsm",
    "odt", "ods", "odp", "odg", "epub",
)
private val ZIP_CONTAINER_EXTENSIONS = setOf(
    "zip", "zipx", "apk", "aab", "apks", "xapk", "apkm", "jar", "war", "ear",
    "cbz", "3mf", "kmz", "usdz", "vsdx", "nupkg", "whl",
)
// Non-ZIP structured archives ExtendedArchiveBrowserService lists real entries for: 7z, the TAR
// family (incl. the compressed tgz/tbz/tbz2/txz shorthands), cpio, ar, arj. RAR is deliberately
// absent -- the service doesn't support it, and FileFormatRegistry already carries an honest
// "not supported" note for it that the universal inspector fallback below surfaces.
private val EXTENDED_ARCHIVE_EXTENSIONS = setOf(
    "7z", "tar", "tgz", "tbz", "tbz2", "txz", "cpio", "ar", "arj",
)
// Lone gz/bz2/xz compression wrapping a single inner file, listed by the same service --
// including the compound "tar.gz"-style tokens FileFormatRegistry.compoundExtension() resolves
// to for a doubly-extended name, which never reduce to the bare "gz" this set would otherwise need.
// No zstd spellings: commons-compress's zstd codec needs zstd-jni, which Fylz does not bundle, so
// .zst/.tar.zst fall through to the universal inspector honestly (same as RAR).
private val COMPRESSED_STREAM_EXTENSIONS = setOf(
    "gz", "gzip", "bz2", "xz", "tar.gz", "tar.bz2", "tar.xz",
)

@Composable
fun PreviewPane(
    entry: FileEntry?,
    textContent: String?,
    textTruncated: Boolean,
    loading: Boolean,
    editorValue: String,
    onEditorValueChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(entry?.uri) { mutableStateOf(false) }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        if (entry == null) {
            EmptyPreview()
            return@Surface
        }
        val descriptor = remember(entry.name, entry.mimeType, entry.kind) {
            FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName(entry.name, entry.isDirectory, LocalShowExtensions.current),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        descriptor.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (FileType.isEditable(entry.kind)) {
                    // Icon dropped (no leading-icon slot on TactileButton); the label itself
                    // already carries the Edit<->Preview state, so no separate latched cap is
                    // needed here -- this button isn't icon-shaped, so it stays a plain SECONDARY
                    // cap rather than a latched TactileIconKey per the mission's own branch.
                    TactileButton(
                        text = if (editing) "Preview" else "Edit",
                        onClick = { editing = !editing },
                        style = TactileButtonStyle.SECONDARY,
                    )
                    if (editing) {
                        TactileButton(text = "Save", onClick = onSave, style = TactileButtonStyle.PRIMARY)
                    }
                }
                ExternalOpenButton(entry)
            }
            HorizontalDivider()

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                // TactileField can't carry this: its BasicTextField's textStyle is fixed to
                // bodyLarge internally, no override slot, and this editor's whole point is the
                // monospace body. Per the mission's own escape hatch: keep a hand-rolled field but
                // dress it in the RECESSED GROOVE recipe (tactileFieldGroove) instead of the full
                // TactileField anatomy (no slant edge / slash-tick / mandatory asterisk here -- an
                // editor body, not a form field).
                editing && FileType.isEditable(entry.kind) -> {
                    val editorPalette = tactilePalette()
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(
                            "UTF-8 text",
                            style = microLabel(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp, start = 8.dp),
                        )
                        BasicTextField(
                            value = editorValue,
                            onValueChange = onEditorValueChange,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(editorPalette.accent),
                            modifier = Modifier
                                .fillMaxSize()
                                .tactileFieldGroove(editorPalette, RoundedCornerShape(FylzGeometry.RadiusLg))
                                .padding(12.dp),
                        )
                    }
                }
                entry.kind == EntryKind.MARKDOWN && textContent != null -> Column(Modifier.fillMaxSize()) {
                    if (textTruncated) TruncationNotice()
                    MarkdownPreview(textContent, Modifier.fillMaxSize())
                }
                entry.kind == EntryKind.TEXT && textContent != null -> Column(Modifier.fillMaxSize()) {
                    if (textTruncated) TruncationNotice()
                    MonospaceTextPreview(textContent, Modifier.fillMaxSize())
                }
                // The pane keeps the inset the quick-look card dropped: the card resizes itself
                // to the image's aspect, but this pane is a fixed panel, so Fit still needs a
                // margin to keep the picture off a mismatched frame's edges.
                descriptor.family == PreviewFamily.IMAGE -> RichImagePreview(entry, Modifier.fillMaxSize().padding(12.dp))
                descriptor.family == PreviewFamily.PDF -> PdfPagerPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.family == PreviewFamily.AUDIO || descriptor.family == PreviewFamily.VIDEO ->
                    MediaFilePreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.family == PreviewFamily.FONT -> FontFilePreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.family == PreviewFamily.DESIGN -> DesignDocumentPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.extension in SEMANTIC_ZIP_DOCUMENTS ->
                    ZipDocumentPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.extension in ZIP_CONTAINER_EXTENSIONS ->
                    ZipArchivePreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.extension in EXTENDED_ARCHIVE_EXTENSIONS || descriptor.extension in COMPRESSED_STREAM_EXTENSIONS ->
                    ExtendedArchivePreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.rendererId == "mesh-wireframe" || descriptor.rendererId == "dxf" ->
                    GeometryFilePreview(entry, descriptor, Modifier.fillMaxSize())
                else -> UniversalInspectorPreview(entry, descriptor, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun EmptyPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Select a file to preview",
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TruncationNotice() {
    Text(
        text = "Large file preview is limited to 512 KiB.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}
