package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.offset as foundationOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset

/**
 * Keeps the lambda-based layout offset available package-wide without coupling each movable-pane
 * file to the foundation-layout import. This delegates directly to Compose's layout modifier.
 */
internal fun Modifier.offset(block: Density.() -> IntOffset): Modifier = foundationOffset(block)
