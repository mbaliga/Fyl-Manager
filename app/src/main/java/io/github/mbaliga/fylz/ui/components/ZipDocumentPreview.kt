package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.preview.ZipDocumentInspection
import io.github.mbaliga.fylz.preview.ZipDocumentInspector

@Composable
fun ZipDocumentPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val result by produceState<Result<ZipDocumentInspection>?>(null, entry.uri, descriptor.extension) {
        value = runCatching {
            ZipDocumentInspector(context.applicationContext).inspect(entry.uri, descriptor.extension)
        }
    }
    when (val current = result) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> current.fold(
            onSuccess = { inspection ->
                if (inspection.sections.isEmpty()) {
                    UniversalInspectorPreview(
                        entry,
                        descriptor,
                        modifier,
                        "No readable document text was found in this container.",
                    )
                } else {
                    SelectionContainer {
                        Column(
                            modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Article, contentDescription = null)
                                Column(Modifier.weight(1f)) {
                                    Text(inspection.formatLabel, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${inspection.sections.size} sections · ${inspection.entryCount} container entries",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (inspection.truncated) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                                        Text("The semantic preview was bounded for safety.")
                                    }
                                }
                            }
                            inspection.sections.forEachIndexed { index, section ->
                                if (index > 0) HorizontalDivider()
                                Text(section.name, style = MaterialTheme.typography.titleSmall)
                                Text(section.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            onFailure = { failure ->
                UniversalInspectorPreview(
                    entry,
                    descriptor,
                    modifier,
                    failure.message ?: "The document container could not be read safely.",
                )
            },
        )
    }
}
