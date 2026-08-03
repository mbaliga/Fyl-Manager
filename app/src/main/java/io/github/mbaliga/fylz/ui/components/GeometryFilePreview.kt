package io.github.mbaliga.fylz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.FileFormatDescriptor

@Composable
fun GeometryFilePreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    when (descriptor.rendererId) {
        "mesh-wireframe" -> MeshTechnicalPreview(entry, modifier)
        "dxf" -> DxfTechnicalPreview(entry, modifier)
        else -> UniversalInspectorPreview(entry, descriptor, modifier)
    }
}
