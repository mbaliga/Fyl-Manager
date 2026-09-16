package io.github.mbaliga.fylz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import io.github.mbaliga.fylz.core.format.FileFormatRegistry

/**
 * Whether names are drawn in full (`report.pdf`) or with a known extension hidden (`report`).
 * Mirrors [LocalIconStyle] -- a display preference threaded from the settings store down to
 * every leaf that draws a file name, rather than a parameter plumbed through the row, the card,
 * the preview header, the details room and the picker separately. Defaults to true so a preview
 * or a test that does not provide one still shows full names.
 */
val LocalShowExtensions: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/** Scopes [content] to whether extensions show; the composition root provides the persisted preference. */
@Composable
fun ProvideShowExtensions(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShowExtensions provides enabled, content = content)
}

/**
 * The name as it should be drawn: unchanged when [showExtensions] is on, [isDirectory] is true
 * (folders have no extension to hide), or the name has no known extension.
 *
 * Never strips to an empty string. A name like `.gitignore` reads entirely as its own extension
 * to [FileFormatRegistry.compoundExtension] -- stripping it would leave nothing to show, so the
 * full name wins whenever removing the extension would.
 */
fun displayName(name: String, isDirectory: Boolean, showExtensions: Boolean): String {
    if (showExtensions || isDirectory) return name
    val extension = FileFormatRegistry.compoundExtension(name)
    if (extension.isEmpty()) return name
    return name.dropLast(extension.length + 1).ifEmpty { name }
}
