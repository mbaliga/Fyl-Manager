package io.github.mbaliga.fylz.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.mbaliga.fylz.model.FileEntry
import androidx.annotation.OptIn as AndroidOptIn
import androidx.media3.common.util.UnstableApi
import io.github.mbaliga.fylz.preview.FileFormatDescriptor

// AspectRatioFrameLayout.RESIZE_MODE_FIT is one of Media3's UnstableApi-annotated surfaces --
// stable in practice (it's been part of ExoPlayer/Media3's UI module for years) but formally
// opt-in, so its usage must be explicitly acknowledged rather than silently allowed.
@AndroidOptIn(UnstableApi::class)
@Composable
fun MediaFilePreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var error by remember(entry.uri) { mutableStateOf<String?>(null) }
    var ready by remember(entry.uri) { mutableStateOf(false) }
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
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
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
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    contentDescription = "Media preview for ${entry.name}"
                }
            },
            update = { it.player = player },
        )
        if (!ready) CircularProgressIndicator()
        if (entry.sizeBytes == 0L) {
            Text(
                "Empty media file",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
