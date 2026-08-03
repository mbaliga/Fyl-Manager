package io.github.mbaliga.fylz.preview

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry

@Composable
fun UniversalInspectorPreview(entry: FileEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val descriptor = FileFormatRegistry.describe(entry.name, entry.mimeType, entry.kind)
    val result by produceState<Result<UniversalInspection>?>(initialValue = null, entry.uri) {
        value = runCatching { UniversalFileInspector(context.applicationContext).inspect(entry.uri) }
    }

    when (val state = result) {
        null -> androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> state.fold(
            onSuccess = { inspection -> InspectorContent(entry, descriptor, inspection, modifier) },
            onFailure = { failure -> UniversalPreviewError("Unable to inspect file", failure.message, modifier) },
        )
    }
}

@Composable
private fun InspectorContent(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    inspection: UniversalInspection,
    modifier: Modifier,
) {
    val context = LocalContext.current
    SelectionContainer {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append(descriptor.depth.name.lowercase().replaceFirstChar(Char::uppercase))
                            descriptor.extension.takeIf(String::isNotBlank)?.let { append(" · .$it") }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(entry.uri, entry.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(intent) }
                }) {
                    androidx.compose.material3.Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                    Text("Open with", Modifier.padding(start = 6.dp))
                }
            }
            descriptor.notes?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(it, modifier = Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric("Sample", formatBytes(inspection.sampledBytes.toLong()))
                Metric("Entropy", "%.2f bits".format(inspection.entropyBitsPerByte))
                Metric("Text-like", if (inspection.probableText) "Yes" else "No")
            }
            inspection.detectedSignature?.let {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("Detected: $it", modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
            }
            if (inspection.truncated) {
                Text(
                    "This is a bounded sample, not the complete file.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text("Sample SHA-256", style = MaterialTheme.typography.titleSmall)
            Text(inspection.sha256OfSample, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Signature bytes", style = MaterialTheme.typography.titleSmall)
            Text(inspection.signatureHex.ifBlank { "No bytes" }, fontFamily = FontFamily.Monospace)
            HorizontalDivider()
            Text("Printable strings", style = MaterialTheme.typography.titleSmall)
            if (inspection.printableStrings.isEmpty()) {
                Text("No printable strings found in the sample.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.weight(0.45f, fill = false)) {
                    inspection.printableStrings.take(60).forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Text("Hex", style = MaterialTheme.typography.titleSmall)
            Column(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                inspection.hexLines.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}
