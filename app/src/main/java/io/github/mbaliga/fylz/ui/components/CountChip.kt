package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The gray count pill under a folder's name in the Fylz grid -- "83 photos". Frames 1/3 of the
 * Build 11.5 fidelity spec show this as a small rounded-full pill, never as plain inline text, so
 * a count reads as metadata about the folder rather than a second line of its name.
 *
 * [text] is nullable -- the shared-contract sketch names it a plain `String`, but the same line
 * goes on to require "the chip renders nothing for null/blank", and a non-null `String` can never
 * satisfy that, so this widens to `String?` to actually keep the honesty rule: a folder whose count is
 * still loading, or a caller with nothing true to say, passes `null` and gets an empty composable
 * back rather than a chip reading "null" or an empty pill. Callers are expected to have already
 * done the honesty check (e.g. "83 photos" only once the real count is known) -- this composable's
 * only job is to not draw a lie, not to compute one.
 */
@Composable
fun CountChip(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
