package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mbaliga.fylz.data.MediaTrackOption
import io.github.mbaliga.fylz.ui.tactile.TactileOptionRow
import io.github.mbaliga.fylz.ui.tactile.TactileSlider
import io.github.mbaliga.fylz.ui.tactile.TactileSwitch

/** Speed presets, deliberately discrete rather than a continuous slider -- a small, recognisable
 * set (matching the WAND_TOLERANCES precedent in SelectImageOverlay) is easier to pick from at a
 * glance than fine-tuning a slider to an exact multiplier, and this task is explicitly scoped to
 * "not FFmpeg/VLC parity". */
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
    return "${text}x"
}

/**
 * The live playback controls this task adds: subtitle/audio track pick, speed, and a per-band
 * equalizer -- anchored to the bottom of the screen rather than the full-screen takeover the
 * other file-operation overlays (Annotate/Convert/Select) use, because those operate on a file
 * once and are done, while this adjusts a player that must stay visible and audible underneath
 * while the user tunes it. Purely presentational: every value in and callback out is a plain
 * type, with all androidx.media3 / android.media.audiofx specifics resolved by the caller
 * ([MediaFilePreview]) so this composable is easy to render with synthetic sample data.
 */
@Composable
fun PlaybackSettingsSheet(
    subtitleOptions: List<MediaTrackOption>,
    subtitlesEnabled: Boolean,
    onSelectSubtitle: (MediaTrackOption) -> Unit,
    onSubtitlesOff: () -> Unit,
    audioTrackOptions: List<MediaTrackOption>,
    onSelectAudioTrack: (MediaTrackOption) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    equalizerAvailable: Boolean,
    equalizerEnabled: Boolean,
    onEqualizerEnabledChange: (Boolean) -> Unit,
    bandLabels: List<String>,
    bandFractions: List<Float>,
    onBandFractionChange: (band: Int, fraction: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 0.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
            ) {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Playback settings",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close")
                        }
                    }

                    if (subtitleOptions.isNotEmpty()) {
                        SectionLabel("Subtitles")
                        TactileOptionRow(text = "Off", selected = !subtitlesEnabled, onClick = onSubtitlesOff)
                        subtitleOptions.forEach { option ->
                            TactileOptionRow(
                                text = option.label,
                                selected = subtitlesEnabled && option.isSelected,
                                enabled = option.isSupported,
                                onClick = { onSelectSubtitle(option) },
                            )
                        }
                    }

                    if (audioTrackOptions.size > 1) {
                        SectionLabel("Audio track")
                        audioTrackOptions.forEach { option ->
                            TactileOptionRow(
                                text = option.label,
                                selected = option.isSelected,
                                enabled = option.isSupported,
                                onClick = { onSelectAudioTrack(option) },
                            )
                        }
                    }

                    SectionLabel("Speed")
                    PLAYBACK_SPEEDS.forEach { candidate ->
                        TactileOptionRow(
                            text = speedLabel(candidate),
                            selected = speed == candidate,
                            onClick = { onSpeedChange(candidate) },
                        )
                    }

                    SectionLabel("Equalizer")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Enabled",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TactileSwitch(
                            checked = equalizerEnabled,
                            onCheckedChange = onEqualizerEnabledChange,
                            enabled = equalizerAvailable,
                        )
                    }
                    if (!equalizerAvailable) {
                        Text(
                            "The equalizer isn't ready yet for this file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (equalizerEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            bandLabels.forEachIndexed { band, label ->
                                Text(label, style = MaterialTheme.typography.labelSmall)
                                TactileSlider(
                                    value = bandFractions.getOrElse(band) { 0.5f },
                                    onValueChange = { fraction -> onBandFractionChange(band, fraction) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
