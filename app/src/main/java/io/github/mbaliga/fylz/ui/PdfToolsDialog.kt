package io.github.mbaliga.fylz.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.pdf.PdfPageRef
import io.github.mbaliga.fylz.pdf.PdfToolService
import io.github.mbaliga.fylz.ui.tactile.TactileButton
import io.github.mbaliga.fylz.ui.tactile.TactileButtonStyle
import io.github.mbaliga.fylz.ui.tactile.TactileField
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch
import io.github.mbaliga.fylz.ui.tactile.TactileToggle
import io.github.mbaliga.fylz.ui.tactile.TactileToggleOption

/**
 * PDF page tools: the UI for `pdf/PdfToolService`, `pdf/PdfPageTools` and
 * `pdf/SearchablePdfService`, all of which were fully implemented with **no UI references at all**.
 *
 * What is exposed:
 *
 * - **Inspect** — page count and page geometry, so a user can see what they are about to act on.
 * - **Extract pages** — a `1-3,7,9-12` style range, with optional per-page rotation, written to a
 *   destination the caller supplies through `CreateDocument`.
 * - **Merge** — every selected PDF, in selection order, into one document.
 * - **Searchable (OCR)** — runs on-device ML Kit text recognition and draws an invisible text layer.
 *   Off by default: it is much slower and it is a processing decision the user should make.
 *
 * All of it runs through the existing services; this file adds no PDF logic of its own.
 */
@Composable
fun PdfToolsDialog(
    sources: List<Uri>,
    service: PdfToolService,
    onDismiss: () -> Unit,
    onExport: (pages: List<PdfPageRef>, searchableOcr: Boolean) -> Unit,
    onMerge: (searchableOcr: Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    var pageCount by remember { mutableStateOf<Int?>(null) }
    var inspecting by remember { mutableStateOf(sources.size == 1) }
    var range by remember { mutableStateOf("") }
    var rotation by remember { mutableStateOf(0) }
    var searchableOcr by remember { mutableStateOf(false) }

    val single = sources.singleOrNull()

    LaunchedEffect(single) {
        if (single == null) return@LaunchedEffect
        inspecting = true
        runCatching { service.inspect(single) }
            .onSuccess { pageCount = it.pageCount; range = "1-${it.pageCount}" }
            .onFailure { onError(it.message ?: "That file could not be read as a PDF.") }
        inspecting = false
    }

    val parsedPages = remember(range, pageCount) {
        pageCount?.let { count -> parsePageRange(range, count) } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF tools") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (inspecting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading the document…", style = MaterialTheme.typography.bodySmall)
                }

                if (sources.size > 1) {
                    Text(
                        "${sources.size} PDFs selected. Merge combines them in selection order.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    pageCount?.let { count ->
                        Text("$count page${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                    }
                    TactileField(
                        value = range,
                        onValueChange = { range = it },
                        label = "Pages",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // TactileField has no persistent (non-error) supporting-text slot, unlike the
                    // OutlinedTextField this replaces -- this hint was never an error state, so it
                    // moves to a plain caption below the field rather than being dropped or
                    // recast as an invented Error().
                    Text(
                        "For example 1-3,7,9-12. Blank means every page.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Rotate", style = MaterialTheme.typography.labelLarge)
                        TactileToggle(
                            options = ROTATION_DEGREES.map { degrees ->
                                TactileToggleOption(label = "$degrees°", contentDescription = "Rotate $degrees degrees")
                            },
                            selectedIndex = ROTATION_DEGREES.indexOf(rotation).coerceAtLeast(0),
                            onSelect = { index -> rotation = ROTATION_DEGREES[index] },
                        )
                    }
                }

                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    TactileSwitch(checked = searchableOcr, onCheckedChange = { searchableOcr = it })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Make searchable (OCR)")
                        Text(
                            // AI/ML processing is opt-in per PRODUCT_BRIEF.md; this one is local
                            // and offline, but it is still the user's call and it is not free.
                            "On-device text recognition. Slower, and it rasterises pages.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (sources.size > 1) {
                TactileButton(text = "Merge", onClick = { onMerge(searchableOcr) }, style = TactileButtonStyle.PRIMARY)
            } else {
                TactileButton(
                    text = if (parsedPages.isEmpty()) "Extract" else "Extract ${parsedPages.size} pages",
                    onClick = {
                        val uri = single ?: return@TactileButton
                        onExport(
                            parsedPages.map { PdfPageRef(uri, it, rotation) },
                            searchableOcr,
                        )
                    },
                    style = TactileButtonStyle.PRIMARY,
                    enabled = parsedPages.isNotEmpty(),
                )
            }
        },
        dismissButton = { TactileButton(text = "Cancel", onClick = onDismiss, style = TactileButtonStyle.SECONDARY) },
    )
}

private val ROTATION_DEGREES = listOf(0, 90, 180, 270)

/**
 * Parses a `1-3,7,9-12` page range into zero-based indices.
 *
 * Out-of-range and malformed segments are dropped rather than throwing: a half-typed range must not
 * make the dialog explode while the user is still typing. Blank input means every page.
 */
internal fun parsePageRange(raw: String, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    if (raw.isBlank()) return (0 until pageCount).toList()
    val indices = linkedSetOf<Int>()
    raw.split(',').forEach { segment ->
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return@forEach
        if ('-' in trimmed) {
            // Open-ended forms are accepted: "5-" means 5 to the last page, "-5" means 1 to 5.
            // "1-" is also the state the field is in mid-typing, and it should already select
            // something rather than momentarily clearing the range.
            val rawStart = trimmed.substringBefore('-').trim()
            val rawEnd = trimmed.substringAfter('-').trim()
            val start = if (rawStart.isEmpty()) 1 else rawStart.toIntOrNull() ?: return@forEach
            val end = if (rawEnd.isEmpty()) pageCount else rawEnd.toIntOrNull() ?: return@forEach
            if (start > end) return@forEach
            (start..end).forEach { page ->
                if (page in 1..pageCount) indices += page - 1
            }
        } else {
            trimmed.toIntOrNull()?.let { page -> if (page in 1..pageCount) indices += page - 1 }
        }
    }
    return indices.toList()
}
