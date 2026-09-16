package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.mbaliga.fylz.audio.PlaybackEqualizer
import io.github.mbaliga.fylz.data.EqualizerScale
import io.github.mbaliga.fylz.data.MediaTrackKind
import io.github.mbaliga.fylz.data.MediaTrackOption
import io.github.mbaliga.fylz.data.MediaTrackOptions
import io.github.mbaliga.fylz.model.FileEntry
import androidx.annotation.OptIn as AndroidOptIn
import androidx.media3.common.util.UnstableApi
import io.github.mbaliga.fylz.core.format.FileFormatDescriptor
import io.github.mbaliga.fylz.core.format.PreviewFamily

// AspectRatioFrameLayout.RESIZE_MODE_FIT is one of Media3's UnstableApi-annotated surfaces --
// stable in practice (it's been part of ExoPlayer/Media3's UI module for years) but formally
// opt-in, so its usage must be explicitly acknowledged rather than silently allowed.
@AndroidOptIn(UnstableApi::class)
@Composable
fun MediaFilePreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    useController: Boolean = true,
    onVideoSize: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var ready by remember(entry.uri) { mutableStateOf(false) }
    var tracks by remember(entry.uri) { mutableStateOf(Tracks.EMPTY) }
    var speed by remember(entry.uri) { mutableFloatStateOf(1f) }
    var equalizer by remember(entry.uri) { mutableStateOf<PlaybackEqualizer?>(null) }
    var equalizerEnabled by remember(entry.uri) { mutableStateOf(false) }
    var bandLevels by remember(entry.uri) { mutableStateOf<List<Int>>(emptyList()) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Audio has no video track, so onVideoSizeChanged below never fires for it -- without this the
    // card falls back to its free-aspect box, which is whatever shape the user last left it at
    // rather than a deliberate one. Reported once per file rather than left for the player to
    // discover on its own timeline.
    LaunchedEffect(entry.uri, descriptor.family) {
        if (descriptor.family == PreviewFamily.AUDIO) onVideoSize?.invoke(1f)
    }
    val player = remember(entry.uri) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.Builder().setUri(entry.uri).setMimeType(entry.mimeType).build())
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                ready = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                error = playbackError.message ?: "The device cannot decode this media file."
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                // Unapplied rotation means the coded frame is sideways; the aspect callers care
                // about is the one the player will actually display, not the one the codec stored.
                val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                val width = if (rotated) videoSize.height else videoSize.width
                val height = if (rotated) videoSize.width else videoSize.height
                onVideoSize?.invoke(width.toFloat() * videoSize.pixelWidthHeightRatio / height.toFloat())
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed = playbackParameters.speed
            }

            // The audio session id can change more than once across the player's life (format
            // switches between playlist items, stop()+re-prepare) -- the equalizer must be rebuilt
            // every time this fires, never attached once at player-construction time. See
            // PlaybackEqualizer.attach's own KDoc for why session id 0 is refused outright.
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer?.release()
                val attached = PlaybackEqualizer.attach(audioSessionId)
                equalizer = attached
                if (attached == null) {
                    bandLevels = emptyList()
                } else {
                    attached.setEnabled(equalizerEnabled)
                    bandLevels = List(attached.bandCount) { band -> attached.bandLevel(band) }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            equalizer?.release()
            player.release()
        }
    }
    // Autoplay is quiet by construction, per the HIG motion rule: content moves on its own only
    // muted. Reacting to `autoPlay` here rather than at build time lets the same player instance
    // pick up a live toggle of the setting without a restart.
    LaunchedEffect(player, autoPlay) {
        player.volume = if (autoPlay) 0f else 1f
        player.playWhenReady = autoPlay
    }

    val subtitleOptions = remember(tracks) { MediaTrackOptions.listFor(tracks, MediaTrackKind.SUBTITLE) }
    val audioTrackOptions = remember(tracks) { MediaTrackOptions.listFor(tracks, MediaTrackKind.AUDIO) }
    val subtitlesEnabled = remember(tracks) { MediaTrackOptions.hasSelection(tracks, MediaTrackKind.SUBTITLE) }
    val bandLabels = remember(equalizer) {
        equalizer?.let { eq -> List(eq.bandCount) { band -> "${eq.centerFrequencyHz(band)} Hz" } } ?: emptyList()
    }
    val bandFractions = remember(equalizer, bandLevels) {
        equalizer?.let { eq -> val range = eq.bandLevelRange(); bandLevels.map { level -> EqualizerScale.fractionForLevel(range, level) } }
            ?: emptyList()
    }

    fun selectTrack(kind: MediaTrackKind, option: MediaTrackOption) {
        val override = MediaTrackOptions.overrideFor(tracks, option)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(kind.trackType, false)
            .setOverrideForType(override)
            .build()
    }

    fun turnSubtitlesOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(MediaTrackKind.SUBTITLE.trackType)
            .setTrackTypeDisabled(MediaTrackKind.SUBTITLE.trackType, true)
            .build()
    }

    fun changeEqualizerEnabled(enabled: Boolean) {
        equalizerEnabled = enabled
        equalizer?.setEnabled(enabled)
    }

    fun changeBandFraction(band: Int, fraction: Float) {
        val eq = equalizer ?: return
        val level = EqualizerScale.levelForFraction(eq.bandLevelRange(), fraction)
        eq.setBandLevel(band, level)
        bandLevels = bandLevels.toMutableList().also { it[band] = level }
    }

    val failure = error
    if (failure != null) {
        UniversalInspectorPreview(
            entry = entry,
            descriptor = descriptor,
            modifier = modifier,
            warning = failure,
        )
        return
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    this.useController = useController
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    contentDescription = "Media preview for ${entry.name}"
                }
            },
            // The mini docked card has no room for a scrubber and reuses this same AndroidView
            // instance across the dock/undock transition (see QuickLookCard) -- the controller
            // has to be re-applied here too, not only at construction, or a tap on a docked video
            // keeps hitting PlayerView's own show/hide-controls handling instead of the card's.
            update = {
                it.player = player
                it.useController = useController
            },
        )
        if (!ready) CircularProgressIndicator()
        if (entry.sizeBytes == 0L) {
            Text(
                "Empty media file",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Docked mode has no room for a scrubber, per the same useController gate PlayerView's
        // own controller already uses -- this settings affordance shouldn't outlive that room.
        if (useController && ready) {
            IconButton(
                onClick = { settingsOpen = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = "Playback settings", tint = Color.White)
            }
        }
    }

    if (settingsOpen) {
        PlaybackSettingsSheet(
            subtitleOptions = subtitleOptions,
            subtitlesEnabled = subtitlesEnabled,
            onSelectSubtitle = { option -> selectTrack(MediaTrackKind.SUBTITLE, option) },
            onSubtitlesOff = ::turnSubtitlesOff,
            audioTrackOptions = audioTrackOptions,
            onSelectAudioTrack = { option -> selectTrack(MediaTrackKind.AUDIO, option) },
            speed = speed,
            onSpeedChange = { newSpeed -> player.setPlaybackSpeed(newSpeed) },
            equalizerAvailable = equalizer != null,
            equalizerEnabled = equalizerEnabled,
            onEqualizerEnabledChange = ::changeEqualizerEnabled,
            bandLabels = bandLabels,
            bandFractions = bandFractions,
            onBandFractionChange = ::changeBandFraction,
            onDismiss = { settingsOpen = false },
        )
    }
}
