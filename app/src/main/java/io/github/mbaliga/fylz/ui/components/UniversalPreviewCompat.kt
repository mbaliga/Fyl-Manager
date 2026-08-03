package io.github.mbaliga.fylz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.FileFormatDescriptor

/** Compatibility overload for document previews that provide the warning before the modifier. */
@Composable
fun UniversalInspectorPreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    warning: String,
    modifier: Modifier = Modifier,
) {
    UniversalInspectorPreview(
        entry = entry,
        descriptor = descriptor,
        modifier = modifier,
        warning = warning,
    )
}
