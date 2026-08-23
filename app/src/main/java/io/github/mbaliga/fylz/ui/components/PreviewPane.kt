package io.github.mbaliga.fylz.ui.components

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.data.PresentationDeckReader
import io.github.mbaliga.fylz.data.WorkbookReader
import io.github.mbaliga.fylz.ui.components.preview.ArchiveContentPreview
import io.github.mbaliga.fylz.ui.components.preview.DatabasePeekPreview
import io.github.mbaliga.fylz.ui.components.preview.DocumentTextPreview
import io.github.mbaliga.fylz.ui.components.preview.EmailPreview
import io.github.mbaliga.fylz.ui.components.preview.EpubTextPreview
import io.github.mbaliga.fylz.ui.components.preview.ModelWireframePreview
import io.github.mbaliga.fylz.ui.components.preview.PresentationPreview
import io.github.mbaliga.fylz.ui.components.preview.RawEmbeddedPreview
import io.github.mbaliga.fylz.ui.components.preview.SpreadsheetPreview
import io.github.mbaliga.fylz.ui.components.preview.StructuredCardPreview
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileIconKey
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
                    // This pane is mounted at a FIXED 380dp width (FylzV1App), and three text
                    // keycaps here (Edit/Preview, Save, Open with...) wanted ~260-280dp of it,
                    // squeezing the filename column above to under 80dp. Edit<->Preview has no
                    // label text to lose by going icon-only -- `latched = editing` is exactly the
                    // two-state toggle the "Edit"/"Preview" label used to carry, and the
                    // per-state contentDescription below keeps those same words for a screen
                    // reader even though they no longer render as a visible label.
                    TactileIconKey(
                        icon = Icons.Outlined.Edit,
                        contentDescription = if (editing) "Preview" else "Edit",
                        onClick = { editing = !editing },
                        latched = editing,
                    )
                    if (editing) {
                        // Save stays a labelled TactileButton: it's a commit action, not a mode
                        // switch, and earns a real keycap rather than an icon-only affordance.
                        TactileButton(text = "Save", onClick = onSave, style = TactileButtonStyle.PRIMARY)
                    }
                }
                ExternalOpenIconButton(entry)
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
                // Spreadsheets before the plain-text branch, because a .csv classifies as TEXT
                // and would otherwise render as a wall of commas. The reader itself decides what
                // it can open, so this route can never claim a format the parser does not handle:
                // .xls, .xlsb and .numbers answer null and keep the bounded inspector.
                WorkbookReader.spreadsheetKind(descriptor.extension) != null ->
                    SpreadsheetPreview(entry, descriptor, Modifier.fillMaxSize())
                // Presentations, slide by slide, from the text each slide actually carries. Same
                // rule: the deck reader is asked, rather than a second list drifting alongside it.
                PresentationDeckReader.deckKind(descriptor.extension) != null ->
                    PresentationPreview(entry, descriptor, Modifier.fillMaxSize())
                // Preview wave 1, mirroring QuickLook's routes in the same order and above the
                // plain-text branches for the same .ics/.vcf reason stated there.
                descriptor.rendererId == "calendar-card" || descriptor.rendererId == "contact-card" ->
                    StructuredCardPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.rendererId == "email" -> EmailPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.rendererId == "word-text" -> DocumentTextPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.rendererId == "epub-text" -> EpubTextPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.rendererId == "raw-embedded" -> RawEmbeddedPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.rendererId == "sqlite-peek" -> DatabasePeekPreview(entry, descriptor, Modifier.fillMaxSize())
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
                // Every archive family Fylz can actually open goes to the same browsable tree:
                // descend into a folder inside the archive, come back out, and open one entry in
                // the ordinary renderers. Families with no bundled reader (RAR, zstd, ISO) are
                // absent from these sets and keep falling through to the universal inspector.
                descriptor.extension in ZIP_CONTAINER_EXTENSIONS ||
                    descriptor.extension in EXTENDED_ARCHIVE_EXTENSIONS ||
                    descriptor.extension in COMPRESSED_STREAM_EXTENSIONS ->
                    ArchiveContentPreview(entry, descriptor, Modifier.fillMaxSize())
                // Wireframes for meshes, glTF/GLB scenes and 2D CAD alike -- ModelWireframePreview
                // adds the pan the old canvas had no gesture for, and "gltf" finally has the
                // renderer the format registry has been advertising for it.
                descriptor.rendererId == "mesh-wireframe" || descriptor.rendererId == "dxf" ||
                    descriptor.rendererId == "gltf" ->
                    ModelWireframePreview(entry, descriptor, Modifier.fillMaxSize())
                else -> UniversalInspectorPreview(entry, descriptor, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * "Open with…" as an icon key rather than a text keycap: this pane is mounted at a fixed 380dp,
 * and three text keycaps in its header left under 80dp for the filename itself. The label's exact
 * words survive as the contentDescription, so nothing is lost to a screen reader.
 *
 * This is the only implementation -- the `ExternalOpenButton` that used to live in
 * `SpecializedDocumentPreview.kt` had no other caller and was deleted rather than left as a second
 * copy of the same Intent + Toast fallback.
 */
@Composable
private fun ExternalOpenIconButton(entry: FileEntry) {
    val context = LocalContext.current
    TactileIconKey(
        icon = Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = "Open with…",
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(entry.uri, entry.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(intent) }
                .onFailure { Toast.makeText(context, "No installed app advertises support for this type.", Toast.LENGTH_SHORT).show() }
        },
    )
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
