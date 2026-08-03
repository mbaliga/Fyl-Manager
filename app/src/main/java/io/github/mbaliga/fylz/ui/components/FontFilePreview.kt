package io.github.mbaliga.fylz.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mbaliga.fylz.model.FileEntry
import io.github.mbaliga.fylz.preview.FileFormatDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FontFilePreview(
    entry: FileEntry,
    descriptor: FileFormatDescriptor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val typeface by produceState<Result<Typeface>?>(null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { descriptorFd ->
                    Typeface.Builder(descriptorFd.fileDescriptor).build()
                } ?: error("The provider did not return a font descriptor.")
            }
        }
    }
    when (val result = typeface) {
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> result.fold(
            onSuccess = { font -> FontSpecimen(font, entry.name, modifier) },
            onFailure = {
                UniversalInspectorPreview(entry, descriptor, modifier, it.message ?: "Unable to load this font.")
            },
        )
    }
}

@Composable
private fun FontSpecimen(typeface: Typeface, name: String, modifier: Modifier) {
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text("Font specimen · samples use fixed readable sizes.")
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                color = foreground
            }
            val lines = listOf(
                54f to "Aa Bb Cc 123",
                32f to "The quick brown fox jumps over the lazy dog.",
                25f to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                25f to "abcdefghijklmnopqrstuvwxyz",
                25f to "0123456789 !? @# ₹ € £ ¥",
            )
            var y = 72f
            lines.forEach { (size, text) ->
                paint.textSize = size
                drawContext.canvas.nativeCanvas.drawText(text, 12f, y, paint)
                y += size * 1.8f
            }
        }
    }
}
