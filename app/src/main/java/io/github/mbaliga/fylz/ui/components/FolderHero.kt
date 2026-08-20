package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Frame 4's hero face size -- large enough to carry a per-folder appearance, not just an icon. */
private val HERO_FACE_SIZE: Dp = 120.dp

/**
 * Frame 4's folder hero: the folder's own face rendered large at the top of its own listing,
 * centered name and count beneath it -- what a folder becomes once you're inside it, replacing a
 * plain "Documents" app-bar title with the same identity the grid card outside showed.
 *
 * @param face the folder's own [FolderFace] (or any composable standing in for one), sized to
 *   [HERO_FACE_SIZE] by this composable's own `Box` -- the slot exists so the caller supplies the
 *   real per-folder appearance ([LocalFolderAppearance]-driven colour, stickers, material) rather
 *   than this composable drawing a second, generic folder icon of its own.
 * @param countLabel frame 4's count line ("5 documents") -- plain gray text under the name here,
 *   not [CountChip]'s pill: frame 4's own mock shows it as a second line of text, not a chip,
 *   unlike frames 1/3's grid cards. Honest counts only; null or blank renders nothing, the same
 *   contract [CountChip] holds itself to.
 */
@Composable
fun FolderHero(
    name: String,
    countLabel: String?,
    modifier: Modifier = Modifier,
    face: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(HERO_FACE_SIZE)) { face() }
        Spacer(Modifier.height(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!countLabel.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = countLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
