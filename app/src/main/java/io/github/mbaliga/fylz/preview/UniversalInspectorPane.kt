package io.github.mbaliga.fylz.preview

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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

@Composable
fun UniversalInspectorPane(
    uri: Uri,
    descriptor: FileFormatDescriptor,
    sizeBytes: Long?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val inspection by produceState<Result<UniversalInspection>?>(null, uri) {
        value = runCatching { UniversalFileInspector(context.applicationContext).inspect(uri) }
    }
    when (val current = inspection) {
        null -> androidx.compose.foundation.layout.Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> current.fold(
            onSuccess = { InspectorContent(it, descriptor, sizeBytes, modifier) },
            onFailure = {
                androidx.compose.foundation.layout.Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(it.message ?: "Unable to inspect this file.")
                }
            },
        )
    }
}

@Composable
private fun InspectorContent(
    inspection: UniversalInspection,
    descriptor: FileFormatDescriptor,
    sizeBytes: Long?,
    modifier: Modifier,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(descriptor.label, style = MaterialTheme.typography.titleMedium)
            Text(
                descriptor.notes ?: when (descriptor.depth) {
                    PreviewDepth.RENDERED -> "A semantic renderer is available for this format."
                    PreviewDepth.STRUCTURED -> "Fylz can inspect this format's structure without executing its contents."
                    PreviewDepth.INSPECTED -> "No safe built-in semantic decoder is available; showing bounded binary inspection."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InspectorRow("Extension", descriptor.extension.ifBlank { "Unknown" })
                    InspectorRow("Declared size", sizeBytes?.let(::formatBytes) ?: "Provider did not report")
                    InspectorRow("Sample", "${formatBytes(inspection.sampledBytes.toLong())}${if (inspection.truncated) " (limited)" else ""}")
                    InspectorRow("Signature", inspection.detectedSignature ?: "No recognized magic signature")
                    InspectorRow("Sample SHA-256", inspection.sha256OfSample)
                    InspectorRow("Entropy", "%.3f bits/byte".format(inspection.entropyBitsPerByte))
                    InspectorRow("Likely text", if (inspection.probableText) "Yes" else "No")
                }
            }
        }
        descriptor.notes?.let { note -> item { Text(note, color = MaterialTheme.colorScheme.tertiary) } }
        item { HorizontalDivider() }
        item {
            Text("Signature bytes", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(inspection.signatureHex, fontFamily = FontFamily.Monospace)
            }
        }
        if (inspection.printableStrings.isNotEmpty()) {
            item { Text("Printable strings", style = MaterialTheme.typography.titleSmall) }
            items(inspection.printableStrings.size) { index ->
                SelectionContainer {
                    Text(
                        inspection.printableStrings[index],
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { Text("Hex preview", style = MaterialTheme.typography.titleSmall) }
        item {
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    inspection.hexLines.forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.65f))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
