package io.github.mbaliga.fylz.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.browse.readableLabel
import io.github.mbaliga.fylz.core.format.FileFormatRegistry
import io.github.mbaliga.fylz.core.format.PreviewFamily
import io.github.mbaliga.fylz.core.model.EntryKind
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.util.formatBytes

// Copied from PreviewPane's routing tables rather than shared: they are file-private there, and
// the two panes are allowed to drift (quick-look drops the editor path entirely).
private val QUICK_LOOK_SEMANTIC_ZIP_DOCUMENTS = setOf(
    "docx", "docm", "dotx", "pptx", "pptm", "ppsx", "xlsx", "xlsm",
    "odt", "ods", "odp", "odg", "epub",
)
private val QUICK_LOOK_ZIP_CONTAINER_EXTENSIONS = setOf(
    "zip", "zipx", "apk", "aab", "apks", "xapk", "apkm", "jar", "war", "ear",
    "cbz", "3mf", "kmz", "usdz", "vsdx", "nupkg", "whl",
)

/**
 * Everything [QuickLookContent] reads to pick and draw a preview, frozen together.
 *
 * `entry` alone used to be the only thing frozen for the exit animation; `textContent` and
 * `loading` kept coming straight from the live parameters, which are reset by a caller-side effect
 * the instant focus clears. Freezing only the entry let the `when` in [QuickLookContent] re-branch
 * mid-fade onto a *different* preview family than the one on screen — see [QuickLook]'s doc.
 */
private data class QuickLookSnapshot(
    val entry: FileEntry,
    val textContent: String?,
    val textTruncated: Boolean,
    val loading: Boolean,
)

/**
 * The transient centered preview that replaced `FloatingPreviewPane` on phones.
 *
 * The pane it replaces was pinned chrome: it lingered after the file it was showing stopped being
 * relevant, and its "close" action ([PreviewMode.HIDDEN][io.github.mbaliga.fylz.model.PreviewMode])
 * had no way back short of relaunching the app. Quick-look has no state of its own to get stuck
 * in — it is on screen exactly when [entry] is non-null, and every dismissal (scrim tap, Back)
 * calls [onDismiss], which the caller wires straight to clearing focus. There is nothing here to
 * leave "hidden forever".
 *
 * [entry] going null plays the exit animation rather than yanking the card away: the last
 * non-null snapshot — entry AND the preview state that went with it — is what the card keeps
 * drawing while it fades and shrinks out. Freezing only [entry] would leave `textContent`/`loading`
 * live: the caller nulls `previewText` the instant focus clears (well inside the ~140ms exit), so
 * a text/markdown file's `when` branch in [QuickLookContent] would stop matching mid-fade and fall
 * through to the generic inspector preview instead of just shrinking away.
 */
@Composable
fun QuickLook(
    entry: FileEntry?,
    textContent: String?,
    textTruncated: Boolean,
    loading: Boolean,
    onOpenExternal: (FileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var lastShown by remember { mutableStateOf<QuickLookSnapshot?>(null) }
    if (entry != null) {
        lastShown = QuickLookSnapshot(entry, textContent, textTruncated, loading)
    }

    BackHandler(enabled = entry != null, onBack = onDismiss)

    AnimatedVisibility(
        visible = entry != null,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.95f, animationSpec = tween(180)),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.95f, animationSpec = tween(140)),
    ) {
        val shown = lastShown ?: return@AnimatedVisibility
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                // Scrim tap dismisses; the card below consumes its own taps via Surface's onClick
                // so this never fires for a tap that landed on the card.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = {},
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.7f),
            ) {
                QuickLookContent(shown.entry, shown.textContent, shown.textTruncated, shown.loading, onOpenExternal)
            }
        }
    }
}

@Composable
private fun QuickLookContent(
    entry: FileEntry,
    textContent: String?,
    textTruncated: Boolean,
    loading: Boolean,
    onOpenExternal: (FileEntry) -> Unit,
) {
    val descriptor = remember(entry.name, entry.mimeType, entry.kind) {
        FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(entry.kind.readableLabel(), entry.sizeBytes?.let(::formatBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                entry.kind == EntryKind.MARKDOWN && textContent != null -> Column(Modifier.fillMaxSize()) {
                    if (textTruncated) QuickLookTruncationNotice()
                    MarkdownPreview(textContent, Modifier.fillMaxSize())
                }
                entry.kind == EntryKind.TEXT && textContent != null -> Column(Modifier.fillMaxSize()) {
                    if (textTruncated) QuickLookTruncationNotice()
                    // Read-only here even though the docked pane can edit: a transient card that
                    // vanishes on the next tap elsewhere is the wrong place to hold unsaved text.
                    MonospaceTextPreview(textContent, Modifier.fillMaxSize())
                }
                descriptor.family == PreviewFamily.IMAGE -> RichImagePreview(entry, Modifier.fillMaxSize())
                descriptor.family == PreviewFamily.PDF -> PdfPagerPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.family == PreviewFamily.AUDIO || descriptor.family == PreviewFamily.VIDEO ->
                    MediaFilePreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.family == PreviewFamily.FONT -> FontFilePreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.extension in QUICK_LOOK_SEMANTIC_ZIP_DOCUMENTS ->
                    ZipDocumentPreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.extension in QUICK_LOOK_ZIP_CONTAINER_EXTENSIONS ->
                    ZipArchivePreview(entry, descriptor, Modifier.fillMaxSize())
                descriptor.rendererId == "mesh-wireframe" || descriptor.rendererId == "dxf" ->
                    GeometryFilePreview(entry, descriptor, Modifier.fillMaxSize())
                else -> UniversalInspectorPreview(entry, descriptor, Modifier.fillMaxSize())
            }
        }
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(onClick = { onOpenExternal(entry) }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                Text("Open with…", Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun QuickLookTruncationNotice() {
    Text(
        text = "Large file preview is limited to 512 KiB.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}
